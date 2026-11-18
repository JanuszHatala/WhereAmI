package online.hatala.whereami

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.osmdroid.util.GeoPoint
import java.io.File
import java.net.UnknownHostException
import java.nio.file.Files

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

    @Test
    fun `CacheManager filterMissingBoundaryTargetsInDir respects municipality key parity with deficit count`() {
        val dir = Files.createTempDirectory("boundary_target_parity").toFile()
        try {
            val poly = listOf(
                GeoPoint(47.0, 10.0),
                GeoPoint(47.0, 10.1),
                GeoPoint(47.1, 10.1),
                GeoPoint(47.0, 10.0)
            )
            // Bare city key exists, but municipality-scoped key does NOT yet exist
            val bareOakKey = BoundaryHelper.buildLocalityCacheKey("Oakville", "us", null)!!
            BoundaryHelper.saveToDiskCacheInDir(dir, bareOakKey, poly)

            // Municipality-scoped key exists (must not be treated as missing or re-fetched)
            val riverKey = BoundaryHelper.buildLocalityCacheKey("Riverbend", "at", "North District")!!
            BoundaryHelper.saveToDiskCacheInDir(dir, riverKey, poly)

            // Authoritative empty sentinel satisfies missing-boundary check
            val sentinelKey = BoundaryHelper.buildLocalityCacheKey("BorderTown", "de", "South Shire")!!
            BoundaryHelper.saveToDiskCacheInDir(dir, sentinelKey, emptyList())

            val targets = listOf(
                CacheManager.BoundaryTarget("Oakville", "us", "Valley Township", 47.05, 10.05),
                CacheManager.BoundaryTarget("Riverbend", "at", "North District", 47.05, 10.05),
                CacheManager.BoundaryTarget("BorderTown", "de", "South Shire", 47.05, 10.05)
            )
            val missing = CacheManager.filterMissingBoundaryTargetsInDir(dir, targets)
            assertEquals(listOf(targets[0]), missing)
            assertFalse(BoundaryHelper.hasBoundaryInDir(dir, "Oakville", "us", "Valley Township"))
            assertTrue(BoundaryHelper.hasBoundaryInDir(dir, "Riverbend", "at", "North District"))
            assertTrue(BoundaryHelper.hasBoundaryInDir(dir, "BorderTown", "de", "South Shire"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `BoundaryHelper loadFromDiskCacheInDir preserves identical sibling and legacy polygon files`() {
        val dir = Files.createTempDirectory("boundary_sibling_preserve").toFile()
        try {
            val poly = listOf(
                GeoPoint(48.0, 11.0),
                GeoPoint(48.0, 11.1),
                GeoPoint(48.1, 11.1),
                GeoPoint(48.1, 11.0),
                GeoPoint(48.0, 11.0)
            )
            val bareKey = BoundaryHelper.buildLocalityCacheKey("Hilltop", "at", null)!!
            val munKey = BoundaryHelper.buildLocalityCacheKey("Hilltop", "at", "Hilltop Municipality")!!
            BoundaryHelper.saveToDiskCacheInDir(dir, bareKey, poly)
            BoundaryHelper.saveToDiskCacheInDir(dir, munKey, poly)
            val legacyV3 = File(dir, "hilltop_at_v3.json")
            legacyV3.writeText(File(dir, "$bareKey.json").readText())

            val loadedBare = BoundaryHelper.loadFromDiskCacheInDir(dir, bareKey)
            val loadedMun = BoundaryHelper.loadFromDiskCacheInDir(dir, munKey)

            assertEquals(5, loadedBare?.size)
            assertEquals(5, loadedMun?.size)
            assertTrue(File(dir, "$bareKey.json").exists())
            assertTrue(File(dir, "$munKey.json").exists())
            assertTrue(legacyV3.exists())
            assertTrue(BoundaryHelper.hasBoundaryInDir(dir, "Hilltop", "at", "Hilltop Municipality"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `TripDatabaseHelper estimateElevationCacheBytes reflects row count and key storage accurately`() {
        assertEquals(0L, TripDatabaseHelper.estimateElevationCacheBytes(rowCount = 0, keyBytes = 0L))
        assertEquals(0L, TripDatabaseHelper.estimateElevationCacheBytes(rowCount = -1, keyBytes = 100L))
        // 10,000 coordinate grid keys (~15 bytes each) stored in table + primary key B-tree (~740 KB total)
        val estimated = TripDatabaseHelper.estimateElevationCacheBytes(rowCount = 10_000, keyBytes = 150_000L)
        assertEquals(740_000L, estimated)
    }

    @Test
    fun `BoundaryHelper expires stale empty sentinel after TTL so missing city borders retry`() {
        val dir = Files.createTempDirectory("boundary_negative_ttl").toFile()
        try {
            val staleKey = BoundaryHelper.buildLocalityCacheKey("Ośno", "pl", "Gmina Wiązów")!!
            BoundaryHelper.saveToDiskCacheInDir(dir, staleKey, emptyList())
            val sentinelFile = File(dir, "$staleKey.json")
            assertTrue(BoundaryHelper.hasBoundaryInDir(dir, "Ośno", "pl", "Gmina Wiązów"))

            // Simulate 7-hour-old empty sentinel (> 6h NEGATIVE_CACHE_TTL_MS)
            sentinelFile.setLastModified(System.currentTimeMillis() - (7L * 60L * 60L * 1000L))
            assertFalse(
                "Stale negative sentinel must expire so border can be fetched",
                BoundaryHelper.hasBoundaryInDir(dir, "Ośno", "pl", "Gmina Wiązów")
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `BoundaryHelper respects CacheManager 30-day negative TTL while live map uses 6h`() {
        val dir = Files.createTempDirectory("boundary_cache_mgr_ttl").toFile()
        try {
            val key = BoundaryHelper.buildLocalityCacheKey("Ośno", "pl", "Gmina Wiązów")!!
            BoundaryHelper.saveToDiskCacheInDir(dir, key, emptyList())
            val sentinelFile = File(dir, "$key.json")

            // Simulate 24-hour-old empty sentinel (> 6h live TTL, but < 30-day CacheManager TTL)
            sentinelFile.setLastModified(System.currentTimeMillis() - (24L * 60L * 60L * 1000L))

            // Live map: expired after 6h so boundary can retry in background
            assertFalse(
                "Live map should retry after 6 hours",
                BoundaryHelper.hasBoundaryInDir(dir, "Ośno", "pl", "Gmina Wiązów", negativeTtlMs = BoundaryHelper.NEGATIVE_CACHE_TTL_MS)
            )

            // CacheManager: recognized as valid negative cache for 30 days, avoiding perpetual deficit alarm
            assertTrue(
                "CacheManager should recognize negative cache up to 30 days",
                BoundaryHelper.hasBoundaryInDir(dir, "Ośno", "pl", "Gmina Wiązów", negativeTtlMs = BoundaryHelper.CACHE_MANAGER_NEGATIVE_TTL_MS)
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `LocationManager resolvePrimaryNativeLocale prefers primary national language over English regional locale`() {
        val plLocale = LocationManager.resolvePrimaryNativeLocale("PL", java.util.Locale.ENGLISH)
        assertEquals("pl", plLocale.language)
        assertEquals("PL", plLocale.country)

        val deLocale = LocationManager.resolvePrimaryNativeLocale("DE", java.util.Locale.ENGLISH)
        assertEquals("de", deLocale.language)
        assertEquals("DE", deLocale.country)
    }
}
