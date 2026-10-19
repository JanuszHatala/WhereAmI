package janush.tech.whereami.tracking

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Extended Kalman filter with a Constant Turn-Rate and Velocity (CTRV) motion model in a local
 * east/north metric frame.
 *
 * State vector: `[east, north, speed, heading, yawRate]`
 *  - heading is radians clockwise from north (so `d east/dt = v sin(h)`, `d north/dt = v cos(h)`)
 *  - yawRate is rad/s, clockwise positive (the sign convention of a compass heading)
 *
 * The model is exact for circular arcs, so it keeps curving through turns instead of
 * extrapolating along a straight tangent, which is the root of "rotate then snap back" artefacts
 * in line-based interpolators.
 */
class KinematicEkf(var profile: TrackingProfile) {

    companion object {
        const val N = 5
        const val EAST = 0
        const val NORTH = 1
        const val SPEED = 2
        const val HEADING = 3
        const val YAW = 4

        /** Process noise std-dev of heading [rad / sqrt(s)] (model imperfection, very small). */
        private const val SIGMA_HEADING = 0.02

        /** Process noise of position [m / sqrt(s)] (accounts for lever arm / unmodelled drift). */
        private const val SIGMA_POSITION = 0.15
    }

    val x = DoubleArray(N)
    val p = Array(N) { DoubleArray(N) }
    var tMs: Long = 0L
        private set
    var initialized: Boolean = false
        private set

    /** Set by the engine while ZUPT is asserted; shrinks process noise. */
    var stationaryMode: Boolean = false

    /** Add isotropic position variance (m^2), used to release a stationary anchor. */
    fun inflatePositionVariance(extra: Double) {
        p[EAST][EAST] += extra
        p[NORTH][NORTH] += extra
    }

    fun init(
        east: Double,
        north: Double,
        speed: Double,
        headingRad: Double?,
        yawRate: Double,
        posSigmaM: Double,
        speedSigma: Double,
        tElapsedMs: Long
    ) {
        for (i in 0 until N) {
            x[i] = 0.0
            for (j in 0 until N) p[i][j] = 0.0
        }
        x[EAST] = east
        x[NORTH] = north
        x[SPEED] = speed
        x[HEADING] = headingRad ?: 0.0
        x[YAW] = yawRate
        p[EAST][EAST] = posSigmaM * posSigmaM
        p[NORTH][NORTH] = posSigmaM * posSigmaM
        p[SPEED][SPEED] = speedSigma * speedSigma
        p[HEADING][HEADING] = if (headingRad != null) Math.toRadians(12.0).let { it * it } else Math.PI * Math.PI
        p[YAW][YAW] = 0.15 * 0.15
        tMs = tElapsedMs
        initialized = true
    }

    /** Roll back to a previously captured state (used when a fix is rejected after prediction). */
    fun restore(state: DoubleArray, cov: Array<DoubleArray>, t: Long) {
        System.arraycopy(state, 0, x, 0, N)
        for (i in 0 until N) System.arraycopy(cov[i], 0, p[i], 0, N)
        tMs = t
    }

    fun reinitializeEmpty() {
        initialized = false
        stationaryMode = false
        tMs = 0L
        for (i in 0 until N) {
            x[i] = 0.0
            for (j in 0 until N) p[i][j] = 0.0
        }
    }

    /** Shift the local frame origin by (dEast, dNorth): state coordinates decrease by the shift. */
    fun shiftOrigin(dEast: Double, dNorth: Double) {
        x[EAST] -= dEast
        x[NORTH] -= dNorth
    }

    private fun motion(s: DoubleArray, dt: Double, out: DoubleArray) {
        val v = s[SPEED]
        val h = s[HEADING]
        val w = s[YAW]
        val half = w * dt / 2.0
        val sinc = if (abs(half) < 1e-4) 1.0 - half * half / 6.0 else sin(half) / half
        out[EAST] = s[EAST] + v * dt * sinc * sin(h + half)
        out[NORTH] = s[NORTH] + v * dt * sinc * cos(h + half)
        out[SPEED] = v
        out[HEADING] = h + w * dt
        out[YAW] = w * exp(-dt / profile.yawRateTauS)
    }

