package janush.tech.whereami

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Ultra-low power motion trigger using Android's hardware TYPE_SIGNIFICANT_MOTION sensor.
 *
 * This hardware sensor operates directly on the low-power sensor hub/MCU while the main CPU
 * and GNSS hardware remain in deep sleep (~0 mW power draw). It generates a one-shot interrupt
 * when significant physical locomotion occurs (e.g. user starts walking, cycling, or driving).
 *
 * Used by AppStateManager and TripManager to avoid continuous GNSS hardware polling
 * while preserving automatic trip detection.
 */
class MotionWakeManager private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: MotionWakeManager? = null

        fun getInstance(context: Context): MotionWakeManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MotionWakeManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    val motionSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)

    private val _motionEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val motionEvents: SharedFlow<Unit> = _motionEvents.asSharedFlow()

    @Volatile
    private var isArmed = false

    private var onMotionTriggeredCallback: (() -> Unit)? = null

    private val triggerListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            isArmed = false
            TelemetryLogger.log("POWER", "Significant motion hardware sensor triggered! Locomotion detected.")
            _motionEvents.tryEmit(Unit)
            try {
                onMotionTriggeredCallback?.invoke()
            } catch (e: Exception) {
                TelemetryLogger.log("ERROR", "MotionWakeManager callback error: ${e.message}")
            }
        }
    }

    val isAvailable: Boolean
        get() = motionSensor != null

    fun isArmed(): Boolean = isArmed

    fun setOnMotionTriggeredListener(listener: (() -> Unit)?) {
        this.onMotionTriggeredCallback = listener
    }

    @Synchronized
    fun arm(): Boolean {
        if (isArmed) return true
        if (sensorManager == null || motionSensor == null) {
            TelemetryLogger.log("POWER", "Cannot arm motion trigger: TYPE_SIGNIFICANT_MOTION sensor unavailable on device")
            return false
        }
        val success = sensorManager.requestTriggerSensor(triggerListener, motionSensor)
        isArmed = success
        if (success) {
            TelemetryLogger.log("POWER", "Significant motion trigger armed successfully (ultra-low power hardware sensor)")
        } else {
            TelemetryLogger.log("POWER", "Failed to arm significant motion trigger")
        }
        return success
    }

    @Synchronized
    fun disarm() {
        if (!isArmed || sensorManager == null || motionSensor == null) {
            isArmed = false
            return
        }
        try {
            sensorManager.cancelTriggerSensor(triggerListener, motionSensor)
        } catch (e: Exception) {
            TelemetryLogger.log("ERROR", "Error disarming significant motion sensor: ${e.message}")
        }
        isArmed = false
        TelemetryLogger.log("POWER", "Significant motion trigger disarmed")
    }
}
