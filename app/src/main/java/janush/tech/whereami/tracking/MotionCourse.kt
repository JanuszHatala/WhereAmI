package janush.tech.whereami.tracking

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Direction of the cursor's own rendered motion over the last few metres.
 *
 * Used for the heading arrow when the filter has no fresh heading observation (poor GNSS course at
 * walking speed): the arrow then points where the cursor is visibly going instead of a stale held value.
 */
class MotionCourse(
    private val baselineM: Double = 4.0,
    private val windowMs: Long = 8_000L
) {
    private val t = ArrayList<Long>()
    private val e = ArrayList<Double>()
    private val n = ArrayList<Double>()
    private var lat0 = Double.NaN
    private var lng0 = 0.0
    private var k = 1.0

    /** Adds a rendered position and returns the motion course in degrees, or null if not enough travel. */
    fun update(tMs: Long, lat: Double, lng: Double): Double? {
        if (lat0.isNaN()) {
            lat0 = lat; lng0 = lng; k = cos(Math.toRadians(lat)).coerceAtLeast(0.01)
        }
        val x = (lng - lng0) * k * LocalFrame.METERS_PER_DEG_LAT
        val y = (lat - lat0) * LocalFrame.METERS_PER_DEG_LAT
        if (t.isNotEmpty() && tMs < t.last()) clear()
        if (t.isEmpty() || hypot(x - e.last(), y - n.last()) >= MIN_STEP_M) {
            t.add(tMs); e.add(x); n.add(y)
        }
        while (t.isNotEmpty() && tMs - t[0] > windowMs) {
            t.removeAt(0); e.removeAt(0); n.removeAt(0)
        }
        for (i in t.size - 1 downTo 0) {
            val dx = x - e[i]
            val dy = y - n[i]
            if (hypot(dx, dy) >= baselineM) return wrapDeg360(Math.toDegrees(atan2(dx, dy)))
        }
        return null
    }

    fun clear() {
        t.clear(); e.clear(); n.clear()
        lat0 = Double.NaN
    }

    companion object {
        private const val MIN_STEP_M = 0.25

        /** A vehicle cannot turn further than this while crawling with an unobservable course. */
        const val VEHICLE_MAX_UNOBSERVED_TURN_DEG = 90.0

        /** After this long without a fresh heading, visible motion is real motion again. */
        const val VEHICLE_HOLD_MS = 8_000L

        /**
         * Arrow course for a vehicle without a fresh heading. Shortly after the heading was last observed,
         * the cursor sliding back onto a fix that the estimate overshot (braking, stops) or reversing into a
         * space is not a change of direction: keep the held heading (the vehicle's body) instead of flipping
         * the arrow.
         */
        fun forVehicle(motionDeg: Double?, heldDeg: Double?, sinceFreshMs: Long): Double? {
            if (motionDeg == null || heldDeg == null || sinceFreshMs > VEHICLE_HOLD_MS) return motionDeg
            return if (abs(shortestDeltaDeg(heldDeg, motionDeg)) > VEHICLE_MAX_UNOBSERVED_TURN_DEG) heldDeg else motionDeg
        }
    }
}
