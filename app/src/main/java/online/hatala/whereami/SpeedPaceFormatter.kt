package online.hatala.whereami

import java.util.Locale

data class FormattedSpeedMetric(
    val primaryText: String,
    val separator: String? = null,
    val secondaryText: String? = null
) {
    val fullText: String
        get() = if (separator != null && secondaryText != null) {
            "$primaryText$separator$secondaryText"
        } else {
            primaryText
        }
}

/**
 * Computes active-trip moving average speed and formats combined current + average speed/pace strings.
 */
object SpeedPaceFormatter {

    /**
     * Computes moving average speed (km/h) for an active trip, excluding completed and open pauses.
     * Returns null when no trip is active or distance/moving time is below meaningful threshold.
     */
    fun computeMovingAvgSpeedKmh(
        trip: TripRecord?,
        nowUtcMs: Long = System.currentTimeMillis()
    ): Float? {
        if (trip == null) return null
        if (trip.distanceMeters <= 50.0) return null

        val elapsedMs = (nowUtcMs - trip.startTime).coerceAtLeast(0L)
        val pauseMs = trip.pauses.sumOf { pause ->
            val end = pause.endTime ?: nowUtcMs
            (end - pause.startTime).coerceAtLeast(0L)
        }
        val movingMs = (elapsedMs - pauseMs).coerceAtLeast(0L)
        if (movingMs <= 5_000L) return null

        val avgKmh = ((trip.distanceMeters / (movingMs / 1000.0)) * 3.6).toFloat()
        return if (avgKmh.isFinite() && avgKmh > 0f) avgKmh else null
    }

    fun formatPaceValue(speedKmh: Float): String {
        if (!speedKmh.isFinite() || speedKmh <= 1.0f) return "-"
        val paceMinPerKm = 60f / speedKmh
        val totalSec = kotlin.math.round(paceMinPerKm * 60f).toInt().coerceAtLeast(0)
        val min = totalSec / 60
        val sec = totalSec % 60
        return String.format(Locale.getDefault(), "%d:%02d", min, sec)
    }

    fun formatSpeedOrPaceParts(
        currentSpeedMs: Float?,
        movingAvgSpeedKmh: Float?,
        preferPace: Boolean
    ): FormattedSpeedMetric {
        val currentKmh = ((currentSpeedMs ?: 0f) * 3.6f).coerceAtLeast(0f)
        val hasValidAvg = movingAvgSpeedKmh != null && movingAvgSpeedKmh.isFinite() && movingAvgSpeedKmh > 0.5f

        return if (preferPace) {
            val curPace = formatPaceValue(currentKmh)
            if (hasValidAvg) {
                val avgPace = formatPaceValue(movingAvgSpeedKmh!!)
                FormattedSpeedMetric(
                    primaryText = curPace,
                    separator = " · ",
                    secondaryText = "⌀ $avgPace /km"
                )
            } else {
                FormattedSpeedMetric(primaryText = "$curPace min/km")
            }
        } else {
            val curStr = String.format(Locale.getDefault(), "%.1f", currentKmh)
            if (hasValidAvg) {
                val avgStr = String.format(Locale.getDefault(), "%.1f", movingAvgSpeedKmh!!)
                FormattedSpeedMetric(
                    primaryText = curStr,
                    separator = " · ",
                    secondaryText = "⌀ $avgStr km/h"
                )
            } else {
                FormattedSpeedMetric(primaryText = "$curStr km/h")
            }
        }
    }

    /**
     * Formats speed or pace for LocalityCard:
     * - Without avg: "23.4 km/h" or "5:12 min/km" (or "- min/km")
     * - With avg: "23.4 · ⌀ 18.2 km/h" or "5:12 · ⌀ 5:40 /km"
     */
    fun formatSpeedOrPace(
        currentSpeedMs: Float?,
        movingAvgSpeedKmh: Float?,
        preferPace: Boolean
    ): String = formatSpeedOrPaceParts(currentSpeedMs, movingAvgSpeedKmh, preferPace).fullText
}
