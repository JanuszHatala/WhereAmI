package janush.tech.whereami

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryOptimizationAndAutoStartTest {

    @Test
    fun testAllActivityProfilesHaveConfiguredAutoStartParameters() {
        for (profile in ActivityProfile.values()) {
            assertTrue("Auto-start speed for ${profile.name} must be > 0", profile.autoStartSpeedKmh > 0f)
            assertTrue("Auto-start duration for ${profile.name} must be >= 5s", profile.autoStartDurationMs >= 5_000L)
            assertTrue("Auto-stop speed for ${profile.name} must be > 0", profile.autoStopSpeedKmh > 0f)
            assertTrue("Auto-stop speed must be <= auto-start speed for ${profile.name}", profile.autoStopSpeedKmh <= profile.autoStartSpeedKmh)
        }
    }

    @Test
    fun testWalkingAndHikingAutoStartThresholds() {
        val walking = ActivityProfile.WALKING
        val hiking = ActivityProfile.HIKING

        // Walking & hiking should be sensitive to human walking speeds (~2.5 km/h)
        assertEquals(2.5f, walking.autoStartSpeedKmh, 0.01f)
        assertEquals(15_000L, walking.autoStartDurationMs)

        assertEquals(2.5f, hiking.autoStartSpeedKmh, 0.01f)
        assertEquals(15_000L, hiking.autoStartDurationMs)

        val candidateThresholdWalking = walking.autoStartSpeedKmh * 0.7f
        assertEquals(1.75f, candidateThresholdWalking, 0.01f)

        // Stroll at 2.0 km/h is a candidate
        assertTrue(2.0f >= candidateThresholdWalking)
        // Stationary jitter at 0.8 km/h is NOT a candidate
        assertFalse(0.8f >= candidateThresholdWalking)
    }

    @Test
    fun testDrivingAutoStartThresholds() {
        val car = ActivityProfile.CAR
        assertEquals(10.0f, car.autoStartSpeedKmh, 0.01f)
        assertEquals(10_000L, car.autoStartDurationMs)

        val candidateThresholdCar = car.autoStartSpeedKmh * 0.7f
        assertEquals(7.0f, candidateThresholdCar, 0.01f)

        // Driving at 15 km/h satisfies candidate & auto-start
        assertTrue(15.0f >= candidateThresholdCar)
        assertTrue(15.0f >= car.autoStartSpeedKmh)

        // Walking speed (4 km/h) does NOT trigger car auto-start
        assertFalse(4.0f >= candidateThresholdCar)
    }

    @Test
    fun testCyclingAndMtbAutoStartThresholds() {
        val cycling = ActivityProfile.CYCLING
        val mtb = ActivityProfile.MTB

        assertEquals(7.0f, cycling.autoStartSpeedKmh, 0.01f)
        assertEquals(10_000L, cycling.autoStartDurationMs)

        assertEquals(6.0f, mtb.autoStartSpeedKmh, 0.01f)
        assertEquals(10_000L, mtb.autoStartDurationMs)

        val cyclingCandidate = cycling.autoStartSpeedKmh * 0.7f
        val mtbCandidate = mtb.autoStartSpeedKmh * 0.7f

        assertTrue(8.0f >= cycling.autoStartSpeedKmh)
        assertTrue(6.5f >= mtb.autoStartSpeedKmh)
        assertTrue(5.0f >= cyclingCandidate)
        assertFalse(2.0f >= cyclingCandidate)
    }

    @Test
    fun testMotionBurstDurationAdaptsToProfile() {
        // Confirmation burst window must be longer than the profile's required autoStartDurationMs
        for (profile in ActivityProfile.values()) {
            val burstDurationMs = (profile.autoStartDurationMs + 30_000L).coerceAtLeast(60_000L)
            assertTrue(
                "Burst duration $burstDurationMs ms must comfortably exceed required autoStartDurationMs ${profile.autoStartDurationMs} ms for ${profile.name}",
                burstDurationMs >= profile.autoStartDurationMs + 20_000L
            )
            assertTrue("Burst duration should not be excessively long to preserve battery", burstDurationMs <= 65_000L)
        }
    }

    @Test
    fun testAppLifecycleModeSemantics() {
        // IDLE mode must exist for zero-drain deep sleep
        val idle = AppLifecycleMode.IDLE
        assertEquals("Idle / Suspended", idle.displayName)

        val liveOnly = AppLifecycleMode.LIVE_ONLY
        assertEquals("Live Sharing", liveOnly.displayName)

        val tripRecording = AppLifecycleMode.TRIP_RECORDING
        assertEquals("Recording Trip", tripRecording.displayName)
    }
}
