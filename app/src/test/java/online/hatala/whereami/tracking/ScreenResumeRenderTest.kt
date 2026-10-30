package online.hatala.whereami.tracking

import online.hatala.whereami.BatchedRequestSequencer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round 85 (trip 234): after screen-on the live line chorded from the last vertex to the cursor and the
 * cursor overshot into side roads; braking to a stop drew the cursor past the stop and back.
 */
class ScreenResumeRenderTest {

    private val mLat = LocalFrame.METERS_PER_DEG_LAT

    private fun st(
        t: Long, northM: Double, speed: Double, accel: Double = 0.0,
        stationary: Boolean = false, reset: Boolean = false
    ) = TrackState(
        tElapsedMs = t, tUtcMs = t, lat = 49.85 + northM / mLat, lng = 19.28, altitudeM = null,
        speedMps = speed, headingDeg = 0.0, yawRateDegPerS = 0.0, posStdM = 1.5, headingStdDeg = 4.0,
        headingConfident = true, isStationary = stationary, rejectStreak = 0, isReset = reset,
        accuracyM = 5f, accelMps2 = accel
    )

    private fun north(p: RenderPipeline.Pose) = (p.lat - 49.85) * mLat

    @Test
    fun `braking car is not extrapolated past its stop`() {
        val pipe = RenderPipeline(TrackingProfile.CAR)
        // 1 m/s and braking at 1 m/s^2 (Singer tau 2 s => stops in ~1.4 s, ~0.7 m further).
        pipe.onState(st(0, 0.0, 1.0, accel = -1.0))
        var t = 0L
        var maxN = 0.0
        while (t <= 6_000L) { maxN = maxOf(maxN, north(pipe.frame(t)!!)); t += 16 }
        assertTrue("overshoot=$maxN", maxN < 0.9)
    }

    @Test
    fun `constant speed prediction is unchanged`() {
        val pipe = RenderPipeline(TrackingProfile.CAR)
        pipe.onState(st(0, 0.0, 10.0))
        val p = pipe.frame(1_500)!!
        // Vehicle latency 1 s: render time 0.5 s => 5 m.
        assertEquals(10.0 * (p.renderTimeMs / 1000.0), north(p), 0.2)
    }

    @Test
    fun `vehicle keeps its render latency through a data gap`() {
        val pipe = RenderPipeline(TrackingProfile.CAR)
        pipe.onState(st(0, 0.0, 10.0))
        var t = 0L
        var last: RenderPipeline.Pose? = null
        while (t <= 30_000L) { last = pipe.frame(t); t += 16 }
        assertEquals(RenderPipeline.VEHICLE_LATENCY_MS, last!!.latencyMs, 1.0)
    }

    @Test
    fun `path between states follows history and never extrapolates`() {
        val pipe = RenderPipeline(TrackingProfile.CAR)
        for (i in 0..10) pipe.onState(st(i * 1_000L, i * 10.0, 10.0))
        val path = pipe.pathBetween(2_000, 20_000, 500)
        assertTrue(path.isNotEmpty())
        assertEquals(2_000L, path.first().tMs)
        assertTrue(path.last().tMs < 10_000L)
        for (s in path) assertEquals(s.tMs / 100.0, (s.lat - 49.85) * mLat, 0.3)
        assertTrue(pipe.pathBetween(12_000, 20_000).isEmpty())
    }

    @Test
    fun `state log returns every state after the cursor in order and resets on filter reset`() {
        val log = TrackStateLog(capacity = 5)
        for (i in 1..7) log.add(st(i * 1_000L, 0.0, 1.0))
        log.add(st(6_500L, 0.0, 1.0)) // out of order: ignored
        assertEquals(listOf(3_000L, 4_000L, 5_000L, 6_000L, 7_000L), log.since(Long.MIN_VALUE).map { it.tElapsedMs })
        assertEquals(listOf(6_000L, 7_000L), log.since(5_000L).map { it.tElapsedMs })
        assertTrue(log.since(7_000L).isEmpty())
        log.add(st(8_000L, 0.0, 1.0, reset = true))
        assertEquals(listOf(8_000L), log.since(Long.MIN_VALUE).map { it.tElapsedMs })
    }

    @Test
    fun `pencil drops dead reckoned points after a batch arrives`() {
        val pencil = PencilTrail()
        for (i in 0..9) pencil.add(i * 1_000L, 49.85 + i * 10.0 / mLat, 19.28, 10.0)
        pencil.dropAfter(5_000L)
        assertEquals(6, pencil.size)
        assertEquals(5_000L, pencil.timeAt(pencil.size - 1))
        pencil.dropAfter(9_000L)
        assertEquals(6, pencil.size)
    }

    @Test
    fun `request change waits for the batch flush and only the latest change is applied`() {
        val seq = BatchedRequestSequencer()
        val applied = ArrayList<String>()
        seq.run(false, { error("no flush when not batched") }) { applied.add("direct") }
        assertEquals(listOf("direct"), applied)

        val pending = ArrayList<() -> Unit>()
        seq.run(true, { pending.add(it) }) { applied.add("first") }
        assertEquals(listOf("direct"), applied)
        seq.run(true, { pending.add(it) }) { applied.add("second") }
        pending.forEach { it() }
        assertEquals(listOf("direct", "second"), applied)

        // A failed or synchronous flush still applies.
        seq.run(true, { it() }) { applied.add("sync") }
        assertEquals("sync", applied.last())
        assertFalse(applied.contains("first"))
    }
}
