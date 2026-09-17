package janush.tech.whereami

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HierarchyResolutionTest {

    @Test
    fun testBielskoBialaCountyCitySuppressesGminaAndPowiat() {
        val place = PlaceInfo(
            city = "Bielsko-Biała",
            street = "ul. Warszawska 10",
            roadRef = "DK52",
            gmina = "gmina Porąbka", // Simulating corrupted/incorrect gmina returned by geocoder
            powiat = "Bielsko-Biała",
            voivodeship = "województwo śląskie",
            country = "Polska",
            countryCode = "PL"
        )
        val hierarchy = LocationManager.formatHierarchy(place)
        assertEquals("woj. śląskie", hierarchy)
        assertFalse(hierarchy.contains("Porąbka"))
        assertFalse(hierarchy.contains("gm."))
        assertFalse(hierarchy.contains("pow."))
    }

    @Test
    fun testWarszawaCountyCitySuppressesGminaAndPowiat() {
        val place = PlaceInfo(
            city = "Warszawa",
            street = "ul. Marszałkowska",
            roadRef = null,
            gmina = "Warszawa",
            powiat = "Warszawa",
            voivodeship = "województwo mazowieckie",
            country = "Polska",
            countryCode = "PL"
        )
        val hierarchy = LocationManager.formatHierarchy(place)
        assertEquals("woj. mazowieckie", hierarchy)
    }

    @Test
    fun testKrakowCountyCitySuppressesGminaAndPowiat() {
        val place = PlaceInfo(
            city = "Kraków",
            street = "ul. Floriańska",
            roadRef = null,
            gmina = null,
            powiat = "Kraków",
            voivodeship = "województwo małopolskie",
            country = "Polska",
            countryCode = "PL"
        )
        val hierarchy = LocationManager.formatHierarchy(place)
        assertEquals("woj. małopolskie", hierarchy)
    }

    @Test
    fun testCzaniecVillageFormatsFullHierarchy() {
        val place = PlaceInfo(
            city = "Czaniec",
            street = "ul. Kardynała Karola Wojtyły",
            roadRef = "DW948",
            gmina = "gmina Porąbka",
            powiat = "powiat bielski",
            voivodeship = "województwo śląskie",
            country = "Polska",
            countryCode = "PL"
        )
        val hierarchy = LocationManager.formatHierarchy(place)
        assertEquals("gm. Porąbka • pow. bielski • woj. śląskie", hierarchy)
    }

    @Test
    fun testPorabkaTownOmitGminaDuplicate() {
        val place = PlaceInfo(
            city = "Porąbka",
            street = "ul. Rynek",
            roadRef = null,
            gmina = "gmina Porąbka",
            powiat = "powiat bielski",
            voivodeship = "województwo śląskie",
            country = "Polska",
            countryCode = "PL"
        )
        val hierarchy = LocationManager.formatHierarchy(place)
        assertEquals("pow. bielski • woj. śląskie", hierarchy)
    }

    @Test
    fun testInternationalFormattingNoPolishPrefixes() {
        val place = PlaceInfo(
            city = "Berlin",
            street = "Unter den Linden",
            roadRef = "B2",
            gmina = "Mitte",
            powiat = "Berlin",
            voivodeship = "Berlin",
            country = "Germany",
            countryCode = "DE"
        )
        val hierarchy = LocationManager.formatHierarchy(place)
        assertEquals("Mitte • Berlin", hierarchy)
        assertFalse(hierarchy.contains("gm."))
        assertFalse(hierarchy.contains("pow."))
        assertFalse(hierarchy.contains("woj."))
    }

    @Test
    fun testPolishCountyCitiesListIntegrity() {
        assertTrue(LocationManager.POLISH_COUNTY_CITIES.contains("bielsko-biała"))
        assertTrue(LocationManager.POLISH_COUNTY_CITIES.contains("warszawa"))
        assertTrue(LocationManager.POLISH_COUNTY_CITIES.contains("kraków"))
        assertTrue(LocationManager.POLISH_COUNTY_CITIES.contains("wrocław"))
        assertTrue(LocationManager.POLISH_COUNTY_CITIES.contains("katowice"))
        assertTrue(LocationManager.POLISH_COUNTY_CITIES.contains("gdańsk"))
        assertTrue(LocationManager.POLISH_COUNTY_CITIES.contains("poznań"))
        assertTrue(LocationManager.POLISH_COUNTY_CITIES.contains("łódź"))
    }
}
