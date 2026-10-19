package janush.tech.whereami.tracking

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

    private companion object {
        const val MIN_STEP_M = 0.25
    }
}
