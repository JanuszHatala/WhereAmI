package janush.tech.whereami.tracking

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
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
        /** The latest filter state observes heading (false while [headingDeg] is a held last-good value). */
        val headingFresh: Boolean,
        val speedMps: Double,
        val isMoving: Boolean,
        val isStationary: Boolean,
        val latencyMs: Double,
        val accuracyM: Float?,
        /** True when the pose was produced by extrapolation beyond the last fix. */
        val isPredicted: Boolean,
        /** Time the pose represents (`now - latencyMs`, elapsedRealtime ms). */
        val renderTimeMs: Long,
        /** Direction of the rendered path itself at this pose, when the cursor is on an interpolated curve. */
        val pathHeadingDeg: Double? = null
    )

    private class Raw(
        val lat: Double, val lng: Double, val headingDeg: Double,
        val speedMps: Double, val stationary: Boolean, val predicted: Boolean,
        /** Velocity of the slow-mode curve itself (m/s east, north); null outside slow mode. */
        val pathVelE: Double = 0.0, val pathVelN: Double = 0.0, val hasPathVel: Boolean = false
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

    /**
     * Slow motion on foot: a few metres of GNSS noise exceed the distance travelled between fixes, so
     * extrapolating along the Doppler course and blending the correction draws zig-zags with the arrow
     * pointing off the line. Below [slowOnSpeedMps] the cursor instead runs [slowLatencyMs] behind on a
     * Catmull-Rom curve through the filtered positions and the arrow follows that curve.
     */
    var slowOnSpeedMps = 2.2
    var slowOffSpeedMps = 2.8
    var slowLatencyMs = 1_600.0
    private var slow = false

    /**
     * Lower bound of the render latency (ms) for vehicles. Extrapolating a car to "now" overshoots every
     * corner and stop and the correction drew zig-zags that the smoother later re-shaped (trip 233: re-shape
     * p90 2.8 m). One fix interval behind, the cursor moves between known filter states instead.
     */
    var minLatencyMs = VEHICLE_LATENCY_MS

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
        slow = false
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
        val prevLastMs = history.last().tElapsedMs
        if (s.tElapsedMs <= prevLastMs) return
        val gapBeforeState = s.tElapsedMs - prevLastMs > 2_500L || rt - prevLastMs > 2_500.0
        val pre = if (gapBeforeState) null else rawAt(rt)
        history.add(s)
        prune(s.tElapsedMs)
        if (gapBeforeState) {
            clearOffsets()
            return
        }
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

    /** Clears spring correction offsets (used after screen-off / batch catch-up so pose aligns with pathBetween). */
    fun clearCorrectionOffsets() {
        clearOffsets()
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
        // On foot only: a vehicle crawling in traffic would pull away from every stop with the cursor
        // seconds (tens of metres) behind.
        val onFoot = profile.maxSpeedMps <= ON_FOOT_MAX_SPEED_MPS
        slow = onFoot && if (slow) latest.speedMps < slowOffSpeedMps else latest.speedMps < slowOnSpeedMps
        if (slow) target = max(target, slowLatencyMs)
        if (!onFoot) {
            val yawMag = abs(outHeadingRateDegS)
            val turnReduction = if (yawMag > 8.0) ((yawMag - 8.0) / 12.0).coerceIn(0.0, 1.0) * 500.0 else 0.0
            val effectiveMinLatency = (minLatencyMs - turnReduction).coerceAtLeast(400.0)
            target = max(target, effectiveMinLatency)
        }
        // A vehicle keeps its latency through a data gap: dropping to "now" would dead-reckon the cursor
        // further along an unobserved path (screen-on after batched fixes drew loops into side roads).
        if (onFoot && now - latest.tElapsedMs > 2500.0) target = 0.0
        // At foot pace a faster ramp is invisible (the cursor briefly runs at 75-125 % of the real pace).
        // During sharp vehicle turns, allow up to 300 ms/s latency ramp to track roundabout entry/exit tightly.
        val maxStep = (if (onFoot) 250.0 else if (abs(outHeadingRateDegS) > 8.0) 300.0 else 100.0) * dtSec
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

        // Direction the cursor is visibly travelling: the curve plus the correction being blended in.
        val pathHeading = if (raw.hasPathVel) {
            val ve = raw.pathVelE + vE
            val vn = raw.pathVelN + vN
            if (hypot(ve, vn) > 0.05) wrapDeg360(Math.toDegrees(atan2(ve, vn))) else null
        } else null

        // Moving hysteresis
        moving = if (raw.stationary) false
        else if (moving) raw.speedMps >= movingOffSpeedMps
        else raw.speedMps >= movingOnSpeedMps

        return Pose(
            lat = raw.lat + oN / mLat,
            lng = raw.lng + oE / mLng,
            headingDeg = heading,
            headingConfident = latest.headingConfident || lastConfidentHeading != null,
            headingFresh = latest.headingConfident,
            speedMps = raw.speedMps,
            isMoving = moving,
            isStationary = raw.stationary,
            latencyMs = latencyMs,
            accuracyM = latest.accuracyM,
            isPredicted = raw.predicted,
            renderTimeMs = renderT.toLong(),
            pathHeadingDeg = pathHeading
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
            if (slow) return interpolateCr(i, t)
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
            // An unobserved heading is a random walk: follow the chord instead of curving along it.
            if (!s.headingConfident) return dE to dN
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

    /**
     * Time-parametrised Catmull-Rom between history[i] and history[i + 1]: tangents come from the
     * neighbouring filtered positions, not from the Doppler course, so the curve passes through every
     * state without loops and its own direction is the motion shown.
     */
    private fun interpolateCr(i: Int, t: Double): Raw {
        val a = history[i]
        val b = history[i + 1]
        if (a.isStationary && b.isStationary) return interpolate(a, b, t)
        val frame = LocalFrame(a.lat, a.lng)
        fun e(s: TrackState) = frame.east(s.lng)
        fun n(s: TrackState) = frame.north(s.lat)
        val t1 = a.tElapsedMs / 1000.0
        val t2 = b.tElapsedMs / 1000.0
        val span = t2 - t1
        val chordE = e(b)
        val chordN = n(b)
        val chord = hypot(chordE, chordN)
        // Velocity at a state: central difference of its neighbours (one-sided at the ends).
        fun velocity(j: Int): Pair<Double, Double> {
            val p = history.getOrNull(j - 1)?.takeIf { !it.isReset && !history[j].isReset }
            val q = history.getOrNull(j + 1)?.takeIf { !it.isReset }
            val lo = p ?: history[j]
            val hi = q ?: history[j]
            val dt = (hi.tElapsedMs - lo.tElapsedMs) / 1000.0
            if (dt <= 1e-3) return 0.0 to 0.0
            return (e(hi) - e(lo)) / dt to (n(hi) - n(lo)) / dt
        }
        fun tangent(j: Int): Pair<Double, Double> {
            val (vx, vy) = velocity(j)
            var tx = vx * span
            var ty = vy * span
            val mag = hypot(tx, ty)
            val cap = 1.5 * chord
            if (mag > cap && mag > 1e-9) { val k = cap / mag; tx *= k; ty *= k }
            if (tx * chordE + ty * chordN < 0.0) { tx = 0.0; ty = 0.0 }
            return tx to ty
        }
        val (m0x, m0y) = tangent(i)
        val (m1x, m1y) = tangent(i + 1)
        val u = ((t / 1000.0 - t1) / span).coerceIn(0.0, 1.0)
        val u2 = u * u
        val u3 = u2 * u
        val h10 = u3 - 2 * u2 + u
        val h01 = -2 * u3 + 3 * u2
        val h11 = u3 - u2
        val x = h10 * m0x + h01 * chordE + h11 * m1x
        val y = h10 * m0y + h01 * chordN + h11 * m1y
        val d10 = 3 * u2 - 4 * u + 1
        val d01 = -6 * u2 + 6 * u
        val d11 = 3 * u2 - 2 * u
        val dx = d10 * m0x + d01 * chordE + d11 * m1x
        val dy = d10 * m0y + d01 * chordN + d11 * m1y
        val heading = wrapDeg360(a.headingDeg + shortestDeltaDeg(a.headingDeg, b.headingDeg) * u)
        val speed = a.speedMps + (b.speedMps - a.speedMps) * u
        return Raw(frame.lat(y), frame.lng(x), heading, speed, false, false, dx / span, dy / span, true)
    }

    private fun predict(s: TrackState, t: Double): Raw {
        val dt = ((t - s.tElapsedMs) / 1000.0).coerceIn(0.0, MAX_PREDICT_S)
        if (s.isStationary || s.speedMps < 0.05 || dt <= 0.0) {
            return Raw(s.lat, s.lng, s.headingDeg, if (s.isStationary) 0.0 else s.speedMps, s.isStationary, dt > 0.0)
        }
        // Distance saturates: dead reckoning is trustworthy for ~2 s, then fades.
        val tauEff = if (dt <= FULL_TRUST_S) dt else FULL_TRUST_S + FADE_S * (1.0 - exp(-(dt - FULL_TRUST_S) / FADE_S))
        if (!s.headingConfident || slow) return predictAlongChord(s, tauEff, dt)

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
        val (travel, vEnd) = brakedTravel(s, tauEff)
        val dist = travel * sinc
        val mLat = LocalFrame.METERS_PER_DEG_LAT
        val mLng = mLat * cos(Math.toRadians(s.lat)).coerceAtLeast(0.01)
        val lat = s.lat + dist * cos(h0 + half) / mLat
        val lng = s.lng + dist * sin(h0 + half) / mLng
        val speed = vEnd * (if (dt <= FULL_TRUST_S) 1.0 else exp(-(dt - FULL_TRUST_S) / FADE_S))
        return Raw(lat, lng, wrapDeg360(s.headingDeg + dTheta), speed, false, true)
    }

    /**
     * Distance travelled and speed after [tauS] seconds from [s]. Braking follows the filter's Singer
     * acceleration (as [KinematicEkf] predicts it) and stops at zero, so a car pulling up at a junction is
     * not extrapolated past its stop and then pulled back. Acceleration is not extrapolated.
     */
    private fun brakedTravel(s: TrackState, tauS: Double): Pair<Double, Double> {
        val v = s.speedMps
        val a = s.accelMps2
        if (a >= 0.0 || tauS <= 0.0) return v * tauS to v
        val tau = profile.accelTauS
        val decay = exp(-tauS / tau)
        val vEnd = v + a * tau * (1.0 - decay)
        if (vEnd > 0.0) return max(0.0, v * tauS + a * tau * (tauS - tau * (1.0 - decay))) to vEnd
        // Stops within the horizon: integrate up to the stop time.
        val tStop = -tau * ln(1.0 + v / (a * tau))
        val dStop = exp(-tStop / tau)
        return max(0.0, v * tStop + a * tau * (tStop - tau * (1.0 - dStop))) to 0.0
    }

    /** One sample of the cursor's path between filter states (no prediction, no correction offsets). */
    data class PathSample(val tMs: Long, val lat: Double, val lng: Double, val speedMps: Double)

    /**
     * The path the cursor takes between [fromMs] and [toMs], sampled every [stepMs] and clamped to the
     * state history (never beyond the newest state). Used to refill the pencil after frames were not
     * rendered (screen off), so the live line follows the filter instead of drawing a chord to the cursor.
     */
    fun pathBetween(fromMs: Long, toMs: Long, stepMs: Long = 200L): List<PathSample> {
        if (history.size < 2 || toMs <= fromMs) return emptyList()
        val lo = max(fromMs, history.first().tElapsedMs)
        val hi = min(toMs, history.last().tElapsedMs)
        if (hi <= lo) return emptyList()
        val out = ArrayList<PathSample>()
        var t = lo
        while (t < hi) {
            rawAt(t.toDouble())?.let { if (!it.stationary) out.add(PathSample(t, it.lat, it.lng, it.speedMps)) }
            t += stepMs
        }
        return out
    }

    /**
     * An unobserved heading is a random walk, so do not dead-reckon along it: continue the displacement of
     * the last two states instead (what the user has actually been doing), never faster than the filter speed.
     */
    private fun predictAlongChord(s: TrackState, tauEff: Double, dt: Double): Raw {
        val speedOut = s.speedMps * (if (dt <= FULL_TRUST_S) 1.0 else exp(-(dt - FULL_TRUST_S) / FADE_S))
        val prev = history.getOrNull(history.size - 2)
        val span = prev?.let { (s.tElapsedMs - it.tElapsedMs) / 1000.0 } ?: 0.0
        if (prev == null || span < 0.3 || span > 5.0) {
            return Raw(s.lat, s.lng, s.headingDeg, speedOut, false, true)
        }
        val mLat = LocalFrame.METERS_PER_DEG_LAT
        val mLng = mLat * cos(Math.toRadians(s.lat)).coerceAtLeast(0.01)
        var ve = (s.lng - prev.lng) * mLng / span
        var vn = (s.lat - prev.lat) * mLat / span
        val v = hypot(ve, vn)
        if (v > s.speedMps && v > 1e-9) { val k = s.speedMps / v; ve *= k; vn *= k }
        val fade = if (dt <= FULL_TRUST_S) 1.0 else exp(-(dt - FULL_TRUST_S) / FADE_S)
        val brake = if (s.speedMps > 1e-6) brakedTravel(s, tauEff).first / (s.speedMps * tauEff) else 1.0
        return Raw(
            s.lat + vn * tauEff * brake / mLat, s.lng + ve * tauEff * brake / mLng, s.headingDeg, speedOut, false, true,
            ve * fade, vn * fade, slow
        )
    }

    companion object {
        const val HISTORY_MS = 35_000L
        const val MAX_LATENCY_MS = 1_800.0
        const val VEHICLE_LATENCY_MS = 1_000.0
        /** Profiles whose top speed is at most this are on foot (walking, hiking, running). */
        const val ON_FOOT_MAX_SPEED_MPS = 10.0
        const val MAX_BLEND_DISTANCE_M = 60.0
        const val MAX_PREDICT_S = 8.0
        const val FULL_TRUST_S = 2.0
        const val FADE_S = 3.0
    }
}
