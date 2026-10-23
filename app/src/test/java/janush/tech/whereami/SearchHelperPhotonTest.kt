package janush.tech.whereami

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
}
