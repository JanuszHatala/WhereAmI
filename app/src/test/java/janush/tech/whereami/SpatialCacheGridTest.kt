package janush.tech.whereami

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SpatialCacheGridTest {

    @Test
    fun testCloseCoordinatesMapToSameGridKey() {
        // Two points separated by ~20 meters
        val lat1 = 49.82231
        val lon1 = 19.24512
        val lat2 = 49.82239
        val lon2 = 19.24518

        val key1 = SpatialCacheHelper.toGridKey(lat1, lon1)
        val key2 = SpatialCacheHelper.toGridKey(lat2, lon2)

        assertEquals("Close points within 100m grid cell must yield identical grid key", key1, key2)
        assertEquals("49.822_19.245", key1)
    }

    @Test
    fun testDistantCoordinatesMapToDifferentGridKey() {
        val lat1 = 49.82200
        val lon1 = 19.24500
        val lat2 = 49.82600
        val lon2 = 19.25000

        val key1 = SpatialCacheHelper.toGridKey(lat1, lon1)
        val key2 = SpatialCacheHelper.toGridKey(lat2, lon2)

        assertNotEquals("Distant points must yield different grid keys", key1, key2)
    }
}
