package online.hatala.whereami

import online.hatala.whereami.tracking.AdaptiveTrackSampler
import online.hatala.whereami.tracking.LocalFrame
import online.hatala.whereami.tracking.PencilTrail
import online.hatala.whereami.tracking.SmoothedPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Trip314FeedbackTest {

    @Test
    fun `computeTripRecordingSampling keeps 1Hz when paused and carActive while moving`() {
        val (interval, minInterval, maxDelay) = AppStateManager.computeTripRecordingSampling(
            profile = ActivityProfile.CAR,
            policy = BatteryPowerPolicy.SMART_AUTO,
            isAppInForeground = false,
            carActive = true,
            isPaused = true,
            isPhysicallyStationary = false
        )
        assertEquals(1000L, interval)
        assertEquals(1000L, minInterval)
        assertEquals(0L, maxDelay)
    }

    @Test
    fun `computeTripRecordingSampling relaxes to 15s when paused and carActive while stationary`() {
        val (interval, minInterval, maxDelay) = AppStateManager.computeTripRecordingSampling(
            profile = ActivityProfile.CAR,
            policy = BatteryPowerPolicy.SMART_AUTO,
            isAppInForeground = false,
            carActive = true,
            isPaused = true,
            isPhysicallyStationary = true
        )
        assertEquals(15000L, interval)
        assertEquals(5000L, minInterval)
        assertEquals(0L, maxDelay)
    }

    @Test
    fun `computeTripRecordingSampling shuts down GPS when paused with screen off and not carActive`() {
        val (interval, minInterval, maxDelay) = AppStateManager.computeTripRecordingSampling(
            profile = ActivityProfile.CAR,
            policy = BatteryPowerPolicy.SMART_AUTO,
            isAppInForeground = false,
            carActive = false,
            isPaused = true,
            isPhysicallyStationary = false
        )
        assertEquals(0L, interval)
        assertEquals(0L, minInterval)
        assertEquals(0L, maxDelay)
    }

    @Test
    fun `PencilTrail collapses acute short-leg Z-kink in place`() {
        val frame = LocalFrame(49.8134, 19.0545)
        val trail = PencilTrail(maxPoints = 20)
        // Point A: (0, 0)
        val aLat = frame.lat(0.0); val aLng = frame.lng(0.0)
        assertTrue(trail.add(1000L, aLat, aLng, speedMps = 1.0, vehicle = true))

        // Point B: (+4m North)
        val bLat = frame.lat(4.0); val bLng = frame.lng(0.0)
        assertTrue(trail.add(2000L, bLat, bLng, speedMps = 1.0, vehicle = true))

        // Point C: doubles back (+0.5m North, +0.5m East of A, forming acute turn)
        val cLat = frame.lat(0.5); val cLng = frame.lng(0.5)
        assertTrue(trail.add(3000L, cLat, cLng, speedMps = 1.0, vehicle = true))

        // Point B must have been collapsed in place: size is 2, and the second point is C
        assertEquals(2, trail.size)
        assertEquals(cLat, trail.latAt(1), 1e-7)
        assertEquals(cLng, trail.lngAt(1), 1e-7)
    }

    @Test
    fun `AdaptiveTrackSampler does not buffer sub-crawl parking jitter into window`() {
        val sampler = AdaptiveTrackSampler()
        val anchor = SmoothedPoint(
            tElapsedMs = 1000L,
            tUtcMs = 1000L,
            lat = 49.8134,
            lng = 19.0545,
            altitudeM = 300.0,
            speedMps = 0.0,
            headingDeg = 90.0,
            accuracyM = 5f,
            isStationary = true
        )
        val firstCommit = sampler.offer(anchor)
        assertEquals(1, firstCommit.size)

        // Sub-crawl fix (< 1.0 m/s) within parking escape nest (< 6.0m)
        val jitterPoint = SmoothedPoint(
            tElapsedMs = 2000L,
            tUtcMs = 2000L,
            lat = 49.813401,
            lng = 19.054501,
            altitudeM = 300.0,
            speedMps = 0.2,
            headingDeg = 90.0,
            accuracyM = 8f,
            isStationary = false
        )
        val emitted = sampler.offer(jitterPoint)
        assertTrue(emitted.isEmpty())
        assertTrue(sampler.window.isEmpty())
    }
}
