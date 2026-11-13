package online.hatala.whereami

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.osmdroid.util.GeoPoint

class OutdoorFeatureTest {

    @Test
    fun testOutdoorAreaPolygonContainment() {
        val sampleRange = OutdoorArea(
            id = "rel_1001",
            name = "Alpine Sample Range",
            nameEn = "Alpine Sample Range",
            kind = OutdoorAreaKind.MOUNTAIN_RANGE,
            osmRelationId = 1001L
        )
        val poly = listOf(
            GeoPoint(47.10, 11.10),
            GeoPoint(47.10, 11.45),
            GeoPoint(47.30, 11.45),
            GeoPoint(47.30, 11.10),
            GeoPoint(47.10, 11.10)
        )
        val areaWithPoly = OutdoorAreaWithPolygon(
            area = sampleRange,
            minLat = 47.10,
            maxLat = 47.30,
            minLon = 11.10,
            maxLon = 11.45,
            polygon = poly
        )

        assertTrue(areaWithPoly.contains(47.20, 11.25))
        assertFalse(areaWithPoly.contains(47.50, 11.25))
        assertTrue(BoundaryHelper.polygonContains(poly, 47.20, 11.25))
        assertFalse(BoundaryHelper.polygonContains(poly, 46.90, 11.25))
    }

    @Test
    fun testParseTrailColor() {
        assertEquals(TrailColor.RED, OutdoorContextResolver.parseTrailColor("red:white:red_bar", null))
        assertEquals(TrailColor.BLUE, OutdoorContextResolver.parseTrailColor(null, "blue"))
        assertEquals(TrailColor.GREEN, OutdoorContextResolver.parseTrailColor("green:white:green_bar", "green"))
        assertEquals(TrailColor.YELLOW, OutdoorContextResolver.parseTrailColor(null, "yellow"))
        assertEquals(TrailColor.BLACK, OutdoorContextResolver.parseTrailColor("black:white:black_bar", null))
        assertEquals(TrailColor.NONE, OutdoorContextResolver.parseTrailColor(null, null))
    }

    @Test
    fun testParseOverpassTrails() {
        val overpassJson = """
        {
          "elements": [
            {"type": "node", "id": 1, "lat": 49.80, "lon": 19.20},
            {"type": "node", "id": 2, "lat": 49.81, "lon": 19.21},
            {"type": "node", "id": 3, "lat": 49.82, "lon": 19.22},
            {
              "type": "way",
              "id": 10,
              "nodes": [1, 2, 3],
              "tags": {
                "highway": "path"
              }
            },
            {
              "type": "relation",
              "id": 100,
              "members": [
                {"type": "way", "ref": 10, "role": ""}
              ],
              "tags": {
                "type": "route",
                "route": "hiking",
                "name": "Główny Szlak Beskidzki",
                "osmc:symbol": "red:white:red_bar",
                "colour": "red"
              }
            }
          ]
        }
        """.trimIndent()

        val trails = TrailVectorRepository.parseOverpassTrails(overpassJson)
        assertEquals(1, trails.size)
        val trail = trails[0]
        assertEquals(100L, trail.id / 100_000L)
        assertEquals("Główny Szlak Beskidzki", trail.name)
        assertEquals(TrailRouteKind.HIKING, trail.kind)
        assertEquals(TrailColor.RED, trail.color)
        assertEquals(3, trail.points.size)
        assertEquals(49.80, trail.points[0].latitude, 0.0001)
        assertEquals(19.20, trail.points[0].longitude, 0.0001)
    }

    @Test
    fun testOutdoorContextParsing() {
        val overpassJson = """
        {
          "elements": [
            {
              "type": "node",
              "id": 201,
              "lat": 49.805,
              "lon": 19.205,
              "tags": {
                "natural": "peak",
                "name": "Czupel",
                "ele": "933"
              }
            },
            {
              "type": "node",
              "id": 202,
              "lat": 49.808,
              "lon": 19.208,
              "tags": {
                "tourism": "alpine_hut",
                "name": "Schronisko na Magurce"
              }
            },
            {
              "type": "relation",
              "id": 301,
              "tags": {
                "boundary": "national_park",
                "name": "Babiogórski Park Narodowy"
              }
            }
          ]
        }
        """.trimIndent()

        val ctx = OutdoorContextResolver.parseOverpassResponse(
            jsonStr = overpassJson,
            queryLat = 49.80,
            queryLon = 19.20
        )

        assertNotNull(ctx.nearestPeak)
        assertEquals("Czupel", ctx.nearestPeak?.name)
        assertEquals(933, ctx.nearestPeak?.elevationMeters)
        assertEquals(PlaceKind.PEAK, ctx.nearestPeak?.kind)

        assertNotNull(ctx.nearestHut)
        assertEquals("Schronisko na Magurce", ctx.nearestHut?.name)
        assertEquals(PlaceKind.MOUNTAIN_HUT, ctx.nearestHut?.kind)

        assertNotNull(ctx.parkOrReserve)
        assertEquals("Babiogórski Park Narodowy", ctx.parkOrReserve?.name)
        assertEquals(OutdoorAreaKind.NATIONAL_PARK, ctx.parkOrReserve?.kind)

        assertTrue(ctx.hasAnyOutdoor)
    }

    @Test
    fun testKayakingActivityProfile() {
        val profile = ActivityProfile.KAYAKING
        assertEquals("Kayaking", profile.displayName)
        assertEquals("🛶", profile.iconEmoji)
        assertTrue(profile.isOutdoorProfile)

        val hikingProfile = ActivityProfile.HIKING
        assertTrue(hikingProfile.isOutdoorProfile)

        val carProfile = ActivityProfile.CAR
        assertFalse(carProfile.isOutdoorProfile)
    }

    @Test
    fun testPlaceKindOutdoorTypes() {
        val kinds = listOf(
            PlaceKind.PEAK,
            PlaceKind.MOUNTAIN_PASS,
            PlaceKind.MOUNTAIN_HUT,
            PlaceKind.VALLEY,
            PlaceKind.MOUNTAIN_RANGE,
            PlaceKind.PROTECTED_AREA,
            PlaceKind.TRAIL,
            PlaceKind.WATER_BODY
        )
        for (kind in kinds) {
            assertTrue(kind != PlaceKind.LOCALITY)
            assertNotNull(kind.iconEmoji)
            assertNotNull(kind.displayName)
        }
    }
}
