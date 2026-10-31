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
}
