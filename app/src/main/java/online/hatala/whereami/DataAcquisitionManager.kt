package online.hatala.whereami

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.EnumMap

enum class DataAcquisitionStatus {
    HEALTHY,           // All online services responding normally
    CACHED_ONLY,       // Network geocoding failed or unavailable; relying on local SQLite cache
    RATE_LIMITED_429,  // OSM Nominatim / backend rate limit (HTTP 429); cooldown active
    OFFLINE            // No internet connection available
}

enum class DataSourceKind {
    GEOCODER,
    ROUTING,
    OUTDOOR_OVERPASS,
    TRAILS,
    BOUNDARIES,
    GENERAL
}

data class DataAcquisitionHealth(
    val status: DataAcquisitionStatus = DataAcquisitionStatus.HEALTHY,
    val detailMessage: String? = null,
    val lastUpdatedElapsedMs: Long = 0L,
    val cooldownRemainingSec: Int = 0,
    val failingSources: Set<DataSourceKind> = emptySet()
)

enum class PillBadgeKind(val severity: Int) {
    NONE(0),
    STALE(1),
    CACHED(2),
    OFFLINE(3),
    RATE_LIMITED(4)
}

data class AcquisitionPillCandidate(
    val kind: PillBadgeKind,
    val detailMessage: String? = null
) {
    companion object {
        fun from(health: DataAcquisitionHealth, isStaleUnverified: Boolean): AcquisitionPillCandidate {
            val kind = when {
                health.status == DataAcquisitionStatus.RATE_LIMITED_429 -> PillBadgeKind.RATE_LIMITED
                health.status == DataAcquisitionStatus.OFFLINE -> PillBadgeKind.OFFLINE
                health.status == DataAcquisitionStatus.CACHED_ONLY -> PillBadgeKind.CACHED
                isStaleUnverified -> PillBadgeKind.STALE
                else -> PillBadgeKind.NONE
            }
            return AcquisitionPillCandidate(kind = kind, detailMessage = health.detailMessage)
        }
    }
}

/**
 * Hysteresis and minimum-dwell state machine for the map data acquisition status pill.
 * Prevents brief transient errors (e.g. a single tile or Overpass timeout) from flashing
 * an alert badge for a fraction of a second, and holds shown warnings long enough to read.
 */
class AcquisitionPillStabilizer(
    private val severeEntryDebounceMs: Long = 500L,
    private val minorEntryDebounceMs: Long = 1500L,
    private val minVisibleDwellMs: Long = 4000L,
    private val exitDebounceMs: Long = 1500L
) {
    var displayed: AcquisitionPillCandidate? = null
        private set
    private var displayedSinceMs: Long = 0L
    private var pendingCandidate: AcquisitionPillCandidate? = null
    private var pendingSinceMs: Long = 0L
    private var healthySinceMs: Long = 0L

    fun update(candidate: AcquisitionPillCandidate, nowMs: Long): AcquisitionPillCandidate? {
        if (candidate.kind == PillBadgeKind.NONE) {
            pendingCandidate = null
            pendingSinceMs = 0L
            val current = displayed
            if (current == null) {
                healthySinceMs = 0L
                return null
            }
            if (healthySinceMs == 0L) {
                healthySinceMs = nowMs
            }
            val dwellMet = (nowMs - displayedSinceMs) >= minVisibleDwellMs
            val exitMet = (nowMs - healthySinceMs) >= exitDebounceMs
            if (dwellMet && exitMet) {
                displayed = null
                displayedSinceMs = 0L
                healthySinceMs = 0L
            }
            return displayed
        }

        healthySinceMs = 0L
        val debounceMs = entryDebounceMsFor(candidate.kind)
        val current = displayed
        if (current == null) {
            if (pendingCandidate?.kind != candidate.kind) {
                pendingCandidate = candidate
                pendingSinceMs = nowMs
            } else {
                pendingCandidate = candidate
            }
            if ((nowMs - pendingSinceMs) >= debounceMs) {
                displayed = candidate
                displayedSinceMs = nowMs
                pendingCandidate = null
                pendingSinceMs = 0L
            }
            return displayed
        }

        if (candidate.kind == current.kind) {
            pendingCandidate = null
            pendingSinceMs = 0L
            if (candidate.detailMessage != current.detailMessage) {
                displayed = candidate
            }
            return displayed
        }

        if (candidate.kind.severity > current.kind.severity) {
            displayed = candidate
            displayedSinceMs = nowMs
            pendingCandidate = null
            pendingSinceMs = 0L
            return displayed
        }

        // Demotion between degraded states respects both dwell and entry debounce.
        if (pendingCandidate?.kind != candidate.kind) {
            pendingCandidate = candidate
            pendingSinceMs = nowMs
        } else {
            pendingCandidate = candidate
        }
        val dwellMet = (nowMs - displayedSinceMs) >= minVisibleDwellMs
        val candidateMet = (nowMs - pendingSinceMs) >= debounceMs
        if (dwellMet && candidateMet) {
            displayed = candidate
            displayedSinceMs = nowMs
            pendingCandidate = null
            pendingSinceMs = 0L
        }
        return displayed
    }

    fun nextDelayMs(candidate: AcquisitionPillCandidate, nowMs: Long): Long? {
        val current = displayed
        if (current == null) {
            val pending = pendingCandidate ?: return null
            if (candidate.kind == PillBadgeKind.NONE) return null
            val remaining = entryDebounceMsFor(pending.kind) - (nowMs - pendingSinceMs)
            return remaining.coerceAtLeast(16L)
        }

        if (candidate.kind == PillBadgeKind.NONE) {
            val remainDwell = minVisibleDwellMs - (nowMs - displayedSinceMs)
            val remainExit = if (healthySinceMs > 0L) exitDebounceMs - (nowMs - healthySinceMs) else exitDebounceMs
            val wait = maxOf(remainDwell, remainExit)
            return if (wait > 0L) wait.coerceAtLeast(16L) else null
        }

        val pending = pendingCandidate ?: return null
        val remainDwell = minVisibleDwellMs - (nowMs - displayedSinceMs)
        val remainEntry = entryDebounceMsFor(pending.kind) - (nowMs - pendingSinceMs)
        val wait = maxOf(remainDwell, remainEntry)
        return if (wait > 0L) wait.coerceAtLeast(16L) else null
    }

    private fun entryDebounceMsFor(kind: PillBadgeKind): Long = when (kind) {
        PillBadgeKind.RATE_LIMITED, PillBadgeKind.OFFLINE -> severeEntryDebounceMs
        PillBadgeKind.CACHED, PillBadgeKind.STALE -> minorEntryDebounceMs
        PillBadgeKind.NONE -> 0L
    }
}

