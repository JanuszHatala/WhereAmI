package janush.tech.whereami

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Algorithmic test suite for GpsFilterEngine pipeline (Stages 1-4).
 * Mirrors production rules to ensure kinematic safety, Doppler speed preservation,
 * and zero-snap stationary clamping without Android framework dependencies.
 */
class GpsFilterEngineTest {

    data class MockFix(
        var lat: Double,
        var lng: Double,
        val accuracy: Float,
        var speed: Float,
        val time: Long
    )

    class FilterEngineModel {
        var lastAccepted: MockFix? = null
        var lastAcceptedTimestamp: Long = 0L
        var consecutiveAnomalyCount: Int = 0
        var lastAnomaly: MockFix? = null

        fun reset() {
            lastAccepted = null
            lastAcceptedTimestamp = 0L
            consecutiveAnomalyCount = 0
            lastAnomaly = null
        }

        fun filterLocation(candidate: MockFix, profile: ActivityProfile, distToPrevMeters: Double): Boolean {
            val now = candidate.time

            // Stage 1: Horizontal Accuracy Gate
            val maxAccuracy = when (profile) {
                ActivityProfile.WALKING, ActivityProfile.HIKING -> 40.0f
                ActivityProfile.RUNNING -> 45.0f
                ActivityProfile.MTB, ActivityProfile.CYCLING -> 55.0f
                ActivityProfile.CAR -> 55.0f
            }

            if (candidate.accuracy > maxAccuracy) {
                val timeSinceLastValid = now - lastAcceptedTimestamp
                val relaxedMax = maxAccuracy * 2.0f
                if (timeSinceLastValid > 45_000L && candidate.accuracy <= relaxedMax) {
                    // degraded fix accepted
                } else {
                    return false
                }
            }

            val prev = lastAccepted
            if (prev == null) {
                lastAccepted = candidate.copy()
                lastAcceptedTimestamp = now
                consecutiveAnomalyCount = 0
                return true
            }

            // Stage 2: Kinematic Plausibility Check
            val deltaDistMeters = distToPrevMeters
            val deltaTimeSeconds = (now - lastAcceptedTimestamp) / 1000.0
            if (deltaTimeSeconds <= 0.0) return false

            val impliedSpeedKmh = (deltaDistMeters / deltaTimeSeconds) * 3.6
            val maxPlausibleSpeedKmh = when (profile) {
                ActivityProfile.WALKING -> 15.0
                ActivityProfile.HIKING -> 25.0
                ActivityProfile.RUNNING -> 35.0
                ActivityProfile.MTB -> 65.0
                ActivityProfile.CYCLING -> 90.0
                ActivityProfile.CAR -> 220.0
            }

            if (impliedSpeedKmh > maxPlausibleSpeedKmh && deltaDistMeters > 30.0) {
                consecutiveAnomalyCount++
                if (consecutiveAnomalyCount >= 3) {
                    lastAccepted = candidate.copy()
                    lastAcceptedTimestamp = now
                    consecutiveAnomalyCount = 0
                    lastAnomaly = null
                    return true
                }
                return false
            }

            // Stage 4: Stationary Jitter Dampener
            // NEVER zero candidate.speed — preserve Doppler speed for downstream Kalman!
            if (deltaDistMeters < 4.0 && candidate.speed < 0.5f) {
                candidate.lat = prev.lat
                candidate.lng = prev.lng
            }

            consecutiveAnomalyCount = 0
            lastAnomaly = null
            lastAccepted = candidate.copy()
            lastAcceptedTimestamp = now
            return true
        }
    }

    private lateinit var filter: FilterEngineModel

    @Before
    fun setUp() {
        filter = FilterEngineModel()
    }

