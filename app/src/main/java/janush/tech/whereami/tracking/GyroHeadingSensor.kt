package janush.tech.whereami.tracking

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * When is the gyroscope worth its power? Only while the screen is on (a render loop consumes the
 * result), the device is physically moving, and something is following or recording the position.
 * Everything else (screen off, parked, browsing the map) keeps the sensor unregistered.
 */
object GyroPolicy {
    fun shouldRun(isForeground: Boolean, isMoving: Boolean, isFollowingOrRecording: Boolean): Boolean =
        isForeground && isMoving && isFollowingOrRecording

    /** Hysteresis on speed so the sensor does not flap at a crawl: on above 2.0 m/s, off below 0.8 m/s. */
    fun isMovingForGyro(speedMps: Float, wasMoving: Boolean): Boolean =
        if (wasMoving) speedMps >= 0.8f else speedMps >= 2.0f
}

/** Abstraction over [SensorManager] so register/unregister lifecycle is unit-testable. */
interface SensorRegistrar {
    /** @return true if registration succeeded */
    fun register(sensorType: Int, samplingPeriodUs: Int, maxReportLatencyUs: Int, listener: SensorEventListener): Boolean
    fun unregister(listener: SensorEventListener)
}

class AndroidSensorRegistrar(context: Context) : SensorRegistrar {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    override fun register(sensorType: Int, samplingPeriodUs: Int, maxReportLatencyUs: Int, listener: SensorEventListener): Boolean {
        val manager = sm ?: return false
        val sensor = manager.getDefaultSensor(sensorType) ?: return false
        return manager.registerListener(listener, sensor, samplingPeriodUs, maxReportLatencyUs)
    }

    override fun unregister(listener: SensorEventListener) {
        sm?.unregisterListener(listener)
    }
}

/**
 * Foreground-only, gravity-projected yaw rate source for the tracking engine and render pipeline.
 *
 * Gyroscope (50 Hz) gives angular velocity in the device frame; the gravity vector projects it onto
 * the vertical axis so the result is heading rate regardless of how the phone sits in the cradle.
 * Samples go to [GyroYawTracker]. Registered only while [GyroPolicy] allows it; [stop] is idempotent.
 */
class GyroHeadingSensor(
    private val registrar: SensorRegistrar,
    private val tracker: GyroYawTracker = TrackingRuntime.gyro
) : SensorEventListener {

    private val gravity = FloatArray(3)
    private var haveGravity = false
    private var gravitySensorTypeRegistered = false

    @Volatile
    var isRunning: Boolean = false
        private set

    fun start() {
        if (isRunning) return
        tracker.clear()
        haveGravity = false
        var gravityOk = registrar.register(Sensor.TYPE_GRAVITY, GRAVITY_PERIOD_US, 0, this)
        gravitySensorTypeRegistered = gravityOk
        if (!gravityOk) {
            // Devices without the fused gravity sensor: low-passed accelerometer is an acceptable gravity estimate.
            gravityOk = registrar.register(Sensor.TYPE_ACCELEROMETER, GRAVITY_PERIOD_US, 0, this)
        }
        val gyroOk = gravityOk && registrar.register(Sensor.TYPE_GYROSCOPE, GYRO_PERIOD_US, GYRO_MAX_LATENCY_US, this)
        if (!gyroOk) {
            registrar.unregister(this)
            return
        }
        isRunning = true
    }

    fun stop() {
        if (!isRunning) return
        registrar.unregister(this)
        isRunning = false
        haveGravity = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GRAVITY -> {
                gravity[0] = event.values[0]; gravity[1] = event.values[1]; gravity[2] = event.values[2]
                haveGravity = true
            }
            Sensor.TYPE_ACCELEROMETER -> {
                if (!gravitySensorTypeRegistered) {
                    val a = if (haveGravity) ACCEL_ALPHA else 1f
                    for (i in 0..2) gravity[i] += a * (event.values[i] - gravity[i])
                    haveGravity = true
                }
            }
            Sensor.TYPE_GYROSCOPE -> {
                if (!haveGravity) return
                val rate = GyroYawTracker.headingRateFrom(event.values, gravity) ?: return
                tracker.onSample(event.timestamp / 1_000_000L, rate)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        const val GYRO_PERIOD_US = 20_000          // 50 Hz
        const val GYRO_MAX_LATENCY_US = 100_000    // 100 ms FIFO batching: fewer wakeups, still << frame lag
        const val GRAVITY_PERIOD_US = 100_000      // 10 Hz is plenty for a slowly varying vector
        private const val ACCEL_ALPHA = 0.05f
    }
}
