package janush.tech.whereami

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class RoadNameNormalizerTest {

    @Test
    fun testNationalRoadsNormalization() {
        assertEquals("DK52", RoadNameNormalizer.normalize("Droga Krajowa 52"))
        assertEquals("DK52", RoadNameNormalizer.normalize("Droga Krajowa nr 52"))
        assertEquals("DK52", RoadNameNormalizer.normalize("Krajowa 52"))
        assertEquals("DK52", RoadNameNormalizer.normalize("DK 52"))
        assertEquals("DK52", RoadNameNormalizer.normalize("DK52"))
        assertEquals("DK28", RoadNameNormalizer.normalize("Droga Krajowa 28"))
    }

    @Test
    fun testProvincialRoadsNormalization() {
        assertEquals("DW946", RoadNameNormalizer.normalize("Droga Wojewódzka 946"))
        assertEquals("DW946", RoadNameNormalizer.normalize("Wojewódzka 946"))
        assertEquals("DW946", RoadNameNormalizer.normalize("DW 946"))
        assertEquals("DW948", RoadNameNormalizer.normalize("Droga Wojewódzka nr 948"))
    }

    @Test
    fun testHighwaysAndExpressways() {
        assertEquals("A4", RoadNameNormalizer.normalize("Autostrada A4"))
        assertEquals("A4", RoadNameNormalizer.normalize("A 4"))
        assertEquals("S7", RoadNameNormalizer.normalize("Droga Ekspresowa S7"))
        assertEquals("S7", RoadNameNormalizer.normalize("S 7"))
        assertEquals("E77", RoadNameNormalizer.normalize("Droga Międzynarodowa E77"))
    }

    @Test
    fun testMajorRoadsStripHouseNumbers() {
        // Major roads must NOT have house numbers appended
        assertEquals("DK52", RoadNameNormalizer.normalize("DK 52", houseNumber = "142"))
        assertEquals("DW946", RoadNameNormalizer.normalize("Droga Wojewódzka 946", houseNumber = "55B"))
    }

    @Test
    fun testResidentialStreetsStripHouseNumbersAndCleanPrefix() {
        assertEquals("ul. Mickiewicza", RoadNameNormalizer.normalize("ulica Mickiewicza", houseNumber = "12"))
        assertEquals("ul. Mickiewicza", RoadNameNormalizer.normalize("ulica Mickiewicza 12"))
        assertEquals("al. Wolności", RoadNameNormalizer.normalize("aleja Wolności", houseNumber = "4A"))
        assertEquals("os. Tysiąclecia", RoadNameNormalizer.normalize("osiedle Tysiąclecia", houseNumber = "5"))
        assertEquals("pl. Grunwaldzki", RoadNameNormalizer.normalize("plac Grunwaldzki"))
        assertEquals("Zagłębocze", RoadNameNormalizer.normalize("Zagłębocze 45"))
        assertEquals("Górska", RoadNameNormalizer.normalize("Górska 92"))
        assertEquals("Zielona", RoadNameNormalizer.normalize("Zielona 18"))
    }

    @Test
    fun testNamedStreetWithHighwayRef() {
        assertEquals("ul. Wadowicka (DK52)", RoadNameNormalizer.normalize("ulica Wadowicka", rawRef = "DK52"))
        assertEquals("ul. Żywiecka (DW946)", RoadNameNormalizer.normalize("ulica Żywiecka", rawRef = "946"))
        assertEquals("al. świętego Jana Pawła II (S1)", RoadNameNormalizer.normalize("Aleje świętego Jana Pawła II"))
    }

    @Test
    fun testIsMajorRoad() {
        assertTrue(RoadNameNormalizer.isMajorRoad("DK52"))
        assertTrue(RoadNameNormalizer.isMajorRoad("DW946"))
        assertTrue(RoadNameNormalizer.isMajorRoad("A4"))
        assertTrue(RoadNameNormalizer.isMajorRoad("S7"))
        assertTrue(RoadNameNormalizer.isMajorRoad("S1"))
        assertTrue(RoadNameNormalizer.isMajorRoad("Aleje świętego Jana Pawła II (S1)"))
        assertTrue(RoadNameNormalizer.isMajorRoad("ul. Wadowicka (DK52)"))
        assertTrue(RoadNameNormalizer.isMajorRoad("Droga Krajowa 28"))
        assertFalse(RoadNameNormalizer.isMajorRoad("ul. Kościuszki"))
        assertFalse(RoadNameNormalizer.isMajorRoad("Rynek"))
    }

    @Test
    fun testShouldShowHouseNumberGating() {
        // High accuracy and low speed on residential road -> allowed
        assertTrue(RoadNameNormalizer.shouldShowHouseNumber(speedKmh = 5.0f, accuracyMeters = 8.0f, isMajorRoad = false))
        assertTrue(RoadNameNormalizer.shouldShowHouseNumber(speedKmh = 0.0f, accuracyMeters = 4.0f, isMajorRoad = false))
        assertTrue(RoadNameNormalizer.shouldShowHouseNumber(speedKmh = null, accuracyMeters = 10.0f, isMajorRoad = false))

        // Velocity >= 10 km/h -> stripped
        assertFalse(RoadNameNormalizer.shouldShowHouseNumber(speedKmh = 10.0f, accuracyMeters = 8.0f, isMajorRoad = false))
        assertFalse(RoadNameNormalizer.shouldShowHouseNumber(speedKmh = 45.0f, accuracyMeters = 5.0f, isMajorRoad = false))

        // Degraded accuracy > 12m -> stripped
        assertFalse(RoadNameNormalizer.shouldShowHouseNumber(speedKmh = 2.0f, accuracyMeters = 13.0f, isMajorRoad = false))
        assertFalse(RoadNameNormalizer.shouldShowHouseNumber(speedKmh = 0.0f, accuracyMeters = 25.0f, isMajorRoad = false))
        assertFalse(RoadNameNormalizer.shouldShowHouseNumber(speedKmh = 0.0f, accuracyMeters = null, isMajorRoad = false))

        // Major road / corridor -> strictly stripped regardless of speed/accuracy
        assertFalse(RoadNameNormalizer.shouldShowHouseNumber(speedKmh = 0.0f, accuracyMeters = 3.0f, isMajorRoad = true))
    }

    @Test
    fun testSanitizeHouseNumber() {
        assertEquals("ul. Zdrojowa", RoadNameNormalizer.sanitizeHouseNumber("ul. Zdrojowa 11A", allowHouseNumber = false))
        assertEquals("ul. Zdrojowa 11A", RoadNameNormalizer.sanitizeHouseNumber("ul. Zdrojowa 11A", allowHouseNumber = true))
        assertEquals("DK52", RoadNameNormalizer.sanitizeHouseNumber("DK52", allowHouseNumber = false))
        assertEquals("ul. Krakowska (DK52)", RoadNameNormalizer.sanitizeHouseNumber("ul. Krakowska (DK52)", allowHouseNumber = false))
        assertEquals("ul. Bielska", RoadNameNormalizer.sanitizeHouseNumber("ul. Bielska", allowHouseNumber = false))
        assertEquals("os. Młocki Dolne", RoadNameNormalizer.sanitizeHouseNumber("os. Młocki Dolne", allowHouseNumber = false))
    }

    @Test
    fun testNormalizeWithConditionalHouseNumber() {
        assertEquals("ul. Zdrojowa 11A", RoadNameNormalizer.normalize("ulica Zdrojowa", houseNumber = "11A", includeHouseNumber = true))
        assertEquals("ul. Zdrojowa", RoadNameNormalizer.normalize("ulica Zdrojowa", houseNumber = "11A", includeHouseNumber = false))
        assertEquals("Zdrojowa 11A", RoadNameNormalizer.normalize("Zdrojowa 11A", includeHouseNumber = true))
        assertEquals("Zdrojowa", RoadNameNormalizer.normalize("Zdrojowa 11A", includeHouseNumber = false))
    }
}

