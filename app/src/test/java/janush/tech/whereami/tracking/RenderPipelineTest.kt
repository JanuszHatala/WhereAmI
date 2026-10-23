package janush.tech.whereami.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Replay harness: sim trace -> TrackingEngine -> RenderPipeline at 60 Hz with realistic delivery
 * delay, then assert smoothness metrics on the *rendered* output.
 */
class RenderPipelineTest {

    private data class Frame(val tMs: Long, val pose: RenderPipeline.Pose)

    private class Result(val frames: List<Frame>, val sim: TrackSim)

    private fun replay(
        sim: TrackSim,
        profile: TrackingProfile = TrackingProfile.CAR,
        hz: Double = 1.0,
        deliveryDelayMs: Long = 250,
        useGyro: Boolean = false,
        fixes: List<RawFix> = sim.fixes(hz)
    ): Result {
        val engine = TrackingEngine(profile)
        val gyro = if (useGyro) GyroYawTracker() else null
        val pipe = RenderPipeline(profile, gyro)
        fun truthRate(tSim: Long, spanMs: Long): Double {
            val a = sim.truthAt((tSim - spanMs).coerceAtLeast(0)).headingDeg
            val b = sim.truthAt(tSim).headingDeg
            return Math.toRadians(shortestDeltaDeg(a, b)) / (spanMs / 1000.0)
        }
        var lastGyroT = 0L
        val frames = ArrayList<Frame>()
        var next = 0
        val endT = fixes.last().tElapsedMs + 3_000
        var t = fixes.first().tElapsedMs + deliveryDelayMs
        val states = ArrayList<Pair<Long, TrackState>>()
        for (f in fixes) {
            val g = if (useGyro) truthRate(f.tElapsedMs - sim.baseElapsedMs, 1000) else null
            val st = engine.ingest(f, gyroYawRateRadS = g).state ?: continue
            states.add((f.tElapsedMs + deliveryDelayMs) to st)
        }
        while (t <= endT) {
            while (next < states.size && states[next].first <= t) { pipe.onState(states[next].second); next++ }
            if (gyro != null) {
                val tSim = t - sim.baseElapsedMs
                while (lastGyroT + 20 <= tSim) {
                    lastGyroT += 20
                    gyro.onSample(lastGyroT + sim.baseElapsedMs, truthRate(lastGyroT, 20))
                }
            }
            pipe.frame(t)?.let { frames.add(Frame(t, it)) }
            t += 16
        }
        return Result(frames, sim)
    }

    private fun stepMeters(sim: TrackSim, a: Frame, b: Frame) =
        sim.metersBetween(a.pose.lat, a.pose.lng, b.pose.lat, b.pose.lng)

    @Test
    // Vehicles render one fix interval behind, so the arrow shows the filter heading itself; its settle after
    // the turn is bounded per fix by the engine test (5 deg).
    fun `rendered heading barely reverses during a monotonic turn without gyro`() = headingReversal(false, 6.0)

    @Test
    fun `rendered heading never reverses during a monotonic turn with gyro`() = headingReversal(true, 2.5)

    private fun headingReversal(useGyro: Boolean, maxBack: Double) {
        val sim = TrackSim(seed = 5)
        sim.straight(25.0, 5.6)
        sim.turn(90.0, 4.0, 5.6)
        sim.straight(25.0, 5.6)
        val r = replay(sim, useGyro = useGyro)
        val tTurnStart = r.frames.first().tMs + 24_000
        val tTurnEnd = r.frames.first().tMs + 32_000
        val window = r.frames.filter { it.tMs in tTurnStart..tTurnEnd }
        var maxReversal = 0.0
        var net = 0.0
        for (i in 1 until window.size) {
            val d = shortestDeltaDeg(window[i - 1].pose.headingDeg, window[i].pose.headingDeg)
            net += d
            if (d < 0) maxReversal = maxOf(maxReversal, -d)
        }
        // Allow tiny filter wobble (0.3 deg per 16 ms frame) but no visible counter-rotation.
        assertTrue("max single-frame reversal=$maxReversal", maxReversal < 0.4)
        var cumulativeReversal = 0.0
        var running = 0.0
        var peak = 0.0
        for (i in 1 until window.size) {
            running += shortestDeltaDeg(window[i - 1].pose.headingDeg, window[i].pose.headingDeg)
            peak = maxOf(peak, running)
            cumulativeReversal = maxOf(cumulativeReversal, peak - running)
        }
        assertTrue("cumulative back-rotation=$cumulativeReversal (gyro=$useGyro)", cumulativeReversal < maxBack)
        assertTrue("net=$net", net in 75.0..110.0)
    }

