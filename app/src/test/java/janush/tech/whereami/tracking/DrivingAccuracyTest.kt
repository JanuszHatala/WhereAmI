package janush.tech.whereami.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Driving field round (trip 233): braking overshoot, stop convergence, biased bearing, arrow, latency. */
class DrivingAccuracyTest {

    private fun run(sim: TrackSim, profile: TrackingProfile = TrackingProfile.CAR): List<Pair<RawFix, TrackState>> {
        val engine = TrackingEngine(profile)
        return sim.fixes().mapNotNull { f -> engine.ingest(f).state?.let { f to it } }
    }

    /** Along-track offset of the estimate from the truth (+ = ahead). */
    private fun along(sim: TrackSim, s: TrackState): Double {
        val tr = sim.truthAt(s.tElapsedMs - sim.baseElapsedMs)
        val fr = sim.frameOf()
        val h = Math.toRadians(tr.headingDeg)
        return (fr.east(s.lng) - tr.e) * sin(h) + (fr.north(s.lat) - tr.n) * cos(h)
    }

    @Test
    fun `braking does not run the estimate ahead of the vehicle`() {
        val sim = TrackSim(seed = 3, posNoiseM = 1.0, corrNoiseM = 0.5)
        sim.straight(20.0, 15.0)
        sim.straight(8.0, 0.0) // 2.5 m/s^2 to a halt
        sim.stand(20.0)
        val states = run(sim)
        val braking = states.filter { (f, s) -> f.tElapsedMs - sim.baseElapsedMs in 20_000L..27_000L && !s.isStationary }
        val worst = braking.maxOf { along(sim, it.second) }
        assertTrue("overshoot while braking $worst m", worst < 2.0)
    }

    @Test
    fun `pulling away does not leave the estimate behind`() {
        val sim = TrackSim(seed = 5, posNoiseM = 1.0, corrNoiseM = 0.5)
        sim.stand(10.0)
        sim.straight(15.0, 15.0)
        val states = run(sim)
        val accel = states.filter { (f, s) -> f.tElapsedMs - sim.baseElapsedMs in 12_000L..17_000L && !s.isStationary }
        val worst = accel.minOf { along(sim, it.second) }
        assertTrue("lag while accelerating $worst m", worst > -2.0)
    }

    @Test
    fun `a stop converges to its fixes within seconds and does not jump when driving off`() {
        val sim = TrackSim(seed = 11)
        sim.straight(20.0, 14.0)
        sim.straight(7.0, 0.0)
        sim.stand(30.0)
        sim.straight(10.0, 10.0)
        val states = run(sim)
        val stop = states.filter { it.second.isStationary }
        assertTrue("stationary detected", stop.size > 15)
        val t0 = stop.first().second.tElapsedMs
        val fr = sim.frameOf()
        fun dist(a: TrackState, b: TrackState) =
            hypot(fr.east(b.lng) - fr.east(a.lng), fr.north(b.lat) - fr.north(a.lat))
        val first = stop.first().second
        val last = stop.last().second
        // The settle onto the stop is short (field: 5.6 m overshoot crept back for 10 s, then snapped).
        val settle = stop.maxOf { dist(first, it.second) }
        assertTrue("settle after stopping $settle m", settle < 3.5)
        val anchoredErr = sim.distToTruthM(last.tElapsedMs - sim.baseElapsedMs, last.lat, last.lng)
        assertTrue("anchored position error $anchoredErr m", anchoredErr < 3.5)
        val anchored = stop.filter { it.second.tElapsedMs >= t0 + TrackingEngine.ANCHOR_AFTER_MS + 2_000 }
        val drift = anchored.maxOf { dist(anchored.first().second, it.second) }
        assertTrue("drift once anchored $drift m", drift < 0.3)
        val next = states.first { it.second.tElapsedMs > last.tElapsedMs }.second
        val jump = hypot(fr.east(next.lng) - fr.east(last.lng), fr.north(next.lat) - fr.north(last.lat))
        assertTrue("jump when driving off $jump m", jump < 2.5)
    }

    @Test
    fun `a biased provider bearing does not drag the estimate off the road`() {
        // Bearing 20 deg off while claiming 6 deg accuracy (trip 233, 16:39: 33 m off the street).
        val sim = TrackSim(seed = 2, posNoiseM = 2.0, corrNoiseM = 2.0, bearingBiasDeg = 20.0)
        sim.straight(60.0, 13.0)
        val states = run(sim)
        val worst = states.filter { it.first.tElapsedMs - sim.baseElapsedMs > 10_000 }
            .maxOf { sim.distToTruthM(it.second.tElapsedMs - sim.baseElapsedMs, it.second.lat, it.second.lng) }
        assertTrue("offset with a biased bearing $worst m", worst < 10.0)
    }

