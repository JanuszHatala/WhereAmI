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

    const val MIN_DIM_LEVEL_PERCENT = 0
    const val MAX_DIM_LEVEL_PERCENT = 100

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
     * Returns window screenBrightness value (0.0..1.0 when dimmed via perceptual quadratic curve, BRIGHTNESS_OVERRIDE_NONE otherwise).
     */
    fun resolveWindowBrightness(
        shouldKeepScreenOn: Boolean,
        isDimmed: Boolean,
        effectiveDimEnabled: Boolean,
        effectiveLevelPercent: Int,
        isDimPreview: Boolean = false
    ): Float {
        if (!isDimPreview && (!shouldKeepScreenOn || !effectiveDimEnabled || !isDimmed)) {
            return WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        val clampedPercent = clampLevelPercent(effectiveLevelPercent)
        val fraction = clampedPercent / 100f
        return (fraction * fraction).coerceIn(0.0f, 1.0f)
    }

    /**
     * Resolves dark scrim overlay alpha (0.0..0.92) to overcome hardware display minimum luminance floors
     * and produce true deep dimming down to near-black at 0-5%.
     */
    fun resolveScrimAlpha(effectiveLevelPercent: Int): Float {
        val clamped = clampLevelPercent(effectiveLevelPercent)
        return if (clamped >= 50) {
            0.0f
        } else {
            val t = (50 - clamped) / 50f // 0.0 at 50%, 1.0 at 0%
            (t * 0.92f).coerceIn(0.0f, 0.95f)
        }
    }

    fun formatTimeoutLabel(seconds: Int): String = when {
        seconds < 60 -> "${seconds}s"
        seconds % 60 == 0 -> "${seconds / 60}m"
        else -> "${seconds}s"
    }
}
