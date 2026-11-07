package online.hatala.whereami

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrailVectorRepositoryTest {

    @Test
    fun parseOverpassTrails_withDirectWayGeometry_parsesSegmentsSuccessfully() {
        val json = """
        {
          "elements": [
            {
              "type": "way",
              "id": 998877,
              "tags": {
                "highway": "path",
                "name": "Szlak Papieski",
                "osmc:symbol": "red:white:red_bar"
              },
              "geometry": [
                { "lat": 49.820, "lon": 19.300 },
                { "lat": 49.825, "lon": 19.305 },
                { "lat": 49.830, "lon": 19.310 }
              ]
            }
          ]
        }
        """.trimIndent()

        val segments = TrailVectorRepository.parseOverpassTrails(json)
        assertEquals(1, segments.size)

        val seg = segments[0]
        assertEquals(998877L, seg.id)
        assertEquals("Szlak Papieski", seg.name)
        assertEquals(TrailColor.RED, seg.color)
        assertEquals(TrailRouteKind.HIKING, seg.kind)
        assertEquals(3, seg.points.size)
        assertEquals(49.820, seg.points[0].latitude, 0.0001)
        assertEquals(19.300, seg.points[0].longitude, 0.0001)
    }

    @Test
    fun parseOverpassTrails_withRouteRelationAndDirectMemberGeometry_parsesSegments() {
        val json = """
        {
          "elements": [
            {
              "type": "relation",
              "id": 554433,
              "tags": {
                "type": "route",
                "route": "mtb",
                "name": "Beskid MTB Loop",
                "colour": "blue"
              },
              "members": [
                {
                  "type": "way",
                  "ref": 112233,
                  "role": "",
                  "geometry": [
                    { "lat": 49.750, "lon": 19.200 },
                    { "lat": 49.755, "lon": 19.205 }
                  ]
                }
              ]
            }
          ]
        }
        """.trimIndent()

        val segments = TrailVectorRepository.parseOverpassTrails(json)
        assertEquals(1, segments.size)

        val seg = segments[0]
        assertEquals("Beskid MTB Loop", seg.name)
        assertEquals(TrailColor.BLUE, seg.color)
        assertEquals(TrailRouteKind.MTB, seg.kind)
        assertEquals(2, seg.points.size)
    }

    @Test
    fun trailThicknessMultipliers_provideDistinctBoldDifferentiations() {
        assertEquals(1.0f, TrailThickness.NORMAL.widthMultiplier, 0.001f)
        assertEquals(1.6f, TrailThickness.THICK.widthMultiplier, 0.001f)
        assertEquals(2.4f, TrailThickness.EXTRA_THICK.widthMultiplier, 0.001f)
        assertTrue(TrailThickness.EXTRA_THICK.widthMultiplier > TrailThickness.THICK.widthMultiplier)
        assertTrue(TrailThickness.THICK.widthMultiplier > TrailThickness.NORMAL.widthMultiplier)
    }
}
