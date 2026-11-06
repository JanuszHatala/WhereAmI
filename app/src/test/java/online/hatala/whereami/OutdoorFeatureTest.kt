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
    fun testOfflineOutdoorRegionContainment() {
        val jsonStr = """
        {
          "type": "FeatureCollection",
          "features": [
            {
              "type": "Feature",
              "properties": {
                "id": "beskid_maly",
                "name": "Beskid Mały",
                "name_en": "Little Beskids",
                "kind": "mountain_range",
                "osm_id": 1001,
                "importance": 0.85
              },
              "geometry": {
                "type": "Polygon",
                "coordinates": [
                  [
                    [19.10, 49.75],
                    [19.45, 49.75],
                    [19.45, 49.88],
                    [19.10, 49.88],
                    [19.10, 49.75]
                  ]
                ]
              }
            },
            {
              "type": "Feature",
              "properties": {
                "id": "tatry",
                "name": "Tatry",
                "name_en": "Tatra Mountains",
                "kind": "mountain_range",
                "osm_id": 1002,
                "importance": 0.95
              },
              "geometry": {
                "type": "Polygon",
                "coordinates": [
                  [
                    [19.80, 49.15],
                    [20.25, 49.15],
                    [20.25, 49.30],
                    [19.80, 49.30],
                    [19.80, 49.15]
                  ]
                ]
              }
            }
          ]
        }
        """.trimIndent()

        OfflineOutdoorRegionIndex.loadFromJsonString(jsonStr)

        // Point inside Beskid Mały (e.g. Czaniec / Porąbka 49.80, 19.20)
        val regionsMaly = OfflineOutdoorRegionIndex.findContainingRegions(49.80, 19.20)
        assertEquals(1, regionsMaly.size)
        assertEquals("Beskid Mały", regionsMaly[0].area.name)
        assertEquals(OutdoorAreaKind.MOUNTAIN_RANGE, regionsMaly[0].area.kind)

        // Point inside Tatry (e.g. Morskie Oko 49.20, 20.07)
        val regionsTatry = OfflineOutdoorRegionIndex.findContainingRegions(49.20, 20.07)
        assertEquals(1, regionsTatry.size)
        assertEquals("Tatry", regionsTatry[0].area.name)

        // Point outside both (e.g. Kraków 50.06, 19.94)
        val regionsKrakow = OfflineOutdoorRegionIndex.findContainingRegions(50.06, 19.94)
        assertTrue(regionsKrakow.isEmpty())

        // Search by query
        val hit = OfflineOutdoorRegionIndex.getPolygonByIdOrName("beskid")
        assertNotNull(hit)
        assertEquals("Beskid Mały", hit?.area?.name)
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
