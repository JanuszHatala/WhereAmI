package janush.tech.whereami

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

/**
 * Accelerometer-based physical motion detector.
 *
 * Distinguishes true physical locomotion from stationary GPS multipath noise
 * (e.g. resting on a desk or during sleep where Doppler GPS creates 5-14 km/h phantom spikes).
 *
 * Computes the variance of the acceleration vector magnitude |a| over a sliding window.
 * When variance is below threshold, the device is verified to be physically stationary.
 */
class StationaryDetector private constructor(context: Context) : SensorEventListener {

    companion object {
        @Volatile
        private var INSTANCE: StationaryDetector? = null

        fun getInstance(context: Context): StationaryDetector {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: StationaryDetector(context.applicationContext).also { INSTANCE = it }
            }
        }

        // Accelerometer variance threshold: below 0.04 (m/s^2)^2 indicates zero physical movement (desk/table/sleep)
        const val VARIANCE_THRESHOLD = 0.045f
        const val WINDOW_SIZE = 25 // ~1.5 - 2.5 seconds of sensor readings at SENSOR_DELAY_UI

        fun computeVariance(magnitudes: Collection<Float>): Float {
            if (magnitudes.isEmpty()) return 0f
            var sum = 0f
            for (m in magnitudes) sum += m
            val mean = sum / magnitudes.size
            var sumSquares = 0f
            for (m in magnitudes) {
                val diff = m - mean
                sumSquares += diff * diff
            }
            return sumSquares / magnitudes.size
        }
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val _isPhysicallyStationary = MutableStateFlow(false)
    val isPhysicallyStationary: StateFlow<Boolean> = _isPhysicallyStationary.asStateFlow()

    private val magnitudeWindow = ArrayDeque<Float>(WINDOW_SIZE)
    private var isListening = false

    fun startListening() {
        if (isListening || sensorManager == null || accelerometer == null) return
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
        isListening = true
        TelemetryLogger.log("SENSOR", "StationaryDetector started listening to accelerometer")
    }

    fun stopListening() {
        if (!isListening) return
        sensorManager?.unregisterListener(this)
        isListening = false
        _isPhysicallyStationary.value = false
        magnitudeWindow.clear()
        TelemetryLogger.log("SENSOR", "StationaryDetector stopped listening")
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val ax = event.values[0]
        val ay = event.values[1]
        val az = event.values[2]
        val magnitude = sqrt((ax * ax + ay * ay + az * az).toDouble()).toFloat()

        synchronized(magnitudeWindow) {
            magnitudeWindow.add(magnitude)
            if (magnitudeWindow.size > WINDOW_SIZE) {
                magnitudeWindow.removeFirst()
            }

            if (magnitudeWindow.size >= 12) {
                val variance = computeVariance(magnitudeWindow)
                val nowStationary = variance < VARIANCE_THRESHOLD
                if (_isPhysicallyStationary.value != nowStationary) {
                    _isPhysicallyStationary.value = nowStationary
                    if (nowStationary) {
                        TelemetryLogger.log("SENSOR", "Device is physically stationary on surface (var=${String.format("%.4f", variance)})")
                    } else {
                        TelemetryLogger.log("SENSOR", "Device physical motion detected (var=${String.format("%.4f", variance)})")
                    }
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op
    }
}
