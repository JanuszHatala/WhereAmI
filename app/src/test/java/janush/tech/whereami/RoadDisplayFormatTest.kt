package janush.tech.whereami

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadDisplayFormatTest {

    @Test
    fun `Krakowska plus bare ref 52 becomes Krakowska DK52 in brackets`() {
        assertEquals(
            "Krakowska (DK52)",
            RoadNameNormalizer.formatStreetDisplay("Krakowska", "52", "PL")
        )
    }

    @Test
    fun `does not produce bullet-separated bare ref`() {
        val shown = RoadNameNormalizer.formatStreetDisplay("Krakowska", "52", "PL")!!
        assertFalse(shown.contains("•"))
        assertFalse(shown.matches(Regex(""".*\b52\b.*""")) && !shown.contains("DK52"))
        assertTrue(shown.contains("DK52"))
    }

    @Test
    fun `already normalised street is not doubled with raw ref`() {
        assertEquals(
            "Krakowska (DK52)",
            RoadNameNormalizer.formatStreetDisplay("Krakowska (DK52)", "52", "PL")
        )
    }

    @Test
    fun `highway-only ref stays DK52`() {
        assertEquals("DK52", RoadNameNormalizer.formatStreetDisplay(null, "52", "PL"))
        assertEquals("DK52", RoadNameNormalizer.formatStreetDisplay("DK52", "52", "PL"))
    }

    @Test
    fun `OSRM bare DW948 plus OSM Krakowska becomes Krakowska DW948`() {
        val (street, ref) = RoadNameNormalizer.coalesceOsrmAndOsmStreet(
            osrmStreet = "DW948",
            osmStreet = "Krakowska",
            osmRoadRef = null
        )
        assertEquals("Krakowska", street)
        assertEquals("DW948", ref)
        assertEquals(
            "Krakowska (DW948)",
            RoadNameNormalizer.normalize(street, ref, countryCode = "PL")
        )
        assertEquals(
            "Krakowska (DW948)",
            RoadNameNormalizer.formatStreetDisplay(street, ref, "PL")
        )
    }

    @Test
    fun `OSRM Krakowska keeps local name`() {
        val (street, ref) = RoadNameNormalizer.coalesceOsrmAndOsmStreet(
            osrmStreet = "Krakowska",
            osmStreet = "Something Else",
            osmRoadRef = "52"
        )
        assertEquals("Krakowska", street)
        assertEquals("52", ref)
    }

    @Test
    fun `cache must not overwrite Krakowska with DW948`() {
        assertTrue(RoadNameNormalizer.shouldPreferExistingStreet("Krakowska", "DW948"))
        assertTrue(RoadNameNormalizer.shouldPreferExistingStreet("Krakowska (DK52)", "DK52"))
        assertFalse(RoadNameNormalizer.shouldPreferExistingStreet("DW948", "Krakowska"))
        assertFalse(RoadNameNormalizer.shouldPreferExistingStreet(null, "DW948"))
    }

    @Test
    fun `extractHighwayCode from paren and bare code`() {
        assertEquals("DW948", RoadNameNormalizer.extractHighwayCode("DW948"))
        assertEquals("DK52", RoadNameNormalizer.extractHighwayCode("Krakowska (DK52)"))
        assertEquals(null, RoadNameNormalizer.extractHighwayCode("Krakowska"))
    }
}
