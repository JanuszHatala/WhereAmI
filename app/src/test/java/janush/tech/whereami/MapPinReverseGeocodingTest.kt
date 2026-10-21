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

    @Test
    fun testParseGeoJsonPolygonExtractsCoordinates() {
        val geoJsonStr = """
            {
              "type": "Polygon",
              "coordinates": [
                [
                  [19.20, 49.80],
                  [19.25, 49.80],
                  [19.25, 49.85],
                  [19.20, 49.85],
                  [19.20, 49.80]
                ]
              ]
            }
        """.trimIndent()
        val json = org.json.JSONObject(geoJsonStr)
        val poly = BoundaryHelper.parseGeoJsonPolygon(json)
        org.junit.Assert.assertNotNull(poly)
        assertEquals(5, poly!!.size)
        assertEquals(49.80, poly[0].latitude, 0.0001)
        assertEquals(19.20, poly[0].longitude, 0.0001)
    }

    @Test
    fun testTourismViewpointUsesFeatureNameAsTitleNotCoordinates() {
        val json = """
            {
              "lat": "49.81984",
              "lon": "19.25870",
              "name": "Franoskówki",
              "class": "tourism",
              "type": "viewpoint",
              "address": {
                "road": "Franoskówki",
                "hamlet": "Bielańskie",
                "village": "Wielka Puszcza",
                "municipality": "gmina Porąbka",
                "county": "powiat bielski",
                "state": "województwo śląskie",
                "country": "Polska",
                "country_code": "pl"
              }
            }
        """.trimIndent()

        val result = SearchHelper.parseNominatimReverse(json, 49.81984, 19.25870)
        assertEquals("Franoskówki", result.title)
        assertEquals("Wielka Puszcza", result.localityName)
        assertEquals("Porąbka", result.municipalityName)
        assertTrue(result.subtitle.contains("gm. Porąbka"))
        assertFalse(result.title.contains("49.81984"))
    }

    @Test
    fun testNaturalPeakUsesPeakNameAsTitle() {
        val json = """
            {
              "lat": "49.82000",
              "lon": "19.26000",
              "name": "Magurka Wilkowicka",
              "class": "natural",
              "type": "peak",
              "address": {
                "village": "Bystra",
                "municipality": "gmina Wilkowice",
                "county": "powiat bielski",
                "state": "województwo śląskie",
                "country": "Polska",
                "country_code": "pl"
              }
            }
        """.trimIndent()

        val result = SearchHelper.parseNominatimReverse(json, 49.82000, 19.26000)
        assertEquals("Magurka Wilkowicka", result.title)
        assertEquals("Bystra", result.localityName)
    }

    @Test
    fun testSelectNearestTouristicPoiPrefersPeakOverViewpoint() {
        val overpass = """
            {
              "elements": [
                {
                  "type": "node",
                  "id": 1,
                  "lat": 49.82000,
                  "lon": 19.25900,
                  "tags": { "tourism": "viewpoint", "name": "Near View" }
                },
                {
                  "type": "node",
                  "id": 2,
                  "lat": 49.82100,
                  "lon": 19.26000,
                  "tags": { "natural": "peak", "name": "Distant Peak" }
                }
              ]
            }
        """.trimIndent()
        val poi = SearchHelper.selectNearestTouristicPoi(overpass, 49.81984, 19.25870, maxDistanceMeters = 400f)
        org.junit.Assert.assertNotNull(poi)
        assertEquals("Distant Peak", poi!!.name)
        assertEquals("peak", poi.kind)
    }

    @Test
    fun testSelectNearestTouristicPoiIgnoresBeyondRadius() {
        val overpass = """
            {
              "elements": [
                {
                  "type": "node",
                  "id": 1,
                  "lat": 49.83000,
                  "lon": 19.27000,
                  "tags": { "natural": "peak", "name": "Far Peak" }
                }
              ]
            }
        """.trimIndent()
        val poi = SearchHelper.selectNearestTouristicPoi(overpass, 49.81984, 19.25870, maxDistanceMeters = 400f)
        org.junit.Assert.assertNull(poi)
    }
}
