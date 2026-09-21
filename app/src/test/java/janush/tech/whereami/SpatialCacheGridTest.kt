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
}