    /**
     * Propagate to [tNewMs]. Returns the state transition Jacobian F (needed by the RTS smoother),
     * or null when no time elapsed.
     */
    fun predict(tNewMs: Long): Array<DoubleArray>? {
        if (!initialized) return null
        val dt = (tNewMs - tMs) / 1000.0
        if (dt <= 0.0) return null
        // A turn rate older than a few seconds carries no information: do not arc through gaps.
        if (dt > 3.0) x[YAW] = 0.0

        val f = Array(N) { DoubleArray(N) }
        val base = DoubleArray(N)
        motion(x, dt, base)
        val pert = DoubleArray(N)
        val out = DoubleArray(N)
        for (j in 0 until N) {
            System.arraycopy(x, 0, pert, 0, N)
            val eps = 1e-6 * max(1.0, abs(x[j]))
            pert[j] += eps
            motion(pert, dt, out)
            for (i in 0 until N) f[i][j] = (out[i] - base[i]) / eps
        }

        // P = F P F^T + Q
        val fp = Array(N) { DoubleArray(N) }
        for (i in 0 until N) for (j in 0 until N) {
            var s = 0.0
            for (k in 0 until N) s += f[i][k] * p[k][j]
            fp[i][j] = s
        }
        for (i in 0 until N) for (j in 0 until N) {
            var s = 0.0
            for (k in 0 until N) s += fp[i][k] * f[j][k]
            p[i][j] = s
        }
        // While zero-velocity is asserted the target is physically at rest: no random walk, so the
        // filter converges to the mean of the measurements (a spatial anchor, derived not hard-coded).
        val sp = if (stationaryMode) 0.01 else SIGMA_POSITION
        val sa = if (stationaryMode) 0.05 else profile.sigmaAccel
        val sy = if (stationaryMode) 0.05 else profile.sigmaYawAccel
        p[EAST][EAST] += sp * sp * dt
        p[NORTH][NORTH] += sp * sp * dt
        p[SPEED][SPEED] += sa * sa * dt
        p[HEADING][HEADING] += SIGMA_HEADING * SIGMA_HEADING * dt
        p[YAW][YAW] += sy * sy * dt
        // Continuous white-noise-acceleration cross terms: a yaw-rate change integrates into heading.
        // Without them a precisely known gyro rate made the filter overconfident in heading.
        val qy = sy * sy
        p[HEADING][HEADING] += qy * dt * dt * dt / 3.0
        p[HEADING][YAW] += qy * dt * dt / 2.0
        p[YAW][HEADING] += qy * dt * dt / 2.0
        symmetrize()

        System.arraycopy(base, 0, x, 0, N)
        x[HEADING] = wrapRad(x[HEADING])
        if (x[SPEED] < 0.0) x[SPEED] = 0.0
        tMs = tNewMs
        return f
    }

    /**
     * Mahalanobis distance squared of a 2D position measurement against the current prediction,
     * without applying it.
     */
    fun positionInnovationD2(zEast: Double, zNorth: Double, sigmaM: Double): Double {
        val r = sigmaM * sigmaM
        val a = p[EAST][EAST] + r
        val b = p[EAST][NORTH]
        val d = p[NORTH][NORTH] + r
        val det = a * d - b * b
        if (det <= 1e-12) return Double.MAX_VALUE
        val ne = zEast - x[EAST]
        val nn = zNorth - x[NORTH]
        return (d * ne * ne - 2.0 * b * ne * nn + a * nn * nn) / det
    }

    fun updatePosition(zEast: Double, zNorth: Double, sigmaM: Double) {
        val r = sigmaM * sigmaM
        updateScalar(EAST, zEast, r, angular = false)
        updateScalar(NORTH, zNorth, r, angular = false)
    }

    /** Sequential scalar measurement of state component [idx] with variance [r]. */
    fun updateScalar(idx: Int, z: Double, r: Double, angular: Boolean) {
        var nu = z - x[idx]
        if (angular) nu = wrapRad(nu)
        val s = p[idx][idx] + r
        if (s <= 1e-12) return
        val k = DoubleArray(N) { p[it][idx] / s }
        for (i in 0 until N) x[i] += k[i] * nu
        val row = DoubleArray(N) { p[idx][it] }
        for (i in 0 until N) for (j in 0 until N) p[i][j] -= k[i] * row[j]
        symmetrize()
        x[HEADING] = wrapRad(x[HEADING])
        if (x[SPEED] < 0.0) x[SPEED] = 0.0
    }

    /** Innovation of a scalar measurement and its total variance (for soft gating). */
    fun scalarInnovation(idx: Int, z: Double, r: Double, angular: Boolean): Pair<Double, Double> {
        var nu = z - x[idx]
        if (angular) nu = wrapRad(nu)
        return nu to (p[idx][idx] + r)
    }

    /** Re-seed heading with a (usually course-derived) value, discarding stale heading history. */
    fun reseedHeading(headingRad: Double, sigmaRad: Double) {
        x[HEADING] = wrapRad(headingRad)
        for (j in 0 until N) {
            p[HEADING][j] = 0.0
            p[j][HEADING] = 0.0
        }
        p[HEADING][HEADING] = sigmaRad * sigmaRad
        x[YAW] = 0.0
        p[YAW][YAW] = 0.15 * 0.15
    }

    /**
     * Zero-velocity update: pins speed and turn rate to zero. Position uncertainty then only
     * shrinks, which makes the filter behave like a spatial anchor without ad-hoc clamping.
     */
    fun applyZupt() {
        updateScalar(SPEED, 0.0, 0.05 * 0.05, angular = false)
        updateScalar(YAW, 0.0, 0.01 * 0.01, angular = false)
    }

    val posStdM: Double get() = sqrt(max(0.0, (p[EAST][EAST] + p[NORTH][NORTH]) / 2.0))
    val headingStdRad: Double get() = sqrt(max(0.0, p[HEADING][HEADING]))

    fun copyState(): DoubleArray = x.copyOf()
    fun copyCov(): Array<DoubleArray> = Array(N) { p[it].copyOf() }

    private fun symmetrize() {
        for (i in 0 until N) {
            for (j in i + 1 until N) {
                val m = 0.5 * (p[i][j] + p[j][i])
                p[i][j] = m
                p[j][i] = m
            }
            if (p[i][i] < 1e-9) p[i][i] = 1e-9
        }
    }
}