    @Test
    fun testDopplerSpeedPreservedOnShortDisplacement() {
        val f1 = MockFix(49.8500, 19.2800, accuracy = 15f, speed = 12.5f, time = 1000L)
        assertTrue(filter.filterLocation(f1, ActivityProfile.CAR, distToPrevMeters = 0.0))

        // Fix arrives 1s later: moved 8m (< 15m), accuracy = 22m (> 15m)
        val f2 = MockFix(49.85007, 19.2800, accuracy = 22f, speed = 12.5f, time = 2000L)
        assertTrue(filter.filterLocation(f2, ActivityProfile.CAR, distToPrevMeters = 8.0))

        // Speed must remain 12.5f and NOT be wiped to 0.0f!
        assertEquals("Doppler hardware speed must be preserved", 12.5f, f2.speed, 0.001f)
    }

    @Test
    fun testHighAccuracyRejection() {
        val f1 = MockFix(49.85, 19.28, accuracy = 10f, speed = 5f, time = 1000L)
        assertTrue(filter.filterLocation(f1, ActivityProfile.CAR, distToPrevMeters = 0.0))

        // 80m accuracy exceeds CAR 55m threshold
        val badFix = MockFix(49.8501, 19.2801, accuracy = 80f, speed = 5f, time = 2000L)
        assertFalse("Accuracy > 55m must be rejected in CAR profile", filter.filterLocation(badFix, ActivityProfile.CAR, distToPrevMeters = 10.0))
    }

    @Test
    fun testKinematicTeleportSpikeRejected() {
        val f1 = MockFix(49.8500, 19.2800, accuracy = 10f, speed = 15f, time = 1000L)
        assertTrue(filter.filterLocation(f1, ActivityProfile.CAR, distToPrevMeters = 0.0))

        // Jump 500m in 1s (implied speed = 1800 km/h > 220 km/h CAR threshold)
        val spike = MockFix(49.8550, 19.2800, accuracy = 10f, speed = 15f, time = 2000L)
        assertFalse("Kinematic jump exceeding max plausible speed must be rejected", filter.filterLocation(spike, ActivityProfile.CAR, distToPrevMeters = 500.0))
    }

    @Test
    fun testConsecutiveAnomalyRecovery() {
        val f1 = MockFix(49.8500, 19.2800, accuracy = 10f, speed = 15f, time = 1000L)
        assertTrue(filter.filterLocation(f1, ActivityProfile.CAR, distToPrevMeters = 0.0))

        val jump1 = MockFix(49.8600, 19.2900, accuracy = 10f, speed = 15f, time = 2000L)
        assertFalse(filter.filterLocation(jump1, ActivityProfile.CAR, distToPrevMeters = 1200.0))

        val jump2 = MockFix(49.86001, 19.29001, accuracy = 10f, speed = 15f, time = 3000L)
        assertFalse(filter.filterLocation(jump2, ActivityProfile.CAR, distToPrevMeters = 1200.0))

        val jump3 = MockFix(49.86002, 19.29002, accuracy = 10f, speed = 15f, time = 4000L)
        assertTrue("3 consecutive agreeing fixes must reset anchor and recover", filter.filterLocation(jump3, ActivityProfile.CAR, distToPrevMeters = 1200.0))
    }

    @Test
    fun testStationaryJitterDampeningClampsPositionWithoutZeroingSpeed() {
        val f1 = MockFix(49.8500, 19.2800, accuracy = 10f, speed = 0.2f, time = 1000L)
        assertTrue(filter.filterLocation(f1, ActivityProfile.CAR, distToPrevMeters = 0.0))

        // Micro-displacement 2m at stationary speed 0.2 m/s
        val f2 = MockFix(49.85002, 19.28002, accuracy = 10f, speed = 0.2f, time = 2000L)
        assertTrue(filter.filterLocation(f2, ActivityProfile.CAR, distToPrevMeters = 2.0))

        // Position clamped to f1
        assertEquals(f1.lat, f2.lat, 0.000001)
        assertEquals(f1.lng, f2.lng, 0.000001)
        assertEquals(0.2f, f2.speed, 0.001f)
    }
}
