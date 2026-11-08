package online.hatala.whereami

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppStateManagerBurstTest {

    @Test
    fun testCarBurstEarlyAbortWhenPhysicallyStationaryAt15Seconds() {
        // Device resting on desk/nightstand: variance < 0.045 (physically stationary), 0 km/h, 0 displacement
        val canAbort = AppStateManager.shouldEarlyAbortBurst(
            elapsedMs = 15_000L,
            minDurationMs = 15_000L,
            currentSpeedMs = 0.0f,
            targetCandidateSpeedMs = 5.0f,
            displacementMeters = 1.2,
            isPhysicallyStationary = true,
            isCarProfile = true
        )
        assertTrue(
            "CAR profile resting on nightstand must early abort at 15s instead of running 36s GPS",
            canAbort
        )
    }

    @Test
    fun testCarBurstDoesNotAbortUnder15Seconds() {
        val canAbort = AppStateManager.shouldEarlyAbortBurst(
            elapsedMs = 14_000L,
            minDurationMs = 15_000L,
            currentSpeedMs = 0.0f,
            targetCandidateSpeedMs = 5.0f,
            displacementMeters = 0.0,
            isPhysicallyStationary = true,
            isCarProfile = true
        )
        assertFalse("Must never abort under minimum window duration", canAbort)
    }

    @Test
    fun testCarBurstDoesNotEarlyAbortWhenEngineVibratingOrMoving() {
        // In car with engine running / moving: isPhysicallyStationary is false
        val canAbort = AppStateManager.shouldEarlyAbortBurst(
            elapsedMs = 16_000L,
            minDurationMs = 15_000L,
            currentSpeedMs = 1.2f,
            targetCandidateSpeedMs = 5.0f,
            displacementMeters = 8.0,
            isPhysicallyStationary = false,
            isCarProfile = true
        )
        assertFalse(
            "CAR profile with engine vibration or low speed motion must not early abort at 16s",
            canAbort
        )
    }

    @Test
    fun testCarBurstDoesNotEarlyAbortAt25SecondsWhenEngineVibrating() {
        val canAbort = AppStateManager.shouldEarlyAbortBurst(
            elapsedMs = 26_000L,
            minDurationMs = 15_000L,
            currentSpeedMs = 0.5f,
            targetCandidateSpeedMs = 5.0f,
            displacementMeters = 8.0,
            isPhysicallyStationary = false,
            isCarProfile = true
        )
        assertFalse(
            "CAR profile with engine vibration (isPhysicallyStationary = false) must not early abort at 25s",
            canAbort
        )
    }

    @Test
    fun testCarBurstAbortsAt35SecondsWhenBelowDisplacementThreshold() {
        // After 35s in CAR profile with no real speed or net displacement (< 15m)
        val canAbort = AppStateManager.shouldEarlyAbortBurst(
            elapsedMs = 36_000L,
            minDurationMs = 15_000L,
            currentSpeedMs = 0.5f,
            targetCandidateSpeedMs = 5.0f,
            displacementMeters = 10.0,
            isPhysicallyStationary = false,
            isCarProfile = true
        )
        assertTrue("CAR profile after 35s with displacement < 15m must abort", canAbort)
    }

    @Test
    fun testCandidateSpeedReachedNeverAborts() {
        val canAbort = AppStateManager.shouldEarlyAbortBurst(
            elapsedMs = 20_000L,
            minDurationMs = 15_000L,
            currentSpeedMs = 6.0f,
            targetCandidateSpeedMs = 5.0f,
            displacementMeters = 25.0,
            isPhysicallyStationary = false,
            isCarProfile = true
        )
        assertFalse("Burst must never abort when candidate speed reached", canAbort)
    }

    @Test
    fun testCarBurstIndoorZeroSpeedAbortAt20Seconds() {
        // Field Day 60 False Burst #3: holding phone indoors (isPhysicallyStationary = false),
        // speed = 0.0, displacement = 4.07m. Must abort at 20s instead of holding GPS for 36s.
        val canAbort = AppStateManager.shouldEarlyAbortBurst(
            elapsedMs = 20_000L,
            minDurationMs = 15_000L,
            currentSpeedMs = 0.0f,
            targetCandidateSpeedMs = 5.0f,
            displacementMeters = 4.07,
            isPhysicallyStationary = false,
            isCarProfile = true
        )
        assertTrue("CAR profile at 20s with zero speed and displacement < 6m must abort early", canAbort)
    }

    @Test
    fun testCarBurstIndoorLowSpeedDoesNotAbortAt20SecondsWhenCrawling() {
        val canAbort = AppStateManager.shouldEarlyAbortBurst(
            elapsedMs = 20_000L,
            minDurationMs = 15_000L,
            currentSpeedMs = 0.8f,
            targetCandidateSpeedMs = 5.0f,
            displacementMeters = 8.0,
            isPhysicallyStationary = false,
            isCarProfile = true
        )
        assertFalse("CAR profile crawling in traffic with speed >= 0.25 m/s or displacement >= 6m must not abort at 20s", canAbort)
    }

    @Test
    fun testShouldAbortPreBurstBeforeGps() {
        // Screen off + verified physically stationary (bump on nightstand/desk) -> abort before GPS power on
        assertTrue(
            "Stationary screen-off motion trigger must abort before starting GNSS",
            AppStateManager.shouldAbortPreBurstBeforeGps(isPhysicallyStationary = true, isAppInForeground = false)
        )
        // Screen off + vibrating/moving (in car or walking) -> do not abort
        assertFalse(
            "Moving screen-off motion trigger must proceed to GNSS burst",
            AppStateManager.shouldAbortPreBurstBeforeGps(isPhysicallyStationary = false, isAppInForeground = false)
        )
        // Screen on (foreground) -> never abort pre-burst
        assertFalse(
            "Foreground motion trigger must proceed",
            AppStateManager.shouldAbortPreBurstBeforeGps(isPhysicallyStationary = true, isAppInForeground = true)
        )
    }

    @Test
    fun testShouldActivateForegroundOnStart() {
        // Unlocked and interactive -> activate
        assertTrue(
            "Interactive and unlocked must activate foreground tracking",
            AppStateManager.shouldActivateForegroundOnStart(isInteractive = true, isKeyguardLocked = false)
        )
        // Alarm ringing over lockscreen (interactive screen on, but keyguard locked) -> DO NOT activate!
        assertFalse(
            "Keyguard locked during alarm must NOT activate foreground tracking",
            AppStateManager.shouldActivateForegroundOnStart(isInteractive = true, isKeyguardLocked = true)
        )
        // Screen off -> DO NOT activate
        assertFalse(
            "Screen off must NOT activate foreground tracking",
            AppStateManager.shouldActivateForegroundOnStart(isInteractive = false, isKeyguardLocked = false)
        )
    }
}
