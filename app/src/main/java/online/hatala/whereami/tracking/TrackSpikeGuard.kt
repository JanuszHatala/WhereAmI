package online.hatala.whereami.tracking

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.abs

/**
 * Rejects GNSS multipath "spikes" that would draw a long chord into the recorded polyline
 * (field trip 240: 386 m jump in 4 s at 0 m/s on auto-stop at home;
 * field trip 244: 59.6 m / 172° reverse after a 24 s gap near stop while still reporting 7 m/s;
 * field trip 248: lateral V/Z zigzags on straight urban corridors while course stays consistent).
 *
 * The innovation / implied-speed gate usually catches these in the filter; this is a last line of
 * defence on **stored** vertices (sampler drain + stop flush) where a discontinuous tip still slips in.
 * Prefer dropping lateral outliers vs the recent course average over rewriting geometry.
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

    /** Lateral V-spike (A→B→C): minimum cross-track of B from chord A–C (m). */
    const val LATERAL_V_XT_M = 6.0
    /** Lateral V-spike: minimum heading change at B (deg); softer than [REVERSAL_COURSE_DEG]. */
    const val LATERAL_V_TURN_DEG = 75.0
    /** Lateral V-spike: each leg A→B and B→C must be at least this long (m). */
    const val LATERAL_V_LEG_M = 3.0
    /** Lateral V-spike: chord A→C must retain forward progress (m). */
    const val LATERAL_V_CHORD_M = 4.0

    /**
     * Online lateral outlier vs recent course: minimum cross-track component of the new step (m).
     * Field trip 248 urban weave on Piłsudskiego / Głęboka was ~5–12 m off a steady corridor.
     */
    const val LATERAL_COURSE_XT_M = 8.0
    /** Reject when |cross| / along exceeds this and |cross| >= [LATERAL_COURSE_XT_M] * 0.65. */
    const val LATERAL_COURSE_XT_FRACTION = 0.65
    /** Minimum recent-course consistency: consecutive recent step bearings within this (deg). */
    const val LATERAL_COURSE_STABLE_DEG = 35.0
    /** Apply course-lateral rule only at driving-ish speeds (m/s). */
    const val LATERAL_COURSE_MIN_SPEED_MPS = 3.0
    /** How many recent steps (before the candidate) establish the mean course. */
    const val LATERAL_COURSE_WINDOW = 4

    fun gatesForProfile(profileName: String?): Pair<Double, Double> {
        val upper = profileName?.uppercase(java.util.Locale.ROOT) ?: ""
        return when {
            upper.contains("WALK") || upper.contains("HIKE") -> Pair(15.0, 5.0)
            upper.contains("RUN") -> Pair(20.0, 8.5)
            upper.contains("CYCLE") || upper.contains("BIKE") || upper.contains("MTB") -> Pair(30.0, 20.0)
            else -> Pair(MIN_JUMP_M, MAX_IMPLIED_MPS)
        }
    }

    fun isSpikeStep(
        prevLat: Double,
        prevLng: Double,
        prevUtcMs: Long,
        lat: Double,
        lng: Double,
        utcMs: Long,
        speedMps: Double,
        isStationary: Boolean,
        minJumpM: Double = MIN_JUMP_M,
        maxImpliedMps: Double = MAX_IMPLIED_MPS
    ): Boolean {
        val d = distanceM(prevLat, prevLng, lat, lng)
        if (d < minJumpM) return false
        val dtSec = (utcMs - prevUtcMs) / 1000.0
        val implied = if (dtSec > 0.05) d / dtSec else Double.POSITIVE_INFINITY
        val slowTeleport = speedMps < SLOW_MPS && implied > SLOW_MPS * 2.0
        if (isStationary || slowTeleport || implied > maxImpliedMps) return true
        // Long gap with a large teleport that covers far less than Doppler travel: nest snap-back
        // (trip 244: 59.6 m in 24 s at 7 m/s - expected ~168 m along-track).
        if (dtSec in 10.0..30.0 && speedMps > 0.5) {
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
     * Three-point lateral V/Z: B sticks out beside a short A→C chord with a sharp turn at B.
     * Unlike [isReversalSpike], no gap is required — urban 1 Hz multipath on a straight corridor
     * (trip 248 Głęboka / Piłsudskiego) returns within a couple of seconds.
     * When true, the caller should drop B (the middle), not C.
     */
    fun isLateralVSpike(
        aLat: Double,
        aLng: Double,
        bLat: Double,
        bLng: Double,
        cLat: Double,
        cLng: Double
    ): Boolean {
        val ab = distanceM(aLat, aLng, bLat, bLng)
        val bc = distanceM(bLat, bLng, cLat, cLng)
        val ac = distanceM(aLat, aLng, cLat, cLng)
        if (ab < LATERAL_V_LEG_M || bc < LATERAL_V_LEG_M || ac < LATERAL_V_CHORD_M) return false
        // Forward progress along the corridor: detour must be a side excursion, not a U-turn nest.
        val detour = ab + bc - ac
        if (detour < LATERAL_V_XT_M) return false
        if (ac < ab * 0.35 && ac < bc * 0.35) return false
        val turn = courseDeltaDeg(
            bearingDeg(aLat, aLng, bLat, bLng),
            bearingDeg(bLat, bLng, cLat, cLng)
        )
        if (turn < LATERAL_V_TURN_DEG) return false
        return crossTrackM(aLat, aLng, cLat, cLng, bLat, bLng) >= LATERAL_V_XT_M
    }

    /**
     * Online: the new step from the last committed point is mostly sideways relative to a stable
     * recent course (mean of the last [LATERAL_COURSE_WINDOW] steps). Real intersection turns
     * break course stability and are kept.
     *
     * [recent] is oldest→newest and must already include the previous committed point as its last
     * entry; the candidate is [newLat]/[newLng].
     */
    fun isLateralCourseOutlier(
        recent: List<Pair<Double, Double>>,
        newLat: Double,
        newLng: Double,
        speedMps: Double
    ): Boolean {
        if (speedMps < LATERAL_COURSE_MIN_SPEED_MPS) return false
        if (recent.size < LATERAL_COURSE_WINDOW + 1) return false
        val window = recent.takeLast(LATERAL_COURSE_WINDOW + 1)
        val stepBearings = ArrayList<Double>(LATERAL_COURSE_WINDOW)
        for (i in 1 until window.size) {
            val d = distanceM(window[i - 1].first, window[i - 1].second, window[i].first, window[i].second)
            if (d < 2.0) continue
            stepBearings.add(bearingDeg(window[i - 1].first, window[i - 1].second, window[i].first, window[i].second))
        }
        if (stepBearings.size < 2) return false
        for (i in 1 until stepBearings.size) {
            if (courseDeltaDeg(stepBearings[i - 1], stepBearings[i]) > LATERAL_COURSE_STABLE_DEG) return false
        }
        val meanCourse = circularMeanDeg(stepBearings)
        val prev = window.last()
        val step = distanceM(prev.first, prev.second, newLat, newLng)
        if (step < 3.0) return false
        val stepBrg = bearingDeg(prev.first, prev.second, newLat, newLng)
        val lateral = abs(step * sin(Math.toRadians(signedCourseDeltaDeg(meanCourse, stepBrg))))
        val along = abs(step * cos(Math.toRadians(signedCourseDeltaDeg(meanCourse, stepBrg))))
        if (lateral < LATERAL_COURSE_XT_M * 0.65) return false
        if (lateral >= LATERAL_COURSE_XT_M) return true
        return along > 0.5 && lateral / along >= LATERAL_COURSE_XT_FRACTION
    }

    /**
     * Drops trailing vertices that form spike chords from the previous point (and 3-point reversals /
     * lateral V tips). Idempotent; keeps at least one point. For a lateral V, removes the tip C
     * when A–B–C spikes (caller that can edit the middle should prefer [isLateralVSpike] + drop B).
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
            val lateralTip = out.size >= 3 && isLateralVSpike(
                latOf(out[out.size - 3]), lngOf(out[out.size - 3]),
                latOf(prev), lngOf(prev),
                latOf(last), lngOf(last)
            )
            if (!twoPoint && !threePoint && !lateralTip) break
            out.removeAt(out.size - 1)
        }
        return out
    }

    /** Absolute cross-track distance (m) of point P from the great-circle chord A→C. */
    fun crossTrackM(
        aLat: Double, aLng: Double,
        cLat: Double, cLng: Double,
        pLat: Double, pLng: Double
    ): Double {
        val ac = distanceM(aLat, aLng, cLat, cLng)
        if (ac < 0.5) return 0.0
        val ap = distanceM(aLat, aLng, pLat, pLng)
        val pc = distanceM(pLat, pLng, cLat, cLng)
        val s = (ap + pc + ac) / 2.0
        val areaSq = (s * (s - ap) * (s - pc) * (s - ac)).coerceAtLeast(0.0)
        return 2.0 * kotlin.math.sqrt(areaSq) / ac
    }

    fun circularMeanDeg(degrees: List<Double>): Double {
        var sx = 0.0
        var sy = 0.0
        for (d in degrees) {
            val r = Math.toRadians(d)
            sx += sin(r)
            sy += cos(r)
        }
        return (Math.toDegrees(atan2(sx, sy)) + 360.0) % 360.0
    }

    /** Signed course delta in (−180, 180]. */
    fun signedCourseDeltaDeg(from: Double, to: Double): Double {
        var d = (to - from) % 360.0
        if (d > 180.0) d -= 360.0
        if (d <= -180.0) d += 360.0
        return d
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
