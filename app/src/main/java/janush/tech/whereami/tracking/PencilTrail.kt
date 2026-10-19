package janush.tech.whereami.tracking

import kotlin.math.cos
import kotlin.math.hypot

/**
 * The live end of the recorded polyline, drawn exactly where the cursor has been rendered.
 *
 * The recorded track (smoothed, committed by TripManager) trails the cursor by the smoother lag plus the
 * sampler window. Bridging that gap with a re-estimated tail makes the line morph on every fix; instead the
 * gap is filled with the cursor's own rendered path, so the line behaves like a pencil tip under the cursor.
 * Points older than the newest committed vertex are dropped as the recorded track catches up.
 */
class PencilTrail(private val maxPoints: Int = 600) {

    private val t = ArrayList<Long>()
    private val lat = ArrayList<Double>()
    private val lng = ArrayList<Double>()

    val size: Int get() = t.size

    /** Appends a rendered cursor position at render time [tMs] if it moved far enough from the last one. */
    fun add(tMs: Long, latDeg: Double, lngDeg: Double, speedMps: Double): Boolean {
        val n = t.size
        if (n > 0) {
            if (tMs < t[n - 1]) return false
            val k = cos(Math.toRadians(latDeg)).coerceAtLeast(0.01)
            val d = hypot((lngDeg - lng[n - 1]) * k, latDeg - lat[n - 1]) * LocalFrame.METERS_PER_DEG_LAT
            if (d < minStepM(speedMps)) return false
        }
        t.add(tMs); lat.add(latDeg); lng.add(lngDeg)
        if (t.size > maxPoints) {
            t.removeAt(0); lat.removeAt(0); lng.removeAt(0)
        }
        return true
    }

    /** Drops points rendered at or before [tMs] (covered by the committed track). */
    fun pruneThrough(tMs: Long) {
        var drop = 0
        while (drop < t.size && t[drop] <= tMs) drop++
        if (drop == 0) return
        t.subList(0, drop).clear(); lat.subList(0, drop).clear(); lng.subList(0, drop).clear()
    }

    fun clear() {
        t.clear(); lat.clear(); lng.clear()
    }

    inline fun forEach(block: (lat: Double, lng: Double) -> Unit) {
        for (i in 0 until size) block(latAt(i), lngAt(i))
    }

    fun latAt(i: Int): Double = lat[i]
    fun lngAt(i: Int): Double = lng[i]
    fun timeAt(i: Int): Long = t[i]

    companion object {
        /** About 0.1 s of travel, never below half a metre (sub-pixel at street zoom). */
        fun minStepM(speedMps: Double): Double = (0.1 * speedMps).coerceIn(0.5, 4.0)
    }
}