object DataAcquisitionManager {
    private data class SourceStatus(
        val status: DataAcquisitionStatus,
        val detailMessage: String? = null,
        val updatedElapsedMs: Long = 0L,
        val cooldownUntilElapsedMs: Long = 0L
    )

    private val _health = MutableStateFlow(DataAcquisitionHealth())
    val health: StateFlow<DataAcquisitionHealth> = _health.asStateFlow()

    private val sourceStates = EnumMap<DataSourceKind, SourceStatus>(DataSourceKind::class.java)

    private fun safeElapsedMs(): Long = try {
        android.os.SystemClock.elapsedRealtime()
    } catch (_: Throwable) {
        System.currentTimeMillis()
    }

    @Synchronized
    fun resetForTest() {
        sourceStates.clear()
        _health.value = DataAcquisitionHealth(
            status = DataAcquisitionStatus.HEALTHY,
            detailMessage = null,
            lastUpdatedElapsedMs = safeElapsedMs(),
            cooldownRemainingSec = 0,
            failingSources = emptySet()
        )
    }

    @Synchronized
    fun reportSuccess(source: DataSourceKind = DataSourceKind.GENERAL) {
        val now = safeElapsedMs()
        if (source == DataSourceKind.GENERAL) {
            sourceStates.clear()
        } else {
            sourceStates.remove(source)
            // Recover unattributed GENERAL errors when an outdoor/network source confirms connectivity.
            sourceStates.remove(DataSourceKind.GENERAL)
        }
        recomputeLocked(now)
    }

    @Synchronized
    fun reportRateLimited(cooldownSec: Int = 30, source: DataSourceKind = DataSourceKind.GENERAL) {
        val now = safeElapsedMs()
        val safeSec = cooldownSec.coerceAtLeast(1)
        sourceStates[source] = SourceStatus(
            status = DataAcquisitionStatus.RATE_LIMITED_429,
            detailMessage = "OSM Rate Limit (Cooldown)",
            updatedElapsedMs = now,
            cooldownUntilElapsedMs = now + safeSec * 1000L
        )
        recomputeLocked(now)
    }

    @Synchronized
    fun reportError(error: Throwable?, source: DataSourceKind = DataSourceKind.GENERAL) {
        val now = safeElapsedMs()
        val status = when (error) {
            is UnknownHostException -> DataAcquisitionStatus.OFFLINE
            is SocketTimeoutException -> DataAcquisitionStatus.CACHED_ONLY
            else -> DataAcquisitionStatus.CACHED_ONLY
        }
        val msg = when (status) {
            DataAcquisitionStatus.OFFLINE -> "No Internet Connection"
            DataAcquisitionStatus.CACHED_ONLY -> if (error is SocketTimeoutException) "Connection Timeout • Cached Mode" else "Network Issue • Cached Mode"
            else -> null
        }
        sourceStates[source] = SourceStatus(
            status = status,
            detailMessage = msg,
            updatedElapsedMs = now,
            cooldownUntilElapsedMs = 0L
        )
        recomputeLocked(now)
    }