    @Test
    fun `an unbiased drive keeps trusting the bearing`() {
        val sim = TrackSim(seed = 2, posNoiseM = 2.0, corrNoiseM = 2.0)
        sim.straight(30.0, 13.0)
        sim.turn(90.0, 6.0, 9.0)
        sim.straight(20.0, 13.0)
        val states = run(sim)
        val moving = states.filter { it.first.tElapsedMs - sim.baseElapsedMs > 5_000 && it.second.speedMps > 5.0 }
        val confident = moving.count { it.second.headingConfident }
        assertTrue("heading confident $confident / ${moving.size}", confident >= moving.size - 1)
    }

    @Test
    fun `course consistency measures the angle between bearing and displacement`() {
        val c = CourseConsistency()
        val fr = LocalFrame(49.8, 19.0)
        for (i in 0..7) c.add(i * 1_000L, fr.lat(i * 10.0), fr.lng(0.0), 20.0, 10.0, 3.0)
        assertEquals(20.0 - Math.toDegrees(kotlin.math.atan2(3.0, 50.0)), c.disagreementDeg!!, 0.5)
        val short = CourseConsistency()
        for (i in 0..5) short.add(i * 1_000L, fr.lat(i * 2.0), fr.lng(0.0), 20.0, 2.0, 3.0)
        assertNull("too little travel to judge", short.disagreementDeg)
        // A single spike does not count: only a disagreement lasting three fixes does.
        val spike = CourseConsistency()
        for (i in 0..9) spike.add(i * 1_000L, fr.lat(i * 10.0), fr.lng(0.0), if (i == 8) 20.0 else 0.0, 10.0, 3.0)
        assertTrue("spike ${spike.disagreementDeg}", spike.disagreementDeg!! < 1.0)
        // A braking curve predicts its own chord: no disagreement.
        val turn = CourseConsistency()
        var e = 0.0; var n = 0.0
        for (i in 0..8) {
            val h = 5.0 * i
            val v = 12.0 - 0.6 * i
            turn.add(i * 1_000L, fr.lat(n), fr.lng(e), h, v, 3.0)
            val hm = Math.toRadians(h + 2.5)
            e += (v - 0.3) * sin(hm); n += (v - 0.3) * cos(hm)
        }
        assertTrue("curve disagreement ${turn.disagreementDeg}", turn.disagreementDeg!! < 2.0)
    }

    @Test
    fun `the vehicle arrow does not flip backwards while the cursor slides onto a fix`() {
        assertEquals(10.0, MotionCourse.forVehicle(190.0, 10.0, 2_000L)!!, 1e-9)
        assertEquals(60.0, MotionCourse.forVehicle(60.0, 10.0, 2_000L)!!, 1e-9)
        // Long after the last heading observation the visible motion is real motion.
        assertEquals(190.0, MotionCourse.forVehicle(190.0, 10.0, 20_000L)!!, 1e-9)
        assertNull(MotionCourse.forVehicle(null, 10.0, 0L))
    }

    @Test
    fun `vehicles render one fix interval behind, on foot keeps its own latency`() {
        val sim = TrackSim(seed = 4)
        sim.straight(30.0, 12.0)
        val fixes = sim.fixes()
        fun latencyAfter(profile: TrackingProfile): Double {
            val engine = TrackingEngine(profile)
            val rp = RenderPipeline(profile)
            var pose: RenderPipeline.Pose? = null
            var i = 0
            var now = fixes.first().tElapsedMs
            while (now < fixes.last().tElapsedMs) {
                while (i < fixes.size && fixes[i].tElapsedMs <= now) {
                    engine.ingest(fixes[i]).state?.let(rp::onState); i++
                }
                pose = rp.frame(now) ?: pose
                now += 16
            }
            return pose!!.latencyMs
        }
        assertEquals(RenderPipeline.VEHICLE_LATENCY_MS, latencyAfter(TrackingProfile.CAR), 1.0)
        assertTrue(abs(latencyAfter(TrackingProfile.RUNNING) - RenderPipeline.VEHICLE_LATENCY_MS) > 100.0)
    }
}
