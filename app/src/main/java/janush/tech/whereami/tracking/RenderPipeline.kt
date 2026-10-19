package janush.tech.whereami.tracking

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Turns discrete filtered [TrackState]s into a continuous 60 Hz pose.
 *
 * Replaces PositionInterpolator. Differences that matter:
 *  - **Predictive by default**: pose at frame time is predicted along a CTRV arc (curving through
 *    turns, optionally steered by the live gyro) instead of replaying a fixed 1 s old spline.
 *  - **Correction blending**: when a new fix changes the estimate, the *difference* is absorbed
 *    by a critically damped spring, so output position and heading are continuous (no snap, no
 *    rotating one way then correcting back).
 *  - **Adaptive latency**: 0 ms when the filter is confident; ramps up (slewed at most 100 ms/s)
 *    when accuracy degrades, so rendering can interpolate between fixes (Hermite with velocity
 *    tangents) instead of guessing.
 *  - One clock: every time is `elapsedRealtime` milliseconds.
 */
class RenderPipeline(
    var profile: TrackingProfile = TrackingProfile.CAR,
    private val gyro: GyroYawTracker? = null
) {
    data class Pose(
        val lat: Double,
        val lng: Double,
        val headingDeg: Double,
        val headingConfident: Boolean,
        val speedMps: Double,
        val isMoving: Boolean,
        val isStationary: Boolean,
        val latencyMs: Double,
        val accuracyM: Float?,
        /** True when the pose was produced by extrapolation beyond the last fix. */
        val isPredicted: Boolean
    )

    private class Raw(
        val lat: Double, val lng: Double, val headingDeg: Double,
        val speedMps: Double, val stationary: Boolean, val predicted: Boolean
    )

    private val history = ArrayList<TrackState>()

    // Correction offsets (metres east/north, degrees heading) with velocities for C1 continuity.
    private var oE = 0.0
    private var oN = 0.0
    private var oH = 0.0
    private var vE = 0.0
    private var vN = 0.0
    private var vH = 0.0

    private var lastNowMs: Double? = null
    private var lastRenderT: Double? = null
    private var latencyMs = 0.0
    private var moving = false
    private var lastConfidentHeading: Double? = null

    /** Speed above which the cursor is shown as moving (caller may lower it while recording). */
    var movingOnSpeedMps: Double = profile.movingSpeedMps
    var movingOffSpeedMps: Double = 0.35

    var positionTauS = 0.40
    var headingTauS = 0.30
    var headingReverseTauS = 0.90

    private var lastOutHeading: Double? = null
    private var outHeadingRateDegS = 0.0

    /** Without a gyro, extrapolated turn rate is trusted only partially and for a short horizon. */
    private val YAW_FLOOR_DEG_S = 3.0
    private val YAW_FULL_DEG_S = 12.0
    var yawPredictionGain = 0.7
    var yawPredictionTauS = 0.6

    val latestState: TrackState? get() = history.lastOrNull()

    fun reset() {
        history.clear()
        oE = 0.0; oN = 0.0; oH = 0.0; vE = 0.0; vN = 0.0; vH = 0.0
        lastNowMs = null
        lastRenderT = null
        latencyMs = 0.0
        moving = false
        lastConfidentHeading = null
        lastOutHeading = null
        outHeadingRateDegS = 0.0
    }

    fun onState(s: TrackState) {
        val rt = lastRenderT
        if (history.isEmpty() || rt == null || s.isReset) {
            if (s.isReset) history.clear()
            history.add(s)
            clearOffsets()
            if (lastRenderT == null) lastRenderT = s.tElapsedMs.toDouble()
            return
        }
        if (s.tElapsedMs <= history.last().tElapsedMs) return
        val pre = rawAt(rt)
        history.add(s)
        prune(s.tElapsedMs)
        val post = rawAt(rt)
        if (pre != null && post != null) {
            val mLat = LocalFrame.METERS_PER_DEG_LAT
            val mLng = mLat * cos(Math.toRadians(pre.lat)).coerceAtLeast(0.01)
            oE += (pre.lng - post.lng) * mLng
            oN += (pre.lat - post.lat) * mLat
            oH += shortestDeltaDeg(post.headingDeg, pre.headingDeg)
            if (hypot(oE, oN) > MAX_BLEND_DISTANCE_M) clearOffsets()
        }
    }

    private fun clearOffsets() {
        oE = 0.0; oN = 0.0; oH = 0.0; vE = 0.0; vN = 0.0; vH = 0.0
    }

    private fun prune(nowT: Long) {
        val cutoff = nowT - HISTORY_MS
        while (history.size > 3 && history[1].tElapsedMs < cutoff) history.removeAt(0)
    }

    /** Render the pose for a frame at [nowMs] (`elapsedRealtime`). Null until the first state. */
    fun frame(nowMs: Long): Pose? {
        val latest = history.lastOrNull() ?: return null
        val now = nowMs.toDouble()
        val dtSec = lastNowMs?.let { ((now - it) / 1000.0).coerceIn(0.0, 0.25) } ?: 0.0
        lastNowMs = now

        // Latency adaptation
        val sigma = latest.posStdM
        var target = ((sigma - 4.0) / 10.0).coerceIn(0.0, 1.0) * MAX_LATENCY_MS
        if (latest.rejectStreak >= 2) target = max(target, 1200.0)
        if (now - latest.tElapsedMs > 2500.0) target = 0.0
        val maxStep = 100.0 * dtSec
        latencyMs += (target - latencyMs).coerceIn(-maxStep, maxStep)

        val renderT = now - latencyMs
        lastRenderT = renderT
        val raw = rawAt(renderT) ?: return null

        // Decay correction offsets (critically damped)
        if (dtSec > 0.0) {
            decay(positionTauS, dtSec, { oE }, { vE }) { o, v -> oE = o; vE = v }
            decay(positionTauS, dtSec, { oN }, { vN }) { o, v -> oN = o; vN = v }
            // A heading correction that would rotate *against* the turn in progress is the visible
            // "rotates then corrects back" artefact: absorb those slowly, forward ones quickly.
            val againstTurn = abs(outHeadingRateDegS) > 5.0 && oH * outHeadingRateDegS > 0.0
            decay(if (againstTurn) headingReverseTauS else headingTauS, dtSec, { oH }, { vH }) { o, v -> oH = o; vH = v }
        }
        val mLat = LocalFrame.METERS_PER_DEG_LAT
        val mLng = mLat * cos(Math.toRadians(raw.lat)).coerceAtLeast(0.01)

        var heading = wrapDeg360(raw.headingDeg + oH)
        if (latest.headingConfident) lastConfidentHeading = heading
        else if (lastConfidentHeading != null) heading = lastConfidentHeading!!
        lastOutHeading?.let { prevH ->
            if (dtSec > 0.0) {
                val inst = shortestDeltaDeg(prevH, heading) / dtSec
                val a = 1.0 - exp(-dtSec / 0.4)
                outHeadingRateDegS += a * (inst - outHeadingRateDegS)
            }
        }
        lastOutHeading = heading

        // Moving hysteresis
        moving = if (raw.stationary) false
        else if (moving) raw.speedMps >= movingOffSpeedMps
        else raw.speedMps >= movingOnSpeedMps

        return Pose(
            lat = raw.lat + oN / mLat,
            lng = raw.lng + oE / mLng,
            headingDeg = heading,
            headingConfident = latest.headingConfident || lastConfidentHeading != null,
            speedMps = raw.speedMps,
            isMoving = moving,
            isStationary = raw.stationary,
            latencyMs = latencyMs,
            accuracyM = latest.accuracyM,
            isPredicted = raw.predicted
        )
    }

    private inline fun decay(
        tau: Double, dt: Double,
        o: () -> Double, v: () -> Double,
        set: (Double, Double) -> Unit
    ) {
        val k = 1.0 / tau
        val o0 = o()
        val v0 = v()
        val e = exp(-k * dt)
        val c = v0 + k * o0
        set(e * (o0 + c * dt), e * (v0 - k * c * dt))
    }

    /** Estimated pose for render time [t] (ms) from state history, or null if no state. */
    private fun rawAt(t: Double): Raw? {
        if (history.isEmpty()) return null
        val first = history.first()
        if (t <= first.tElapsedMs) return fromState(first)

        var i = history.size - 1
        while (i > 0 && history[i].tElapsedMs > t) i--
        val s0 = history[i]
        if (i < history.size - 1) {
            val s1 = history[i + 1]
            if (s1.isReset) return fromState(s0)
            return interpolate(s0, s1, t)
        }
        return predict(s0, t)
    }

    private fun fromState(s: TrackState) =
        Raw(s.lat, s.lng, s.headingDeg, s.speedMps, s.isStationary, false)

    private fun interpolate(a: TrackState, b: TrackState, t: Double): Raw {
        val span = (b.tElapsedMs - a.tElapsedMs).toDouble()
        val u = ((t - a.tElapsedMs) / span).coerceIn(0.0, 1.0)
        val heading = wrapDeg360(a.headingDeg + shortestDeltaDeg(a.headingDeg, b.headingDeg) * u)
        if (a.isStationary && b.isStationary) return Raw(b.lat, b.lng, heading, 0.0, true, false)

        val mLat = LocalFrame.METERS_PER_DEG_LAT
        val mLng = mLat * cos(Math.toRadians(a.lat)).coerceAtLeast(0.01)
        val dE = (b.lng - a.lng) * mLng
        val dN = (b.lat - a.lat) * mLat
        val chord = hypot(dE, dN)
        val tSec = span / 1000.0
        // Hermite tangents from filtered velocities, clamped to avoid loops on bad velocities.
        fun tangent(s: TrackState): Pair<Double, Double> {
            val h = Math.toRadians(s.headingDeg)
            var tx = s.speedMps * sin(h) * tSec
            var ty = s.speedMps * cos(h) * tSec
            val mag = hypot(tx, ty)
            val cap = 2.0 * chord
            if (mag > cap && mag > 1e-9) { val k = cap / mag; tx *= k; ty *= k }
            if (tx * dE + ty * dN < 0.0) { tx = dE; ty = dN }
            return tx to ty
        }
        val (m0x, m0y) = tangent(a)
        val (m1x, m1y) = tangent(b)
        val u2 = u * u
        val u3 = u2 * u
        val h00 = 2 * u3 - 3 * u2 + 1
        val h10 = u3 - 2 * u2 + u
        val h01 = -2 * u3 + 3 * u2
        val h11 = u3 - u2
        val e = h10 * m0x + h01 * dE + h11 * m1x
        val n = h10 * m0y + h01 * dN + h11 * m1y
        val speed = a.speedMps + (b.speedMps - a.speedMps) * u
        return Raw(a.lat + n / mLat, a.lng + e / mLng, heading, speed, false, false)
    }

    private fun predict(s: TrackState, t: Double): Raw {
        val dt = ((t - s.tElapsedMs) / 1000.0).coerceIn(0.0, MAX_PREDICT_S)
        if (s.isStationary || s.speedMps < 0.05 || dt <= 0.0) {
            return Raw(s.lat, s.lng, s.headingDeg, if (s.isStationary) 0.0 else s.speedMps, s.isStationary, dt > 0.0)
        }
        // Distance saturates: dead reckoning is trustworthy for ~2 s, then fades.
        val tauEff = if (dt <= FULL_TRUST_S) dt else FULL_TRUST_S + FADE_S * (1.0 - exp(-(dt - FULL_TRUST_S) / FADE_S))

        // Heading change: live gyro if it covers the interval, else decayed filter turn rate.
        val gyroDelta = gyro?.integrate(s.tElapsedMs, t.toLong())
        val dTheta = if (gyroDelta != null && s.speedMps >= profile.minHeadingSpeedMps) {
            Math.toDegrees(gyroDelta)
        } else {
            val tauW = min(profile.yawRateTauS, yawPredictionTauS)
            // Rates near the filter's noise floor are not extrapolated (they would amplify course jitter
            // on straights); a real turn rate ramps in smoothly between 3 and 12 deg/s.
            val x = ((abs(s.yawRateDegPerS) - YAW_FLOOR_DEG_S) / (YAW_FULL_DEG_S - YAW_FLOOR_DEG_S)).coerceIn(0.0, 1.0)
            val trust = x * x * (3.0 - 2.0 * x)
            s.yawRateDegPerS * yawPredictionGain * trust * tauW * (1.0 - exp(-dt / tauW))
        }
        val h0 = Math.toRadians(s.headingDeg)
        val half = Math.toRadians(dTheta) / 2.0 * (tauEff / dt)
        val sinc = if (abs(half) < 1e-4) 1.0 - half * half / 6.0 else sin(half) / half
        val dist = s.speedMps * tauEff * sinc
        val mLat = LocalFrame.METERS_PER_DEG_LAT
        val mLng = mLat * cos(Math.toRadians(s.lat)).coerceAtLeast(0.01)
        val lat = s.lat + dist * cos(h0 + half) / mLat
        val lng = s.lng + dist * sin(h0 + half) / mLng
        val speed = s.speedMps * (if (dt <= FULL_TRUST_S) 1.0 else exp(-(dt - FULL_TRUST_S) / FADE_S))
        return Raw(lat, lng, wrapDeg360(s.headingDeg + dTheta), speed, false, true)
    }

    companion object {
        const val HISTORY_MS = 8_000L
        const val MAX_LATENCY_MS = 1_800.0
        const val MAX_BLEND_DISTANCE_M = 60.0
        const val MAX_PREDICT_S = 8.0
        const val FULL_TRUST_S = 2.0
        const val FADE_S = 3.0
    }
}
