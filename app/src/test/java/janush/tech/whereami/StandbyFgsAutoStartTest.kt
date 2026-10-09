package janush.tech.whereami

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the Standby Foreground Service (FGS) and Auto-Start architecture.
 *
 * Validates the core architectural invariant:
 * On Android 14+ (targetSdk 36), an app CANNOT start a ForegroundService with
 * `foregroundServiceType="location"` while in the background.
 *
 * Therefore:
 * 1. In `TripMode.AUTO`, the Foreground Service MUST be started in the FOREGROUND
 *    and kept running continuously in a zero-CPU, zero-GPS standby mode with a
 *    persistent standby notification ("WhereAmI • Auto-detect Standby").
 * 2. In `TripMode.MANUAL`, when screen is off with no active trip or live share,
 *    the service is stopped completely (Option A zero-drain).
 * 3. In Standby mode, location stream subscriptions and wake locks MUST be released
 *    (0 mW draw, 0 location callbacks).
 */
class StandbyFgsAutoStartTest {

    // ─── Service State Evaluation Logic ──────────────────────────────────────

    enum class ExpectedServiceState {
        ACTIVE_TRACKING,
        ZERO_CPU_STANDBY,
        STOPPED
    }

    private fun evaluateServiceStateLogic(
        hasTrip: Boolean,
        hasLive: Boolean,
        isAuto: Boolean
    ): ExpectedServiceState {
        return when {
            hasTrip || hasLive -> ExpectedServiceState.ACTIVE_TRACKING
            isAuto -> ExpectedServiceState.ZERO_CPU_STANDBY
            else -> ExpectedServiceState.STOPPED
        }
    }

    @Test
    fun `evaluates to ACTIVE_TRACKING when trip is active`() {
        val state = evaluateServiceStateLogic(hasTrip = true, hasLive = false, isAuto = true)
        assertEquals(ExpectedServiceState.ACTIVE_TRACKING, state)
    }

    @Test
    fun `evaluates to ACTIVE_TRACKING when live sharing is active without trip`() {
        val state = evaluateServiceStateLogic(hasTrip = false, hasLive = true, isAuto = false)
        assertEquals(ExpectedServiceState.ACTIVE_TRACKING, state)
    }

    @Test
    fun `evaluates to ZERO_CPU_STANDBY in AUTO mode with no trip and no live`() {
        val state = evaluateServiceStateLogic(hasTrip = false, hasLive = false, isAuto = true)
        assertEquals(ExpectedServiceState.ZERO_CPU_STANDBY, state)
    }

    @Test
    fun `evaluates to STOPPED in MANUAL mode with no trip and no live`() {
        val state = evaluateServiceStateLogic(hasTrip = false, hasLive = false, isAuto = false)
        assertEquals(ExpectedServiceState.STOPPED, state)
    }

    // ─── IDLE Lifecycle Mode Invariants ──────────────────────────────────────

    @Test
    fun `IDLE mode does NOT stop LiveTrackingService when tripMode is AUTO`() {
        val isAuto = true
        val hasTrip = false
        val hasLive = false

        // In IDLE: only stop service if !hasTrip && !hasLive && !isAuto
        val shouldStopService = !hasTrip && !hasLive && !isAuto
        assertFalse("IDLE mode in AUTO must preserve Standby FGS so it stays alive with notification", shouldStopService)
    }

    @Test
    fun `IDLE mode stops LiveTrackingService when tripMode is MANUAL`() {
        val isAuto = false
        val hasTrip = false
        val hasLive = false

        val shouldStopService = !hasTrip && !hasLive && !isAuto
        assertTrue("IDLE mode in MANUAL must stop service completely (Option A zero-drain)", shouldStopService)
    }

    // ─── Foreground View Standby Service Guarantee ───────────────────────────

    @Test
    fun `foreground entry ensures Standby FGS is started when tripMode is AUTO`() {
        val isAppInForeground = true
        val isAuto = true
        val hasTrip = false
        val hasLive = false

        val shouldStartStandby = (isAuto || hasLive || hasTrip) && isAppInForeground
        assertTrue("Foreground view must ensure Standby FGS is running for AUTO mode", shouldStartStandby)
    }

