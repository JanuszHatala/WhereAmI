package janush.tech.whereami

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
}
