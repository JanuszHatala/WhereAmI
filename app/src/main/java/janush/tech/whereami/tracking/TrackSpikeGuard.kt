package janush.tech.whereami.tracking

import kotlin.math.cos
import kotlin.math.hypot

/**
 * Rejects GNSS multipath "spikes" that would draw a long chord into the recorded polyline
 * (field trip 240: 386 m jump in 4 s at 0 m/s on auto-stop at home).
 *
 * The innovation / implied-speed gate usually catches these in the filter; this is a last line of
 * defence on **stored** vertices (sampler drain + stop flush) where a discontinuous tip still slips in.
 */
object TrackSpikeGuard {
    /** Minimum jump (m) before a step can be called a spike. */
    const val MIN_JUMP_M = 60.0
    /** Implied speed (m/s) above which a jump is physically impossible for any profile. */
    const val MAX_IMPLIED_MPS = 40.0
    /** Speeds at or below this count as "slow / parked" for the spike rule. */
    const val SLOW_MPS = 2.5

    fun isSpikeStep(
        prevLat: Double,
        prevLng: Double,
        prevUtcMs: Long,
        lat: Double,
        lng: Double,
        utcMs: Long,
        speedMps: Double,
        isStationary: Boolean
    ): Boolean {
        val d = distanceM(prevLat, prevLng, lat, lng)
        if (d < MIN_JUMP_M) return false
        val dtSec = (utcMs - prevUtcMs) / 1000.0
        val implied = if (dtSec > 0.05) d / dtSec else Double.POSITIVE_INFINITY
        return isStationary || speedMps < SLOW_MPS || implied > MAX_IMPLIED_MPS
    }

    /**
     * Drops trailing vertices that form spike chords from the previous point.
     * Idempotent; keeps at least one point.
     */
    fun <T> trimTrailing(
        points: List<T>,
        latOf: (T) -> Double,
        lngOf: (T) -> Double,
        utcOf: (T) -> Long,
        speedOf: (T) -> Double = { 0.0 },
        stationaryOf: (T) -> Boolean = { true }
    ): List<T> {
        if (points.size < 2) return points
        val out = points.toMutableList()
        while (out.size >= 2) {
            val prev = out[out.size - 2]
            val last = out.last()
            if (!isSpikeStep(
                    latOf(prev), lngOf(prev), utcOf(prev),
                    latOf(last), lngOf(last), utcOf(last),
                    speedOf(last), stationaryOf(last)
                )
            ) break
            out.removeAt(out.size - 1)
        }
        return out
    }

    fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val k = cos(Math.toRadians((lat1 + lat2) / 2.0))
        return hypot((lng2 - lng1) * k, lat2 - lat1) * METERS_PER_DEG
    }

    private const val METERS_PER_DEG = 111_320.0
}
