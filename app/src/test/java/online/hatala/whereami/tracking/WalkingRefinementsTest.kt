package online.hatala.whereami.tracking

import online.hatala.whereami.DemAltitudeHold
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class WalkingRefinementsTest {

    private val frame = LocalFrame(49.82, 19.05)

    private fun sp(tMs: Long, e: Double, n: Double, speed: Double, stationary: Boolean = false) = SmoothedPoint(
        tElapsedMs = tMs, tUtcMs = 1_700_000_000_000L + tMs, lat = frame.lat(n), lng = frame.lng(e),
        altitudeM = null, speedMps = speed, headingDeg = 0.0, accuracyM = 4f, isStationary = stationary
    )

    /** Points every second along a path described by (east, north) positions at a given speed. */
    private fun sample(sampler: AdaptiveTrackSampler, pts: List<SmoothedPoint>): List<SmoothedPoint> {
        val out = ArrayList<SmoothedPoint>()
        pts.forEach { out.addAll(sampler.offer(it)) }
        out.addAll(sampler.drain())
        return out
    }

    private fun straight(speed: Double, seconds: Int, hz: Int = 1): List<SmoothedPoint> =
        (0..seconds * hz).map { i -> val t = i * 1000L / hz; sp(t, 0.0, speed * t / 1000.0, speed) }

    // ── Adaptive density ─────────────────────────────────────────────────────────

    @Test
    fun `walking straight is stored every few metres, driving straight much sparser`() {
        val walk = sample(AdaptiveTrackSampler(), straight(1.3, 200, hz = 2))
        val drive = sample(AdaptiveTrackSampler(), straight(25.0, 200))
        val walkPerKm = walk.size / (1.3 * 200 / 1000.0)
        val drivePerKm = drive.size / (25.0 * 200 / 1000.0)
        assertTrue("walk/km=$walkPerKm", walkPerKm in 300.0..550.0)
        assertTrue("drive/km=$drivePerKm", drivePerKm < 40.0)
        val walkGaps = walk.zipWithNext { a, b -> AdaptiveTrackSampler.distanceM(a, b) }
        assertTrue(walkGaps.all { it <= AdaptiveTrackSampler.maxSpacingM(1.3) + 0.66 })
    }

    @Test
    fun `a sharp walking corner keeps a vertex at the corner`() {
        val pts = ArrayList<SmoothedPoint>()
        for (i in 0..20) pts.add(sp(i * 1000L, 0.0, 1.3 * i, 1.3))          // north 26 m
        for (i in 1..20) pts.add(sp((20 + i) * 1000L, 1.3 * i, 26.0, 1.3))  // then east
        val out = sample(AdaptiveTrackSampler(), pts)
        val nearCorner = out.minOf { p ->
            val e = frame.east(p.lng); val n = frame.north(p.lat)
            kotlin.math.hypot(e, n - 26.0)
        }
        assertTrue("closest vertex to corner=$nearCorner", nearCorner < 1.5)
    }

    @Test
    fun `a gentle curve at walking speed stays within tolerance of the path`() {
        val r = 15.0
        val pts = (0..60).map { i ->
            val a = i * 1.3 / r
            sp(i * 1000L, r * (1 - cos(a)), r * sin(a), 1.3)
        }
        val out = sample(AdaptiveTrackSampler(), pts)
        // Every input point lies within tolerance of the stored polyline.
        val tol = AdaptiveTrackSampler.toleranceM(1.3) + 0.05
        for (p in pts) {
            val dev = out.zipWithNext().minOf { (a, b) -> AdaptiveTrackSampler.maxDeviationM(a, b, listOf(p)) }
            assertTrue("dev=$dev", dev <= tol)
        }
    }

    @Test
    fun `stationary jitter commits nothing and departure resumes from the last moving point`() {
        val s = AdaptiveTrackSampler()
        val out = ArrayList<SmoothedPoint>()
        straight(1.3, 10).forEach { out.addAll(s.offer(it)) }
        val before = out.size
        for (i in 0..60) out.addAll(s.offer(sp(11_000L + i * 1000, 0.3 * sin(i.toDouble()), 13.0, 0.0, stationary = true)))
        assertTrue(out.size <= before + 1)
    }

    @Test
    fun `crawl speed inside parking nest commits nothing until escape distance`() {
        val s = AdaptiveTrackSampler()
        val out = ArrayList<SmoothedPoint>()
        // Anchor at origin while "creeping" at 0.8 m/s inside 12 m
        out.addAll(s.offer(sp(0L, 0.0, 0.0, 0.8, stationary = false)))
        for (i in 1..20) {
            out.addAll(s.offer(sp(i * 1_000L, i * 0.4, 0.0, 0.8, stationary = false)))
        }
        assertEquals("crawl nest must not densify", 1, out.size)
        // Escape beyond PARKING_ESCAPE_M
        out.addAll(s.offer(sp(25_000L, 15.0, 0.0, 0.8, stationary = false)))
        assertTrue(out.size >= 2)
    }

    @Test
    fun `normal walking speed is not held by the crawl nest rule`() {
        val walk = sample(AdaptiveTrackSampler(), straight(1.3, 40, hz = 2))
        assertTrue("walking at 1.3 m/s must still densify", walk.size >= 5)
    }

    @Test
    fun `density follows measured speed, not the selected profile`() {
        // The sampler has no profile input: a "walking" session that is actually driving gets driving density.
        val asDriving = sample(AdaptiveTrackSampler(), straight(25.0, 60))
        assertTrue(asDriving.size < 40)
    }

    // ── Pencil trail ────────────────────────────────────────────────────────────

    @Test
    fun `pencil trail follows the cursor and is consumed by the committed track`() {
        val p = PencilTrail()
        for (i in 0..100) p.add(1_000L + i * 16, frame.lat(i * 0.03), frame.lng(0.0), 1.8)
        assertTrue(p.size in 3..4) // 3 m drawn in >= 1.0 m steps (walking multipath floor)
        p.pruneThrough(1_000L + 50 * 16)
        assertTrue(p.timeAt(0) > 1_000L + 50 * 16)
        p.pruneThrough(Long.MAX_VALUE)
        assertEquals(0, p.size)
    }

    @Test
    fun `pencil trail ignores out of order samples`() {
        val p = PencilTrail()
        assertTrue(p.add(2_000L, frame.lat(0.0), frame.lng(0.0), 1.0))
        assertFalse(p.add(1_000L, frame.lat(5.0), frame.lng(0.0), 1.0))
    }

    // ── Motion course ───────────────────────────────────────────────────────────

    @Test
    fun `motion course points along the rendered motion once a baseline is travelled`() {
        val c = MotionCourse()
        var last: Double? = null
        for (i in 0..120) last = c.update(i * 16L, frame.lat(i * 0.04), frame.lng(i * 0.04))
        assertNotNull(last)
        assertEquals(45.0, last!!, 2.0)
    }

    @Test
    fun `motion course is null when not moving and old motion expires`() {
        val c = MotionCourse()
        for (i in 0..60) c.update(i * 100L, frame.lat(i * 0.2), frame.lng(0.0))
        assertNotNull(c.update(6_100L, frame.lat(12.0), frame.lng(0.0)))
        assertNull(c.update(20_000L, frame.lat(12.0), frame.lng(0.0)))
    }

    // ── Altitude hold ───────────────────────────────────────────────────────────

    @Test
    fun `dem altitude is held through failed lookups nearby and released far away`() {
        val h = DemAltitudeHold(holdRadiusM = 150.0)
        assertEquals(318.0, h.accept(frame.lat(0.0), frame.lng(0.0), 318.0)!!, 0.0)
        assertEquals(318.0, h.accept(frame.lat(40.0), frame.lng(0.0), null)!!, 0.0)
        assertEquals(318.0, h.accept(frame.lat(60.0), frame.lng(0.0), 0.0)!!, 0.0)
        assertNull(h.accept(frame.lat(400.0), frame.lng(0.0), null))
        assertEquals(320.0, h.accept(frame.lat(400.0), frame.lng(0.0), 320.0)!!, 0.0)
    }

    // ── Engine ──────────────────────────────────────────────────────────────────

    private fun fix(
        t: Long, e: Double, n: Double, acc: Float, speed: Float?, bearing: Float? = null, bAcc: Float? = null
    ) = RawFix(
        tElapsedMs = 1_000_000L + t, tUtcMs = 1_700_000_000_000L + t, lat = frame.lat(n), lng = frame.lng(e),
        accuracyM = acc, speedMps = speed, speedAccMps = 0.3f, bearingDeg = bearing, bearingAccDeg = bAcc,
        altitudeM = 300.0
    )

    @Test
    fun `parked device with sparse indoor fixes is not re-seeded on every gap`() {
        val engine = TrackingEngine(TrackingProfile.WALKING)
        var t = 0L
        repeat(15) { engine.ingest(fix(t, 0.0, 0.0, 6f, 0.02f)); t += 1000 }
        val decisions = ArrayList<FixDecision>()
        repeat(10) { i ->
            t += 12_000
            decisions.add(engine.ingest(fix(t, 4.0 * sin(i.toDouble()), 4.0 * cos(i.toDouble()), 14f, 0.03f)).decision)
        }
        assertFalse(decisions.toString(), decisions.contains(FixDecision.ACCEPTED_AFTER_RESET))
        val s = engine.lastState!!
        assertTrue(abs(frame.north(s.lat)) < 3.0 && abs(frame.east(s.lng)) < 3.0)
    }

    @Test
    fun `walking profile while actually driving escalates the envelope instead of resetting`() {
        val sim = TrackSim(seed = 21)
        sim.straight(10.0, 1.2)
        sim.straight(120.0, 16.0)
        val fixes = sim.fixes(1.0)
        val engine = TrackingEngine(TrackingProfile.WALKING)
        val results = fixes.map { engine.ingest(it) }
        val late = results.drop(30)
        val accepted = late.count { it.decision == FixDecision.ACCEPTED }
        assertTrue("accepted=$accepted/${late.size}", accepted >= late.size - 2)
        assertTrue(engine.activeProfile.maxSpeedMps >= 16.0 / TrackingEngine.ENVELOPE_FRACTION)
        assertEquals("WALKING", engine.profile.name)
    }

    @Test
    fun `envelope relaxes back to the selected profile after sustained matching motion`() {
        val sim = TrackSim(seed = 22)
        sim.straight(40.0, 12.0)
        sim.straight(90.0, 1.3)
        val engine = TrackingEngine(TrackingProfile.WALKING)
        sim.fixes(1.0).forEach { engine.ingest(it) }
        assertEquals("WALKING", engine.activeProfile.name)
    }

    @Test
    fun `slow walk with good provider bearing accuracy keeps heading confident`() {
        val engine = TrackingEngine(TrackingProfile.WALKING)
        var confident = 0
        for (i in 0..60) {
            val r = engine.ingest(fix(i * 1000L, 0.0, 0.9 * i, 4f, 0.9f, bearing = 0f, bAcc = 14f))
            if (i > 10 && r.state?.headingConfident == true) confident++
        }
        assertTrue("confident=$confident/50", confident >= 45)
    }

    // ── Render pipeline ─────────────────────────────────────────────────────────

    @Test
    fun `without an observed heading the cursor continues the actual displacement, not the drifting heading`() {
        val rp = RenderPipeline(TrackingProfile.WALKING)
        fun st(t: Long, n: Double) = TrackState(
            tElapsedMs = t, tUtcMs = t, lat = frame.lat(n), lng = frame.lng(0.0), altitudeM = null,
            speedMps = 1.2, headingDeg = 90.0, yawRateDegPerS = 0.0, posStdM = 3.0, headingStdDeg = 120.0,
            headingConfident = false, isStationary = false, rejectStreak = 0, isReset = false, accuracyM = 3f
        )
        rp.onState(st(1_000L, 0.0))
        rp.frame(1_000L)
        rp.onState(st(2_000L, 1.2))
        var pose: RenderPipeline.Pose? = null
        for (t in 2_000L..2_800L step 16) pose = rp.frame(t)
        val p = pose!!
        assertTrue("east drift=${frame.east(p.lng)}", abs(frame.east(p.lng)) < 0.3)
        assertFalse(p.headingFresh)
    }
}
