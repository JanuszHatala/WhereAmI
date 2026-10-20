package janush.tech.whereami

/**
 * Pure rules for surviving process death / hard reset while a trip is open (`end_time IS NULL`).
 *
 * Geometry is already in SQLite via the batched writer; these helpers decide whether an unclosed row
 * is still an active recording or a zombie to close, without touching GPS or wake locks.
 */
object TripRecovery {
    /**
     * Last evidence that the trip was still being recorded.
     * Prefer the newest track point time; place visits alone must not define activity (a long drive
     * with no place commits would otherwise look abandoned from [startTimeUtc]).
     */
    fun lastActivityUtcMs(
        lastTrackPointUtcMs: Long?,
        lastPlaceVisitUtcMs: Long?,
        startTimeUtcMs: Long
    ): Long {
        var t = startTimeUtcMs
        if (lastPlaceVisitUtcMs != null && lastPlaceVisitUtcMs > t) t = lastPlaceVisitUtcMs
        if (lastTrackPointUtcMs != null && lastTrackPointUtcMs > t) t = lastTrackPointUtcMs
        return t
    }

    /** AUTO mode only: close an unpaused trip that has been idle longer than the auto-stop window. */
    fun shouldCloseAsZombie(
        tripModeAuto: Boolean,
        wasPaused: Boolean,
        nowUtcMs: Long,
        lastActivityUtcMs: Long,
        autoStopMinutes: Int
    ): Boolean {
        if (!tripModeAuto || wasPaused) return false
        val timeoutMs = autoStopMinutes.coerceAtLeast(1) * 60_000L
        return nowUtcMs - lastActivityUtcMs > timeoutMs
    }
}
