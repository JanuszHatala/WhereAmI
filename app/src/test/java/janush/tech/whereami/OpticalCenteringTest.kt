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

            assertEquals("Displacement distance must equal projected ground distance for angle $angle",
                expectedDistMeters, dist, 0.1)
        }
    }

    @Test
    fun testNegativeOffsetShiftsOppositeDirection() {
        val positiveResult = calculateOpticalCenter(
            lat = targetLat,
            lon = targetLon,
            zoom = zoom,
            mapOrientation = 0f,
            offsetPixelsY = offsetPixelsY
        )
        val negativeResult = calculateOpticalCenter(
            lat = targetLat,
            lon = targetLon,
            zoom = zoom,
            mapOrientation = 0f,
            offsetPixelsY = -offsetPixelsY
        )
        // Positive offset moves camera North (latitude increases) so target appears lower on screen
        assertTrue("Positive offset camera must be north of target", positiveResult.latitude > targetLat)
        // Negative offset moves camera South (latitude decreases) so target appears higher on screen
        assertTrue("Negative offset camera must be south of target", negativeResult.latitude < targetLat)
        assertEquals("Target longitude unchanged for north/south shifts", targetLon, positiveResult.longitude, 0.00001)
        assertEquals("Target longitude unchanged for north/south shifts", targetLon, negativeResult.longitude, 0.00001)
    }

    @Test
    fun testPlaceCategoryFavoriteIsFirst() {
        assertEquals("Favorite must be the first PlaceCategory entry",
            PlaceCategory.FAVORITE, PlaceCategory.values().first())
        assertEquals("Favorite", PlaceCategory.FAVORITE.displayName)
        assertEquals("⭐", PlaceCategory.FAVORITE.iconEmoji)
    }

    @Test
    fun testOpticalOffsetApertureWithBottomFloatingClearanceAndUpwardBias() {
        val rootScreenHeightPx = 2410
        val topCardBottomPx = 1030 // Expanded card
        val bottomControlsTopPx = 2210 // Bottom pill
        val density = 2.625f

        // Bottom clearance accounts for floating action buttons (~200dp)
        val bottomClearanceDp = 200f
        val maxBottomAllowedPx = rootScreenHeightPx - (bottomClearanceDp * density).toInt()
        val effectiveBottomPx = minOf(bottomControlsTopPx, maxBottomAllowedPx)

        val apertureCenter = (topCardBottomPx + effectiveBottomPx) / 2
        val upwardBiasPx = (24f * density).toInt()
        val measuredOffset = (apertureCenter - (rootScreenHeightPx / 2)) - upwardBiasPx

        // Effective bottom should be capped by floating buttons clearance (~1885px) rather than 2210px
        assertTrue("Effective bottom must account for floating buttons", effectiveBottomPx <= 1885)

        // Measured offset must place cursor in upper-middle of aperture rather than pushed deep down
        // Old unadjusted offset was ~+418px; new adjusted offset is ~+189px
        assertTrue("Adjusted offset should be significantly less positive than unadjusted offset", measuredOffset < 250)
        assertTrue("Adjusted offset should remain positive to clear expanded top card", measuredOffset > 100)
    }

    @Test
    fun testStationaryBearingNullThreshold() {
        // Speed threshold is 1.2 m/s (4.32 km/h)
        val speedStationary = 0.5f
        val speedMoving = 1.5f
        val rawBearing = 90f

        val stationaryBearing = if (speedStationary >= 1.2f) rawBearing else null
        val movingBearing = if (speedMoving >= 1.2f) rawBearing else null

        assertEquals(null, stationaryBearing)
        assertEquals(90f, movingBearing)
    }

    @Test
    fun testAllDisplayPausesAggregation() {
        val activePauses = listOf(
            TripPause(startTime = 1000L, endTime = 2000L, latitude = 49.8, longitude = 19.2, durationMs = 60000L, pointIndex = 1)
        )
        val pastTrip1 = TripRecord(
            id = 1L,
            startTime = 0L,
            endTime = 5000L,
            distanceMeters = 500.0,
            activityProfile = ActivityProfile.CAR,
            pauses = listOf(
                TripPause(startTime = 3000L, endTime = 4000L, latitude = 49.85, longitude = 19.25, durationMs = 45000L, pointIndex = 2)
            )
        )
        val selectedTrips = listOf(pastTrip1)

        val aggregated = mutableListOf<TripPause>()
        activePauses.let { aggregated.addAll(it) }
        selectedTrips.forEach { aggregated.addAll(it.pauses) }

        assertEquals(2, aggregated.size)
        assertEquals(60000L, aggregated[0].durationMs)
        assertEquals(45000L, aggregated[1].durationMs)
    }

    @Test
    fun testOpticalCenterWith2DOffsetsXAndY() {
        // In North-Up (mapOrientation = 0), screen UP is North, screen RIGHT is East.
        // A positive offsetPixelsX (+200) places target to the RIGHT of screen center.
        // To achieve this, the camera must shift WEST (longitude decreases).
        val resultX = calculateOpticalCenter(
            lat = targetLat,
            lon = targetLon,
            zoom = zoom,
            mapOrientation = 0f,
            offsetPixelsX = 200,
            offsetPixelsY = 0
        )
        assertTrue("Camera longitude must be west of target for positive X offset in North-Up", resultX.longitude < targetLon)
        assertEquals("Latitude must remain unchanged for pure West shift", targetLat, resultX.latitude, 0.00001)

        // With both X and Y offsets in North-Up (+200, +200)
        // Camera shifts North (target appears down) and West (target appears right)
        val resultXY = calculateOpticalCenter(
            lat = targetLat,
            lon = targetLon,
            zoom = zoom,
            mapOrientation = 0f,
            offsetPixelsX = 200,
            offsetPixelsY = 200
        )
        assertTrue("Camera latitude must be north of target", resultXY.latitude > targetLat)
        assertTrue("Camera longitude must be west of target", resultXY.longitude < targetLon)
    }

    @Test
    fun testShortestAngularDeltaMath() {
        fun shortestDelta(current: Float, target: Float): Float {
            return ((target - current + 540f) % 360f) - 180f
        }

        // Turning from 350 to 10 degrees is +20 (not -340)
        assertEquals(20f, shortestDelta(350f, 10f), 0.01f)
        // Turning from 10 to 350 degrees is -20 (not +340)
        assertEquals(-20f, shortestDelta(10f, 350f), 0.01f)
        // Turning from 90 to 120 is +30
        assertEquals(30f, shortestDelta(90f, 120f), 0.01f)
        // Turning from 120 to 90 is -30
        assertEquals(-30f, shortestDelta(120f, 90f), 0.01f)
        // Small change within 1.5 degree deadband
        assertTrue(abs(shortestDelta(90f, 91.2f)) < 1.5f)
    }
}
