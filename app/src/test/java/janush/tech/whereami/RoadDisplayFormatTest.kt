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
}
