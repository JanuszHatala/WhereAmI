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
        val time: Long,
        var bearing: Float? = null
    )

    class FilterEngineModel {
        var lastAccepted: MockFix? = null
        var lastAcceptedTimestamp: Long = 0L
        var consecutiveAnomalyCount: Int = 0
        var lastAnomaly: MockFix? = null
        var lastMovementBearing: Float? = null

        fun reset() {
            lastAccepted = null
            lastAcceptedTimestamp = 0L
            consecutiveAnomalyCount = 0
            lastAnomaly = null
            lastMovementBearing = null
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
                if (candidate.bearing != null) lastMovementBearing = candidate.bearing
                return true
            }

            // Stage 2: Kinematic Plausibility Check (Speed Jump Rejection)
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

            val isSpeedAnomaly = impliedSpeedKmh > maxPlausibleSpeedKmh && (deltaDistMeters > 10.0 || deltaTimeSeconds < 0.8)

            // Stage 3: Directional Projection Gate (Anti-Backward-Jump Filter)
            var isBackwardJump = false
            val activeBearing = lastMovementBearing ?: prev.bearing
            val isMovingForward = candidate.speed >= 1.2f || prev.speed >= 1.2f

            if (isMovingForward && activeBearing != null && deltaDistMeters >= 8.0) {
                val radBearing = Math.toRadians(activeBearing.toDouble())
                val vx = kotlin.math.sin(radBearing)
                val vy = kotlin.math.cos(radBearing)

                val dy = (candidate.lat - prev.lat) * 111320.0
                val cosLat = kotlin.math.cos(Math.toRadians(prev.lat)).coerceAtLeast(0.01)
                val dx = (candidate.lng - prev.lng) * 111320.0 * cosLat
                val dispMag = kotlin.math.hypot(dx, dy)

                if (dispMag > 0.1) {
                    val cosTheta = (dx * vx + dy * vy) / dispMag
                    if (cosTheta < -0.35) {
                        isBackwardJump = true
                    }
                }
            }

            if (isSpeedAnomaly || isBackwardJump) {
                consecutiveAnomalyCount++
                if (consecutiveAnomalyCount >= 3) {
                    lastAccepted = candidate.copy()
                    lastAcceptedTimestamp = now
                    consecutiveAnomalyCount = 0
                    lastAnomaly = null
                    if (candidate.bearing != null) lastMovementBearing = candidate.bearing
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
            if (candidate.speed >= 1.2f && candidate.bearing != null) {
                lastMovementBearing = candidate.bearing
            }
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

    @Test
    fun testBackwardSpikeRejectedWhenMovingForward() {
        // Fix 1 moving North (bearing = 0.0°) at 5.0 m/s (~18 km/h)
        val f1 = MockFix(49.8500, 19.2800, accuracy = 8f, speed = 5.0f, time = 1000L, bearing = 0.0f)
        assertTrue(filter.filterLocation(f1, ActivityProfile.MTB, distToPrevMeters = 0.0))

        // Fix 2: 1 second later, GPS jumps South by 15 meters (lat decreases to 49.849865)
        // Implied angle is 180° opposite to heading (0° North). cosTheta ~ -1.0 < -0.35.
        val spikeBackward = MockFix(49.849865, 19.2800, accuracy = 12f, speed = 4.5f, time = 2000L, bearing = 0.0f)
        assertFalse(
            "Backward jump of 15m directly opposing forward heading must be rejected",
            filter.filterLocation(spikeBackward, ActivityProfile.MTB, distToPrevMeters = 15.0)
        )
    }

    @Test
    fun testLegitimateUTurnAcceptedAfterThreeFixes() {
        // Fix 1 moving North at 5.0 m/s
        val f1 = MockFix(49.8500, 19.2800, accuracy = 8f, speed = 5.0f, time = 1000L, bearing = 0.0f)
        assertTrue(filter.filterLocation(f1, ActivityProfile.MTB, distToPrevMeters = 0.0))

        // Anomaly 1: Rider actually turned around and is moving South
        val uTurn1 = MockFix(49.849865, 19.2800, accuracy = 10f, speed = 4.0f, time = 2000L, bearing = 180.0f)
        assertFalse("First fix opposite heading is rejected as candidate spike", filter.filterLocation(uTurn1, ActivityProfile.MTB, distToPrevMeters = 15.0))

        // Anomaly 2: Continues South
        val uTurn2 = MockFix(49.849800, 19.2800, accuracy = 10f, speed = 4.0f, time = 3000L, bearing = 180.0f)
        assertFalse("Second fix opposite heading is rejected", filter.filterLocation(uTurn2, ActivityProfile.MTB, distToPrevMeters = 22.0))

        // Anomaly 3: Confirms rider intentionally reversed direction
        val uTurn3 = MockFix(49.849740, 19.2800, accuracy = 10f, speed = 4.0f, time = 4000L, bearing = 180.0f)
        assertTrue("Third agreeing fix in new direction must reset anchor and accept", filter.filterLocation(uTurn3, ActivityProfile.MTB, distToPrevMeters = 29.0))
    }
}