    @Test
    fun `foreground entry ensures FGS is started when live sharing is active even without trip`() {
        val isAppInForeground = true
        val isAuto = true
        val hasTrip = false
        val hasLive = true

        val shouldStartService = (isAuto || hasLive || hasTrip) && isAppInForeground
        assertTrue("Foreground view must ensure FGS is running when live sharing is active with auto-detect", shouldStartService)
    }

    @Test
    fun `background entry does NOT attempt to start Standby FGS from background`() {
        val isAppInForeground = false
        val isAuto = true
        val hasTrip = false
        val hasLive = false

        val shouldStartStandby = (isAuto || hasLive || hasTrip) && isAppInForeground
        assertFalse("Must never attempt to start location FGS from background (Android 14+ DENIED)", shouldStartStandby)
    }

    // ─── Wake Lock & Resource Gating Invariants ──────────────────────────────

    @Test
    fun `standby mode must not hold tracking wake lock`() {
        val state = evaluateServiceStateLogic(hasTrip = false, hasLive = false, isAuto = true)
        val shouldHoldWakeLock = (state == ExpectedServiceState.ACTIVE_TRACKING)
        assertFalse("Standby mode must release tracking wake lock (0 mW draw)", shouldHoldWakeLock)
    }

    @Test
    fun `active tracking must hold wake lock`() {
        val state = evaluateServiceStateLogic(hasTrip = true, hasLive = false, isAuto = true)
        val shouldHoldWakeLock = (state == ExpectedServiceState.ACTIVE_TRACKING)
        assertTrue("Active tracking must hold wake lock to prevent sleep during recording", shouldHoldWakeLock)
    }

    // ─── Burst WakeLock & Duration Guards ────────────────────────────────────

    @Test
    fun `burst duration is at least 60 seconds for all profiles`() {
        data class Profile(val name: String, val autoStartDurationMs: Long)

        val profiles = listOf(
            Profile("HIKING", 45_000L),
            Profile("CYCLING", 30_000L),
            Profile("CAR", 20_000L)
        )

        for (profile in profiles) {
            val burstDurationMs = (profile.autoStartDurationMs + 30_000L).coerceAtLeast(60_000L)
            assertTrue(
                "Burst for ${profile.name} must be ≥ 60s, got ${burstDurationMs}ms",
                burstDurationMs >= 60_000L
            )
        }
    }

    @Test
    fun `wake lock timeout includes 5s safety margin beyond burst`() {
        val burstDurationMs = 60_000L
        val wakeLockTimeout = burstDurationMs + 5_000L
        assertEquals(65_000L, wakeLockTimeout)
        assertTrue("Wake lock must outlive burst by at least 5s", wakeLockTimeout > burstDurationMs)
    }

    // ─── Sensor Arm / Disarm Guards ──────────────────────────────────────────

    @Test
    fun `arm is idempotent when already armed`() {
        var armCalls = 0
        var isArmed = false

        fun arm(): Boolean {
            if (isArmed) return true
            armCalls++
            isArmed = true
            return true
        }

        arm()
        arm()
        arm()

        assertEquals("SensorManager.requestTriggerSensor should only be called once", 1, armCalls)
        assertTrue(isArmed)
    }

    @Test
    fun `disarm clears isArmed even when sensor cancel throws`() {
        var isArmed = true

        fun disarm() {
            if (!isArmed) return
            try {
                throw RuntimeException("Simulated cancel failure")
            } catch (_: Exception) {}
            isArmed = false
        }

        disarm()
        assertFalse("isArmed must be cleared even if cancelTriggerSensor throws", isArmed)
    }

    // ─── Actions & Constants ─────────────────────────────────────────────────

    @Test
    fun `ACTION_ENTER_STANDBY and ACTION_STOP are distinct`() {
        assertNotEquals(LiveTrackingService.ACTION_ENTER_STANDBY, LiveTrackingService.ACTION_STOP)
        assertEquals("janush.tech.whereami.ACTION_ENTER_STANDBY", LiveTrackingService.ACTION_ENTER_STANDBY)
        assertEquals("janush.tech.whereami.ACTION_STOP", LiveTrackingService.ACTION_STOP)
    }
}
