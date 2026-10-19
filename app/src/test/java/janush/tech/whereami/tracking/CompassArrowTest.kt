package janush.tech.whereami.tracking

import android.hardware.Sensor
import android.hardware.SensorEventListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class CompassArrowTest {

    // ---- CompassMath ----

    /** Quaternion (w, x, y, z) product. */
    private fun mul(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
        a[0] * b[0] - a[1] * b[1] - a[2] * b[2] - a[3] * b[3],
        a[0] * b[1] + a[1] * b[0] + a[2] * b[3] - a[3] * b[2],
        a[0] * b[2] - a[1] * b[3] + a[2] * b[0] + a[3] * b[1],
        a[0] * b[3] + a[1] * b[2] - a[2] * b[1] + a[3] * b[0]
    )

    private fun rotZ(deg: Double) = doubleArrayOf(cos(Math.toRadians(deg) / 2), 0.0, 0.0, sin(Math.toRadians(deg) / 2))
    private fun rotX(deg: Double) = doubleArrayOf(cos(Math.toRadians(deg) / 2), sin(Math.toRadians(deg) / 2), 0.0, 0.0)

    private fun values(q: DoubleArray) = floatArrayOf(q[1].toFloat(), q[2].toFloat(), q[3].toFloat(), q[0].toFloat())

    /** Phone pitched by [pitchDeg] (0 = flat, screen up; 90 = upright, screen to the user) facing [azimuth]. */
    private fun attitude(azimuth: Double, pitchDeg: Double) = values(mul(rotZ(-azimuth), rotX(pitchDeg)))

    @Test
    fun `flat phone reports where its top edge points`() {
        for (az in listOf(0.0, 90.0, 200.0, 359.0)) {
            val h = CompassMath.facingTrueDeg(attitude(az, 0.0), 0.0)!!
            assertEquals(az, h, 0.05)
        }
    }

    @Test
    fun `upright phone reports where its back faces, and declination converts to true north`() {
        for (az in listOf(0.0, 45.0, 180.0, 300.0)) {
            assertEquals(az, CompassMath.facingTrueDeg(attitude(az, 90.0), 0.0)!!, 0.05)
        }
        assertEquals(15.0, CompassMath.facingTrueDeg(attitude(10.0, 90.0), 5.0)!!, 0.05)
        assertEquals(355.0, CompassMath.facingTrueDeg(attitude(358.0, 0.0), -3.0)!!, 0.05)
    }

    @Test
    fun `raising the phone from flat to upright does not move the heading`() {
        for (pitch in 0..90 step 5) {
            val h = CompassMath.facingTrueDeg(attitude(40.0, pitch.toDouble()), 0.0)!!
            assertEquals("pitch $pitch", 40.0, h, 0.2)
        }
    }

    @Test
    fun `degenerate quaternion gives no heading`() {
        assertNull(CompassMath.rotationMatrix(floatArrayOf(0f, 0f)))
    }

    // ---- CompassTracker ----

    @Test
    fun `tracker interpolates circularly across north`() {
        val t = CompassTracker()
        t.onSample(1_000, 350.0)
        t.onSample(1_100, 10.0)
        assertEquals(0.0, t.headingAt(1_050)!!.let { shortestDeltaDeg(0.0, it) }, 0.01)
        assertEquals(350.0, t.headingAt(1_000)!!, 0.01)
    }

    @Test
    fun `tracker holds the newest sample only briefly and knows nothing before the oldest`() {
        val t = CompassTracker()
        assertNull(t.headingAt(0))
        t.onSample(1_000, 90.0)
        t.onSample(1_040, 92.0)
        assertEquals(92.0, t.headingAt(1_300)!!, 0.01)
        assertNull(t.headingAt(1_600))
        assertNull(t.headingAt(900))
        t.onSample(1_020, 0.0) // out-of-order sample is ignored
        assertEquals(91.0, t.headingAt(1_020)!!, 0.01)
    }

    // ---- CompassArrow ----

    private class Walk(seed: Int, val compassNoise: Double, val courseNoise: Double, val offset: Double) {
        val rnd = Random(seed)
        fun gauss(): Double {
            val u1 = rnd.nextDouble().coerceAtLeast(1e-12); val u2 = rnd.nextDouble()
            return kotlin.math.sqrt(-2 * kotlin.math.ln(u1)) * cos(2 * Math.PI * u2)
        }
        /** Truth: walking a slow circle (24 deg/s, a tight loop like the field recording). */
        fun truth(tMs: Long) = wrapDeg360(tMs / 1000.0 * 24.0)
        fun compass(tMs: Long) = wrapDeg360(truth(tMs) - offset + compassNoise * gauss())
        fun course(tMs: Long) = wrapDeg360(truth(tMs) + courseNoise * gauss())
    }

    @Test
    fun `compass beats a noisy course once it agrees with it`() {
        val w = Walk(1, compassNoise = 5.0, courseNoise = 35.0, offset = 20.0)
        val arrow = CompassArrow()
        val errArrow = ArrayList<Double>()
        val errCourse = ArrayList<Double>()
        var t = 0L
        while (t < 90_000) {
            val c = w.course(t)
            val out = arrow.update(t, w.compass(t), c, moving = true)
            if (t > 15_000) {
                errCourse.add(abs(shortestDeltaDeg(w.truth(t), c)))
                if (out != null) errArrow.add(abs(shortestDeltaDeg(w.truth(t), out)))
            }
            t += 20
        }
        assertTrue("trusted most of the time: ${errArrow.size}/${errCourse.size}", errArrow.size > 0.95 * errCourse.size)
        fun p90(l: List<Double>) = l.sorted()[(l.size * 0.9).toInt()]
        assertTrue("arrow p90 ${p90(errArrow)}", p90(errArrow) < 15.0)
        assertTrue("course p90 ${p90(errCourse)}", p90(errCourse) > 40.0)
        assertEquals(20.0, arrow.offsetDeg!!, 8.0)
    }

    @Test
    fun `a disturbed compass is never trusted`() {
        val rnd = Random(9)
        val arrow = CompassArrow()
        var used = 0
        var n = 0
        var t = 0L
        while (t < 60_000) {
            val out = arrow.update(t, rnd.nextDouble() * 360.0, wrapDeg360(t / 1000.0 * 24.0), moving = true)
            if (t > 5_000) { n++; if (out != null) used++ }
            t += 20
        }
        assertTrue("used $used of $n", used < 0.05 * n)
    }

    @Test
    fun `never drives the arrow at rest or before learning`() {
        val w = Walk(2, 3.0, 20.0, 0.0)
        val arrow = CompassArrow()
        // Before the learning window the course is used.
        for (t in 0L until 2_500L step 20L) assertNull(arrow.update(t, w.compass(t), w.course(t), moving = true))
        for (t in 2_500L until 8_000L step 20L) arrow.update(t, w.compass(t), w.course(t), moving = true)
        assertTrue(arrow.isTrusted)
        // At rest: null (caller holds the last heading), whatever the compass does.
        for (t in 8_000L until 12_000L step 20L) assertNull(arrow.update(t, (t / 5.0) % 360, null, moving = false))
        assertTrue("learning survives a short stop", arrow.isTrusted)
    }

    @Test
    fun `no compass sample means no compass arrow and a long gap restarts learning`() {
        val w = Walk(3, 3.0, 20.0, 0.0)
        val arrow = CompassArrow()
        for (t in 0L until 8_000L step 20L) arrow.update(t, w.compass(t), w.course(t), moving = true)
        assertTrue(arrow.isTrusted)
        assertNull(arrow.update(8_020, null, w.course(8_020), moving = true))
        assertNull(arrow.update(20_000, w.compass(20_000), w.course(20_000), moving = true))
        assertFalse("restarted after a 12 s gap", arrow.isTrusted)
        arrow.reset()
        assertNull(arrow.offsetDeg)
    }

    @Test
    fun `a phone swung to a new angle drops trust and recovers at the new offset`() {
        val rnd = Random(4)
        val arrow = CompassArrow()
        var off = 10.0
        var firstDropMs = -1L
        var recovered = false
        var t = 0L
        while (t < 60_000) {
            if (t == 30_000L) off = 100.0
            val truth = wrapDeg360(t / 1000.0 * 20.0)
            val out = arrow.update(t, wrapDeg360(truth - off + rnd.nextDouble() * 6 - 3), truth, moving = true)
            if (t > 30_000L && out == null && firstDropMs < 0) firstDropMs = t - 30_000L
            if (t > 50_000 && out != null && abs(shortestDeltaDeg(truth, out)) < 10.0) recovered = true
            t += 20
        }
        assertTrue("trust dropped within 4 s of the swing: $firstDropMs", firstDropMs in 0..4_000)
        assertTrue(recovered)
    }

    // ---- CompassPolicy / sensor lifecycle ----

    @Test
    fun `policy needs setting, on-foot profile, foreground, motion and follow or record`() {
        assertTrue(CompassPolicy.shouldRun(true, true, true, true, true))
        assertFalse("setting off", CompassPolicy.shouldRun(false, true, true, true, true))
        assertFalse("vehicle profile", CompassPolicy.shouldRun(true, false, true, true, true))
        assertFalse("screen off", CompassPolicy.shouldRun(true, true, false, true, true))
        assertFalse("standing", CompassPolicy.shouldRun(true, true, true, false, true))
        assertFalse("browsing", CompassPolicy.shouldRun(true, true, true, true, false))
        assertFalse(CompassPolicy.isMovingForCompass(0.4f, wasMoving = false))
        assertTrue(CompassPolicy.isMovingForCompass(0.7f, wasMoving = false))
        assertTrue(CompassPolicy.isMovingForCompass(0.4f, wasMoving = true))
        assertFalse(CompassPolicy.isMovingForCompass(0.2f, wasMoving = true))
    }

    private class FakeRegistrar(val supported: Boolean = true) : SensorRegistrar {
        val registered = mutableListOf<Int>()
        var unregisterCalls = 0
        var periodUs = 0
        var latencyUs = 0
        override fun register(sensorType: Int, samplingPeriodUs: Int, maxReportLatencyUs: Int, listener: SensorEventListener): Boolean {
            if (!supported) return false
            registered += sensorType; periodUs = samplingPeriodUs; latencyUs = maxReportLatencyUs
            return true
        }
        override fun unregister(listener: SensorEventListener) { unregisterCalls++; registered.clear() }
    }

    @Test
    fun `sensor registers the rotation vector once, batches, and unregisters without leaking`() {
        val reg = FakeRegistrar()
        val s = CompassHeadingSensor(reg, CompassTracker())
        s.stop()
        assertEquals("stop before start is a no-op", 0, reg.unregisterCalls)
        s.start(); s.start()
        assertTrue(s.isRunning)
        assertEquals(listOf(Sensor.TYPE_ROTATION_VECTOR), reg.registered)
        assertEquals(CompassHeadingSensor.PERIOD_US, reg.periodUs)
        assertEquals(CompassHeadingSensor.MAX_LATENCY_US, reg.latencyUs)
        s.stop(); s.stop()
        assertFalse(s.isRunning)
        assertEquals(1, reg.unregisterCalls)
        assertTrue(reg.registered.isEmpty())
    }

    @Test
    fun `device without a rotation vector sensor stays off`() {
        val s = CompassHeadingSensor(FakeRegistrar(supported = false), CompassTracker())
        s.start()
        assertFalse(s.isRunning)
    }

    @Test
    fun `diagnostics line carries the compass heading`() {
        val fix = RawFix(1_000, 2_000, 49.0, 19.0, 5f, 1f, null, null, null, null)
        val with = TrackDiagnosticsRecorder.formatFixLine(fix, FixDecision.ACCEPTED, null, null, 123.46)
        assertTrue(with, with.contains("\"cp\":123.5"))
        val without = TrackDiagnosticsRecorder.formatFixLine(fix, FixDecision.ACCEPTED, null, null)
        assertFalse(without.contains("\"cp\""))
        assertNotNull(TrackReplay.parse(sequenceOf(with)).single())
    }
}
