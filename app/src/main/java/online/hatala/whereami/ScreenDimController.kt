package online.hatala.whereami

import android.content.SharedPreferences
import android.view.WindowManager

/**
 * Pure state and preference helper for idle screen dimming when Keep Screen On is active.
 * Hardware backlight is controlled via WindowManager.LayoutParams.screenBrightness.
 */
object ScreenDimController {
    const val PREF_DIM_ENABLED = "pref_screen_dim_enabled"
    const val PREF_DIM_TIMEOUT_SEC = "pref_screen_dim_timeout_sec"
    const val PREF_DIM_LEVEL_PERCENT = "pref_screen_dim_level_percent"

    const val DEFAULT_DIM_ENABLED = true
    const val DEFAULT_DIM_TIMEOUT_SEC = 30
    const val DEFAULT_DIM_LEVEL_PERCENT = 20

    const val MIN_DIM_LEVEL_PERCENT = 5
    const val MAX_DIM_LEVEL_PERCENT = 60

    val TIMEOUT_OPTIONS_SEC = listOf(15, 30, 60, 120, 300)

    fun clampLevelPercent(percent: Int): Int =
        percent.coerceIn(MIN_DIM_LEVEL_PERCENT, MAX_DIM_LEVEL_PERCENT)

    fun clampTimeoutSec(seconds: Int): Int =
        seconds.coerceIn(5, 600)

    fun loadDefaultEnabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(PREF_DIM_ENABLED, DEFAULT_DIM_ENABLED)

    fun loadDefaultTimeoutSec(prefs: SharedPreferences): Int =
        clampTimeoutSec(prefs.getInt(PREF_DIM_TIMEOUT_SEC, DEFAULT_DIM_TIMEOUT_SEC))

    fun loadDefaultLevelPercent(prefs: SharedPreferences): Int =
        clampLevelPercent(prefs.getInt(PREF_DIM_LEVEL_PERCENT, DEFAULT_DIM_LEVEL_PERCENT))

    /**
     * Resolves effective dimming state considering per-trip/session overrides.
     */
    fun isEffectiveDimEnabled(defaultEnabled: Boolean, sessionOverrideEnabled: Boolean?): Boolean =
        sessionOverrideEnabled ?: defaultEnabled

    fun effectiveLevelPercent(defaultPercent: Int, sessionOverridePercent: Int?): Int =
        clampLevelPercent(sessionOverridePercent ?: defaultPercent)

    /**
     * Returns window screenBrightness value (0.05..0.60 when dimmed, BRIGHTNESS_OVERRIDE_NONE otherwise).
     */
    fun resolveWindowBrightness(
        shouldKeepScreenOn: Boolean,
        isDimmed: Boolean,
        effectiveDimEnabled: Boolean,
        effectiveLevelPercent: Int
    ): Float {
        if (!shouldKeepScreenOn || !effectiveDimEnabled || !isDimmed) {
            return WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        return (clampLevelPercent(effectiveLevelPercent) / 100f).coerceIn(0.05f, 0.60f)
    }

    fun formatTimeoutLabel(seconds: Int): String = when {
        seconds < 60 -> "${seconds}s"
        seconds % 60 == 0 -> "${seconds / 60}m"
        else -> "${seconds}s"
    }
}
