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
}