    @Test
    fun `per frame displacement stays continuous and bounded in a car`() {
        val sim = TrackSim(seed = 31)
        sim.straight(20.0, 14.0)
        sim.turn(90.0, 5.0, 8.0)
        sim.turn(-90.0, 5.0, 8.0)
        sim.straight(20.0, 14.0)
        val r = replay(sim)
        var worst = 0.0
        for (i in 1 until r.frames.size) {
            val step = stepMeters(sim, r.frames[i - 1], r.frames[i])
            val dt = (r.frames[i].tMs - r.frames[i - 1].tMs) / 1000.0
            val allowed = 1.5 * r.frames[i].pose.speedMps * dt + 0.35
            worst = maxOf(worst, step - allowed)
        }
        assertTrue("worst excess=$worst m/frame", worst <= 0.0)
    }

    @Test
    fun `heading rate per frame is continuous without steps`() {
        val sim = TrackSim(seed = 41)
        sim.straight(20.0, 10.0)
        sim.turn(120.0, 5.0, 8.0)
        sim.straight(20.0, 10.0)
        val r = replay(sim)
        var maxRate = 0.0
        var maxRateJump = 0.0
        var prevRate = 0.0
        for (i in 1 until r.frames.size) {
            val dt = (r.frames[i].tMs - r.frames[i - 1].tMs) / 1000.0
            val rate = shortestDeltaDeg(r.frames[i - 1].pose.headingDeg, r.frames[i].pose.headingDeg) / dt
            maxRate = maxOf(maxRate, abs(rate))
            if (i > 1) maxRateJump = maxOf(maxRateJump, abs(rate - prevRate))
            prevRate = rate
        }
        assertTrue("max heading rate=$maxRate", maxRate < 120.0)
        assertTrue("max rate jump=$maxRateJump deg/s per frame", maxRateJump < 60.0)
    }

    @Test
    fun `predictive rendering stays within a few metres of truth in a car`() {
        val sim = TrackSim(seed = 51)
        sim.straight(60.0, 15.0)
        val r = replay(sim)
        val t0 = r.frames.first().tMs
        val errs = r.frames.filter { it.tMs - t0 > 10_000 && it.tMs - t0 < 55_000 }.map {
            // Vehicles render one fix interval behind: compare with the truth at the rendered time.
            sim.distToTruthM(it.pose.renderTimeMs - sim.baseElapsedMs, it.pose.lat, it.pose.lng)
        }
        val rms = sqrt(errs.sumOf { it * it } / errs.size)
        assertTrue("rms=$rms", rms < 4.0)
    }

    @Test
    fun `stationary rendering has zero drift`() {
        val sim = TrackSim(seed = 61, posNoiseM = 4.0, corrNoiseM = 3.0)
        sim.straight(10.0, 8.0)
        sim.stand(120.0)
        val engine = TrackingEngine(TrackingProfile.CAR)
        val pipe = RenderPipeline(TrackingProfile.CAR)
        val fixes = sim.fixes(1.0)
        var t = fixes.first().tElapsedMs
        var k = 0
        val poses = ArrayList<RenderPipeline.Pose>()
        val perFrame = 16
        while (t < fixes.last().tElapsedMs) {
            while (k < fixes.size && fixes[k].tElapsedMs + 200 <= t) {
                engine.ingest(fixes[k], externalStationary = true).state?.let { pipe.onState(it) }
                k++
            }
            pipe.frame(t)?.let { if (t - fixes.first().tElapsedMs > 40_000) poses.add(it) }
            t += perFrame
        }
        // A parked target converges to the measurement mean: it may creep, but never visibly.
        // Over any 10 s window the marker must stay within 0.5 m, and frame-to-frame motion is nil.
        val window = 10_000 / perFrame
        val worst10s = (0 until poses.size - window).maxOf {
            sim.metersBetween(poses[it].lat, poses[it].lng, poses[it + window].lat, poses[it + window].lng)
        }
        assertTrue("10s drift=$worst10s", worst10s < 0.5)
        val worstStep = (1 until poses.size).maxOf {
            sim.metersBetween(poses[it - 1].lat, poses[it - 1].lng, poses[it].lat, poses[it].lng)
        }
        assertTrue("frame step=$worstStep", worstStep < 0.05)
        assertTrue(poses.all { !it.isMoving })
    }

