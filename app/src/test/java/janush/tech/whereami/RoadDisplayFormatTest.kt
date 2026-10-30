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
    fun `OSRM bare DW948 plus OSM Krakowska with osm ref becomes Krakowska DW948`() {
        val (street, ref) = RoadNameNormalizer.coalesceOsrmAndOsmStreet(
            osrmStreet = "DW948",
            osmStreet = "Krakowska",
            osmRoadRef = "DW948"
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
    fun `OSRM bare DK52 without osm ref does not glue onto commemorative street`() {
        // Trip 249 Czaniec: OSRM snapped to DK52 while Nominatim named Kardynała Karola Wojtyły.
        val (street, ref) = RoadNameNormalizer.coalesceOsrmAndOsmStreet(
            osrmStreet = "DK52",
            osmStreet = "Kardynała Karola Wojtyły",
            osmRoadRef = null
        )
        assertEquals("Kardynała Karola Wojtyły", street)
        assertEquals(null, ref)
        assertEquals(
            "Kardynała Karola Wojtyły",
            RoadNameNormalizer.formatStreetDisplay(street, ref, "PL")
        )
    }

    @Test
    fun `OSRM bare code without osm ref leaves Krakowska unannotated`() {
        val (street, ref) = RoadNameNormalizer.coalesceOsrmAndOsmStreet(
            osrmStreet = "DK52",
            osmStreet = "Krakowska",
            osmRoadRef = null
        )
        assertEquals("Krakowska", street)
        assertEquals(null, ref)
    }

    @Test
    fun `isLikelyCorridorLocalName accepts thoroughfare aliases not commemorative names`() {
        assertTrue(RoadNameNormalizer.isLikelyCorridorLocalName("Krakowska"))
        assertTrue(RoadNameNormalizer.isLikelyCorridorLocalName("Krakowska (DK52)"))
        assertTrue(RoadNameNormalizer.isLikelyCorridorLocalName("3 Maja"))
        assertFalse(RoadNameNormalizer.isLikelyCorridorLocalName("Kardynała Karola Wojtyły"))
        assertFalse(RoadNameNormalizer.isLikelyCorridorLocalName("Kardynała Karola Wojtyły (DK52)"))
        assertEquals(
            null,
            RoadNameNormalizer.coalesceBareCodeWithCachedName(
                "DK52", "DK52", "Kardynała Karola Wojtyły", "DK52"
            )
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
    fun `bare expressway S1 and DW957 upgrade to local names but never overwrite existing local names`() {
        assertFalse(RoadNameNormalizer.shouldPreferExistingStreet("S1", "Warszawska"))
        assertFalse(RoadNameNormalizer.shouldPreferExistingStreet("DW957", "Kolejowa"))
        assertTrue(RoadNameNormalizer.shouldPreferExistingStreet("Warszawska", "S1"))
        assertTrue(RoadNameNormalizer.shouldPreferExistingStreet("Kolejowa", "DW957"))
    }

    @Test
    fun `extractHighwayCode from paren and bare code`() {
        assertEquals("DW948", RoadNameNormalizer.extractHighwayCode("DW948"))
        assertEquals("DK52", RoadNameNormalizer.extractHighwayCode("Krakowska (DK52)"))
        assertEquals(null, RoadNameNormalizer.extractHighwayCode("Krakowska"))
    }

    @Test
    fun `normalize does not double-wrap Krakowska DK52`() {
        assertEquals(
            "Krakowska (DK52)",
            RoadNameNormalizer.normalize("Krakowska (DK52)", "52", countryCode = "PL")
        )
        assertEquals(
            "Krakowska (DK52)",
            RoadNameNormalizer.dedupeParenHighwayRef("Krakowska (DK52) (DK52)")
        )
    }

    @Test
    fun `bare DK52 committed plus cached Krakowska becomes Krakowska DK52`() {
        val pair = RoadNameNormalizer.coalesceBareCodeWithCachedName(
            committedStreet = "DK52",
            committedRoadRef = "52",
            cachedStreet = "Krakowska",
            cachedRoadRef = null
        )
        assertEquals("Krakowska", pair!!.first)
        assertEquals("DK52", pair.second)
        assertEquals(
            "Krakowska (DK52)",
            RoadNameNormalizer.formatStreetDisplay(pair.first, pair.second, "PL")
        )
    }

    @Test
    fun `residential side street with house number is not coalesced onto corridor`() {
        assertTrue(RoadNameNormalizer.isResidentialSideStreet("Kamieniecka 1"))
        assertFalse(RoadNameNormalizer.isResidentialSideStreet("Krakowska"))
        assertEquals(
            null,
            RoadNameNormalizer.coalesceBareCodeWithCachedName(
                "DK52", "52", "Kamieniecka 1", null
            )
        )
    }

    @Test
    fun `OSRM bare DK52 with Nominatim side street Szkolna never emits Szkolna DK52`() {
        // Field trip 272 Bujaków: Nominatim geocoded Szkolna at the DK52 junction
        val (street, ref) = RoadNameNormalizer.coalesceOsrmAndOsmStreet(
            osrmStreet = "DK52",
            osmStreet = "Szkolna",
            osmRoadRef = null
        )
        assertEquals("DK52", street)
        assertEquals(
            "DK52",
            RoadNameNormalizer.formatStreetDisplay(street, ref, "PL")
        )
        // Even if normalize is called directly with Szkolna and DK52:
        assertEquals(
            "DK52",
            RoadNameNormalizer.normalize("Szkolna", "DK52", countryCode = "PL")
        )
    }

    @Test
    fun `sanitizeHouseNumber on corridor preserves highway code when stripped`() {
        assertEquals(
            "Krakowska (DK52)",
            RoadNameNormalizer.sanitizeHouseNumber("Krakowska 242 (DK52)", allowHouseNumber = false)
        )
        assertEquals(
            "Krakowska 242 (DK52)",
            RoadNameNormalizer.sanitizeHouseNumber("Krakowska 242 (DK52)", allowHouseNumber = true)
        )
    }
}
