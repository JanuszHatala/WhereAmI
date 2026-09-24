package janush.tech.whereami

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests verifying street resolution priority, Polish territorial locality resolution,
 * and OSRM/OSM precedence over Android native Geocoder.
 *
 * Covers scenarios reported during field testing:
 * - Driving along Wincentego Witosa in Kozy: ensuring "Wincentego Witosa" is shown instead of "Krzemionki"
 * - Driving along Przecznia in Kozy: ensuring "Przecznia" is shown instead of "Klonowa"
 * - Driving near municipal border: ensuring "Kozy" in "gmina Kozy" is not overridden by postal city "Bielsko-Biała"
 */
class StreetResolutionTest {

    /**
     * Standalone model of the street resolution priority logic from LocationManager.resolvePlace.
     */
    private fun resolveCanonicalStreet(
        osrmStreet: String?,
        osmStreet: String?,
        osmRoadRef: String?,
        osmHouseNum: String?,
        geocoderThoroughfare: String?,
        geocoderSubThoroughfare: String?,
        basePlaceStreet: String?
    ): String? {
        return when {
            // 1. Highest precision: OSRM Map Matching road centerline
            !osrmStreet.isNullOrBlank() -> {
                val houseNumber = osmHouseNum ?: geocoderSubThoroughfare
                RoadNameNormalizer.normalize(osrmStreet, osmRoadRef, houseNumber)
            }
            // 2. Primary road awareness: OpenStreetMap Nominatim street vector
            !osmStreet.isNullOrBlank() -> {
                osmStreet
            }
            // 3. Road ref enrichment if thoroughfare provided
            osmRoadRef != null && osmRoadRef.isNotBlank() -> {
                RoadNameNormalizer.normalize(geocoderThoroughfare ?: osmStreet, osmRoadRef, geocoderSubThoroughfare)
            }
            // 4. Fallback to Android native Geocoder thoroughfare
            else -> {
                basePlaceStreet
            }
        }
    }

    /**
     * Standalone model of the Polish territorial locality resolution logic from LocationManager.geocodeWithOsm.
     */
    private fun resolvePolishLocality(
        rawCity: String?,
        rawTown: String?,
        rawVillage: String?,
        rawHamlet: String?,
        rawMunicipality: String?
    ): String? {
        val isPolishGmina = rawMunicipality != null && rawMunicipality.startsWith("gmina ", ignoreCase = true)
        val gminaName = if (isPolishGmina) rawMunicipality.removePrefix("gmina ").trim() else null
        val villageOrTown = rawVillage ?: rawTown ?: rawHamlet

        return if (isPolishGmina && !villageOrTown.isNullOrBlank()) {
            villageOrTown
        } else if (isPolishGmina && gminaName != null && rawCity != null && !rawCity.equals(gminaName, ignoreCase = true)) {
            gminaName
        } else {
            rawCity ?: rawTown ?: rawVillage ?: rawHamlet
        }
    }

    // ── Street Resolution Priority Tests ─────────────────────────────────────────

    @Test
    fun `OSM Nominatim street overrides Google Geocoder rural parcel or hamlet name`() {
        // Real-world scenario from Screenshot 160718 / 160826:
        // Google Geocoder returned thoroughfare = "Krzemionki" (subdivision/hamlet)
        // OpenStreetMap Nominatim returned road = "Wincentego Witosa 14"
        val resolved = resolveCanonicalStreet(
            osrmStreet = null,
            osmStreet = "Wincentego Witosa 14",
            osmRoadRef = null,
            osmHouseNum = "14",
            geocoderThoroughfare = "Krzemionki",
            geocoderSubThoroughfare = "8",
            basePlaceStreet = "Krzemionki 8"
        )
        assertEquals(
            "OSM street 'Wincentego Witosa 14' must take priority over Google 'Krzemionki 8'",
            "Wincentego Witosa 14",
            resolved
        )
    }

    @Test
    fun `OSM Nominatim street overrides Google Geocoder wrong cross-street`() {
        // Real-world scenario from Screenshot 161528:
        // Google Geocoder returned thoroughfare = "Klonowa"
        // OpenStreetMap Nominatim returned road = "Przecznia"
        val resolved = resolveCanonicalStreet(
            osrmStreet = null,
            osmStreet = "Przecznia",
            osmRoadRef = null,
            osmHouseNum = null,
            geocoderThoroughfare = "Klonowa",
            geocoderSubThoroughfare = "1",
            basePlaceStreet = "Klonowa 1"
        )
        assertEquals(
            "OSM street 'Przecznia' must take priority over Google 'Klonowa 1'",
            "Przecznia",
            resolved
        )
    }

    @Test
    fun `OSRM map matching road centerline takes highest precedence`() {
        // When OSRM map-matching identifies the road vector matching vehicle heading
        val resolved = resolveCanonicalStreet(
            osrmStreet = "Wincentego Witosa",
            osmStreet = "Krzemionki",
            osmRoadRef = null,
            osmHouseNum = "16",
            geocoderThoroughfare = "Krzemionki",
            geocoderSubThoroughfare = "16",
            basePlaceStreet = "Krzemionki 16"
        )
        assertEquals(
            "OSRM map-matched road 'Wincentego Witosa 16' must take highest precedence",
            "Wincentego Witosa 16",
            resolved
        )
    }

