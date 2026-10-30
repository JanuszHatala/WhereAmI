package online.hatala.whereami

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeLockPolicyTest {

    @Test
    fun `wake lock only while trip active`() {
        assertTrue(WakeLockPolicy.shouldHoldTripWakeLock(true))
        assertFalse(WakeLockPolicy.shouldHoldTripWakeLock(false))
    }

    @Test
    fun `refresh before slice expires so long trips keep the lock`() {
        val slice = WakeLockPolicy.TRIP_WAKE_LOCK_SLICE_MS
        assertTrue(WakeLockPolicy.shouldRefreshHeldLock(isHeld = false, heldForMs = 0))
        assertFalse(WakeLockPolicy.shouldRefreshHeldLock(isHeld = true, heldForMs = slice / 4))
        assertTrue(WakeLockPolicy.shouldRefreshHeldLock(isHeld = true, heldForMs = slice / 2))
        assertTrue(WakeLockPolicy.shouldRefreshHeldLock(isHeld = true, heldForMs = slice))
        // Old 3h timeout would leave a 4h trip without a lock for the last hour
        assertTrue(
            "4h trip must refresh within a 1h slice policy",
            WakeLockPolicy.shouldRefreshHeldLock(true, 3 * 60 * 60_000L + 1, slice)
        )
        assertEquals(60L * 60L * 1000L, WakeLockPolicy.TRIP_WAKE_LOCK_SLICE_MS)
    }
}