    @Synchronized
    fun reportCachedOnly(reason: String? = null, source: DataSourceKind = DataSourceKind.GENERAL) {
        val now = safeElapsedMs()
        pruneExpiredLocked(now)
        val existing = sourceStates[source]
        if (existing?.status == DataAcquisitionStatus.RATE_LIMITED_429) {
            return
        }
        if (source == DataSourceKind.GENERAL && _health.value.status == DataAcquisitionStatus.RATE_LIMITED_429) {
            return
        }
        sourceStates[source] = SourceStatus(
            status = DataAcquisitionStatus.CACHED_ONLY,
            detailMessage = reason ?: "Cached Fallback Mode",
            updatedElapsedMs = now,
            cooldownUntilElapsedMs = 0L
        )
        recomputeLocked(now)
    }

    @Synchronized
    fun isGeocoderRateLimited(): Boolean {
        val now = safeElapsedMs()
        val before = sourceStates[DataSourceKind.GEOCODER]
        pruneExpiredLocked(now)
        val active = sourceStates[DataSourceKind.GEOCODER]?.status == DataAcquisitionStatus.RATE_LIMITED_429
        if (before != null && !active) {
            recomputeLocked(now)
        }
        return active
    }

    private fun pruneExpiredLocked(nowMs: Long) {
        val it = sourceStates.entries.iterator()
        while (it.hasNext()) {
            val st = it.next().value
            if (st.status == DataAcquisitionStatus.RATE_LIMITED_429 &&
                st.cooldownUntilElapsedMs > 0L &&
                nowMs >= st.cooldownUntilElapsedMs
            ) {
                it.remove()
            }
        }
    }

    private fun recomputeLocked(nowMs: Long) {
        pruneExpiredLocked(nowMs)
        if (sourceStates.isEmpty()) {
            if (_health.value.status != DataAcquisitionStatus.HEALTHY || _health.value.failingSources.isNotEmpty()) {
                _health.value = DataAcquisitionHealth(
                    status = DataAcquisitionStatus.HEALTHY,
                    detailMessage = null,
                    lastUpdatedElapsedMs = nowMs,
                    cooldownRemainingSec = 0,
                    failingSources = emptySet()
                )
            }
            return
        }

        val failing = sourceStates.keys.toSet()

        val rateLimited = sourceStates.values
            .filter { it.status == DataAcquisitionStatus.RATE_LIMITED_429 }
            .maxByOrNull { it.cooldownUntilElapsedMs }
        if (rateLimited != null) {
            val remSec = (((rateLimited.cooldownUntilElapsedMs - nowMs) + 999L) / 1000L)
                .toInt()
                .coerceAtLeast(1)
            _health.value = DataAcquisitionHealth(
                status = DataAcquisitionStatus.RATE_LIMITED_429,
                detailMessage = rateLimited.detailMessage ?: "OSM Rate Limit (Cooldown)",
                lastUpdatedElapsedMs = nowMs,
                cooldownRemainingSec = remSec,
                failingSources = failing
            )
            return
        }

        val offline = sourceStates[DataSourceKind.GEOCODER]
            ?.takeIf { it.status == DataAcquisitionStatus.OFFLINE }
            ?: sourceStates.values.firstOrNull { it.status == DataAcquisitionStatus.OFFLINE }
        if (offline != null) {
            _health.value = DataAcquisitionHealth(
                status = DataAcquisitionStatus.OFFLINE,
                detailMessage = offline.detailMessage ?: "No Internet Connection",
                lastUpdatedElapsedMs = nowMs,
                cooldownRemainingSec = 0,
                failingSources = failing
            )
            return
        }

        val cached = sourceStates[DataSourceKind.GEOCODER]
            ?.takeIf { it.status == DataAcquisitionStatus.CACHED_ONLY }
            ?: sourceStates.values.firstOrNull { it.status == DataAcquisitionStatus.CACHED_ONLY }
        if (cached != null) {
            _health.value = DataAcquisitionHealth(
                status = DataAcquisitionStatus.CACHED_ONLY,
                detailMessage = cached.detailMessage ?: "Cached Fallback Mode",
                lastUpdatedElapsedMs = nowMs,
                cooldownRemainingSec = 0,
                failingSources = failing
            )
        }
    }
}
