package online.hatala.whereami

import online.hatala.whereami.tracking.KinematicEkf
import online.hatala.whereami.tracking.LocalFrame
import online.hatala.whereami.tracking.TrackSpikeGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldTestTrip312StabilizationTest {

    @Test
    fun testResidentialStreetHighwayRefSanitizationInDeserialization() {
        val residentialWithRefJson = """{"city":"AnyTown","street":"Quiet St 12 (DK52)","roadRef":"DK52","highwayType":"residential","country":"Polska","countryCode":"PL"}"""
        val p1 = SpatialCacheHelper.deserializePlaceInfo(residentialWithRefJson)
        assertEquals("Quiet St 12", p1?.street)
        assertNull(p1?.roadRef)

        val houseNumberedStreetJson = """{"city":"AnyTown","street":"Main Ave 45B (DW781)","roadRef":"DW781","country":"Polska","countryCode":"PL"}"""
        val p2 = SpatialCacheHelper.deserializePlaceInfo(houseNumberedStreetJson)
        assertEquals("Main Ave 45B", p2?.street)
        assertNull(p2?.roadRef)
    }

    @Test
    fun testAutonomousCountyCityStructuralHierarchy() {
        assertTrue(LocationManager.isAutonomousCountyCity("Bielsko-Biała", null, "powiat Bielsko-Biała"))
        assertTrue(LocationManager.isAutonomousCountyCity("Kraków", null, "m. Kraków"))
        assertTrue(LocationManager.isAutonomousCountyCity("Warszawa", null, "m. st. Warszawa"))
        assertFalse(LocationManager.isAutonomousCountyCity("Kozy", "gmina Kozy", "powiat bielski"))
        assertFalse(LocationManager.isAutonomousCountyCity("Andrychów", "gmina Andrychów", "powiat wadowicki"))
    }

    @Test
    fun testTripDatabaseHelperJsonToPlacesCoalescesConsecutiveDuplicates() {
        val placesJson = """[
            {"name":"CityA","sub":"gm. CityA","time":1731000000000,"lat":49.85,"lng":19.34,"dist":0.0,"kind":"LOCALITY"},
            {"name":"CityA","sub":"gm. CityA","time":1731000500000,"lat":49.851,"lng":19.341,"dist":520.0,"kind":"LOCALITY"},
            {"name":"CityB","sub":"gm. CityB","time":1731000800000,"lat":49.86,"lng":19.35,"dist":2100.0,"kind":"LOCALITY"}
        ]"""
        val places = TripDatabaseHelper.jsonToPlaces(placesJson)
        assertEquals(2, places.size)
        assertEquals("CityA", places[0].placeName)
        assertEquals("CityB", places[1].placeName)
    }

    @Test
    fun testTertiaryClassifiedAsNonMajorHighwayInRoadNameNormalizer() {
        assertFalse("tertiary road without ref must not be classified as major highway", RoadNameNormalizer.isMajorHighway("tertiary", null))
        assertFalse("tertiary_link road without ref must not be classified as major highway", RoadNameNormalizer.isMajorHighway("tertiary_link", null))
        assertTrue("tertiary road must be in residential / non-major highway types", RoadNameNormalizer.isResidentialHighwayType("tertiary"))
        assertTrue("tertiary_link road must be in residential / non-major highway types", RoadNameNormalizer.isResidentialHighwayType("tertiary_link"))
        assertTrue("primary road is major", RoadNameNormalizer.isMajorHighway("primary", null))
        assertTrue("secondary road is major", RoadNameNormalizer.isMajorHighway("secondary", null))
    }

    @Test
    fun testTrackSpikeGuardLongitudinalOvershootHook() {
        // Replay Trip 312 seq 651..653 geometry:
        // A (seq 651): (15.52m E, 21.63m N)
        // B (seq 652): (22.20m E, 26.66m N) -- overshot collinear by 8.36m
        // C (seq 653): (19.98m E, 23.29m N) -- reversed back 160.4° by 4.04m
        val frame = LocalFrame(49.8270, 19.3150)
        val aLat = frame.lat(21.63)
        val aLng = frame.lng(15.52)
        val bLat = frame.lat(26.66)
        val bLng = frame.lng(22.20)
        val cLat = frame.lat(23.29)
        val cLng = frame.lng(19.98)

        val isSpike = TrackSpikeGuard.isLateralVSpike(aLat, aLng, bLat, bLng, cLat, cLng)
        assertTrue("Overshoot-and-reverse hook B must be detected as spike", isSpike)
    }
}
