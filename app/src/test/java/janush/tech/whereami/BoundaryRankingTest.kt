package janush.tech.whereami

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundaryRankingTest {

    @Test
    fun testSelectsAdministrativeRelationOverAerowayAirfield() {
        // Mock response modeled after Nominatim for Międzybrodzie Żywieckie:
        // Item 0: Lotnisko Żar (aeroway airfield with 4 coordinates)
        // Item 1: Międzybrodzie Żywieckie (administrative relation with full polygon)
        val jsonString = """
        [
          {
            "place_id": 101,
            "osm_type": "way",
            "osm_id": 12345,
            "class": "aeroway",
            "type": "aerodrome",
            "name": "Lotnisko Żar",
            "geojson": {
              "type": "Polygon",
              "coordinates": [
                [
                  [19.230, 49.780],
                  [19.235, 49.780],
                  [19.235, 49.785],
                  [19.230, 49.780]
                ]
              ]
            }
          },
          {
            "place_id": 202,
            "osm_type": "relation",
            "osm_id": 67890,
            "class": "boundary",
            "type": "administrative",
            "name": "Międzybrodzie Żywieckie",
            "geojson": {
              "type": "Polygon",
              "coordinates": [
                [
                  [19.200, 49.770],
                  [19.250, 49.770],
                  [19.260, 49.800],
                  [19.210, 49.800],
                  [19.200, 49.770]
                ]
              ]
            }
          }
        ]
        """.trimIndent()

        val array = JSONArray(jsonString)
        val selectedPoly = BoundaryHelper.selectBestBoundaryPolygon(array)

        assertNotNull("Should select a boundary polygon", selectedPoly)
        assertEquals(5, selectedPoly!!.size)
        // Check that the selected polygon matches the administrative relation coordinates, NOT the airfield
        assertEquals(49.770, selectedPoly[0].latitude, 0.0001)
        assertEquals(19.200, selectedPoly[0].longitude, 0.0001)
    }

    @Test
    fun testIgnoresDegenerateAndMissingGeoJson() {
        val jsonString = """
        [
          {
            "place_id": 301,
            "class": "amenity",
            "type": "restaurant"
          },
          {
            "place_id": 302,
            "class": "place",
            "type": "village",
            "osm_type": "relation",
            "geojson": {
              "type": "Polygon",
              "coordinates": [
                [
                  [19.100, 49.700],
                  [19.120, 49.700],
                  [19.120, 49.720],
                  [19.100, 49.700]
                ]
              ]
            }
          }
        ]
        """.trimIndent()

        val array = JSONArray(jsonString)
        val selectedPoly = BoundaryHelper.selectBestBoundaryPolygon(array)

        assertNotNull(selectedPoly)
        assertEquals(4, selectedPoly!!.size)
        assertEquals(49.700, selectedPoly[0].latitude, 0.0001)
    }
}
