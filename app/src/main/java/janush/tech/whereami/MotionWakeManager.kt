package janush.tech.whereami

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.Build
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
 * ## FGS Bootstrap Architecture (Android 14+)
 * When motion fires, we dispatch a PendingIntent broadcast to [MotionWakeBroadcastReceiver].
 * That receiver's onReceive() context is granted a 10-second FGS-start exemption by the OS
 * (code:BROADCAST), which is the ONLY reliable way to start a Location FGS from background
 * on Android 14+ (targetSdk 36) without a visible foreground token already held.
 *
 * The legacy [motionEvents] SharedFlow is preserved for in-process consumers
 * (AppStateManager coroutines) that run when the app is already in foreground.
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

    /**
     * PendingIntent delivered to [MotionWakeBroadcastReceiver] when motion fires.
     * Uses FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE (required on Android 12+).
     * The broadcast delivery gives onReceive() a guaranteed FGS-start exemption window.
     */
    private val motionWakePendingIntent: PendingIntent by lazy {
        val intent = Intent(context, MotionWakeBroadcastReceiver::class.java).apply {
            action = MotionWakeBroadcastReceiver.ACTION_MOTION_WAKE
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        PendingIntent.getBroadcast(context, 0, intent, flags)
    }

    private val triggerListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            isArmed = false
            TelemetryLogger.log("POWER", "Significant motion hardware sensor triggered! Locomotion detected.")

            // 1. Emit to in-process flow (for foreground-mode subscribers if any)
            _motionEvents.tryEmit(Unit)

            // 2. Deliver PendingIntent broadcast → MotionWakeBroadcastReceiver
            // This is the PRIMARY path for background FGS bootstrap on Android 14+.
            try {
                motionWakePendingIntent.send()
                TelemetryLogger.log("POWER", "MotionWake PendingIntent broadcast dispatched to MotionWakeBroadcastReceiver")
            } catch (e: PendingIntent.CanceledException) {
                TelemetryLogger.log("ERROR", "MotionWake PendingIntent was cancelled: ${e.message}")
            }

            // 3. Legacy callback for any direct in-process listeners
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
