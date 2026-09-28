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
            "OSRM map-matched road 'Wincentego Witosa' must take highest precedence (house number stripped)",
            "Wincentego Witosa",
            resolved
        )
    }

    @Test
    fun `OSRM road centerline retains base street name with house number stripped`() {
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
            "OSRM snapped street should strip house number 17",
            "Przecznia",
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

    // ── Locality Transition Decoupling Hysteresis Tests ─────────────────────────

    /**
     * Standalone model of the street hysteresis logic during locality transitions from LocationManager.applyStreetHysteresis.
     */
    private class LocalityStreetHysteresisModel {
        var committedStreetPl: String? = null
        var committedStreetBase: String? = null
        var lastCommittedStreetLocality: String? = null
        var candidateStreetPl: String? = null
        var candidateStreetBase: String? = null
        var candidateStreetCount = 0
        var candidateStreetFirstSeenTime = 0L

        fun processFix(
            rawStreetPl: String?,
            city: String,
            roadRef: String?,
            speedKmh: Float,
            now: Long
        ): String? {
            val rawBase = RoadNameNormalizer.extractBaseStreet(rawStreetPl)
            if (rawStreetPl.isNullOrBlank()) return committedStreetPl

            // Initial commit
            if (committedStreetPl == null || committedStreetBase == null) {
                committedStreetPl = rawStreetPl
                committedStreetBase = rawBase
                lastCommittedStreetLocality = city
                candidateStreetPl = null
                candidateStreetBase = null
                candidateStreetCount = 0
                return committedStreetPl
            }

            if (rawBase.equals(committedStreetBase, ignoreCase = true)) {
                committedStreetPl = rawStreetPl
                candidateStreetPl = null
                candidateStreetBase = null
                candidateStreetCount = 0
                return committedStreetPl
            }

            if (candidateStreetBase != null && candidateStreetBase.equals(rawBase, ignoreCase = true)) {
                candidateStreetCount++
            } else {
                candidateStreetPl = rawStreetPl
                candidateStreetBase = rawBase
                candidateStreetCount = 1
                candidateStreetFirstSeenTime = now
            }

            val isLocalityTransition = lastCommittedStreetLocality != null &&
                    city.isNotBlank() &&
                    !city.equals("Unknown City", ignoreCase = true) &&
                    !city.equals(lastCommittedStreetLocality, ignoreCase = true)

            // When crossing into a confirmed new locality, adopt new locality's street immediately on 1st fix
            if (isLocalityTransition && !rawStreetPl.isNullOrBlank()) {
                committedStreetPl = rawStreetPl
                committedStreetBase = rawBase
                lastCommittedStreetLocality = city
                candidateStreetPl = null
                candidateStreetBase = null
                candidateStreetCount = 0
                return committedStreetPl
            }

            val isCommittedMajor = RoadNameNormalizer.isMajorRoad(committedStreetBase)
            val isCandidateMajor = RoadNameNormalizer.isMajorRoad(rawBase)

            val rawRequiredCount = when {
                speedKmh > 35f -> if (isCommittedMajor && !isCandidateMajor) 7 else 2
                speedKmh > 15f -> if (isCommittedMajor && !isCandidateMajor) 5 else 2
                else -> if (isCommittedMajor && !isCandidateMajor) 5 else 3
            }
            val rawRequiredDuration = when {
                speedKmh > 35f -> if (isCommittedMajor && !isCandidateMajor) 10_000L else 2_000L
                speedKmh > 15f -> if (isCommittedMajor && !isCandidateMajor) 6_000L else 2_000L
                else -> if (isCommittedMajor && !isCandidateMajor) 6_000L else 2_500L
            }

            val requiredCount = rawRequiredCount
            val requiredDuration = rawRequiredDuration
            val candidateDuration = now - candidateStreetFirstSeenTime

            if (candidateStreetCount >= requiredCount && candidateDuration >= requiredDuration) {
                committedStreetPl = candidateStreetPl
                committedStreetBase = candidateStreetBase
                lastCommittedStreetLocality = city
                candidateStreetPl = null
                candidateStreetBase = null
                candidateStreetCount = 0
                return committedStreetPl
            } else {
                if (isLocalityTransition) {
                    val fallbackStreet = if (!roadRef.isNullOrBlank()) {
                        RoadNameNormalizer.normalize(null, roadRef, null)
                    } else rawStreetPl
                    return fallbackStreet
                }
                return committedStreetPl
            }
        }
    }

    @Test
    fun `Locality transition adopts new town street on 1st fix without blanking`() {
        val model = LocalityStreetHysteresisModel()

        // Step 1: Initial commit in Kozy along DK52 (ul. Bielska)
        val initialStreet = model.processFix(
            rawStreetPl = "ul. Bielska (DK52)",
            city = "Kozy",
            roadRef = "DK52",
            speedKmh = 60f,
            now = 1000L
        )
        assertEquals("ul. Bielska (DK52)", initialStreet)
        assertEquals("Kozy", model.lastCommittedStreetLocality)

        // Step 2: Cross boundary into Bielsko-Biała on DK52 (1st fix in Bielsko-Biała, raw is ul. Krakowska)
        // Adopts new locality's street immediately on 1st fix! Zero blanking, zero stale street!
        val fix1 = model.processFix(
            rawStreetPl = "ul. Krakowska (DK52)",
            city = "Bielsko-Biała",
            roadRef = "DK52",
            speedKmh = 60f,
            now = 2000L
        )
        assertEquals(
            "During locality transition, must adopt new town street 'ul. Krakowska (DK52)' immediately on 1st fix",
            "ul. Krakowska (DK52)",
            fix1
        )
        assertEquals("Bielsko-Biała", model.lastCommittedStreetLocality)
    }

    @Test
    fun `Locality transition on residential road without roadRef adopts new street immediately without blanking`() {
        val model = LocalityStreetHysteresisModel()

        // Initial commit in Town A
        model.processFix(
            rawStreetPl = "ul. Polna",
            city = "Town A",
            roadRef = null,
            speedKmh = 30f,
            now = 1000L
        )

        // Cross into Town B on residential road (1st fix)
        val fix1 = model.processFix(
            rawStreetPl = "ul. Leśna",
            city = "Town B",
            roadRef = null,
            speedKmh = 30f,
            now = 2000L
        )
        // Must adopt "ul. Leśna" immediately without returning null or blanking out!
        assertEquals("Residential road in new locality must adopt immediately without blanking", "ul. Leśna", fix1)
        assertEquals("Town B", model.lastCommittedStreetLocality)
    }

    @Test
    fun `Driving speed switch between regular streets commits after 2 confirmations and 2000ms`() {
        val model = LocalityStreetHysteresisModel()

        // Commit on Polna
        model.processFix(
            rawStreetPl = "ul. Polna",
            city = "Kozy",
            roadRef = null,
            speedKmh = 50f,
            now = 1000L
        )

        // Turn onto Krakowska at 60 km/h (Fix 1)
        val fix1 = model.processFix(
            rawStreetPl = "ul. Krakowska",
            city = "Kozy",
            roadRef = null,
            speedKmh = 60f,
            now = 2000L
        )
        // 1st fix preserves current street (anti-jitter)
        assertEquals("ul. Polna", fix1)

        // Fix 2: 2nd agreeing fix after 2000ms -> commits to Krakowska!
        val fix2 = model.processFix(
            rawStreetPl = "ul. Krakowska",
            city = "Kozy",
            roadRef = null,
            speedKmh = 60f,
            now = 4100L
        )
        assertEquals("Switch to ul. Krakowska must commit on 2nd confirmation", "ul. Krakowska", fix2)
    }
}

