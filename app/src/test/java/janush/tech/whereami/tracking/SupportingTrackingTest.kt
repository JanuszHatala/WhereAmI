package janush.tech.whereami.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SupportingTrackingTest {

    // ── GyroYawTracker ──────────────────────────────────────────────────────

    @Test
    fun `gyro integrates constant rate`() {
        val g = GyroYawTracker()
        var t = 0L
        while (t <= 2000) { g.onSample(t, 0.5); t += 20 }
        assertEquals(0.5, g.integrate(0, 1000)!!, 0.01)
        assertEquals(0.5, g.meanRate(500, 1500)!!, 0.01)
    }

    @Test
    fun `gyro returns null without coverage`() {
        val g = GyroYawTracker()
        assertNull(g.integrate(0, 1000))
        g.onSample(100, 1.0); g.onSample(120, 1.0)
        assertNull(g.integrate(0, 5000))
    }

    @Test
    fun `heading rate sign convention - turning left gives negative heading rate`() {
        // Phone lying flat face-up: gravity (up) = +z. Counter-clockwise rotation seen from above = +omega_z.
        val up = floatArrayOf(0f, 0f, 9.81f)
        val ccw = GyroYawTracker.headingRateFrom(floatArrayOf(0f, 0f, 0.5f), up)!!
        assertEquals(-0.5, ccw, 1e-6)
        // Phone mounted upright in portrait (gravity up = +y): rotation about device y axis.
        val upright = floatArrayOf(0f, 9.81f, 0f)
        assertEquals(0.3, GyroYawTracker.headingRateFrom(floatArrayOf(0f, -0.3f, 0f), upright)!!, 1e-6)
    }

    @Test
    fun `heading rate rejects free fall`() {
        assertNull(GyroYawTracker.headingRateFrom(floatArrayOf(1f, 1f, 1f), floatArrayOf(0.1f, 0.1f, 0.1f)))
    }

    // ── RotationSlew ────────────────────────────────────────────────────────

    @Test
    fun `slew converges without overshoot`() {
        val s = RotationSlew(naturalFreqRadS = 7.0, maxRateDegS = 160.0)
        s.snap(0.0)
        var maxAngle = 0.0
        repeat(300) {
            val a = s.step(60.0, 0.016)
            maxAngle = maxOf(maxAngle, a)
        }
        assertEquals(60.0, s.angleDeg, 0.5)
        assertTrue("overshoot=$maxAngle", maxAngle < 60.5)
    }

    @Test
    fun `slew respects rate limit and takes shortest arc across north`() {
        val s = RotationSlew(naturalFreqRadS = 20.0, maxRateDegS = 100.0)
        s.snap(350.0)
        var maxRate = 0.0
        var prev = s.angleDeg
        repeat(60) {
            val a = s.step(10.0, 0.016)
            maxRate = maxOf(maxRate, abs(shortestDeltaDeg(prev, a)) / 0.016)
            prev = a
        }
        assertTrue("rate=$maxRate", maxRate <= 101.0)
        assertEquals(10.0, s.angleDeg, 1.0)
    }

    @Test
    fun `slew rate is continuous on target step`() {
        val s = RotationSlew(naturalFreqRadS = 7.0, maxRateDegS = 500.0)
        s.snap(0.0)
        var prevRate = 0.0
        var worstJump = 0.0
        repeat(120) { i ->
            s.step(if (i < 10) 0.0 else 90.0, 0.016)
            worstJump = maxOf(worstJump, abs(s.rateDegS - prevRate))
            prevRate = s.rateDegS
        }
        assertTrue("rate jump per frame=$worstJump", worstJump < 40.0)
    }

    // ── Diagnostics format round trip ───────────────────────────────────────

    @Test
    fun `diagnostics line round trips through replay parser`() {
        val fix = RawFix(123456, 1_700_000_000_000, 49.8512345, 19.2812345, 4.5f, 12.3f, 0.3f, 91.5f, 6f, 301.2)
        val st = TrackState(123456, 1_700_000_000_000, 49.85, 19.28, null, 12.0, 90.0, 1.5, 2.0, 6.0, true, false, 0, false, 4.5f)
        val line = TrackDiagnosticsRecorder.formatFixLine(fix, FixDecision.ACCEPTED, st, 0.02)
        val parsed = TrackReplay.parse(sequenceOf(line, "{\"k\":\"note\",\"te\":1,\"m\":\"x\"}"))
        assertEquals(1, parsed.size)
        val p = parsed[0]
        assertEquals(fix.tElapsedMs, p.fix.tElapsedMs)
        assertEquals(fix.lat, p.fix.lat, 1e-7)
        assertEquals(4.5f, p.fix.accuracyM!!, 0.01f)
        assertEquals(91.5f, p.fix.bearingDeg!!, 0.01f)
        assertEquals(301.2, p.fix.altitudeM!!, 0.05)
        assertEquals(0.02, p.gyroYawRateRadS!!, 1e-6)
    }

    @Test
    fun `recorder writes and prunes old session files`() {
        val dir = java.nio.file.Files.createTempDirectory("diag").toFile()
        val rec = TrackDiagnosticsRecorder(dir)
        for (i in 0 until 13) {
            rec.startSession("trip$i", 1_700_000_000_000L + i)
            rec.onFix(
                RawFix(1, 1, 49.0, 19.0, 3f, null, null, null, null, null),
                FixDecision.ACCEPTED, null
            )
        }
        rec.close()
        val files = dir.listFiles()!!.filter { it.name.endsWith(".jsonl") }
        assertEquals(TrackDiagnosticsRecorder.MAX_FILES, files.size)
        assertNotNull(files.firstOrNull { it.readText().contains("\"k\":\"fix\"") })
        dir.deleteRecursively()
    }

    @Test
    fun `replay of recorded trace reproduces engine output`() {
        val sim = TrackSim(seed = 77)
        sim.straight(30.0, 12.0)
        sim.turn(90.0, 4.0, 8.0)
        sim.straight(10.0, 12.0)
        val fixes = sim.fixes(1.0)
        val lines = fixes.map { TrackDiagnosticsRecorder.formatFixLine(it, FixDecision.ACCEPTED, null, null) }
        val replayed = TrackReplay.parse(lines.asSequence()).map { it.fix }
        val a = TrackingEngine(TrackingProfile.CAR)
        val b = TrackingEngine(TrackingProfile.CAR)
        val sa = fixes.mapNotNull { a.ingest(it).state }
        val sb = replayed.mapNotNull { b.ingest(it).state }
        assertEquals(sa.size, sb.size)
        // Serialisation rounds to 7 decimals (~1 cm): outputs must agree to well under a metre.
        for (i in sa.indices) assertTrue(abs(sa[i].lat - sb[i].lat) < 1e-5 && abs(sa[i].lng - sb[i].lng) < 1e-5)
    }
}
