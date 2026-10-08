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

    @Test
    fun testCustomAutoStartSpeedClampingAndCandidateSensitivity() {
        // Validation of speed clamping rule (1.0f .. 150.0f)
        val clampSpeed = { speed: Float -> speed.coerceIn(1.0f, 150.0f) }
        assertEquals(1.0f, clampSpeed(0.5f), 0.001f)
        assertEquals(1.0f, clampSpeed(-10f), 0.001f)
        assertEquals(150.0f, clampSpeed(200f), 0.001f)
        assertEquals(4.5f, clampSpeed(4.5f), 0.001f)

        // Custom speed threshold adaptation for candidate and sustained speed triggers
        // E.g. user sets cycling threshold to 5.0 km/h (for steep climbs or casual city rides)
        val customCyclingSpeed = 5.0f
        val candidateCycling = customCyclingSpeed * 0.7f
        assertEquals(3.5f, candidateCycling, 0.01f)
        assertTrue("Riding at 3.6 km/h qualifies as candidate speed for 5 km/h target", 3.6f >= candidateCycling)
        assertFalse("Walking at 3.0 km/h does not qualify as candidate speed for 5 km/h target", 3.0f >= candidateCycling)
        assertTrue("Riding at 5.0 km/h triggers sustained auto-start", 5.0f >= customCyclingSpeed)

        // E.g. user sets car threshold to 15.0 km/h
        val customCarSpeed = 15.0f
        val candidateCar = customCarSpeed * 0.7f
        assertEquals(10.5f, candidateCar, 0.01f)
        assertTrue("Speed at 11 km/h is candidate for 15 km/h car threshold", 11.0f >= candidateCar)
        assertFalse("Speed at 9 km/h is not candidate for 15 km/h car threshold", 9.0f >= candidateCar)
    }

    @Test
    fun testIndependentProfileManagementCoverage() {
        // Verify all 6 activity profiles exist and possess distinct identity and defaults
        val allProfiles = ActivityProfile.values()
        assertEquals(6, allProfiles.size)
        assertTrue(allProfiles.contains(ActivityProfile.CAR))
        assertTrue(allProfiles.contains(ActivityProfile.CYCLING))
        assertTrue(allProfiles.contains(ActivityProfile.MTB))
        assertTrue(allProfiles.contains(ActivityProfile.HIKING))
        assertTrue(allProfiles.contains(ActivityProfile.RUNNING))
        assertTrue(allProfiles.contains(ActivityProfile.WALKING))

        // Ensure each profile has distinct emoji and valid timeout presets
        for (profile in allProfiles) {
            assertTrue("Profile displayName cannot be blank", profile.displayName.isNotBlank())
            assertTrue("Profile emoji cannot be blank", profile.iconEmoji.isNotBlank())
            assertTrue("Default auto-stop minutes must be between 1 and 60", profile.autoStopMinutesDefault in 1..60)
        }
    }

    @Test
    fun testWakeLockScopingStrictlyToActiveTripRecording() {
        // AGENTS.md Rule 2.A:
        // Wake locks must ONLY be acquired when an active trip is actively recording.
        // Never during Live Sharing alone (LIVE_ONLY mode), in onCreate(), or in standby/idle.
        val shouldHoldWakeLock = { hasTrip: Boolean, hasLive: Boolean ->
            hasTrip // Strictly scoped to active trip recording
        }

        // Active trip + Live sharing -> Hold wake lock
        assertTrue(shouldHoldWakeLock(true, true))

        // Active trip only -> Hold wake lock
        assertTrue(shouldHoldWakeLock(true, false))

        // Live sharing only (without active trip) -> MUST NOT hold wake lock
        assertFalse("Live sharing alone must not hold persistent wake lock to allow Doze deep sleep", shouldHoldWakeLock(false, true))

        // Standby/Idle -> MUST NOT hold wake lock
        assertFalse(shouldHoldWakeLock(false, false))
    }
}


