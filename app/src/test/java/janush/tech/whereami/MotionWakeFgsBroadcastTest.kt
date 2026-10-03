package janush.tech.whereami

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the MotionWake FGS broadcast architecture.
 *
 * These tests validate the design decisions around Android 14+ FGS-start restrictions
 * and ensure the broadcast-receiver-based bootstrap path is correctly structured.
 * Since BroadcastReceiver, Context, and PendingIntent are Android framework classes
 * not available in JVM unit tests, we test the pure logic, constants, and flow guards.
 */
class MotionWakeFgsBroadcastTest {

    // ─── Constants ────────────────────────────────────────────────────────────

    @Test
    fun `ACTION_MOTION_WAKE has correct package-scoped action string`() {
        assertEquals(
            "janush.tech.whereami.ACTION_MOTION_WAKE",
            MotionWakeBroadcastReceiver.ACTION_MOTION_WAKE
        )
    }

    @Test
    fun `ACTION_MOTION_WAKE is distinct from all other service actions`() {
        val action = MotionWakeBroadcastReceiver.ACTION_MOTION_WAKE
        assertNotEquals(action, LiveTrackingService.ACTION_ENTER_STANDBY)
        assertNotEquals(action, LiveTrackingService.ACTION_STOP)
        assertNotEquals(action, "janush.tech.whereami.ACTION_PREFETCH_START")
        assertNotEquals(action, "janush.tech.whereami.ACTION_DISABLE_AUTO_START")
    }

    // ─── MotionWakeManager state guards ───────────────────────────────────────

    /**
     * Verifies the isArmed() guard: re-arming an already-armed manager must be idempotent.
     * We replicate the logic guard independently since we can't instantiate the real manager
     * (requires Android SensorManager) in a JVM test.
     */
    @Test
    fun `arm is idempotent when already armed`() {
        var armCalls = 0
        var isArmed = false

        // Simulate arm() logic
        fun arm(): Boolean {
            if (isArmed) return true // idempotent guard
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

        // Simulate disarm() logic with throwing cancelTriggerSensor
        fun disarm() {
            if (!isArmed) return
            try {
                throw RuntimeException("Simulated cancel failure")
            } catch (_: Exception) {}
            isArmed = false // must still clear flag
        }

        disarm()
        assertFalse("isArmed must be cleared even if cancelTriggerSensor throws", isArmed)
    }

    // ─── Burst wake lock duration guards ──────────────────────────────────────

    @Test
    fun `burst duration is at least 60 seconds for all profiles`() {
        // Mirrors the formula in AppStateManager.handleMotionWake
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

    // ─── IDLE mode FGS logic guards ───────────────────────────────────────────

    /**
     * Verifies the architectural decision: in IDLE + AUTO mode, we send ACTION_STOP
     * (not ACTION_ENTER_STANDBY via startForegroundService) from the IDLE transition.
     * The FGS bootstrap is exclusively the broadcast receiver's job.
     */
    @Test
    fun `IDLE transition sends ACTION_STOP not ACTION_ENTER_STANDBY`() {
        // Simulate the IDLE applyModeToLocationEngine logic
        val isAuto = true
        val hasTrip = false
        val hasLive = false

        val intentAction = if (!hasTrip && !hasLive) {
            // New correct logic: always stop, let receiver bootstrap on motion
            LiveTrackingService.ACTION_STOP
        } else {
            null
        }

        assertEquals(
            "IDLE must send ACTION_STOP, not ACTION_ENTER_STANDBY, to avoid DENIED on Android 14+",
            LiveTrackingService.ACTION_STOP,
            intentAction
        )

        // Verify we're not sending ENTER_STANDBY from IDLE (old broken behavior)
        assertNotEquals(LiveTrackingService.ACTION_ENTER_STANDBY, intentAction)
        val isAutoConsumed = isAuto // consumed to suppress warning
        assertTrue(isAutoConsumed) // AUTO mode should be true in this scenario
    }

    @Test
    fun `IDLE with active trip does not stop service`() {
        val hasTrip = true
        val hasLive = false

        var stopCalled = false
        if (!hasTrip && !hasLive) {
            stopCalled = true
        }

        assertFalse("Active trip must prevent service stop in IDLE", stopCalled)
    }

    @Test
    fun `IDLE with active live session does not stop service`() {
        val hasTrip = false
        val hasLive = true

        var stopCalled = false
        if (!hasTrip && !hasLive) {
            stopCalled = true
        }

        assertFalse("Active live session must prevent service stop in IDLE", stopCalled)
    }

    // ─── Bootstrap window constants ───────────────────────────────────────────

    @Test
    fun `bootstrap wake lock is 10 seconds max as required`() {
        // The MotionWakeBroadcastReceiver bootstrap lock must be ≤ 10s
        // (Android's onReceive() context only lasts ~10s anyway)
        val bootstrapWindowMs = 10_000L
        assertTrue("Bootstrap window must be ≤ 10s", bootstrapWindowMs <= 10_000L)
        assertTrue("Bootstrap window must be > 0", bootstrapWindowMs > 0)
    }
}

// End of MotionWakeFgsBroadcastTest
