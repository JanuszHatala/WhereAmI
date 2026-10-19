package janush.tech.whereami

import android.content.Intent

/**
 * Encapsulates target detection and consumption of notification and deep-link intents.
 * Prevents re-triggering dialogs/sheets upon activity recreation during device rotation.
 */
data class ParsedNotificationIntent(
    val isElevation: Boolean = false,
    val isCache: Boolean = false,
    val isLiveSharing: Boolean = false
) {
    val hasTarget: Boolean get() = isElevation || isCache || isLiveSharing
}

object NotificationIntentEvaluator {

    /**
     * Notification actions are one-shot triggers intended only for fresh launches or explicit new intents.
     * When [savedInstanceState] is non-null, the activity is being recreated (e.g. rotation),
     * so notification intents must NOT be replayed.
     */
    fun shouldHandleOnCreate(savedInstanceState: Any?, intent: Intent?): Boolean {
        return savedInstanceState == null && intent != null
    }

    /**
     * Evaluates the notification intent, dispatches the intended target, and immediately consumes
     * and clears the triggering action and extras to prevent replay on subsequent configuration changes.
     */
    fun parseAndConsume(intent: Intent?): ParsedNotificationIntent {
        if (intent == null) return ParsedNotificationIntent()

        val isElevation = intent.getBooleanExtra(MainActivity.EXTRA_OPEN_SETTINGS_ELEVATION, false) ||
            intent.action == MainActivity.ACTION_SHOW_ELEVATION_SETTINGS
        val isCache = intent.getBooleanExtra(CacheManager.EXTRA_OPEN_CACHE_MANAGER, false) ||
            intent.action == CacheManager.ACTION_OPEN_CACHE_MANAGER
        val isLiveSharing = intent.getBooleanExtra(LiveTrackingService.EXTRA_OPEN_LIVE_SHARING, false) ||
            intent.action == LiveTrackingService.ACTION_OPEN_LIVE_SHARING

        if (isElevation) {
            intent.removeExtra(MainActivity.EXTRA_OPEN_SETTINGS_ELEVATION)
            if (intent.action == MainActivity.ACTION_SHOW_ELEVATION_SETTINGS) {
                intent.action = Intent.ACTION_MAIN
            }
        }
        if (isCache) {
            intent.removeExtra(CacheManager.EXTRA_OPEN_CACHE_MANAGER)
            if (intent.action == CacheManager.ACTION_OPEN_CACHE_MANAGER) {
                intent.action = Intent.ACTION_MAIN
            }
        }
        if (isLiveSharing) {
            intent.removeExtra(LiveTrackingService.EXTRA_OPEN_LIVE_SHARING)
            if (intent.action == LiveTrackingService.ACTION_OPEN_LIVE_SHARING) {
                intent.action = Intent.ACTION_MAIN
            }
        }

        return ParsedNotificationIntent(
            isElevation = isElevation,
            isCache = isCache,
            isLiveSharing = isLiveSharing
        )
    }

    /**
     * Pure logic evaluator for unit testing without Android Intent stubs.
     */
    fun parseActions(
        action: String?,
        extraElevation: Boolean = false,
        extraCache: Boolean = false,
        extraLiveSharing: Boolean = false
    ): ParsedNotificationIntent {
        val isElevation = extraElevation || action == MainActivity.ACTION_SHOW_ELEVATION_SETTINGS
        val isCache = extraCache || action == CacheManager.ACTION_OPEN_CACHE_MANAGER
        val isLiveSharing = extraLiveSharing || action == LiveTrackingService.ACTION_OPEN_LIVE_SHARING
        return ParsedNotificationIntent(isElevation, isCache, isLiveSharing)
    }
}
