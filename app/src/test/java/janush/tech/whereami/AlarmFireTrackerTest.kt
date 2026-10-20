package janush.tech.whereami

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmFireTrackerTest {
    private val min = 60_000L
    private val alarm = 1_000_000_000L

    @Test
    fun `alarm moved to snooze after ringing counts as fired`() {
        val t = AlarmFireTracker()
        t.update(alarm - 8 * 60 * min, alarm) // bedtime: 06:20 alarm upcoming
        assertFalse(t.firedWithin(alarm - 60 * min))
        t.update(alarm + 10_000L, alarm + 9 * min) // rang, snoozed by 9 min
        assertTrue(t.firedWithin(alarm + 7 * min))
        assertFalse(t.firedWithin(alarm + 16 * min))
    }

    @Test
    fun `last of several morning alarms keeps the wake window open`() {
        val t = AlarmFireTracker(upcomingTriggerMs = alarm)
        t.update(alarm + 1_000L, alarm + 5 * min)
        t.update(alarm + 5 * min + 1_000L, alarm + 10 * min)
        t.update(alarm + 10 * min + 1_000L, alarm + 24 * 60 * min) // dismissed: tomorrow
        assertTrue(t.firedWithin(alarm + 17 * min)) // 7 min after the third alarm
    }

    @Test
    fun `alarm cancelled before its time is not a fire`() {
        val t = AlarmFireTracker(upcomingTriggerMs = alarm)
        t.update(alarm - 30 * min, null)
        assertFalse(t.firedWithin(alarm))
        assertFalse(t.firedWithin(alarm + min))
    }

    @Test
    fun `ringing alarm not yet moved by the clock app counts as fired`() {
        val t = AlarmFireTracker()
        t.update(alarm + 20_000L, alarm)
        assertTrue(t.firedWithin(alarm + 30_000L))
    }

    @Test
    fun `persisted upcoming alarm survives a process restart`() {
        // Process died overnight; restarted after the alarm rang and moved to tomorrow.
        val t = AlarmFireTracker(upcomingTriggerMs = alarm, lastFiredMs = 0L)
        t.update(alarm + 2 * min, alarm + 24 * 60 * min)
        assertTrue(t.firedWithin(alarm + 7 * min))
    }
}
