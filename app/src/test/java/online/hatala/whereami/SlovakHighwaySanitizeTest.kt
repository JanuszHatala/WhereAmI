package online.hatala.whereami

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SlovakHighwaySanitizeTest {

    @Test
    fun `SK bare DW and DK become I or II class`() {
        assertEquals("II/537", RoadNameNormalizer.sanitizeStreetForCountry("DW537", "SK"))
        assertEquals("I/18", RoadNameNormalizer.sanitizeStreetForCountry("DK18", "SK"))
        assertEquals("I/59", RoadNameNormalizer.sanitizeStreetForCountry("DK59", "SK"))
        assertEquals("II/520", RoadNameNormalizer.sanitizeStreetForCountry("DW520", "SK"))
    }

    @Test
    fun `SK named street with Polish paren ref is rewritten`() {
        assertEquals(
            "Hlavná (I/18)",
            RoadNameNormalizer.sanitizeStreetForCountry("Hlavná (DK18)", "SK")
        )
    }

    @Test
    fun `PL streets unchanged`() {
        assertEquals("DW948", RoadNameNormalizer.sanitizeStreetForCountry("DW948", "PL"))
        assertEquals("Krakowska (DK52)", RoadNameNormalizer.sanitizeStreetForCountry("Krakowska (DK52)", "PL"))
    }

    @Test
    fun `normalize with SK country rewrites Polish codes`() {
        assertEquals("II/537", RoadNameNormalizer.normalize("DW537", countryCode = "SK"))
        assertEquals("I/18", RoadNameNormalizer.normalize("DK18", countryCode = "SK"))
        assertEquals("I/18", RoadNameNormalizer.normalize("18", countryCode = "SK"))
        assertEquals("II/537", RoadNameNormalizer.normalize("537", countryCode = "SK"))
    }

    @Test
    fun `other countries strip Polish prefix to bare number`() {
        assertEquals("18", RoadNameNormalizer.sanitizeStreetForCountry("DK18", "CZ"))
        assertFalse(RoadNameNormalizer.sanitizeStreetForCountry("DK18", "DE")!!.startsWith("DK"))
    }
}
