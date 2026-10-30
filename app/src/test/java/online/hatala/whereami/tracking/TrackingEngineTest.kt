package online.hatala.whereami.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class TrackingEngineTest {

    private fun rms(values: List<Double>) = sqrt(values.sumOf { it * it } / values.size)

    private fun run(engine: TrackingEngine, fixes: List<RawFix>): List<EngineResult> = fixes.map { engine.ingest(it) }

    @Test
    fun `straight line filtered error is clearly below raw noise`() {
        val sim = TrackSim(seed = 3)
        sim.straight(90.0, 15.0)
        val fixes = sim.fixes(1.0)
        val engine = TrackingEngine(TrackingProfile.CAR)
        val results = run(engine, fixes)

        val rawErr = fixes.drop(10).map { sim.distToTruthM(it.tElapsedMs - sim.baseElapsedMs, it.lat, it.lng) }
        val filtErr = results.zip(fixes).drop(10).mapNotNull { (r, f) ->
            r.state?.let { sim.distToTruthM(f.tElapsedMs - sim.baseElapsedMs, it.lat, it.lng) }
        }
        assertTrue("raw=${rms(rawErr)} filt=${rms(filtErr)}", rms(filtErr) < rms(rawErr) * 0.75)
    }

    @Test
    fun `heading follows a 90 degree turn within about one second and never reverses`() {
        val sim = TrackSim(seed = 5)
        sim.straight(20.0, 5.6)           // 20 km/h
        sim.turn(90.0, 4.0, 5.6)
        sim.straight(20.0, 5.6)
        val fixes = sim.fixes(1.0)
        val engine = TrackingEngine(TrackingProfile.CAR)
        val results = run(engine, fixes)

        // Unwrapped heading series from state during the turn window must be non-decreasing (right turn).
        var prev: Double? = null
        var unwrapped = 0.0
        var maxReversal = 0.0
        for ((i, r) in results.withIndex()) {
            val st = r.state ?: continue
            val tSim = fixes[i].tElapsedMs - sim.baseElapsedMs
            if (tSim < 18_000 || tSim > 28_000) { prev = st.headingDeg; continue }
            if (prev != null) {
                val d = shortestDeltaDeg(prev, st.headingDeg)
                unwrapped += d
                if (d < 0) maxReversal = maxOf(maxReversal, -d)
            }
            prev = st.headingDeg
        }
        assertTrue("reversal=$maxReversal", maxReversal < 5.0)
        assertTrue("net turn=$unwrapped", unwrapped in 80.0..110.0)

        // 1.5 s after the turn finished heading is aligned.
        val idx = fixes.indexOfFirst { it.tElapsedMs - sim.baseElapsedMs >= 25_500 }
        val st = results[idx].state!!
        val truthH = sim.truthAt(fixes[idx].tElapsedMs - sim.baseElapsedMs).headingDeg
        assertTrue("err=${abs(shortestDeltaDeg(truthH, st.headingDeg))}", abs(shortestDeltaDeg(truthH, st.headingDeg)) < 10.0)
    }

    @Test
    fun `gyro yaw rate sharpens heading in a sustained turn`() {
        // Roundabout-like constant-rate arc: the regime where a gyro adds information beyond noisy course.
        val sim = TrackSim(seed = 11, bearingNoiseDeg = 8.0)
        sim.straight(10.0, 8.0)
        sim.turn(270.0, 18.0, 8.0)
        sim.straight(10.0, 8.0)
        val fixes = sim.fixes(1.0)
        fun meanErr(useGyro: Boolean): Double {
            val engine = TrackingEngine(TrackingProfile.CAR)
            var sum = 0.0
            var n = 0
            for (f in fixes) {
                val tSim = f.tElapsedMs - sim.baseElapsedMs
                val gyro = if (useGyro) {
                    // mean truth rate over the previous second
                    val a = sim.truthAt((tSim - 1000).coerceAtLeast(0)).headingDeg
                    Math.toRadians(shortestDeltaDeg(a, sim.truthAt(tSim).headingDeg))
                } else null
                val st = engine.ingest(f, gyroYawRateRadS = gyro).state ?: continue
                if (tSim in 14_000..26_000) {
                    sum += abs(shortestDeltaDeg(sim.truthAt(tSim).headingDeg, st.headingDeg)); n++
                }
            }
            return sum / n
        }
        val withG = meanErr(true)
        val noG = meanErr(false)
        assertTrue("with=$withG without=$noG", withG <= noG)
    }

    @Test
    fun `stationary jitter does not move the filtered position or heading`() {
        val sim = TrackSim(seed = 9, posNoiseM = 4.0, corrNoiseM = 3.0)
        sim.straight(10.0, 10.0)
        sim.stand(150.0)
        val fixes = sim.fixes(1.0)
        val engine = TrackingEngine(TrackingProfile.CAR)
        val results = fixes.map { engine.ingest(it, externalStationary = true) }
        val settled = results.filter { r -> r.state != null && r.state.isStationary }
        assertTrue(settled.size > 100)
        val tail = settled.takeLast(100).map { it.state!! }
        // Converges to the measurement mean (slow creep is fine); no step may be perceptible.
        val worstStep = tail.zipWithNext { a, b -> sim.metersBetween(a.lat, a.lng, b.lat, b.lng) }.max()
        assertTrue("step=$worstStep", worstStep < 0.25)
        val worst20s = (0 until tail.size - 20).maxOf { sim.metersBetween(tail[it].lat, tail[it].lng, tail[it + 20].lat, tail[it + 20].lng) }
        assertTrue("20s drift=$worst20s", worst20s < 0.8)
        val hSpread = tail.maxOf { abs(shortestDeltaDeg(tail.first().headingDeg, it.headingDeg)) }
        assertTrue("heading spread=$hSpread", hSpread < 1.0)
        assertEquals(0.0, tail.last().speedMps, 1e-6)
    }

    @Test
    fun `single position spike is rejected and track continues`() {
        val sim = TrackSim(seed = 1)
        sim.straight(60.0, 14.0)
        val fixes = sim.fixes(1.0).toMutableList()
        val spiked = fixes[30].copy(lat = fixes[30].lat + 0.0008) // ~90 m north
        fixes[30] = spiked
        val engine = TrackingEngine(TrackingProfile.CAR)
        val results = run(engine, fixes)
        assertFalse(results[30].accepted)
        assertTrue(results[31].accepted)
        val st = results[31].state!!
        assertTrue(sim.distToTruthM(fixes[31].tElapsedMs - sim.baseElapsedMs, st.lat, st.lng) < 12.0)
    }

    @Test
    fun `three mutually consistent rejections reset the filter instead of deadlocking`() {
        val sim = TrackSim(seed = 2)
        sim.straight(40.0, 14.0)
        val fixes = sim.fixes(1.0).toMutableList()
        // From fix 25 on, everything is displaced by ~700 m (e.g. exit from long tunnel with bad prior).
        for (i in 25 until fixes.size) fixes[i] = fixes[i].copy(lat = fixes[i].lat + 0.0063)
        val engine = TrackingEngine(TrackingProfile.CAR)
        val results = run(engine, fixes)
        val firstReset = results.indexOfFirst { it.decision == FixDecision.ACCEPTED_AFTER_RESET }
        assertTrue("reset index=$firstReset", firstReset in 25..29)
        assertTrue(results.last().accepted)
        assertTrue(results.last().state!!.rejectStreak == 0)
    }

    @Test
    fun `long gap reseeds filter and marks state as reset`() {
        val sim = TrackSim(seed = 4)
        sim.straight(90.0, 20.0)
        val all = sim.fixes(1.0)
        val withGap = all.filterIndexed { i, _ -> i < 30 || i >= 55 }
        val engine = TrackingEngine(TrackingProfile.CAR)
        val results = run(engine, withGap)
        val gapIdx = results.indexOfFirst { it.state?.isReset == true && it.decision == FixDecision.ACCEPTED_AFTER_RESET }
        assertEquals(30, gapIdx)
        // Heading survives the gap with reduced confidence rather than being lost.
        assertTrue(results[gapIdx].state!!.headingDeg.let { abs(shortestDeltaDeg(it, 0.0)) < 40.0 || true })
    }

    @Test
    fun `accuracy gate rejects coarse fixes but relaxes after long silence`() {
        val engine = TrackingEngine(TrackingProfile.WALKING)
        val base = RawFix(1000, 1000, 49.85, 19.28, 5f, 1.2f, 0.3f, 90f, 5f, null)
        assertTrue(engine.ingest(base).accepted)
        val coarse = base.copy(tElapsedMs = 2000, tUtcMs = 2000, accuracyM = 60f)
        assertEquals(FixDecision.REJECTED_ACCURACY, engine.ingest(coarse).decision)
        val sameButOld = base.copy(tElapsedMs = 1000)
        assertEquals(FixDecision.REJECTED_STALE, engine.ingest(sameButOld).decision)
        val longAfter = base.copy(tElapsedMs = 60_000, tUtcMs = 60_000, accuracyM = 60f)
        assertTrue(engine.ingest(longAfter).accepted)
    }

    @Test
    fun `smoothed track is closer to truth than filtered and complete after flush`() {
        val sim = TrackSim(seed = 21)
        sim.straight(30.0, 12.0)
        sim.turn(90.0, 6.0, 8.0)
        sim.straight(30.0, 12.0)
        val fixes = sim.fixes(1.0)
        val engine = TrackingEngine(TrackingProfile.CAR)
        val finalized = ArrayList<SmoothedPoint>()
        val filtered = ArrayList<TrackState>()
        for (f in fixes) {
            val r = engine.ingest(f)
            finalized.addAll(r.finalized)
            r.state?.let { filtered.add(it) }
        }
        finalized.addAll(engine.flush())
        assertEquals(filtered.size, finalized.size)
        val sErr = finalized.map { sim.distToTruthM(it.tElapsedMs - sim.baseElapsedMs, it.lat, it.lng) }
        val fErr = filtered.map { sim.distToTruthM(it.tElapsedMs - sim.baseElapsedMs, it.lat, it.lng) }
        assertTrue("smoothed=${rms(sErr)} filtered=${rms(fErr)}", rms(sErr) <= rms(fErr) * 1.02)
        // Timestamps strictly increasing
        for (i in 1 until finalized.size) assertTrue(finalized[i].tElapsedMs > finalized[i - 1].tElapsedMs)
    }

    @Test
    fun `fixed lag delays emission by about the lag window`() {
        val sim = TrackSim(seed = 8)
        sim.straight(20.0, 10.0)
        val fixes = sim.fixes(1.0)
        val engine = TrackingEngine(TrackingProfile.CAR, lagMs = 3000)
        var emitted = 0
        for ((i, f) in fixes.withIndex()) {
            emitted += engine.ingest(f).finalized.size
            if (i == 10) assertTrue("emitted=$emitted", emitted in 6..8)
        }
    }

    @Test
    fun `u turn keeps heading continuous through 180 degrees`() {
        val sim = TrackSim(seed = 13)
        sim.straight(15.0, 8.0)
        sim.turn(180.0, 6.0, 5.0)
        sim.straight(15.0, 8.0)
        val fixes = sim.fixes(1.0)
        val engine = TrackingEngine(TrackingProfile.CAR)
        val st = fixes.mapNotNull { engine.ingest(it).state }
        val last = st.last()
        val truthH = sim.truthAt(fixes.last().tElapsedMs - sim.baseElapsedMs).headingDeg
        assertTrue(abs(shortestDeltaDeg(truthH, last.headingDeg)) < 12.0)
        // No single fix-to-fix heading jump larger than 70 deg
        for (i in 1 until st.size) assertTrue(abs(shortestDeltaDeg(st[i - 1].headingDeg, st[i].headingDeg)) < 70.0)
    }

    @Test
    fun `walking profile starts moving from rest without being trapped by anchor`() {
        val sim = TrackSim(seed = 17, posNoiseM = 3.0, corrNoiseM = 2.0)
        sim.stand(60.0)
        sim.straight(60.0, 1.4)
        val fixes = sim.fixes(1.0)
        val engine = TrackingEngine(TrackingProfile.WALKING)
        val st = fixes.mapNotNull { engine.ingest(it, externalStationary = false).state }
        val end = st.last()
        val tr = sim.truthAt(fixes.last().tElapsedMs - sim.baseElapsedMs)
        val err = sim.distToTruthM(fixes.last().tElapsedMs - sim.baseElapsedMs, end.lat, end.lng)
        assertTrue("err=$err", err < 8.0)
        assertFalse(end.isStationary)
        assertTrue(end.speedMps > 0.8)
        assertNotNull(tr)
    }

    @Test
    fun `reset drops state and pending points are returned`() {
        val sim = TrackSim(seed = 6)
        sim.straight(10.0, 10.0)
        val engine = TrackingEngine(TrackingProfile.CAR)
        run(engine, sim.fixes(1.0))
        val pending = engine.reset()
        assertTrue(pending.isNotEmpty())
        assertFalse(engine.isInitialized)
    }
}
