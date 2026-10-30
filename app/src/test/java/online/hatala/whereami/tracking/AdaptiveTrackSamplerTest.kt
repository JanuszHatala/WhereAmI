package online.hatala.whereami.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveTrackSamplerTest {

    private fun pt(tMs: Long, lat: Double, lng: Double, speed: Double): SmoothedPoint {
        return SmoothedPoint(
            tElapsedMs = tMs,
            tUtcMs = 1_700_000_000_000L + tMs,
            lat = lat,
            lng = lng,
            altitudeM = 250.0,
            speedMps = speed,
            headingDeg = 90.0,
            accuracyM = 3.0f,
            isStationary = false
        )
    }

    @Test
    fun `findMaxDeviationPoint correctly identifies the apex point`() {
        val speed = 12.0
        val pAnchor = pt(0L, 50.000000, 19.000000, speed)
        val p1 = pt(500L, 50.000010, 19.000100, speed)
        val p2Apex = pt(1000L, 50.000200, 19.000200, speed) // ~22m off chord
        val p3 = pt(1500L, 50.000020, 19.000300, speed)
        val pEnd = pt(2000L, 50.000000, 19.000400, speed)

        val window = listOf(p1, p2Apex, p3)
        val result = AdaptiveTrackSampler.findMaxDeviationPoint(pAnchor, pEnd, window)

        assertNotNull(result)
        assertEquals("Must identify p2Apex as max deviation point", p2Apex.lat, result!!.first.lat, 1e-6)
        assertEquals("Must identify p2Apex as max deviation point", p2Apex.lng, result.first.lng, 1e-6)
        assertTrue("Deviation must be > 20 meters", result.second > 20.0)
    }

    @Test
    fun `bend commits apex when deviation exceeds tolerance`() {
        val sampler = AdaptiveTrackSampler()
        val speed = 12.0 // ~43 km/h, minSpacing = 6m, maxSpacing = 24m, tol = 0.97m

        // Start anchor at (50.000000, 19.000000)
        val p0 = pt(0L, 50.000000, 19.000000, speed)
        assertTrue(sampler.offer(p0).isNotEmpty())

        // Point 1: apex point at distance 4m (< minSpacing 6m), but lateral displacement 3m north
        val p1Apex = pt(300L, 50.000027, 19.000040, speed)
        val c1 = sampler.offer(p1Apex)
        assertTrue("p1Apex must be buffered in window (< minSpacing)", c1.isEmpty())

        // Point 2: distance 10m from anchor (> minSpacing 6m), back on east baseline
        // Chord p0 -> p2 is along the baseline; p1Apex is in the window with ~3m lateral deviation (> tol 0.97m)
        val p2 = pt(800L, 50.000000, 19.000140, speed)
        val c2 = sampler.offer(p2)

        assertTrue("Bend should trigger vertex commit", c2.isNotEmpty())
        assertEquals("Committed vertex must be the apex p1Apex", p1Apex.lat, c2[0].lat, 1e-6)
        assertEquals("Committed vertex must be the apex p1Apex", p1Apex.lng, c2[0].lng, 1e-6)
    }

    @Test
    fun `sub-metre jitter apex candidate is buffered rather than committed as micro-vertex`() {
        val sampler = AdaptiveTrackSampler()
        val speed = 2.0

        val p0 = pt(0L, 50.000000, 19.000000, speed)
        assertTrue(sampler.offer(p0).isNotEmpty())

        // Point 1: 0.5m from anchor (< 0.8m)
        val p1Jitter = pt(500L, 50.000003, 19.000004, speed)
        assertTrue("Sub-metre point must be buffered", sampler.offer(p1Jitter).isEmpty())

        // Point 2: 3m from anchor (< maxSpacingM 4m)
        val p2 = pt(1200L, 50.000000, 19.000040, speed)
        val c2 = sampler.offer(p2)
        assertTrue("Sub-metre jitter must not trigger vertex commit", c2.isEmpty())
        // And even when chord reaches maxSpacing, p1Jitter must never be emitted as an apex
        val p3 = pt(2000L, 50.000000, 19.000070, speed)
        val c3 = sampler.offer(p3)
        assertTrue("Only p3 may be emitted on maxSpacing", c3.none { it.lat == p1Jitter.lat })
    }
}
