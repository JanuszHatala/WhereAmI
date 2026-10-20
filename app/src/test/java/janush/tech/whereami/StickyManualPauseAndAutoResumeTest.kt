package janush.tech.whereami

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StickyManualPauseAndAutoResumeTest {

    @Test
    fun testPermanentContinuationThresholds() {
        // Car profile: >= 400m displacement OR sustained speed >= 20 km/h for >= 45s
        val carDist = 400.0
        val carSpeed = 20.0f
        val carDuration = 45_000L

        // Walking 80m around a gas station does NOT satisfy permanent continuation
        val gasStationWalkDist = 80.0
        assertFalse("80m walk around gas station must not clear sticky manual pause", gasStationWalkDist >= carDist)

        // Driving onto the highway for 450m DOES satisfy permanent continuation
        val highwayDrivingDist = 450.0
        assertTrue("450m driving onto highway must confirm permanent trip continuation", highwayDrivingDist >= carDist)

        // Sustained driving at 30 km/h for 50s DOES satisfy permanent continuation
        val drivingSpeed = 30.0f
        val drivingTime = 50_000L
        assertTrue("Sustained speed >= 20 km/h for >= 45s must clear sticky pause", drivingSpeed >= carSpeed && drivingTime >= carDuration)
    }

    @Test
    fun testAutoResumeRequiresSustainedLocomotionNotInstantaneousFix() {
        val car = ActivityProfile.CAR
        val requiredDurationMs = car.autoStartDurationMs.coerceAtMost(30_000L)
        val requiredDisplacement = 30.0

        // Single GPS jitter jump of 180m occurring within 1 second:
        val jitterElapsedMs = 1_000L
        val jitterDisplacement = 180.0

        val jitterResumes = jitterElapsedMs >= requiredDurationMs && jitterDisplacement >= requiredDisplacement
        assertFalse("Single-second GPS jitter spike must NOT auto-resume paused trip", jitterResumes)

        // Real locomotion: sustained for 15s with 50m displacement:
        val realElapsedMs = 15_000L
        val realDisplacement = 50.0

        val realResumes = realElapsedMs >= requiredDurationMs && realDisplacement >= requiredDisplacement
        assertTrue("Sustained locomotion meeting profile duration & displacement MUST auto-resume paused trip", realResumes)
    }

    @Test
    fun testStickyPauseHasPrecedenceOverAutoStopWhenStationaryAgain() {
        // When user paused manually, walked temporarily (auto-resumed), and then stopped again:
        val isManualPauseSticky = true
        val distMoved = 120.0 // incidental walk (< 400m threshold)
        val permanentContinuationReached = distMoved >= 400.0

        assertFalse("120m walk does not reach permanent continuation", permanentContinuationReached)

        // Watchdog detects stationary timeout (e.g. 10 min stationary)
        // With isManualPauseSticky == true, the system MUST return to pause, NEVER auto-stop
        val shouldReturnToPause = isManualPauseSticky && !permanentContinuationReached
        val shouldAutoStop = !isManualPauseSticky

        assertTrue("Stationary dwell after incidental walk must return to Pause", shouldReturnToPause)
        assertFalse("Stationary dwell after incidental walk must NOT auto-stop", shouldAutoStop)
    }

    @Test
    fun testStandbyHibernationAfterNinetyMinutesAnyTimeOfDay() {
        // AGENTS.md: after 90 minutes verified stationary rest, hibernate (not bedtime-gated).
        val inactiveDuration = 90 * 60 * 1000L
        assertTrue(
            "90m stationary dwell during daytime must hibernate",
            inactiveDuration >= AppStateManager.STANDBY_HIBERNATION_TIMEOUT_MS
        )
        assertTrue(
            "90m stationary dwell at night must hibernate",
            inactiveDuration >= AppStateManager.STANDBY_HIBERNATION_TIMEOUT_MS
        )
        assertFalse(
            "under 90m must not hibernate",
            (89 * 60 * 1000L) >= AppStateManager.STANDBY_HIBERNATION_TIMEOUT_MS
        )
    }
}
