package janush.tech.whereami

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Best-effort flush of an open trip when the device is shutting down or the battery is critically low.
 * Does not start services or acquire wake locks (Android 14+ FGS / AGENTS.md battery rules).
 */
class TripSurvivalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_SHUTDOWN && action != Intent.ACTION_BATTERY_LOW) return
        try {
            TripManager.getInstance(context.applicationContext).flushPendingForSurvival()
            TelemetryLogger.log("POWER", "TripSurvivalReceiver flushed pending trip rows ($action)")
        } catch (e: Exception) {
            TelemetryLogger.log("ERROR", "TripSurvivalReceiver flush failed: ${e.message}")
        }
    }
}
