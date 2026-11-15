package online.hatala.whereami

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.UnknownHostException

class DataAcquisitionAndDemResilienceTest {

    @Before
    fun setUp() {
        DataAcquisitionManager.resetForTest()
    }

    @After
    fun tearDown() {
        DataAcquisitionManager.resetForTest()
    }

    @Test
    fun `DataAcquisitionManager isolates per-source state so unrelated 200 cannot clear Nominatim 429`() {
        DataAcquisitionManager.reportRateLimited(30, DataSourceKind.GEOCODER)
        assertEquals(DataAcquisitionStatus.RATE_LIMITED_429, DataAcquisitionManager.health.value.status)
        assertTrue(DataAcquisitionManager.isGeocoderRateLimited())

        // Waymarked / OSRM / Overpass recovering must NEVER clear GEOCODER rate limit
        DataAcquisitionManager.reportSuccess(DataSourceKind.TRAILS)
        DataAcquisitionManager.reportSuccess(DataSourceKind.ROUTING)
        DataAcquisitionManager.reportSuccess(DataSourceKind.OUTDOOR_OVERPASS)
        DataAcquisitionManager.reportSuccess(DataSourceKind.BOUNDARIES)

        assertEquals(DataAcquisitionStatus.RATE_LIMITED_429, DataAcquisitionManager.health.value.status)
        assertTrue(DataAcquisitionManager.isGeocoderRateLimited())
        assertTrue(DataSourceKind.GEOCODER in DataAcquisitionManager.health.value.failingSources)

        // Only GEOCODER success clears GEOCODER state
        DataAcquisitionManager.reportSuccess(DataSourceKind.GEOCODER)
        assertEquals(DataAcquisitionStatus.HEALTHY, DataAcquisitionManager.health.value.status)
        assertFalse(DataAcquisitionManager.isGeocoderRateLimited())
    }

    @Test
    fun `DataAcquisitionManager ranks severity RATE_LIMITED_429 over OFFLINE over CACHED_ONLY`() {
        DataAcquisitionManager.reportCachedOnly("Stale tile", DataSourceKind.TRAILS)
        assertEquals(DataAcquisitionStatus.CACHED_ONLY, DataAcquisitionManager.health.value.status)

        DataAcquisitionManager.reportError(UnknownHostException("offline"), DataSourceKind.ROUTING)
        assertEquals(DataAcquisitionStatus.OFFLINE, DataAcquisitionManager.health.value.status)

        DataAcquisitionManager.reportRateLimited(30, DataSourceKind.GEOCODER)
        assertEquals(DataAcquisitionStatus.RATE_LIMITED_429, DataAcquisitionManager.health.value.status)

        // Recovering GEOCODER drops aggregate down to remaining OFFLINE (ROUTING), not HEALTHY
        DataAcquisitionManager.reportSuccess(DataSourceKind.GEOCODER)
        assertEquals(DataAcquisitionStatus.OFFLINE, DataAcquisitionManager.health.value.status)

        DataAcquisitionManager.reportSuccess(DataSourceKind.ROUTING)
        assertEquals(DataAcquisitionStatus.CACHED_ONLY, DataAcquisitionManager.health.value.status)

        DataAcquisitionManager.reportSuccess(DataSourceKind.TRAILS)
        assertEquals(DataAcquisitionStatus.HEALTHY, DataAcquisitionManager.health.value.status)
    }

    @Test
    fun `AcquisitionPillStabilizer debounces transient blips and enforces 4s min dwell`() {
        val stabilizer = AcquisitionPillStabilizer(
            severeEntryDebounceMs = 500L,
            minorEntryDebounceMs = 1500L,
            minVisibleDwellMs = 4000L,
            exitDebounceMs = 1500L
        )
        val cached = AcquisitionPillCandidate(PillBadgeKind.CACHED, "Cache Only")
        val healthy = AcquisitionPillCandidate(PillBadgeKind.NONE)

        // Transient CACHED blip (< 1500 ms) must never appear on screen
        assertNull(stabilizer.update(cached, 1000L))
        assertNull(stabilizer.update(cached, 2000L))
        assertNull(stabilizer.update(healthy, 2200L))

        // Sustained CACHED (>= 1500 ms) enters and remains held for at least 4000 ms dwell
        assertNull(stabilizer.update(cached, 3000L))
        assertEquals(PillBadgeKind.CACHED, stabilizer.update(cached, 4600L)?.kind)

        // Immediate recovery at +500 ms must keep badge visible until minVisibleDwellMs (4600 + 4000 = 8600)
        assertEquals(PillBadgeKind.CACHED, stabilizer.update(healthy, 5100L)?.kind)
        assertEquals(PillBadgeKind.CACHED, stabilizer.update(healthy, 8500L)?.kind)
        assertNull(stabilizer.update(healthy, 8600L))
    }

    @Test
    fun `AcquisitionPillStabilizer suppresses severe blip under 500ms and clears only after dwell plus exit debounce`() {
        val stabilizer = AcquisitionPillStabilizer(
            severeEntryDebounceMs = 500L,
            minorEntryDebounceMs = 1500L,
            minVisibleDwellMs = 4000L,
            exitDebounceMs = 1500L
        )
        val offline = AcquisitionPillCandidate(PillBadgeKind.OFFLINE, "Offline")
        val healthy = AcquisitionPillCandidate(PillBadgeKind.NONE)

        assertNull(stabilizer.update(offline, 1000L))
        assertNull(stabilizer.update(offline, 1400L))
        assertNull(stabilizer.update(healthy, 1450L))

        // Severe state held for 500 ms displays
        assertNull(stabilizer.update(offline, 2000L))
        assertEquals(PillBadgeKind.OFFLINE, stabilizer.update(offline, 2500L)?.kind)

        // Healthy begins only at t=6000 ms (dwell is met at 6500 ms, exit debounce at 6000 + 1500 = 7500 ms)
        assertEquals(PillBadgeKind.OFFLINE, stabilizer.update(healthy, 6000L)?.kind)
        assertEquals(PillBadgeKind.OFFLINE, stabilizer.update(healthy, 7400L)?.kind)
        assertNull(stabilizer.update(healthy, 7600L))
    }

    @Test
    fun `CacheManager shouldAutoPausePrefetch pauses during active recording or Nominatim 429`() {
        assertFalse(CacheManager.shouldAutoPausePrefetch(isRecording = false, isGeocoderRateLimited = false))
        assertTrue(CacheManager.shouldAutoPausePrefetch(isRecording = true, isGeocoderRateLimited = false))
        assertTrue(CacheManager.shouldAutoPausePrefetch(isRecording = false, isGeocoderRateLimited = true))
        assertTrue(CacheManager.shouldAutoPausePrefetch(isRecording = true, isGeocoderRateLimited = true))
    }

    @Test
    fun `CacheDeficit and MissingDemSummary carry missing DEM counts cleanly`() {
        val summary = TripDatabaseHelper.MissingDemSummary(
            tripCount = 2,
            pointCount = 320,
            incompleteTripIds = listOf(10L, 14L)
        )
        assertEquals(2, summary.tripCount)
        assertEquals(2, summary.incompleteTripIds.size)
        assertEquals(320, summary.pointCount)

        val deficit = CacheManager.CacheDeficit(
            missingRoutes = 12,
            missingBoundaries = 3,
            missingDemTrips = summary.tripCount,
            missingDemPoints = summary.pointCount
        )
        assertEquals(2, deficit.missingDemTrips)
        assertEquals(320, deficit.missingDemPoints)
    }
}
