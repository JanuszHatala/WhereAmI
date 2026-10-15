package janush.tech.whereami

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.osmdroid.util.GeoPoint

class ElevationSourceAndFallbackTest {

    @Test
    fun `pointsToJson and jsonToPointsWithSources preserves DEM and GPS sources`() {
        val points = listOf(
            GeoPoint(50.0, 19.0, 318.0),
            GeoPoint(50.001, 19.001, 330.0),
            GeoPoint(50.002, 19.002, 350.0)
        )
        val sources = listOf("DEM", "GPS", "DEM")

        val jsonStr = TripDatabaseHelper.pointsToJson(points, sources)
        assertTrue(jsonStr.contains("\"src\":\"DEM\""))
        assertTrue(jsonStr.contains("\"src\":\"GPS\""))

        val (parsedPoints, parsedSources) = TripDatabaseHelper.jsonToPointsWithSources(jsonStr)
        assertEquals(3, parsedPoints.size)
        assertEquals(3, parsedSources.size)
        assertEquals("DEM", parsedSources[0])
        assertEquals("GPS", parsedSources[1])
        assertEquals("DEM", parsedSources[2])
        assertEquals(318.0, parsedPoints[0].altitude, 0.001)
        assertEquals(330.0, parsedPoints[1].altitude, 0.001)
        assertEquals(350.0, parsedPoints[2].altitude, 0.001)
    }

    @Test
    fun `legacy points JSON without src defaults to GPS in jsonToPointsWithSources`() {
        val legacyJson = """[{"lat":50.0,"lng":19.0,"alt":318.0},{"lat":50.001,"lng":19.001,"alt":330.0}]"""
        val (parsedPoints, parsedSources) = TripDatabaseHelper.jsonToPointsWithSources(legacyJson)
        assertEquals(2, parsedPoints.size)
        assertEquals(2, parsedSources.size)
        assertEquals("GPS", parsedSources[0])
        assertEquals("GPS", parsedSources[1])
        assertEquals(318.0, parsedPoints[0].altitude, 0.001)
        assertEquals(330.0, parsedPoints[1].altitude, 0.001)
    }

    @Test
    fun `SavedPlace preserves altitudeSource property`() {
        val placeDem = SavedPlace(
            id = 1L,
            name = "Timebook",
            category = PlaceCategory.WORK,
            latitude = 49.816,
            longitude = 19.038,
            altitude = 318.0,
            altitudeSource = "DEM"
        )
        assertEquals("DEM", placeDem.altitudeSource)
        assertEquals(318.0, placeDem.altitude)

        val placeGps = SavedPlace(
            id = 2L,
            name = "Home",
            category = PlaceCategory.HOME,
            latitude = 49.850,
            longitude = 19.120,
            altitude = 330.0,
            altitudeSource = "GPS"
        )
        assertEquals("GPS", placeGps.altitudeSource)

        val placeNull = SavedPlace(
            id = 3L,
            name = "Unknown Spot",
            category = PlaceCategory.FAVORITE,
            latitude = 50.0,
            longitude = 20.0
        )
        assertNull(placeNull.altitudeSource)
        assertNull(placeNull.altitude)
    }

    @Test
    fun `AltitudeSourceMode enum has DEM as default and GPS as fallback mode`() {
        assertEquals(2, AltitudeSourceMode.values().size)
        assertEquals(AltitudeSourceMode.DEM, AltitudeSourceMode.valueOf("DEM"))
        assertEquals(AltitudeSourceMode.GPS, AltitudeSourceMode.valueOf("GPS"))
    }

    @Test
    fun `MapFontScale scale factors scale altitude badge proportions`() {
        assertTrue(MapFontScale.COMPACT.scaleFactor < MapFontScale.NORMAL.scaleFactor)
        assertEquals(1.0f, MapFontScale.NORMAL.scaleFactor, 0.001f)
        assertTrue(MapFontScale.LARGE.scaleFactor > MapFontScale.NORMAL.scaleFactor)
        assertTrue(MapFontScale.EXTRA_LARGE.scaleFactor > MapFontScale.LARGE.scaleFactor)
        assertTrue(MapFontScale.MAXIMUM.scaleFactor > MapFontScale.EXTRA_LARGE.scaleFactor)
    }
}
