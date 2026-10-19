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
 */
class ResumeMotionGate(private val maxAccuracyM: Float = MAX_ACCURACY_M) {
    private var startMs = -1L
    private var startLat = 0.0
    private var startLng = 0.0

    fun reset() {
        startMs = -1L
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
        if (accuracyM == null || accuracyM > maxAccuracyM || speedKmh < targetSpeedKmh * SPEED_FRACTION) {
            reset()
            return false
        }
        if (startMs < 0L) {
            if (speedKmh < targetSpeedKmh) return false
            startMs = nowMs
            startLat = lat
            startLng = lng
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
        /** Same accuracy gate as auto-start. */
        const val MAX_ACCURACY_M = 25f
        const val SPEED_FRACTION = 0.7f
        private const val METERS_PER_DEG = 111_320.0
    }
}