    @Test
    fun `latency stays zero for a confident filter and ramps slowly when accuracy degrades`() {
        val pipe = RenderPipeline(TrackingProfile.CAR)
        fun state(t: Long, posStd: Double, rejects: Int = 0) = TrackState(
            tElapsedMs = t, tUtcMs = t, lat = 49.85, lng = 19.28, altitudeM = null,
            speedMps = 10.0, headingDeg = 90.0, yawRateDegPerS = 0.0, posStdM = posStd,
            headingStdDeg = 5.0, headingConfident = true, isStationary = false,
            rejectStreak = rejects, isReset = false, accuracyM = 5f
        )
        pipe.onState(state(1000, 1.5))
        var t = 1000L
        var last = pipe.frame(t)!!
        assertEquals(0.0, last.latencyMs, 1e-9)
        // Degrade
        var maxStep = 0.0
        for (i in 1..200) {
            t += 16
            if (i % 60 == 0) pipe.onState(state(t, 25.0, rejects = 2))
            val p = pipe.frame(t)!!
            maxStep = maxOf(maxStep, abs(p.latencyMs - last.latencyMs))
            last = p
        }
        assertTrue("latency=${last.latencyMs}", last.latencyMs > 150.0)
        // 100 ms/s slew => at most 1.6 ms per 16 ms frame
        assertTrue("maxStep=$maxStep", maxStep <= 1.7)
    }

    @Test
    fun `heading is held when not confident`() {
        val pipe = RenderPipeline(TrackingProfile.WALKING)
        fun st(t: Long, h: Double, conf: Boolean) = TrackState(
            t, t, 49.85, 19.28, null, 0.0, h, 0.0, 2.0, 5.0, conf, true, 0, false, 5f
        )
        pipe.onState(st(1000, 40.0, true))
        val a = pipe.frame(1000)!!
        pipe.onState(st(2000, 200.0, false))
        val b = pipe.frame(2000)!!
        assertEquals(a.headingDeg, b.headingDeg, 0.5)
    }

    @Test
    fun `gyro steers prediction between fixes`() {
        val gyro = GyroYawTracker()
        val pipe = RenderPipeline(TrackingProfile.CAR, gyro)
        val s = TrackState(
            1000, 1000, 49.85, 19.28, null, 10.0, 0.0, 0.0, 1.5, 4.0, true, false, 0, false, 5f
        )
        pipe.onState(s)
        // 30 deg/s clockwise for 800 ms after the fix
        var t = 1000L
        while (t <= 1800L) { gyro.onSample(t, Math.toRadians(30.0)); t += 20 }
        val p = pipe.frame(1800)!!
        assertEquals(24.0, p.headingDeg, 3.0)
        assertTrue(p.lng > 19.28) // curved east
    }

    @Test
    fun `big reset snaps instead of blending`() {
        val pipe = RenderPipeline(TrackingProfile.CAR)
        fun st(t: Long, lat: Double, reset: Boolean) = TrackState(
            t, t, lat, 19.28, null, 5.0, 0.0, 0.0, 2.0, 5.0, true, false, 0, reset, 5f
        )
        pipe.onState(st(1000, 49.85, false))
        pipe.frame(1000)
        pipe.onState(st(2000, 49.90, true))
        val p = pipe.frame(2000)!!
        assertEquals(49.90, p.lat, 1e-4)
    }

    @Test
    fun `gap before state and clearCorrectionOffsets align pose with pathBetween without backward zigzag`() {
        val pipe = RenderPipeline(TrackingProfile.CAR)
        fun st(t: Long, northM: Double) = TrackState(
            tElapsedMs = t,
            tUtcMs = t,
            lat = 49.85 + northM / LocalFrame.METERS_PER_DEG_LAT,
            lng = 19.28,
            altitudeM = null,
            speedMps = 14.0,
            headingDeg = 0.0,
            yawRateDegPerS = 0.0,
            posStdM = 2.5,
            headingStdDeg = 3.0,
            headingConfident = true,
            isStationary = false,
            rejectStreak = 0,
            isReset = false,
            accuracyM = 5f
        )
        pipe.onState(st(1_000L, 0.0))
        pipe.onState(st(2_000L, 14.0))
        // Simulate screen turning on at t = 16_000L before batched fixes flush
        pipe.frame(16_000L)
        // Now flush delivers states from t = 3_000L .. 16_000L
        for (sec in 3..16) {
            pipe.onState(st(sec * 1_000L, (sec - 1) * 14.0))
        }
        pipe.clearCorrectionOffsets()
        val pose = pipe.frame(16_000L)!!
        val samples = pipe.pathBetween(2_000L, pose.renderTimeMs)
        assertTrue("Expected pathBetween to cover multi-second screen-off batch", samples.size >= 50)
        val lastSample = samples.last()
        val stepM = abs(pose.lat - lastSample.lat) * LocalFrame.METERS_PER_DEG_LAT
        assertTrue("Pose must join pathBetween without backward spike, got step=$stepM m", stepM < 4.0)
    }
}
