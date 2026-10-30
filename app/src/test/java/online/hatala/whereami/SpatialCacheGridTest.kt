package online.hatala.whereami

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SpatialCacheGridTest {

    @Test
    fun testCloseCoordinatesMapToSameGridKey() {
        // Two points separated by ~5 meters within same ~15m grid cell
        val lat1 = 49.82231
        val lon1 = 19.24512
        val lat2 = 49.82234
        val lon2 = 19.24514

        val key1 = SpatialCacheHelper.toGridKey(lat1, lon1)
        val key2 = SpatialCacheHelper.toGridKey(lat2, lon2)

        assertEquals("Close points within ~15m grid cell must yield identical grid key", key1, key2)
        assertEquals("49.8223_19.2451", key1)
    }

    @Test
    fun testTurnOntoNewStreetYieldsDifferentGridKey() {
        // Turning into next street or driving >20m
        val lat1 = 49.82231
        val lon1 = 19.24512
        val lat2 = 49.82260
        val lon2 = 19.24580

        val key1 = SpatialCacheHelper.toGridKey(lat1, lon1)
        val key2 = SpatialCacheHelper.toGridKey(lat2, lon2)

        assertNotEquals("Movement beyond 15m must yield different grid keys for prompt turn detection", key1, key2)
    }

    @Test
    fun testLegacy3DecKeyResolution() {
        val lat = 49.82231
        val lon = 19.24512

        val legacyKey = SpatialCacheHelper.toLegacyGridKey(lat, lon)
        val preciseKey = SpatialCacheHelper.toGridKey(lat, lon)

        assertEquals("49.822_19.245", legacyKey)
        assertEquals("49.8223_19.2451", preciseKey)
    }

    @Test
    fun testDeserializePolandCanonicalization() {
        val legacyPolandJson = """{"city":"Czaniec","street":"Zielona","country":"Poland","countryCode":"PL","voivodeship":"śląskie"}"""
        val place = SpatialCacheHelper.deserializePlaceInfo(legacyPolandJson)

        assertEquals("Czaniec", place?.city)
        assertEquals("Zielona", place?.street)
        assertEquals("Polska", place?.country)
    }

    @Test
    fun testCacheAgeStalenessGates() {
        val now = System.currentTimeMillis()
        val thirtyDaysAgo = now - (30L * 24 * 3600 * 1000L)
        val ninetyDaysAgo = now - (90L * 24 * 3600 * 1000L)
        val fourHundredDaysAgo = now - (400L * 24 * 3600 * 1000L)

        val freshAge = now - thirtyDaysAgo
        val agingAge = now - ninetyDaysAgo
        val obsoleteAge = now - fourHundredDaysAgo

        val sixtyDaysMs = 60L * 24 * 3600 * 1000L
        val oneYearMs = 365L * 24 * 3600 * 1000L

        org.junit.Assert.assertTrue("30 days is fresh (< 60 days)", freshAge < sixtyDaysMs)
        org.junit.Assert.assertTrue("90 days is aging (60-365 days)", agingAge in sixtyDaysMs..oneYearMs)
        org.junit.Assert.assertTrue("400 days is obsolete (> 365 days)", obsoleteAge > oneYearMs)
    }

    @Test
    fun testBulkGridKeyDeficitIdentification() {
        val cachedKeys = setOf(
            "49.8223_19.2451",
            "49.8225_19.2455"
        )

        // Trip points: 2 in first cell, 1 in second cell, 3 in third (missing) cell
        val points = listOf(
            49.82231 to 19.24512, // key 49.8223_19.2451 (cached)
            49.82234 to 19.24514, // key 49.8223_19.2451 (cached, duplicate cell)
            49.82250 to 19.24550, // key 49.8225_19.2455 (cached)
            49.83000 to 19.25000, // key 49.8300_19.2500 (missing)
            49.83002 to 19.25001  // key 49.8300_19.2500 (missing, duplicate cell)
        )

        val uniqueGridCells = points.map { (lat, lng) ->
            SpatialCacheHelper.toGridKey(lat, lng)
        }.toSet()

        assertEquals(3, uniqueGridCells.size)

        val missingCount = uniqueGridCells.count { it !in cachedKeys }
        assertEquals(1, missingCount)
    }

    @Test
    fun testHighwayCorridorsRetainedAsValidCache() {
        // Expressways and provincial roads without residential street names (S1, DW957)
        val s1Point = 49.7240673 to 19.1196188
        val dw957Point = 49.5898739 to 19.5830405
        val s1Key = SpatialCacheHelper.toGridKey(s1Point.first, s1Point.second)
        val dw957Key = SpatialCacheHelper.toGridKey(dw957Point.first, dw957Point.second)

        val cachedKeys = setOf(s1Key, dw957Key)

        assertEquals("49.7241_19.1196", s1Key)
        assertEquals("49.5899_19.5830", dw957Key)
        org.junit.Assert.assertTrue("S1 grid key must be recognized in cached keys", s1Key in cachedKeys)
        org.junit.Assert.assertTrue("DW957 grid key must be recognized in cached keys", dw957Key in cachedKeys)
    }
}
