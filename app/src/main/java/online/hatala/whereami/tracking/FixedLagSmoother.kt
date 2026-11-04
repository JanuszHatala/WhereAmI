package online.hatala.whereami.tracking

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Fixed-lag Rauch-Tung-Striebel smoother over the EKF forward estimates.
 *
 * The live (filtered) estimate is optimal for the *present* but noisy about the *past*; once a few
 * more fixes exist, the past can be revised using them. Points leaving the lag window are the
 * smoothed track that is recorded and drawn: zig-zags from multipath disappear without lagging the
 * live cursor, which keeps using the filtered estimate.
 */
class FixedLagSmoother(private val lagMs: Long = 3_000L) {

    class Step(
        val tElapsedMs: Long,
        val tUtcMs: Long,
        val altitudeM: Double?,
        val accuracyM: Float,
        val isStationary: Boolean,
        /** Filtered posterior at this step. */
        val xf: DoubleArray,
        val pf: Array<DoubleArray>,
        /** Prior (prediction) at this step, from the previous step. Null for the first step. */
        val xPred: DoubleArray?,
        val pPred: Array<DoubleArray>?,
        /** Transition Jacobian previous step -> this step. Null for the first step. */
        val f: Array<DoubleArray>?,
        val rawLat: Double? = null,
        val rawLng: Double? = null
    )

    private val steps = ArrayList<Step>()

    val size: Int get() = steps.size

    fun shiftOrigin(dEast: Double, dNorth: Double) {
        for (s in steps) {
            s.xf[KinematicEkf.EAST] -= dEast
            s.xf[KinematicEkf.NORTH] -= dNorth
            s.xPred?.let {
                it[KinematicEkf.EAST] -= dEast
                it[KinematicEkf.NORTH] -= dNorth
            }
        }
    }

    /** Add a step; returns points whose age exceeds the lag, smoothed with everything newer. */
    fun add(step: Step, frame: LocalFrame): List<SmoothedPoint> {
        steps.add(step)
        if (steps.size < 2) return emptyList()
        val cutoff = step.tElapsedMs - lagMs
        var emitCount = 0
        if (step.isStationary) {
            emitCount = steps.size - 1
        } else {
            while (emitCount < steps.size - 1 && steps[emitCount].tElapsedMs <= cutoff) emitCount++
        }
        if (emitCount == 0) return emptyList()
        val smoothed = smoothWindow()
        val out = ArrayList<SmoothedPoint>(emitCount)
        for (i in 0 until emitCount) out.add(toPoint(steps[i], smoothed[i], frame))
        for (i in 0 until emitCount) steps.removeAt(0)
        return out
    }

    /** Emit every remaining step (trip stop / filter reset / app shutdown). */
    fun flush(frame: LocalFrame): List<SmoothedPoint> {
        if (steps.isEmpty()) return emptyList()
        val smoothed = smoothWindow()
        val out = ArrayList<SmoothedPoint>(steps.size)
        for (i in steps.indices) out.add(toPoint(steps[i], smoothed[i], frame))
        steps.clear()
        return out
    }

    /** Current smoothed estimate of the not-yet-finalised window (for the live polyline tail). */
    fun provisional(frame: LocalFrame): List<SmoothedPoint> {
        if (steps.isEmpty()) return emptyList()
        val smoothed = smoothWindow()
        return steps.indices.map { toPoint(steps[it], smoothed[it], frame) }
    }

    fun clear() = steps.clear()

