package online.hatala.whereami.tracking

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Display-only corner rounding of a polyline using Chaikin-style quadratic Bezier corner fillets.
 *
 * Recorded vertices stay as stored (the sampler keeps them metres apart); only the drawn line is densified
 * at corners to eliminate angular corners. Straight stretches cost no extra points and stay 100% straight
 * on the road without lateral oscillation.
 *
 * By the Convex Hull Property of Bezier curves, all generated fillet points lie strictly inside the corner triangle,
 * making it mathematically impossible to create S-curves, loops, or squary zig-zags.
 */
object CurvedPolyline {
    /** Target spacing of the inserted points along a rounded corner. */
    const val STEP_M = 0.8
    const val MAX_FILLET_M = 3.5
    const val MIN_CORNER_DEG = 5.0
    const val MAX_CORNER_DEG = 150.0

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
        prevLat: Double? = null,
        prevLng: Double? = null,
        emit: (Double, Double) -> Unit
    ) {
        val end = minOf(toSeg, n - 1)
        for (j in maxOf(fromSeg, 0) until end) {
            val lat1 = latAt(j); val lng1 = lngAt(j)
            val lat2 = latAt(j + 1); val lng2 = lngAt(j + 1)
            val mLat = LocalFrame.METERS_PER_DEG_LAT
            val mLng = mLat * cos(Math.toRadians(lat1)).coerceAtLeast(0.01)

            val x2 = (lng2 - lng1) * mLng
            val y2 = (lat2 - lat1) * mLat
            val lin = hypot(x2, y2)

            if (j + 2 < n && lin > 0.5) {
                val lat3 = latAt(j + 2); val lng3 = lngAt(j + 2)
                val x3 = (lng3 - lng2) * mLng
                val y3 = (lat3 - lat2) * mLat
                val lout = hypot(x3, y3)

                val theta = turnDeg(x2, y2, x3, y3)
                if (theta in MIN_CORNER_DEG..MAX_CORNER_DEG && lout > 0.5) {
                    val r = minOf(MAX_FILLET_M, 0.25 * lin, 0.25 * lout)
                    // Fillet start along incoming leg (relative to vertex j)
                    val qx = x2 - r * (x2 / lin)
                    val qy = y2 - r * (y2 / lin)

                    // Fillet end along outgoing leg (relative to vertex j)
                    val rx = x2 + r * (x3 / lout)
                    val ry = y2 + r * (y3 / lout)

                    // Emit straight approach to fillet start if not adjacent to vertex j
                    if (hypot(qx, qy) > 0.2) {
                        emit(lat1 + qy / mLat, lng1 + qx / mLng)
                    }

                    // Emit interior quadratic Bezier fillet points: B is control point at (x2, y2)
                    val steps = maxOf(2, minOf(5, (r / STEP_M).roundToInt()))
                    for (s in 1 until steps) {
                        val u = s.toDouble() / steps
                        val w0 = (1.0 - u) * (1.0 - u)
                        val w1 = 2.0 * (1.0 - u) * u
                        val w2 = u * u
                        val px = w0 * qx + w1 * x2 + w2 * rx
                        val py = w0 * qy + w1 * y2 + w2 * ry
                        emit(lat1 + py / mLat, lng1 + px / mLng)
                    }

                    // Emit fillet exit point
                    emit(lat1 + ry / mLat, lng1 + rx / mLng)
                    continue
                }
            }

            // Straight segment: emit end vertex
            emit(lat2, lng2)
        }
    }

    fun turnDeg(ax: Double, ay: Double, bx: Double, by: Double): Double {
        val den = hypot(ax, ay) * hypot(bx, by)
        if (den < 1e-12) return 0.0
        val c = ((ax * bx + ay * by) / den).coerceIn(-1.0, 1.0)
        return Math.toDegrees(kotlin.math.acos(c))
    }
}
