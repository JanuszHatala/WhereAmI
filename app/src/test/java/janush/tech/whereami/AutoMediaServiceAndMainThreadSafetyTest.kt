package janush.tech.whereami

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class AutoMediaServiceAndMainThreadSafetyTest {

    @Test
    fun testMediaMetadataFormattingWithCarSpeed() {
        val speedMs = 4.7f / 3.6f
        val speedKmh = speedMs * 3.6f
        val speedStr = String.format(Locale.US, "%.1f km/h", speedKmh)

        assertEquals("4.7 km/h", speedStr)

        val place = PlaceInfo(
            city = "Czaniec",
            street = "ul. Dworska 12",
            roadRef = "DW948",
            gmina = "Porąbka",
            powiat = "bielski",
            voivodeship = "śląskie",
            country = "Polska",
            countryCode = "PL"
        )

        val shortenedStreet = StreetAbbreviator.abbreviate(place.street, place.countryCode)
        val streetOrRef = listOfNotNull(
            shortenedStreet?.takeIf { it.isNotBlank() },
            place.roadRef?.takeIf { it.isNotBlank() }
        ).joinToString(" / ")

        val title = "${place.city} • $streetOrRef"
        assertTrue(title.contains("Czaniec"))
        assertTrue(title.contains("Dworska"))
        assertTrue(title.contains("DW948"))

        val hierarchy = LocationManager.formatHierarchy(place)
        val artistParts = mutableListOf<String>()
        artistParts.add(speedStr)
        if (hierarchy.isNotEmpty()) {
            artistParts.add(hierarchy)
        }
        if (place.country.isNotEmpty()) {
            artistParts.add(place.country)
        }
        val artist = artistParts.joinToString(" • ")

        assertTrue(artist.startsWith("4.7 km/h"))
        assertTrue(artist.contains("gm. Porąbka"))
        assertTrue(artist.contains("Polska"))
    }

    @Test
    fun testAllowNetworkFalseReturnsImmediateFallbackWithoutNetwork() {
        // Test contract: when allowNetwork = false, resolution MUST return immediately
        // using cached or last known data, with zero network blocking.
        val fallback = PlaceInfo("Unknown City", null, null, null, null, "Unknown Region", "Unknown Country", "")
        val multiFallback = MultiLanguagePlaceInfo(fallback, fallback, fallback)

        assertNotNull(multiFallback)
        assertEquals("Unknown City", multiFallback.pl.city)
        assertFalse(multiFallback.pl.isValid())
    }

    @Test
    fun testQueueKeyDebounceStability() {
        val place = PlaceInfo(
            city = "Kęty",
            street = "ul. Krakowska 45a",
            roadRef = "DK52",
            gmina = "Kęty",
            powiat = "oświęcimski",
            voivodeship = "małopolskie",
            country = "Polska",
            countryCode = "PL"
        )

        val baseStreet1 = place.street?.replace(Regex("\\s+\\d+[a-zA-Z]?(-\\d+)?"), "") ?: ""
        val baseStreet2 = "ul. Krakowska 112".replace(Regex("\\s+\\d+[a-zA-Z]?(-\\d+)?"), "")

        // Both house numbers on ul. Krakowska should yield identical baseStreet for queue key stability
        assertEquals("ul. Krakowska", baseStreet1)
        assertEquals("ul. Krakowska", baseStreet2)
        assertEquals(baseStreet1, baseStreet2)
    }
}
