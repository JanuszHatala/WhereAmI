package online.hatala.whereami

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchHelperPhotonTest {

    @Test
    fun parsePhotonResponse_mapsNameStreetAndCountry() {
        val json = """
            {
              "type": "FeatureCollection",
              "features": [
                {
                  "type": "Feature",
                  "geometry": { "type": "Point", "coordinates": [19.3383, 49.8550] },
                  "properties": {
                    "name": "Andrychów",
                    "city": "Andrychów",
                    "state": "Małopolskie",
                    "country": "Poland",
                    "countrycode": "PL",
                    "osm_key": "place",
                    "osm_value": "town",
                    "type": "city",
                    "importance": 0.45
                  }
                },
                {
                  "type": "Feature",
                  "geometry": { "type": "Point", "coordinates": [19.05, 49.82] },
                  "properties": {
                    "street": "Andersa",
                    "housenumber": "12",
                    "city": "Bielsko-Biała",
                    "country": "Poland",
                    "countrycode": "PL",
                    "osm_key": "place",
                    "osm_value": "house",
                    "type": "house"
                  }
                }
              ]
            }
        """.trimIndent()

        val results = SearchHelper.parsePhotonResponse(json)
        assertEquals(2, results.size)
        assertEquals("Andrychów", results[0].title)
        assertEquals("pl", results[0].countryCode)
        assertEquals("city", results[0].placeType)
        assertEquals(0.45, results[0].importance, 1e-6)
        assertTrue(results[0].subtitle.contains("Poland"))
        assertEquals("Andersa 12", results[1].title)
        assertEquals("house", results[1].placeType)
        assertEquals("photon", results[1].source)
    }

    @Test
    fun parseNominatimSearch_extractsImportanceAndType() {
        val json = """
            [
              {
                "lat": "49.855",
                "lon": "19.338",
                "display_name": "Andrychów, powiat wadowicki, Polska",
                "class": "place",
                "type": "town",
                "importance": 0.5,
                "address": {
                  "town": "Andrychów",
                  "county": "powiat wadowicki",
                  "country": "Polska",
                  "country_code": "pl"
                }
              }
            ]
        """.trimIndent()
        val results = SearchHelper.parseNominatimSearch(json)
        assertEquals(1, results.size)
        assertEquals("Andrychów", results[0].title)
        assertEquals("town", results[0].placeType)
        assertEquals("nominatim", results[0].source)
        assertEquals(0.5, results[0].importance, 1e-6)
    }

    @Test
    fun parsePhotonResponse_preservesPeakTypeWhenPhotonTypeIsOther() {
        val json = """
            {
              "type": "FeatureCollection",
              "features": [
                {
                  "type": "Feature",
                  "geometry": { "type": "Point", "coordinates": [16.2780, 50.6808] },
                  "properties": {
                    "name": "Waligóra",
                    "city": "Grzmiąca",
                    "state": "województwo dolnośląskie",
                    "country": "Polska",
                    "countrycode": "PL",
                    "osm_key": "natural",
                    "osm_value": "peak",
                    "type": "other"
                  }
                }
              ]
            }
        """.trimIndent()
        val results = SearchHelper.parsePhotonResponse(json)
        assertEquals(1, results.size)
        assertEquals("Waligóra", results[0].title)
        assertEquals("peak", results[0].placeType)
    }

    @Test
    fun parseNominatimSearch_extractsElevationFromExtratags() {
        val json = """
            [
              {
                "lat": "50.6808",
                "lon": "16.2780",
                "display_name": "Waligóra, Grzmiąca, Polska",
                "class": "natural",
                "type": "peak",
                "osm_type": "node",
                "osm_id": 2470310941,
                "importance": 0.3,
                "extratags": {
                  "ele": "933.9"
                },
                "address": {
                  "country_code": "pl"
                }
              }
            ]
        """.trimIndent()
        val results = SearchHelper.parseNominatimSearch(json)
        assertEquals(1, results.size)
        assertEquals(933, results[0].elevationMeters)
        assertEquals("peak", results[0].placeType)
    }

    @Test
    fun buildPhotonUrl_includesOsmTagsForPeaksAndPasses() {
        val url = SearchHelper.buildPhotonUrl("Waligóra", 50.0, 19.0, category = SearchCategory.PEAKS_PASSES)
        assertTrue(url.contains("osm_tag=natural%3Apeak"))
        assertTrue(url.contains("osm_tag=mountain_pass%3Ayes"))
    }

    @Test
    fun matchesCategory_filtersVillagesOutOfPeaksAndPasses() {
        val peak = SearchResultItem("Chełmiec", "Szczawno-Zdrój", org.osmdroid.util.GeoPoint(50.77, 16.21), placeType = "peak")
        val pass = SearchResultItem("Przełęcz Kocierska", "Andrychów", org.osmdroid.util.GeoPoint(49.77, 19.30), placeType = "mountain_pass")
        val village = SearchResultItem("Chełmiec", "gmina Chełmiec", org.osmdroid.util.GeoPoint(49.63, 20.66), placeType = "village")

        assertTrue(SearchHelper.matchesCategory(SearchCategory.PEAKS_PASSES, peak))
        assertTrue(SearchHelper.matchesCategory(SearchCategory.PEAKS_PASSES, pass))
        assertTrue(!SearchHelper.matchesCategory(SearchCategory.PEAKS_PASSES, village))
    }

    @Test
    fun iconForPlaceType_returnsDistinctIcons() {
        assertEquals("🏔️", SearchHelper.iconForPlaceType("peak"))
        assertEquals("⛰️", SearchHelper.iconForPlaceType("mountain_pass"))
        assertEquals("🏙️", SearchHelper.iconForPlaceType("city"))
        assertEquals("🏘️", SearchHelper.iconForPlaceType("village"))
        assertEquals("🛣️", SearchHelper.iconForPlaceType("street"))
        assertEquals("🥾", SearchHelper.iconForPlaceType("trail (hiking)"))
    }
}
