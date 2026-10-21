package janush.tech.whereami.tracking

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.abs

/**
 * Rejects GNSS multipath "spikes" that would draw a long chord into the recorded polyline
 * (field trip 240: 386 m jump in 4 s at 0 m/s on auto-stop at home;
 * field trip 244: 59.6 m / 172° reverse after a 24 s gap near stop while still reporting 7 m/s).
 *
 * The innovation / implied-speed gate usually catches these in the filter; this is a last line of
 * defence on **stored** vertices (sampler drain + stop flush) where a discontinuous tip still slips in.
 */
object TrackSpikeGuard {
    /** Minimum jump (m) before a step can be called a slow/impossible spike. */
    const val MIN_JUMP_M = 45.0
    /** Implied speed (m/s) above which a jump is physically impossible for any profile. */
    const val MAX_IMPLIED_MPS = 40.0
    /** Speeds at or below this count as "slow / parked" for the spike rule. */
    const val SLOW_MPS = 2.5

    /** Course-reversal spike: minimum chord (m). */
    const val REVERSAL_JUMP_M = 25.0
    /** Course-reversal spike: minimum heading change (deg). */
    const val REVERSAL_COURSE_DEG = 135.0
    /** Course-reversal spike: minimum gap (s) so tight hairpins at speed are kept. */
    const val REVERSAL_GAP_S = 5.0

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
        if (isStationary || speedMps < SLOW_MPS || implied > MAX_IMPLIED_MPS) return true
        // Long gap with a large teleport that covers far less than Doppler travel: nest snap-back
        // (trip 244: 59.6 m in 24 s at 7 m/s — expected ~168 m along-track).
        if (dtSec >= 10.0 && speedMps > 0.5) {
            val expected = speedMps * dtSec
            if (expected > 0.0 && d < expected * 0.40) return true
        }
        return false
    }

    /**
     * Three-point course-reversal spike: A→B then B→C reverses hard across a material gap.
     * Catches trip 244 tip (bearing delta ≈ 172°, 59.6 m / 24 s) and the smaller 27.5 m reverse.
     */
    fun isReversalSpike(
        aLat: Double,
        aLng: Double,
        bLat: Double,
        bLng: Double,
        bUtcMs: Long,
        cLat: Double,
        cLng: Double,
        cUtcMs: Long
    ): Boolean {
        val bc = distanceM(bLat, bLng, cLat, cLng)
        if (bc < REVERSAL_JUMP_M) return false
        val ab = distanceM(aLat, aLng, bLat, bLng)
        if (ab < 3.0) return false
        val dtSec = (cUtcMs - bUtcMs) / 1000.0
        if (dtSec < REVERSAL_GAP_S) return false
        val delta = courseDeltaDeg(
            bearingDeg(aLat, aLng, bLat, bLng),
            bearingDeg(bLat, bLng, cLat, cLng)
        )
        return delta >= REVERSAL_COURSE_DEG
    }

    /**
     * Drops trailing vertices that form spike chords from the previous point (and 3-point reversals).
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
            val twoPoint = isSpikeStep(
                latOf(prev), lngOf(prev), utcOf(prev),
                latOf(last), lngOf(last), utcOf(last),
                speedOf(last), stationaryOf(last)
            )
            val threePoint = out.size >= 3 && isReversalSpike(
                latOf(out[out.size - 3]), lngOf(out[out.size - 3]),
                latOf(prev), lngOf(prev), utcOf(prev),
                latOf(last), lngOf(last), utcOf(last)
            )
            if (!twoPoint && !threePoint) break
            out.removeAt(out.size - 1)
        }
        return out
    }

    fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val k = cos(Math.toRadians((lat1 + lat2) / 2.0))
        return hypot((lng2 - lng1) * k, lat2 - lat1) * METERS_PER_DEG
    }

    fun bearingDeg(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val φ1 = Math.toRadians(lat1)
        val φ2 = Math.toRadians(lat2)
        val Δλ = Math.toRadians(lng2 - lng1)
        val y = sin(Δλ) * cos(φ2)
        val x = cos(φ1) * sin(φ2) - sin(φ1) * cos(φ2) * cos(Δλ)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    fun courseDeltaDeg(a: Double, b: Double): Double {
        val d = abs(a - b) % 360.0
        return if (d > 180.0) 360.0 - d else d
    }

    private const val METERS_PER_DEG = 111_320.0
}
