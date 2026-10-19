package janush.tech.whereami.tracking

import janush.tech.whereami.LiveTrackLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.osmdroid.util.GeoPoint
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Slow-mode rendering (arrow = direction of the drawn curve) and the display curve of the polyline.
 */
class SlowRenderAndCurveTest {

    private data class Frame(val tMs: Long, val pose: RenderPipeline.Pose)

    private fun render(sim: TrackSim, profile: TrackingProfile, delayMs: Long = 300): List<Frame> {
        val engine = TrackingEngine(profile)
        val pipe = RenderPipeline(profile)
        val fixes = sim.fixes(1.0)
        val states = fixes.mapNotNull { f -> engine.ingest(f).state?.let { (f.tElapsedMs + delayMs) to it } }
        val out = ArrayList<Frame>()
        var next = 0
        var t = fixes.first().tElapsedMs + delayMs
        while (t <= fixes.last().tElapsedMs + 1_000) {
            while (next < states.size && states[next].first <= t) { pipe.onState(states[next].second); next++ }
            pipe.frame(t)?.let { out.add(Frame(t, it)) }
            t += 16
        }
        return out
    }

    private fun pct(l: List<Double>, q: Double) = l.sorted()[((l.size - 1) * q).toInt()]

    /** Walking in loops with a poor Doppler course (the field case): weak GPS, ~1 m/s. */
    private fun loopWalk(): TrackSim {
        val sim = TrackSim(seed = 11, posNoiseM = 3.0, corrNoiseM = 2.0, bearingNoiseDeg = 35.0)
        sim.straight(20.0, 1.0)
        repeat(3) { sim.turn(360.0, 25.0, 1.0); sim.straight(8.0, 1.0) }
        return sim
    }

    @Test
    fun `slow walk renders behind on a curve and the arrow follows the drawn motion`() {
        val sim = loopWalk()
        val frames = render(sim, TrackingProfile.WALKING)
        val start = frames.first().tMs + 25_000
        val moving = frames.filter { it.tMs >= start && it.pose.isMoving }
        assertTrue(moving.size > 1000)
        val withPath = moving.count { it.pose.pathHeadingDeg != null }
        assertTrue("path heading available: $withPath/${moving.size}", withPath > 0.95 * moving.size)
        assertEquals(1_600.0, moving.map { it.pose.latencyMs }.sorted()[moving.size / 2], 50.0)

        // Arrow vs. where the cursor actually goes next on screen.
        val errs = ArrayList<Double>()
        for (k in 0 until moving.size - 6) {
            val a = moving[k]; val b = moving[k + 6]
            val h = a.pose.pathHeadingDeg ?: continue
            val f = sim.frameOf()
            val dE = f.east(b.pose.lng) - f.east(a.pose.lng)
            val dN = f.north(b.pose.lat) - f.north(a.pose.lat)
            if (hypot(dE, dN) < 0.03) continue
            errs.add(abs(shortestDeltaDeg(h, Math.toDegrees(atan2(dE, dN)))))
        }
        assertTrue("arrow vs drawn motion p90=${pct(errs, 0.9)}", pct(errs, 0.9) < 10.0)
    }

    @Test
    fun `driving keeps the predictive pipeline, also crawling in traffic`() {
        val sim = TrackSim(seed = 3)
        sim.straight(25.0, 1.5)
        sim.straight(40.0, 15.0)
        val frames = render(sim, TrackingProfile.CAR)
        assertTrue(frames.all { it.pose.pathHeadingDeg == null })
        val late = frames.filter { it.tMs >= frames.first().tMs + 45_000 }
        assertTrue(late.maxOf { it.pose.latencyMs } < 200.0)
    }

    // ---- CurvedPolyline / LiveTrackLine ----

    private val f = LocalFrame(49.8, 19.0)
    private fun gp(e: Double, n: Double) = GeoPoint(f.lat(n), f.lng(e))

    private fun curved(pts: List<GeoPoint>) = LiveTrackLine.curved(pts)

