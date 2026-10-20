package janush.tech.whereami

/**
 * Partial wake lock rules for [LiveTrackingService] / [AutoMediaService].
 *
 * Locks are held only while a trip is recording. Slices are short and refreshed while the trip
 * stays active so a multi-hour drive does not silently drop the lock after a single long timeout.
 */
object WakeLockPolicy {
    /** One acquire window; refresh before expiry while [hasActiveTrip] remains true. */
    const val TRIP_WAKE_LOCK_SLICE_MS = 60L * 60L * 1000L

    /** Refresh when at least half the slice has elapsed (or the lock is not held). */
    fun shouldRefreshHeldLock(isHeld: Boolean, heldForMs: Long, sliceMs: Long = TRIP_WAKE_LOCK_SLICE_MS): Boolean {
        if (!isHeld) return true
        return heldForMs >= sliceMs / 2
    }

    /** Trip recording only — never LIVE_ONLY / standby / Android Auto without a trip. */
    fun shouldHoldTripWakeLock(hasActiveTrip: Boolean): Boolean = hasActiveTrip
}
