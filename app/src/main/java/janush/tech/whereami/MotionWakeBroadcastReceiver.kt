package janush.tech.whereami

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager

/**
 * Exported BroadcastReceiver that receives the significant-motion wake signal from
 * [MotionWakeManager] via a PendingIntent-delivered broadcast.
 *
 * WHY A BROADCAST RECEIVER?
 * Android 14+ (targetSdk 36) forbids starting a Location ForegroundService from background
 * via [Context.startForegroundService] unless the app is in one of the exempted states
 * (e.g. PROC_STATE_TOP, BFSL, or the ~10 s grace period right after leaving foreground).
 * However, a BroadcastReceiver's [onReceive] context is always granted a 10-second
 * FGS-start exemption by the OS (code:BROADCAST), regardless of the caller's process state.
 *
 * This makes the receiver the only reliable path to start the GPS burst FGS from a
 * background sensor wakeup (TYPE_SIGNIFICANT_MOTION → PendingIntent → onReceive → FGS).
 */
class MotionWakeBroadcastReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_MOTION_WAKE = "janush.tech.whereami.ACTION_MOTION_WAKE"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_MOTION_WAKE) return

        val tripManager = TripManager.getInstance(context)

        // Only act if in AUTO mode with no trip already running
        if (tripManager.tripMode.value != TripMode.AUTO) return
        if (tripManager.activeTrip.value != null) return

        TelemetryLogger.log("POWER", "MotionWakeBroadcastReceiver: significant motion broadcast received — starting GPS burst via AppStateManager")

        // Acquire a CPU wake lock so the main thread doesn't go back to sleep
        // before AppStateManager can set up the burst. Keep it short — AppStateManager
        // will acquire its own MotionBurstWakeLock immediately.
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val bootstrapLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "WhereAmI:MotionWakeBootstrap"
        )
        bootstrapLock.acquire(10_000L) // 10 s max bootstrap window

        try {
            // Delegate to AppStateManager which holds the full burst logic and wake lock
            AppStateManager.getInstance(context).handleMotionWakeFromBroadcast()

            // Start LiveTrackingService as FGS to hold the location FGS type token.
            // onReceive() context has a guaranteed ~10 s FGS-start exemption on Android 14+
            // (code:BROADCAST), so this call always succeeds regardless of app process state.
            val fgsIntent = Intent(context, LiveTrackingService::class.java).apply {
                action = LiveTrackingService.ACTION_ENTER_STANDBY
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(fgsIntent)
            } else {
                context.startService(fgsIntent)
            }
        } finally {
            if (bootstrapLock.isHeld) bootstrapLock.release()
        }
    }
}