    private fun vertexTurns(l: List<GeoPoint>): List<Double> {
        val out = ArrayList<Double>()
        for (j in 1 until l.size - 1) {
            val ax = f.east(l[j].longitude) - f.east(l[j - 1].longitude)
            val ay = f.north(l[j].latitude) - f.north(l[j - 1].latitude)
            val bx = f.east(l[j + 1].longitude) - f.east(l[j].longitude)
            val by = f.north(l[j + 1].latitude) - f.north(l[j].latitude)
            out.add(abs(shortestDeltaDeg(Math.toDegrees(atan2(ax, ay)), Math.toDegrees(atan2(bx, by)))))
        }
        return out
    }

    @Test
    fun `straight track gets no extra points`() {
        val pts = (0..10).map { gp(0.0, it * 3.0) }
        assertEquals(pts.size, curved(pts).size)
    }

    @Test
    fun `square corners are rounded and every recorded vertex stays on the line`() {
        val square = listOf(gp(0.0, 0.0), gp(0.0, 2.0), gp(2.0, 2.0), gp(2.0, 0.0), gp(4.0, 0.0), gp(4.0, 2.0))
        val c = curved(square)
        assertTrue(c.size > square.size * 2)
        for (v in square) {
            assertTrue(c.any { abs(it.latitude - v.latitude) < 1e-12 && abs(it.longitude - v.longitude) < 1e-12 })
        }
        assertTrue("max turn ${vertexTurns(c).max()}", vertexTurns(c).max() < 60.0)
        assertTrue(vertexTurns(square).max() > 89.0)
        // Rounding stays close to the recorded shape (display only, no wild overshoot).
        for (p in c) {
            val e = f.east(p.longitude); val n = f.north(p.latitude)
            assertTrue("overshoot at %.2f, %.2f".format(e, n), e > -0.6 && e < 4.6 && n > -0.6 && n < 2.6)
        }
    }

    @Test
    fun `incremental live line equals the full curve`() {
        val c = (0 until 14).map { i -> gp(i * 1.5, if (i % 2 == 0) 0.0 else 1.2) }
        val live = LiveTrackLine()
        val pencil = PencilTrail()
        for (k in 1..c.size) {
            live.setCommitted(c.subList(0, k).toList())
            live.rebuildTail(pencil, c[k - 1])
        }
        val full = LiveTrackLine()
        full.setCommitted(c)
        full.rebuildTail(pencil, c.last())
        assertEquals(full.points.size, live.points.size)
        val expected = curved(c) + c.last()
        assertEquals(expected.size, live.points.size)
        for (i in expected.indices) {
            assertEquals(expected[i].latitude, live.points[i].latitude, 1e-12)
            assertEquals(expected[i].longitude, live.points[i].longitude, 1e-12)
        }
    }

    @Test
    fun `live line ends exactly under the cursor and moves the tip in place`() {
        val live = LiveTrackLine()
        live.setCommitted(listOf(gp(0.0, 0.0), gp(0.0, 2.0), gp(2.0, 3.0)))
        val pencil = PencilTrail()
        for (i in 1..6) {
            val a = Math.toRadians(30.0 * i)
            val p = gp(2.0 + sin(a) * 2.0, 3.0 + (1 - cos(a)) * 2.0)
            pencil.add(i * 500L, p.latitude, p.longitude, 1.0)
        }
        val tip = gp(4.5, 6.0)
        live.rebuildTail(pencil, tip)
        assertEquals(tip, live.points.last())
        val moved = gp(4.6, 6.1)
        assertTrue(live.moveTip(moved))
        assertEquals(moved, live.points.last())
        assertTrue("sub-5 cm move is not redrawn", !live.moveTip(gp(4.6, 6.1 + 0.01)))
        live.setCommitted(listOf(gp(9.0, 9.0)))
        assertTrue(!live.hasTail)
    }

    @Test
    fun `replaced track restarts the curve`() {
        val live = LiveTrackLine()
        live.setCommitted((0..5).map { gp(it * 2.0, (it % 2) * 1.0) })
        val other = (0..5).map { gp(-it * 2.0, (it % 2) * 1.0) }
        live.setCommitted(other)
        live.rebuildTail(PencilTrail(), other.last())
        assertEquals(curved(other).size + 1, live.points.size)
        assertNull(live.points.firstOrNull { f.east(it.longitude) > 0.01 })
    }
}
