package janush.tech.whereami

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class OpticalCenteringTest {

    private val targetLat = 49.8225
    private val targetLon = 19.2450
    private val zoom = 16.0
    private val offsetPixelsY = 200

    @Test
    fun testZeroOffsetReturnsIdenticalCoordinates() {
        val result = calculateOpticalCenter(
            lat = targetLat,
            lon = targetLon,
            zoom = zoom,
            mapOrientation = 0f,
            offsetPixelsY = 0
        )
        assertEquals(targetLat, result.latitude, 0.000001)
        assertEquals(targetLon, result.longitude, 0.000001)
    }

    @Test
    fun testNorthUpOrientationShiftsGeographicallyNorth() {
        val result = calculateOpticalCenter(
            lat = targetLat,
            lon = targetLon,
            zoom = zoom,
            mapOrientation = 0f, // North-Up: Screen UP is Geographic North (bearing 0°)
            offsetPixelsY = offsetPixelsY
        )
        // Shifting camera north causes target to appear in lower half of screen
        assertTrue("Camera latitude must be north of target", result.latitude > targetLat)
        assertEquals("Longitude must remain unchanged for due north shift", targetLon, result.longitude, 0.00001)
    }

    @Test
    fun testSouthUpOrientationShiftsGeographicallySouth() {
        val result = calculateOpticalCenter(
            lat = targetLat,
            lon = targetLon,
            zoom = zoom,
            mapOrientation = 180f, // South-Up: Screen UP is Geographic South (bearing 180°)
            offsetPixelsY = offsetPixelsY
        )
        assertTrue("Camera latitude must be south of target", result.latitude < targetLat)
        assertEquals("Longitude must remain unchanged for due south shift", targetLon, result.longitude, 0.00001)
    }

    @Test
    fun testWestUpOrientationShiftsGeographicallyWest() {
        val result = calculateOpticalCenter(
            lat = targetLat,
            lon = targetLon,
            zoom = zoom,
            mapOrientation = 90f, // West-Up (canvas rotated 90° CW): Screen UP is Geographic West (bearing 270°)
            offsetPixelsY = offsetPixelsY
        )
        assertEquals("Latitude must remain unchanged for due west shift", targetLat, result.latitude, 0.00001)
        assertTrue("Camera longitude must be west of target", result.longitude < targetLon)
    }

    @Test
    fun testEastUpOrientationShiftsGeographicallyEast() {
        val result = calculateOpticalCenter(
            lat = targetLat,
            lon = targetLon,
            zoom = zoom,
            mapOrientation = 270f, // East-Up (canvas rotated 270° CW): Screen UP is Geographic East (bearing 90°)
            offsetPixelsY = offsetPixelsY
        )
        assertEquals("Latitude must remain unchanged for due east shift", targetLat, result.latitude, 0.00001)
        assertTrue("Camera longitude must be east of target", result.longitude > targetLon)
    }

    @Test
    fun testDistanceInvariantUnderAllRotations() {
        val metersPerPixel = (156543.03392 * cos(Math.toRadians(targetLat))) / Math.pow(2.0, zoom)
        val expectedDistMeters = offsetPixelsY * metersPerPixel

        val angles = listOf(0f, 30f, 45f, 90f, 135f, 180f, 225f, 270f, 315f)
        for (angle in angles) {
            val result = calculateOpticalCenter(
                lat = targetLat,
                lon = targetLon,
                zoom = zoom,
                mapOrientation = angle,
                offsetPixelsY = offsetPixelsY
            )

            // Haversine distance from target to center
            val rEarth = 6378137.0
            val dLat = Math.toRadians(result.latitude - targetLat)
            val dLon = Math.toRadians(result.longitude - targetLon)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                    cos(Math.toRadians(targetLat)) * cos(Math.toRadians(result.latitude)) *
                    sin(dLon / 2) * sin(dLon / 2)
            val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
            val dist = rEarth * c

            assertEquals("Displacement distance must equal projected ground distance for angle ",
                expectedDistMeters, dist, 0.1)
        }
    }
}
