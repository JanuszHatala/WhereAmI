package online.hatala.whereami

/**
 * Detects that the user's alarm clock rang.
 *
 * `AlarmManager.nextAlarmClock` only ever reports the *upcoming* alarm: when an alarm rings, the clock app
 * moves it to the snooze or the next day and broadcasts `ACTION_NEXT_ALARM_CLOCK_CHANGED`. The fired alarm
 * is therefore the previously upcoming trigger once it lies in the past. (Recording only the new upcoming
 * value meant a fired alarm was never seen and sleep mode stayed on after three morning alarms.)
 */
class AlarmFireTracker(
    /** Upcoming trigger known before (persisted across process restarts), 0 = none. */
    var upcomingTriggerMs: Long = 0L,
    var lastFiredMs: Long = 0L
) {
    /** Call with the current `nextAlarmClock` trigger (null = none) on every change and evaluation. */
    fun update(nowMs: Long, nextTriggerMs: Long?) {
        val prev = upcomingTriggerMs
        if (prev in 1..nowMs && nextTriggerMs != prev) lastFiredMs = prev
        upcomingTriggerMs = nextTriggerMs ?: 0L
        // The alarm may be ringing right now (not yet moved by the clock app).
        if (upcomingTriggerMs in 1..nowMs) lastFiredMs = maxOf(lastFiredMs, upcomingTriggerMs)
    }

    fun firedWithin(nowMs: Long, windowMs: Long = FIRED_WINDOW_MS): Boolean =
        lastFiredMs > 0L && lastFiredMs in (nowMs - windowMs)..nowMs

    companion object {
        const val FIRED_WINDOW_MS = 15 * 60_000L
    }
}
