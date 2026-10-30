package online.hatala.whereami

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * BroadcastReceiver for controlling background Elevation Correction
 * via notification actions (Pause, Resume, Stop).
 */
class ElevationActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_PAUSE = "online.hatala.whereami.ACTION_PAUSE_ELEVATION"
        const val ACTION_RESUME = "online.hatala.whereami.ACTION_RESUME_ELEVATION"
        const val ACTION_STOP = "online.hatala.whereami.ACTION_STOP_ELEVATION"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            ACTION_PAUSE -> ElevationCorrectionManager.pause()
            ACTION_RESUME -> ElevationCorrectionManager.resume()
            ACTION_STOP -> ElevationCorrectionManager.stop()
        }
    }
}