    private fun smoothWindow(): List<DoubleArray> {
        val n = steps.size
        val xs = ArrayList<DoubleArray>(n)
        for (i in 0 until n) xs.add(steps[i].xf.copyOf())
        for (k in n - 2 downTo 0) {
            val curr = steps[k]
            val next = steps[k + 1]
            // Decouple RTS smoother across multi-second stationary gaps or when current step is stationary
            val dtMs = next.tElapsedMs - curr.tElapsedMs
            if (dtMs > 4_000L || curr.isStationary) continue

            val f = next.f
            val pPred = next.pPred
            val xPred = next.xPred
            if (f == null || pPred == null || xPred == null) continue
            val inv = invert(pPred) ?: continue
            // C = Pf[k] * F^T * inv(PPred[k+1])
            val pf = curr.pf
            val pft = matMul(pf, transpose(f))
            val c = matMul(pft, inv)
            val dx = DoubleArray(KinematicEkf.N) { xs[k + 1][it] - xPred[it] }
            dx[KinematicEkf.HEADING] = wrapRad(dx[KinematicEkf.HEADING])

            val corr = DoubleArray(KinematicEkf.N)
            for (i in 0 until KinematicEkf.N) {
                var sum = 0.0
                for (j in 0 until KinematicEkf.N) sum += c[i][j] * dx[j]
                corr[i] = sum
            }

            // Bound position correction by 3-sigma position uncertainty (max 5.0m)
            var corrEast = corr[KinematicEkf.EAST]
            var corrNorth = corr[KinematicEkf.NORTH]
            val corrDist = kotlin.math.hypot(corrEast, corrNorth)
            val maxCorrM = minOf(5.0, 3.0 * sqrt(max(0.0, pf[0][0] + pf[1][1])))
            if (corrDist > maxCorrM && corrDist > 1e-6) {
                val scale = maxCorrM / corrDist
                corrEast *= scale
                corrNorth *= scale
            }
            corr[KinematicEkf.EAST] = corrEast
            corr[KinematicEkf.NORTH] = corrNorth

            for (i in 0 until KinematicEkf.N) {
                xs[k][i] += corr[i]
            }
            xs[k][KinematicEkf.HEADING] = wrapRad(xs[k][KinematicEkf.HEADING])
            if (xs[k][KinematicEkf.SPEED] < 0.0) xs[k][KinematicEkf.SPEED] = 0.0
        }
        return xs
    }

    private fun toPoint(step: Step, x: DoubleArray, frame: LocalFrame): SmoothedPoint {
        val headingDeg = wrapDeg360(Math.toDegrees(x[KinematicEkf.HEADING]))
        val posStd = sqrt(max(0.0, (step.pf[0][0] + step.pf[1][1]) / 2.0)).toFloat()
        val sLat = if (kotlin.math.abs(x[KinematicEkf.NORTH]) <= 200_000.0) {
            frame.lat(x[KinematicEkf.NORTH])
        } else (step.rawLat ?: frame.lat(0.0))
        val sLng = if (kotlin.math.abs(x[KinematicEkf.EAST]) <= 200_000.0) {
            frame.lng(x[KinematicEkf.EAST])
        } else (step.rawLng ?: frame.lng(0.0))
        return SmoothedPoint(
            tElapsedMs = step.tElapsedMs,
            tUtcMs = step.tUtcMs,
            lat = sLat,
            lng = sLng,
            altitudeM = step.altitudeM,
            speedMps = x[KinematicEkf.SPEED],
            headingDeg = headingDeg,
            accuracyM = max(posStd, 0.5f),
            isStationary = step.isStationary,
            rawLat = step.rawLat,
            rawLng = step.rawLng
        )
    }

    companion object {
        internal fun transpose(a: Array<DoubleArray>): Array<DoubleArray> {
            val n = a.size
            val m = a[0].size
            return Array(m) { j -> DoubleArray(n) { i -> a[i][j] } }
        }

        internal fun matMul(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> {
            val n = a.size
            val k = b.size
            val m = b[0].size
            return Array(n) { i ->
                DoubleArray(m) { j ->
                    var s = 0.0
                    for (t in 0 until k) s += a[i][t] * b[t][j]
                    s
                }
            }
        }

        /** Gauss-Jordan inverse with partial pivoting; null when (numerically) singular. */
        internal fun invert(src: Array<DoubleArray>): Array<DoubleArray>? {
            val n = src.size
            val a = Array(n) { i -> DoubleArray(2 * n) { j -> if (j < n) src[i][j] else if (j - n == i) 1.0 else 0.0 } }
            for (col in 0 until n) {
                var pivot = col
                for (r in col + 1 until n) if (kotlin.math.abs(a[r][col]) > kotlin.math.abs(a[pivot][col])) pivot = r
                if (kotlin.math.abs(a[pivot][col]) < 1e-14) return null
                if (pivot != col) {
                    val tmp = a[pivot]; a[pivot] = a[col]; a[col] = tmp
                }
                val d = a[col][col]
                for (j in 0 until 2 * n) a[col][j] /= d
                for (r in 0 until n) {
                    if (r == col) continue
                    val factor = a[r][col]
                    if (factor == 0.0) continue
                    for (j in 0 until 2 * n) a[r][j] -= factor * a[col][j]
                }
            }
            return Array(n) { i -> DoubleArray(n) { j -> a[i][n + j] } }
        }
    }
}
