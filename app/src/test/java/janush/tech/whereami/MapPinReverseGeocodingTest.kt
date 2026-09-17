package janush.tech.whereami

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapPinReverseGeocodingTest {

    @Test
    fun testResidentialAddressWithin150mRetainsStreetAndHouseNumber() {
        val json = """
            {
              "lat": "49.78910",
              "lon": "19.04510",
              "class": "building",
              "type": "residential",
              "address": {
                "road": "ul. Kościelna",
                "house_number": "12",
                "village": "Wilkowice",
                "municipality": "gmina Wilkowice",
                "county": "powiat bielski",
                "state": "województwo śląskie",
                "country": "Polska",
                "country_code": "pl"
              }
            }
        """.trimIndent()

        // Query point ~13 meters away
        val result = SearchHelper.parseNominatimReverse(json, 49.78900, 19.04500)

        assertEquals("ul. Kościelna 12", result.title)
        assertEquals("Wilkowice", result.localityName)
        assertEquals("Wilkowice", result.municipalityName)
        assertEquals("pl", result.countryCode)
        assertTrue(result.subtitle.contains("Wilkowice"))
        assertTrue(result.subtitle.contains("pow. bielski"))
        assertTrue(result.subtitle.contains("woj. śląskie"))
    }

    @Test
    fun testForestNaturalTerrainDoesNotInventStreet() {
        val json = """
            {
              "lat": "49.75300",
              "lon": "19.10300",
              "class": "natural",
              "type": "wood",
              "address": {
                "road": "ul. Leśna",
                "house_number": "88",
                "village": "Wilkowice",
                "municipality": "gmina Wilkowice",
                "county": "powiat bielski",
                "state": "województwo śląskie",
                "country": "Polska",
                "country_code": "pl"
              }
            }
        """.trimIndent()

        // Dropped pin on natural terrain in the forest
        val queryLat = 49.75000
        val queryLon = 19.10000
        val result = SearchHelper.parseNominatimReverse(json, queryLat, queryLon)

        // Must NOT invent street or house number
        assertEquals("49.75000, 19.10000", result.title)
        assertFalse(result.title.contains("ul. Leśna"))
        assertFalse(result.title.contains("88"))

        // Subtitle must preserve the full administrative hierarchy
        assertEquals("Wilkowice", result.localityName)
        assertEquals("Wilkowice", result.municipalityName)
        assertEquals("pl", result.countryCode)
        assertTrue(result.subtitle.contains("Wilkowice"))
        assertTrue(result.subtitle.contains("gm. Wilkowice"))
        assertTrue(result.subtitle.contains("pow. bielski"))
        assertTrue(result.subtitle.contains("woj. śląskie"))
        assertTrue(result.subtitle.contains("Polska"))
    }

    @Test
    fun testHamletWithSeparateMunicipalityPopulatesFallbackMunicipality() {
        val json = """
            {
              "lat": "49.81675",
              "lon": "19.24328",
              "class": "place",
              "type": "village",
              "address": {
                "village": "Kozubnik",
                "municipality": "gmina Porąbka",
                "county": "powiat bielski",
                "state": "województwo śląskie",
                "country": "Polska",
                "country_code": "pl"
              }
            }
        """.trimIndent()

        val result = SearchHelper.parseNominatimReverse(json, 49.81675, 19.24328)
        assertEquals("Kozubnik", result.localityName)
        assertEquals("Porąbka", result.municipalityName)
        assertEquals("pl", result.countryCode)
        assertTrue(result.subtitle.contains("gm. Porąbka"))
    }

    @Test
    fun testOffRoadDistantFeatureExceeding150mDisplaysCoordinates() {
        // Feature is ~270m away
        val json = """
            {
              "lat": "49.70200",
              "lon": "19.00200",
              "class": "highway",
              "type": "residential",
              "address": {
                "road": "ul. Żywiecka",
                "house_number": "200",
                "city": "Bielsko-Biała",
                "county": "Bielsko-Biała",
                "state": "województwo śląskie",
                "country": "Polska",
                "country_code": "pl"
              }
            }
        """.trimIndent()

        val queryLat = 49.70000
        val queryLon = 19.00000
        val result = SearchHelper.parseNominatimReverse(json, queryLat, queryLon)

        // Distance > 150m -> Title is coordinates, not snapped road
        assertEquals("49.70000, 19.00000", result.title)
        assertEquals("Bielsko-Biała", result.localityName)
        assertTrue(result.subtitle.contains("Bielsko-Biała"))
    }

    @Test
    fun testHaversineDistanceCalculation() {
        // Distance between Krakow (50.0647, 19.9450) and Warsaw (52.2297, 21.0122) ~ 252 km
        val dist = SearchHelper.computeDistanceMeters(50.0647, 19.9450, 52.2297, 21.0122)
        assertTrue(dist in 250_000f..255_000f)

        // 100m displacement check
        val dist100m = SearchHelper.computeDistanceMeters(50.0, 19.0, 50.0009, 19.0)
        assertTrue(dist100m in 95f..105f)
    }
}
