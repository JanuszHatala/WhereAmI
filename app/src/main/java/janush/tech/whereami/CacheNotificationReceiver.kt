package janush.tech.whereami

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class CacheNotificationReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_PAUSE = "janush.tech.whereami.ACTION_PREFETCH_PAUSE"
        const val ACTION_RESUME = "janush.tech.whereami.ACTION_PREFETCH_RESUME"
        const val ACTION_STOP = "janush.tech.whereami.ACTION_PREFETCH_STOP"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val cacheManager = CacheManager.getInstance(context)
        when (intent?.action) {
            ACTION_PAUSE -> cacheManager.pausePrefetch()
            ACTION_RESUME -> cacheManager.resumePrefetch()
            ACTION_STOP -> cacheManager.cancelPrefetch()
        }
    }
}
