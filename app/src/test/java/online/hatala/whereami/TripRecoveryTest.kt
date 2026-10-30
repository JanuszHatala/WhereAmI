package online.hatala.whereami

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TripRecoveryTest {

    @Test
    fun `last activity prefers newest track point over place visit and start`() {
        assertEquals(
            1_000_000L,
            TripRecovery.lastActivityUtcMs(
                lastTrackPointUtcMs = 1_000_000L,
                lastPlaceVisitUtcMs = 500_000L,
                startTimeUtcMs = 100_000L
            )
        )
    }

    @Test
    fun `last activity falls back to start when no track points`() {
        assertEquals(
            100_000L,
            TripRecovery.lastActivityUtcMs(null, null, 100_000L)
        )
        assertEquals(
            500_000L,
            TripRecovery.lastActivityUtcMs(null, 500_000L, 100_000L)
        )
    }

    @Test
    fun `zombie uses track activity not trip start`() {
        val start = 0L
        val lastTrack = 3 * 60 * 60_000L // 3 h of driving, no place visits
        val now = lastTrack + 2 * 60_000L // 2 min after last point
        assertFalse(
            "recent track activity must not zombie-close",
            TripRecovery.shouldCloseAsZombie(true, false, now, lastTrack, autoStopMinutes = 15)
        )
        // Bug baseline: if lastActivity were start, this would wrongly close
        assertTrue(
            TripRecovery.shouldCloseAsZombie(true, false, now, start, autoStopMinutes = 15)
        )
    }

    @Test
    fun `paused or manual mode never zombie-closes`() {
        val now = 1_000_000L
        val last = now - 60 * 60_000L
        assertFalse(TripRecovery.shouldCloseAsZombie(true, true, now, last, 15))
        assertFalse(TripRecovery.shouldCloseAsZombie(false, false, now, last, 15))
    }

    @Test
    fun `idle past auto-stop closes as zombie`() {
        val last = 0L
        val now = 20 * 60_000L
        assertTrue(TripRecovery.shouldCloseAsZombie(true, false, now, last, autoStopMinutes = 15))
    }
}
