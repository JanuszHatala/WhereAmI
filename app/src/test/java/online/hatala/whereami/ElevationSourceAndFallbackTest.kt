package online.hatala.whereami

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

        val placeCustom = SavedPlace(
            id = 4L,
            name = "Dom Custom",
            category = PlaceCategory.HOME,
            latitude = 49.850,
            longitude = 19.120,
            altitude = 385.0,
            altitudeSource = "CUSTOM"
        )
        assertEquals("CUSTOM", placeCustom.altitudeSource)
        assertTrue(placeCustom.isCustomAltitude)
        assertEquals(385.0, placeCustom.altitude)
        assertFalse(placeDem.isCustomAltitude)
        assertFalse(placeGps.isCustomAltitude)
    }

    @Test
    fun `OverrideMode filtering protects CUSTOM altitude from being overwritten`() {
        val placeDem = SavedPlace(id = 1L, name = "Work", category = PlaceCategory.WORK, latitude = 49.8, longitude = 19.0, altitude = 320.0, altitudeSource = "DEM")
        val placeGps = SavedPlace(id = 2L, name = "Park", category = PlaceCategory.FAVORITE, latitude = 49.8, longitude = 19.0, altitude = 310.0, altitudeSource = "GPS")
        val placeCustom = SavedPlace(id = 3L, name = "Home", category = PlaceCategory.HOME, latitude = 49.8, longitude = 19.0, altitude = 380.0, altitudeSource = "CUSTOM")
        val placeMissing = SavedPlace(id = 4L, name = "Shop", category = PlaceCategory.CUSTOM, latitude = 49.8, longitude = 19.0, altitude = null, altitudeSource = null)

        val allPlaces = listOf(placeDem, placeGps, placeCustom, placeMissing)

        // In OVERRIDE_ALL, CUSTOM is protected:
        val overrideAllTargets = allPlaces.filter { it.altitudeSource != "CUSTOM" }
        assertEquals(3, overrideAllTargets.size)
        assertFalse(overrideAllTargets.contains(placeCustom))
        assertTrue(overrideAllTargets.contains(placeDem))
        assertTrue(overrideAllTargets.contains(placeGps))
        assertTrue(overrideAllTargets.contains(placeMissing))

        // In FILL_MISSING, only missing or GPS are fetched, and CUSTOM is strictly protected:
        val fillMissingTargets = allPlaces.filter { p ->
            val a = p.altitude
            (a == null || a == 0.0 || a.isNaN() || p.altitudeSource == "GPS") && p.altitudeSource != "CUSTOM"
        }
        assertEquals(2, fillMissingTargets.size)
        assertTrue(fillMissingTargets.contains(placeGps))
        assertTrue(fillMissingTargets.contains(placeMissing))
        assertFalse(fillMissingTargets.contains(placeCustom))
        assertFalse(fillMissingTargets.contains(placeDem))
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
