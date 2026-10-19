package janush.tech.whereami.tracking

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Display-only rounding of a polyline: a centripetal Catmull-Rom spline through the vertices.
 *
 * Recorded vertices stay as stored (the sampler keeps them metres apart); only the drawn line is densified,
 * and only at bends, so straight stretches cost no extra points. The centripetal parametrisation passes
 * through every vertex without cusps or self-intersections inside a segment, also for unevenly spaced points.
 *
 * Segment j joins vertex j and j + 1 and depends on vertices j - 1 .. j + 2 only, so a growing line can be
 * densified incrementally: segments already followed by two vertices never change.
 */
object CurvedPolyline {
    /** Target spacing of the inserted points along a bend. */
    const val STEP_M = 0.6
    const val MAX_SUBDIVISIONS = 8
    /** Both corners of a segment straighter than this: draw it as a straight segment. */
    const val STRAIGHT_DEG = 4.0

    /**
     * Emits segments [fromSeg, toSeg) of the [n] vertices: for each segment its interior curve points and then
     * its end vertex. The start vertex of [fromSeg] is not emitted (the caller already has it).
     */
    inline fun densify(
        n: Int,
        fromSeg: Int,
        toSeg: Int,
        latAt: (Int) -> Double,
        lngAt: (Int) -> Double,
        emit: (Double, Double) -> Unit
    ) {
        val end = minOf(toSeg, n - 1)
        for (j in maxOf(fromSeg, 0) until end) {
            val lat1 = latAt(j); val lng1 = lngAt(j)
            val lat2 = latAt(j + 1); val lng2 = lngAt(j + 1)
            val mLat = LocalFrame.METERS_PER_DEG_LAT
            val mLng = mLat * cos(Math.toRadians(lat1)).coerceAtLeast(0.01)
            // Local metres relative to vertex j.
            val x2 = (lng2 - lng1) * mLng; val y2 = (lat2 - lat1) * mLat
            val len = hypot(x2, y2)
            val x0: Double; val y0: Double
            if (j > 0) { x0 = (lngAt(j - 1) - lng1) * mLng; y0 = (latAt(j - 1) - lat1) * mLat }
            else { x0 = -x2; y0 = -y2 }
            val x3: Double; val y3: Double
            if (j + 2 < n) { x3 = (lngAt(j + 2) - lng1) * mLng; y3 = (latAt(j + 2) - lat1) * mLat }
            else { x3 = 2 * x2; y3 = 2 * y2 }
            val k = subdivisions(x0, y0, x2, y2, x3, y3, len)
            for (s in 1 until k) {
                val p = point(x0, y0, x2, y2, x3, y3, s.toDouble() / k)
                emit(lat1 + p[1] / mLat, lng1 + p[0] / mLng)
            }
            emit(lat2, lng2)
        }
    }

    /** Number of pieces for segment (0,0)-(x2,y2) with neighbours (x0,y0) and (x3,y3); 1 = straight. */
    fun subdivisions(x0: Double, y0: Double, x2: Double, y2: Double, x3: Double, y3: Double, len: Double): Int {
        if (len < 2 * STEP_M / 3) return 1
        val a = turnDeg(-x0, -y0, x2, y2)
        val b = turnDeg(x2, y2, x3 - x2, y3 - y2)
        if (a < STRAIGHT_DEG && b < STRAIGHT_DEG) return 1
        return ceil(len / STEP_M).toInt().coerceIn(1, MAX_SUBDIVISIONS)
    }

    private fun turnDeg(ax: Double, ay: Double, bx: Double, by: Double): Double {
        val den = hypot(ax, ay) * hypot(bx, by)
        if (den < 1e-12) return 0.0
        val c = ((ax * bx + ay * by) / den).coerceIn(-1.0, 1.0)
        return Math.toDegrees(kotlin.math.acos(c))
    }

    /**
     * Centripetal (alpha = 0.5) Catmull-Rom point at [u] in [0, 1] on the segment from (0,0) to (x2,y2),
     * Barry-Goldman pyramidal form. Returns [x, y].
     */
    fun point(x0: Double, y0: Double, x2: Double, y2: Double, x3: Double, y3: Double, u: Double): DoubleArray {
        val d01 = knot(x0, y0, 0.0, 0.0)
        val d12 = knot(0.0, 0.0, x2, y2)
        val d23 = knot(x2, y2, x3, y3)
        val t0 = 0.0
        val t1 = d01
        val t2 = t1 + d12
        val t3 = t2 + d23
        val t = t1 + (t2 - t1) * u
        fun lerp(ax: Double, ay: Double, ta: Double, bx: Double, by: Double, tb: Double): DoubleArray {
            val span = tb - ta
            if (abs(span) < 1e-12) return doubleArrayOf(ax, ay)
            val wa = (tb - t) / span
            val wb = (t - ta) / span
            return doubleArrayOf(wa * ax + wb * bx, wa * ay + wb * by)
        }
        val a1 = lerp(x0, y0, t0, 0.0, 0.0, t1)
        val a2 = lerp(0.0, 0.0, t1, x2, y2, t2)
        val a3 = lerp(x2, y2, t2, x3, y3, t3)
        val b1 = lerp(a1[0], a1[1], t0, a2[0], a2[1], t2)
        val b2 = lerp(a2[0], a2[1], t1, a3[0], a3[1], t3)
        return lerp(b1[0], b1[1], t1, b2[0], b2[1], t2)
    }

    private fun knot(ax: Double, ay: Double, bx: Double, by: Double): Double =
        sqrt(hypot(bx - ax, by - ay)).coerceAtLeast(1e-4)
}