    @Test
    fun `OSRM road centerline retains house number from Nominatim`() {
        val resolved = resolveCanonicalStreet(
            osrmStreet = "Przecznia",
            osmStreet = "Przecznia 17",
            osmRoadRef = null,
            osmHouseNum = "17",
            geocoderThoroughfare = null,
            geocoderSubThoroughfare = null,
            basePlaceStreet = null
        )
        assertEquals(
            "OSRM snapped street should preserve house number 17",
            "Przecznia 17",
            resolved
        )
    }

    @Test
    fun `Major road ref takes precedence on classified corridors`() {
        // When OSRM returns named road and OSM returns ref "52", strips house number and appends canonical ref
        val resolved = resolveCanonicalStreet(
            osrmStreet = "Krakowska",
            osmStreet = "DK52",
            osmRoadRef = "52",
            osmHouseNum = null,
            geocoderThoroughfare = "Krakowska",
            geocoderSubThoroughfare = "114",
            basePlaceStreet = "Krakowska 114"
        )
        assertEquals(
            "On DK52 corridor, named street with ref must be formatted without house number",
            "Krakowska (DK52)",
            resolved
        )

        // When OSRM returns raw highway designation
        val resolvedHighway = resolveCanonicalStreet(
            osrmStreet = "DK 52",
            osmStreet = "DK52",
            osmRoadRef = "52",
            osmHouseNum = null,
            geocoderThoroughfare = "DK 52",
            geocoderSubThoroughfare = "114",
            basePlaceStreet = "DK 52 114"
        )
        assertEquals("DK52", resolvedHighway)
    }

    @Test
    fun `Fallback to Android Geocoder when OSM has no street data`() {
        val resolved = resolveCanonicalStreet(
            osrmStreet = null,
            osmStreet = null,
            osmRoadRef = null,
            osmHouseNum = null,
            geocoderThoroughfare = "Leśna Polana",
            geocoderSubThoroughfare = "3",
            basePlaceStreet = "Leśna Polana 3"
        )
        assertEquals(
            "When OSM has no street data, fallback to Google Geocoder",
            "Leśna Polana 3",
            resolved
        )
    }

    @Test
    fun `Returns null when all sources have no street`() {
        val resolved = resolveCanonicalStreet(
            osrmStreet = null,
            osmStreet = null,
            osmRoadRef = null,
            osmHouseNum = null,
            geocoderThoroughfare = null,
            geocoderSubThoroughfare = null,
            basePlaceStreet = null
        )
        assertNull("When no street data exists anywhere, result is null", resolved)
    }

    // ── Polish Locality vs Postal City Resolution Tests ──────────────────────────

    @Test
    fun `Postal delivery city does not override village in gmina Kozy`() {
        // Real-world scenario from Screenshot 161157:
        // Building on border of Kozy and Bielsko-Biała:
        // city = "Bielsko-Biała" (postal sorting office)
        // village = "Kozy"
        // municipality = "gmina Kozy"
        val locality = resolvePolishLocality(
            rawCity = "Bielsko-Biała",
            rawTown = null,
            rawVillage = "Kozy",
            rawHamlet = "Krzemionki",
            rawMunicipality = "gmina Kozy"
        )
        assertEquals(
            "Locality must be 'Kozy' (village in gmina Kozy), NOT postal city 'Bielsko-Biała'",
            "Kozy",
            locality
        )
    }

    @Test
    fun `Gmina name is used when village is null but city is neighbouring metropolis`() {
        // If Nominatim returns city="Bielsko-Biała" (postal), village=null, municipality="gmina Kozy"
        val locality = resolvePolishLocality(
            rawCity = "Bielsko-Biała",
            rawTown = null,
            rawVillage = null,
            rawHamlet = null,
            rawMunicipality = "gmina Kozy"
        )
        assertEquals(
            "When village is missing, gmina name 'Kozy' must be used instead of external metropolis",
            "Kozy",
            locality
        )
    }

    @Test
    fun `Metropolis is kept when municipality is not a rural gmina`() {
        // When actually inside Bielsko-Biała city center:
        // city = "Bielsko-Biała", municipality = "Bielsko-Biała"
        val locality = resolvePolishLocality(
            rawCity = "Bielsko-Biała",
            rawTown = null,
            rawVillage = null,
            rawHamlet = null,
            rawMunicipality = "Bielsko-Biała"
        )
        assertEquals(
            "Inside county city, city name 'Bielsko-Biała' must be preserved",
            "Bielsko-Biała",
            locality
        )
    }

    @Test
    fun `Village in gmina Porabka resolves to Czaniec`() {
        // When in Czaniec (gmina Porąbka)
        val locality = resolvePolishLocality(
            rawCity = null,
            rawTown = null,
            rawVillage = "Czaniec",
            rawHamlet = null,
            rawMunicipality = "gmina Porąbka"
        )
        assertEquals("Czaniec", locality)
    }
}
