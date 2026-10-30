package online.hatala.whereami.tracking

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin

/**
 * When is the phone compass allowed to drive the heading arrow, and what does it show?
 *
 * GNSS course at walking pace is noisy (p90 error of 30-60 degrees at weak signal); a fused compass is smooth
 * and immediate but can be disturbed or sit at a constant angle to the walking direction (phone held sideways,
 * in a pocket). So the compass is never trusted blindly: while the user visibly moves, the difference between
 * the course (the direction of the drawn line) and the compass is averaged as a unit vector. A consistent
 * difference (resultant length >= [minConsistency]) is applied as a learned offset; an inconsistent one
 * (magnetic disturbance, phone swinging) switches the compass off and the caller falls back to the course.
 *
 * Standing still the arrow is never driven by the compass (product rule: no spinning at rest stops); [update]
 * returns null and the caller holds the last heading.
 */
class CompassArrow(
    private val tauS: Double = 6.0,
    private val minConsistency: Double = 0.70,
    private val minLearnS: Double = 3.0
) {
    private var cx = 0.0
    private var cy = 0.0
    private var has = false
    // Fast average of the same differences: catches a sudden change (phone swung, disturbance) long before
    // the slow average moves.
    private var fx = 0.0
    private var fy = 0.0
    private var learnedS = 0.0
    private var lastMs = -1L

    /** Learned compass offset (course minus compass) in degrees, or null before the first sample. */
    val offsetDeg: Double? get() = if (has) Math.toDegrees(atan2(cy, cx)) else null
    val consistency: Double get() = if (has) hypot(cx, cy) else 0.0
    val isTrusted: Boolean
        get() = has && learnedS >= minLearnS && consistency >= minConsistency && recentAgreement()

    /** The last ~2 s of differences still point where the learned offset says (or are too scattered to tell). */
    private fun recentAgreement(): Boolean {
        if (hypot(fx, fy) < FAST_MIN_R) return true
        return kotlin.math.abs(shortestDeltaDeg(Math.toDegrees(atan2(cy, cx)), Math.toDegrees(atan2(fy, fx)))) <= FAST_MAX_DEVIATION_DEG
    }

    fun reset() {
        cx = 0.0; cy = 0.0; fx = 0.0; fy = 0.0; has = false; learnedS = 0.0; lastMs = -1L
    }

    /**
     * @param compassDeg compass heading sampled at the render time (true north), null if unavailable
     * @param courseDeg direction of the drawn line / motion course at the render time, null if unknown
     * @param moving the cursor is visibly moving
     * @return the arrow heading, or null when the compass must not be used
     */
    fun update(tMs: Long, compassDeg: Double?, courseDeg: Double?, moving: Boolean): Double? {
        val dt = if (lastMs >= 0L) (tMs - lastMs) / 1000.0 else 0.0
        lastMs = tMs
        if (dt > GAP_RESET_S || dt < 0.0) reset().also { lastMs = tMs }
        if (compassDeg == null) return null
        if (moving && courseDeg != null) {
            val d = Math.toRadians(shortestDeltaDeg(compassDeg, courseDeg))
            val ux = cos(d)
            val uy = sin(d)
            if (!has) {
                cx = ux; cy = uy; fx = ux; fy = uy; has = true
            } else {
                val step = dt.coerceIn(0.0, 0.25)
                val a = 1.0 - exp(-step / tauS)
                cx += a * (ux - cx)
                cy += a * (uy - cy)
                val b = 1.0 - exp(-step / FAST_TAU_S)
                fx += b * (ux - fx)
                fy += b * (uy - fy)
            }
            learnedS += dt.coerceIn(0.0, 0.25)
        }
        if (!moving || !isTrusted) return null
        return wrapDeg360(compassDeg + Math.toDegrees(atan2(cy, cx)))
    }

    private companion object {
        const val GAP_RESET_S = 3.0
        const val FAST_TAU_S = 1.5
        const val FAST_MIN_R = 0.5
        const val FAST_MAX_DEVIATION_DEG = 40.0
    }
}
