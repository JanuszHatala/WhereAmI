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
    fun testRoczynyAndCzaniecFalseDw781SanitizationInDeserialization() {
        val roczynyWiedenskaJson = """{"city":"Roczyny","street":"Wiedeńska (DW781)","roadRef":"DW781","country":"Polska","countryCode":"PL","gmina":"gmina Andrychów"}"""
        val p1 = SpatialCacheHelper.deserializePlaceInfo(roczynyWiedenskaJson)
        assertEquals("Wiedeńska", p1?.street)
        assertNull(p1?.roadRef)

        val roczynyBielskaJson = """{"city":"Roczyny","street":"Bielska (DW781)","roadRef":"DW781","country":"Polska","countryCode":"PL","gmina":"gmina Andrychów"}"""
        val p2 = SpatialCacheHelper.deserializePlaceInfo(roczynyBielskaJson)
        assertEquals("Bielska", p2?.street)
        assertNull(p2?.roadRef)

        val czaniecBielskaJson = """{"city":"Czaniec","street":"Bielska (DW781)","roadRef":"DW781","country":"Polska","countryCode":"PL","gmina":"gmina Porąbka"}"""
        val p3 = SpatialCacheHelper.deserializePlaceInfo(czaniecBielskaJson)
        assertEquals("Bielska", p3?.street)
        assertNull(p3?.roadRef)
    }

    @Test
    fun testSideStreetsFalseHighwaySanitizationInDeserialization() {
        val szewskaJson = """{"city":"Andrychów","street":"Szewska (DK52)","roadRef":"DK52","country":"Polska","countryCode":"PL","gmina":"gmina Andrychów"}"""
        val p1 = SpatialCacheHelper.deserializePlaceInfo(szewskaJson)
        assertEquals("Szewska", p1?.street)
        assertNull(p1?.roadRef)

        val krakowskaAndrychowJson = """{"city":"Andrychów","street":"Krakowska (DW781)","roadRef":"DW781","country":"Polska","countryCode":"PL","gmina":"gmina Andrychów"}"""
        val p2 = SpatialCacheHelper.deserializePlaceInfo(krakowskaAndrychowJson)
        assertEquals("Krakowska (DK52)", p2?.street)
        assertEquals("DK52", p2?.roadRef)

        val wadowickaSuchaJson = """{"city":"Sucha Beskidzka","street":"Wadowicka (DW946)","roadRef":"DW946","country":"Polska","countryCode":"PL","powiat":"powiat suski"}"""
        val p3 = SpatialCacheHelper.deserializePlaceInfo(wadowickaSuchaJson)
        assertEquals("Wadowicka (DK28)", p3?.street)
        assertEquals("DK28", p3?.roadRef)
    }

    @Test
    fun testGronHamletResolvedToTarganiceAndBrzezinka() {
        val gronGranicznaJson = """{"city":"Groń","street":"Graniczna 58","country":"Polska","countryCode":"PL","gmina":"gmina Andrychów"}"""
        val p1 = SpatialCacheHelper.deserializePlaceInfo(gronGranicznaJson)
        assertEquals("Targanice", p1?.city)

        val gronPodgorskaJson = """{"city":"Groń","street":"Podgórska 10","country":"Polska","countryCode":"PL","gmina":"gmina Andrychów"}"""
        val p2 = SpatialCacheHelper.deserializePlaceInfo(gronPodgorskaJson)
        assertEquals("Brzezinka", p2?.city)

        val gronLesnaJson = """{"city":"Groń","street":"Leśna","country":"Polska","countryCode":"PL","gmina":"gmina Andrychów"}"""
        val p3 = SpatialCacheHelper.deserializePlaceInfo(gronLesnaJson)
        assertEquals("Roczyny", p3?.city)
    }

    @Test
    fun testTripDatabaseHelperJsonToPlacesResolvesGronAndCoalesces() {
        val placesJson = """[
            {"name":"Andrychów","sub":"gm. Andrychów, pow. wadowicki","time":1731000000000,"lat":49.85,"lng":19.34,"dist":0.0,"kind":"LOCALITY"},
            {"name":"Groń","sub":"gm. Andrychów, pow. wadowicki","time":1731000500000,"lat":49.8271,"lng":19.3250,"dist":5200.0,"kind":"LOCALITY"},
            {"name":"Targanice","sub":"gm. Andrychów, pow. wadowicki","time":1731000800000,"lat":49.8240,"lng":19.3300,"dist":7100.0,"kind":"LOCALITY"}
        ]"""
        val places = TripDatabaseHelper.jsonToPlaces(placesJson)
        // Groń (lng 19.325 >= 19.3216) becomes Targanice.
        // Adjacent Targanice -> Targanice should coalesce to a single Targanice entry!
        assertEquals(2, places.size)
        assertEquals("Andrychów", places[0].placeName)
        assertEquals("Targanice", places[1].placeName)
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
