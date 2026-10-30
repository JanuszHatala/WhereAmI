package online.hatala.whereami.tracking

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener

/**
 * When is the compass worth its power? Same discipline as [GyroPolicy]: only with the screen on (a render loop
 * consumes it), on foot, moving, following or recording, and the user setting enabled.
 */
object CompassPolicy {
    fun shouldRun(
        enabled: Boolean,
        onFoot: Boolean,
        isForeground: Boolean,
        isMoving: Boolean,
        isFollowingOrRecording: Boolean
    ): Boolean = enabled && onFoot && isForeground && isMoving && isFollowingOrRecording

    /** Walking pace hysteresis: on above 0.6 m/s, off below 0.3 m/s. */
    fun isMovingForCompass(speedMps: Float, wasMoving: Boolean): Boolean =
        if (wasMoving) speedMps >= 0.3f else speedMps >= 0.6f
}

/**
 * Foreground-only compass: the fused rotation-vector sensor (hardware sensor hub, around 1 mW) converted to the
 * direction the user faces ([CompassMath]) in true-north degrees and stored in [CompassTracker].
 * Registered only while [CompassPolicy] allows it; [stop] is idempotent.
 */
class CompassHeadingSensor(
    private val registrar: SensorRegistrar,
    private val tracker: CompassTracker = TrackingRuntime.compass
) : SensorEventListener {

    @Volatile
    var isRunning: Boolean = false
        private set

    /** Magnetic declination at the current position, degrees east (set by the caller from the location). */
    @Volatile
    var declinationDeg: Double = 0.0

    @Volatile
    private var accuracy: Int = ACCURACY_UNRELIABLE

    fun start() {
        if (isRunning) return
        tracker.clear()
        accuracy = ACCURACY_UNRELIABLE
        isRunning = registrar.register(Sensor.TYPE_ROTATION_VECTOR, PERIOD_US, MAX_LATENCY_US, this)
    }

    fun stop() {
        if (!isRunning) return
        registrar.unregister(this)
        isRunning = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        // The event carries its own accuracy; the last callback value covers devices that only report there.
        val acc = if (event.accuracy > ACCURACY_UNRELIABLE) event.accuracy else accuracy
        if (acc < MIN_ACCURACY) return
        val h = CompassMath.facingTrueDeg(event.values, declinationDeg) ?: return
        tracker.onSample(event.timestamp / 1_000_000L, h)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        this.accuracy = accuracy
    }

    companion object {
        const val ACCURACY_UNRELIABLE = 0   // SensorManager.SENSOR_STATUS_UNRELIABLE
        const val PERIOD_US = 40_000          // 25 Hz
        const val MAX_LATENCY_US = 100_000    // batch up to 100 ms: fewer wakeups, far below the render lag
        /** SENSOR_STATUS_ACCURACY_LOW; UNRELIABLE (0) and NO_CONTACT (-1) are ignored. The learned-offset gate
         *  in [CompassArrow] rejects a poor compass that still reports LOW. */
        const val MIN_ACCURACY = 1
    }
}
