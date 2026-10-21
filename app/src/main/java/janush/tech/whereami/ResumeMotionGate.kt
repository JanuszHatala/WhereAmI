package janush.tech.whereami

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max

/**
 * Confirms that a paused trip is moving again at the profile's travel speed.
 *
 * Indoors the provider reports 5-20 km/h (spikes to 70 km/h) with 25-170 m accuracy while the user walks
 * around; positions jump tens of metres. A first sample above the speed plus 30 m of jump within 10 s used
 * to resume a manually paused car trip (field trip 233, 16:22). Here every sample must be accurate and at
 * least [SPEED_FRACTION] of the target speed, otherwise the confirmation restarts, and the displacement
 * must match travelling at half the target speed for the whole window.
 *
 * Auto-start (trip 243): a single 26–35 m accuracy blip used to zero the whole window; vehicles now allow
 * one forgiven miss and start the window at [SPEED_FRACTION] (not full target). Indoor multipath still
 * fails the displacement floor.
 */
class ResumeMotionGate(
    private val maxAccuracyM: Float = MAX_ACCURACY_M,
    private val accuracyMissGrace: Int = 0
) {
    private var startMs = -1L
    private var startLat = 0.0
    private var startLng = 0.0
    private var accuracyMisses = 0

    fun reset() {
        startMs = -1L
        accuracyMisses = 0
    }

    /** Offers a fix; returns true when motion is confirmed (the caller resumes and should then [reset]). */
    fun offer(
        nowMs: Long,
        lat: Double,
        lng: Double,
        speedKmh: Float,
        accuracyM: Float?,
        targetSpeedKmh: Float,
        requiredDurationMs: Long,
        requiredDisplacementM: Double
    ): Boolean {
        val minSpeed = targetSpeedKmh * SPEED_FRACTION
        if (speedKmh < minSpeed) {
            reset()
            return false
        }
        if (accuracyM == null || accuracyM > maxAccuracyM) {
            if (startMs >= 0L && accuracyMisses < accuracyMissGrace) {
                accuracyMisses++
                return false
            }
            reset()
            return false
        }
        if (startMs < 0L) {
            // Window starts at the fraction threshold (was: full target), so a clean pull-away at
            // 7–9 km/h on a 10 km/h CAR profile is not ignored until a later spike.
            startMs = nowMs
            startLat = lat
            startLng = lng
            accuracyMisses = 0
            return false
        }
        val elapsedMs = nowMs - startMs
        if (elapsedMs < requiredDurationMs) return false
        val travelM = 0.5 * targetSpeedKmh / 3.6 * elapsedMs / 1000.0
        return distanceM(startLat, startLng, lat, lng) >= max(requiredDisplacementM, travelM)
    }

    private fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val k = cos(Math.toRadians((lat1 + lat2) / 2.0))
        return hypot((lng2 - lng1) * k, lat2 - lat1) * METERS_PER_DEG
    }

    companion object {
        /** Pause-resume accuracy gate (strict). */
        const val MAX_ACCURACY_M = 25f
        /** Vehicle auto-start may accept slightly softer GNSS while leaving courtyards / tree cover. */
        const val AUTO_START_MAX_ACCURACY_M = 35f
        const val SPEED_FRACTION = 0.7f
        private const val METERS_PER_DEG = 111_320.0

        fun forVehicleAutoStart() = ResumeMotionGate(
            maxAccuracyM = AUTO_START_MAX_ACCURACY_M,
            accuracyMissGrace = 1
        )
    }
}
