package online.hatala.whereami

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.osmdroid.util.GeoPoint
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

class OutdoorResilienceTest {

    private var server: HttpServer? = null

    @Before
    fun setUp() {
        OutdoorContextResolver.resetCooldownsForTest()
        DataAcquisitionManager.reportSuccess()
    }

    @After
    fun tearDown() {
        server?.stop(0)
        server = null
        OutdoorContextResolver.resetCooldownsForTest()
        DataAcquisitionManager.reportSuccess()
    }

    @Test
    fun overpassQueryFailsOverFrom429And503ToHealthyMirrorAndRecoversHealth() {
        val m1Hits = AtomicInteger(0)
        val m2Hits = AtomicInteger(0)
        val m3Hits = AtomicInteger(0)

        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/m1") { ex ->
                m1Hits.incrementAndGet()
                val bytes = "Too Many Requests".toByteArray()
                ex.sendResponseHeaders(429, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            createContext("/m2") { ex ->
                m2Hits.incrementAndGet()
                val bytes = "Service Unavailable".toByteArray()
                ex.sendResponseHeaders(503, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            createContext("/m3") { ex ->
                m3Hits.incrementAndGet()
                val body = """{"version":0.6,"elements":[]}""".toByteArray()
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            start()
        }
        server = srv

        val base = "http://127.0.0.1:${srv.address.port}"
        val endpoints = listOf("$base/m1", "$base/m2", "$base/m3")

        DataAcquisitionManager.reportError(java.net.SocketTimeoutException("prior timeout"))
        val first = OutdoorContextResolver.executeOverpassQuery(
            ql = "[out:json];node(1);",
            connectTimeoutMs = 2000,
            readTimeoutMs = 2000,
            tag = "TEST",
            endpoints = endpoints
        )

        assertNotNull("Query should succeed via third mirror after 429 and 503", first)
        assertTrue(first!!.contains("\"elements\""))
        assertEquals(DataAcquisitionStatus.HEALTHY, DataAcquisitionManager.health.value.status)

        val m1AfterFirst = m1Hits.get()
        val m2AfterFirst = m2Hits.get()
        val m3AfterFirst = m3Hits.get()

        // Subsequent call should skip cooled-down m1 and m2 and hit m3 directly
        val second = OutdoorContextResolver.executeOverpassQuery(
            ql = "[out:json];node(1);",
            connectTimeoutMs = 2000,
            readTimeoutMs = 2000,
            tag = "TEST",
            endpoints = endpoints
        )
        assertNotNull(second)
        assertEquals(m1AfterFirst, m1Hits.get())
        assertEquals(m2AfterFirst, m2Hits.get())
        assertEquals(m3AfterFirst + 1, m3Hits.get())
    }

    @Test
    fun overpassQueryAll429TransitionsToRateLimitedAndAll5xxToCachedOnly() {
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/limit1") { ex ->
                ex.sendResponseHeaders(429, -1)
                ex.close()
            }
            createContext("/limit2") { ex ->
                ex.sendResponseHeaders(429, -1)
                ex.close()
            }
            createContext("/err1") { ex ->
                ex.sendResponseHeaders(503, -1)
                ex.close()
            }
            start()
        }
        server = srv
        val base = "http://127.0.0.1:${srv.address.port}"

        val res429 = OutdoorContextResolver.executeOverpassQuery(
            ql = "[out:json];node(1);",
            endpoints = listOf("$base/limit1", "$base/limit2")
        )
        assertNull(res429)
        assertEquals(DataAcquisitionStatus.RATE_LIMITED_429, DataAcquisitionManager.health.value.status)

        OutdoorContextResolver.resetCooldownsForTest()
        val res503 = OutdoorContextResolver.executeOverpassQuery(
            ql = "[out:json];node(1);",
            endpoints = listOf("$base/err1")
        )
        assertNull(res503)
        assertEquals(DataAcquisitionStatus.CACHED_ONLY, DataAcquisitionManager.health.value.status)
    }

    @Test
    fun parseOverpassResponseResolvesWorldwidePointMountainRangeValleyAndPois() {
        // Test anywhere in the world (Pennine Alps / Switzerland): point range <= 15km, valley <= 3km, POIs
        val qLat = 46.0207
        val qLon = 7.7491
        val json = """
            {
              "elements": [
                {
                  "type": "node",
                  "id": 1001,
                  "lat": 46.0350,
                  "lon": 7.7600,
                  "tags": {
                    "natural": "mountain_range",
                    "name": "Pennine Alps"
                  }
                },
                {
                  "type": "node",
                  "id": 1002,
                  "lat": 46.3000,
                  "lon": 7.7491,
                  "tags": {
                    "natural": "mountain_range",
                    "name": "Distant Range Too Far"
                  }
                },
                {
                  "type": "way",
                  "id": 2001,
                  "center": { "lat": 46.0220, "lon": 7.7500 },
                  "geometry": [
                    { "lat": 46.0210, "lon": 7.7490 },
                    { "lat": 46.0230, "lon": 7.7510 }
                  ],
                  "tags": {
                    "natural": "valley",
                    "name": "Mattertal"
                  }
                },
                {
                  "type": "node",
                  "id": 3001,
                  "lat": 46.0212,
                  "lon": 7.7495,
                  "tags": {
                    "natural": "spring",
                    "name": "Alpine Source"
                  }
                },
                {
                  "type": "node",
                  "id": 3002,
                  "lat": 46.0218,
                  "lon": 7.7502,
                  "tags": {
                    "natural": "cave_entrance",
                    "name": "Ice Grotto"
                  }
                }
              ]
            }
        """.trimIndent()

        val ctx = OutdoorContextResolver.parseOverpassResponse(json, qLat, qLon)
        assertEquals("Pennine Alps", ctx.mountainRange?.name)
        assertEquals("Mattertal", ctx.valley?.name)
        val poiNames = ctx.nearestFeatures.filter { it.kind == PlaceKind.POI }.map { it.name }
        assertTrue(poiNames.contains("Alpine Source"))
        assertTrue(poiNames.contains("Ice Grotto"))
    }

    @Test
    fun osm06MapXmlFallbackParsesOutdoorContextAndVectorTrailsAndHttpFetchWorks() {
        val qLat = 47.0010
        val qLon = 11.0010
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <osm version="0.6">
              <node id="1" lat="47.0015" lon="11.0015">
                <tag k="natural" v="peak"/>
                <tag k="name" v="Alpine Peak"/>
                <tag k="ele" v="2450"/>
              </node>
              <node id="2" lat="47.0011" lon="11.0011">
                <tag k="natural" v="spring"/>
                <tag k="name" v="Clear Spring"/>
              </node>
              <node id="10" lat="47.0000" lon="11.0000"/>
              <node id="11" lat="47.0000" lon="11.0020"/>
              <node id="12" lat="47.0020" lon="11.0020"/>
              <node id="13" lat="47.0020" lon="11.0000"/>
              <way id="20">
                <nd ref="10"/><nd ref="11"/><nd ref="12"/><nd ref="13"/><nd ref="10"/>
                <tag k="boundary" v="protected_area"/>
                <tag k="name" v="Sample Highland Park"/>
              </way>
              <way id="21">
                <nd ref="10"/><nd ref="12"/>
              </way>
              <relation id="30">
                <member type="way" ref="21" role=""/>
                <tag k="type" v="route"/>
                <tag k="route" v="hiking"/>
                <tag k="name" v="Ridge Traverse"/>
                <tag k="osmc:symbol" v="red:white:red_bar"/>
              </relation>
            </osm>
        """.trimIndent()

        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/api/0.6/map") { ex ->
                val bytes = xml.toByteArray()
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            start()
        }
        server = srv

        val fetched = OutdoorContextResolver.fetchOsmMapXml(
            minLat = 46.99, minLon = 10.99, maxLat = 47.01, maxLon = 11.01,
            baseUrl = "http://127.0.0.1:${srv.address.port}/api/0.6/map"
        )
        assertNotNull(fetched)
        assertEquals(DataAcquisitionStatus.HEALTHY, DataAcquisitionManager.health.value.status)

        val parsedCtx = OutdoorContextResolver.parseOsmMapXmlOutdoorContext(fetched!!, qLat, qLon)
        assertTrue(parsedCtx.hasAnyOutdoor)
        assertEquals("Sample Highland Park", parsedCtx.protectedAreas.firstOrNull()?.name)
        assertEquals("Alpine Peak", parsedCtx.nearestPeak?.name)
        assertEquals(2450, parsedCtx.nearestPeak?.elevationMeters)
        assertTrue(parsedCtx.nearestFeatures.any { it.name == "Clear Spring" && it.kind == PlaceKind.POI })
        assertEquals("Ridge Traverse", parsedCtx.activeTrails.firstOrNull()?.name)
        assertEquals(TrailColor.RED, parsedCtx.activeTrails.firstOrNull()?.color)

        val trails = TrailVectorRepository.parseOsmMapXmlTrails(fetched)
        assertEquals(1, trails.size)
        assertEquals("Ridge Traverse", trails.first().name)
        assertEquals(TrailRouteKind.HIKING, trails.first().kind)
        assertEquals(TrailColor.RED, trails.first().color)
        assertEquals(2, trails.first().points.size)
    }

    @Test
    fun staleFallbackFreshnessAndDistancePropagateToLocationData() {
        val en = PlaceInfo("Old Town", "Main St", null, null, null, "", "Country", "US")
        val pl = en
        val native = en
        val multi = MultiLanguagePlaceInfo(
            en = en,
            pl = pl,
            native = native,
            freshness = PlaceDataFreshness.STALE_FALLBACK,
            staleDistanceMeters = 720f
        )
        val snap = MultiLocationSnapshot(
            multiPlace = multi,
            speedMs = 15f,
            speedKmh = 54f,
            lat = 40.0,
            lng = -75.0,
            altitude = 120.0,
            bearing = 90f,
            accuracy = 6f,
            timestamp = 1_000L
        )
        val locData = snap.toLocationData(DisplayLanguage.EN)
        assertEquals(PlaceDataFreshness.STALE_FALLBACK, locData.dataFreshness)
        assertEquals(720f, locData.staleSinceDistanceMeters)
    }

    @Test
    fun polygonContainsEnvelopePrecheckRejectsOutsideAndAcceptsInside() {
        val square = listOf(
            GeoPoint(47.0, 10.0),
            GeoPoint(47.0, 10.1),
            GeoPoint(47.1, 10.1),
            GeoPoint(47.1, 10.0)
        )
        assertTrue(BoundaryHelper.polygonContains(square, 47.05, 10.05))
        assertFalse(BoundaryHelper.polygonContains(square, 48.0, 10.05))
        assertFalse(BoundaryHelper.polygonContains(square, 47.05, 11.0))
    }

    @Test
    fun parseGeoJsonPolygonsOrdersLargestRingFirstAndPreservesAllOuterRings() {
        val multiPolygon = org.json.JSONObject(
            """
            {
              "type": "MultiPolygon",
              "coordinates": [
                [[[16.80, 50.70], [16.81, 50.70], [16.81, 50.71], [16.80, 50.71], [16.80, 50.70]]],
                [[[16.70, 50.60], [16.80, 50.60], [16.80, 50.70], [16.70, 50.70], [16.70, 50.60]]],
                [[[16.85, 50.75], [16.89, 50.75], [16.89, 50.79], [16.85, 50.79], [16.85, 50.75]]]
              ]
            }
            """.trimIndent()
        )
        val rings = BoundaryHelper.parseGeoJsonPolygons(multiPolygon)
        org.junit.Assert.assertEquals(3, rings.size)
        org.junit.Assert.assertEquals(50.60, rings[0][0].latitude, 1e-6)
        org.junit.Assert.assertEquals(50.75, rings[1][0].latitude, 1e-6)
        org.junit.Assert.assertEquals(50.70, rings[2][0].latitude, 1e-6)

        val single = BoundaryHelper.parseGeoJsonPolygon(multiPolygon)
        org.junit.Assert.assertNotNull(single)
        org.junit.Assert.assertEquals(rings[0].size, single!!.size)
        org.junit.Assert.assertEquals(50.60, single[0].latitude, 1e-6)
    }

    @Test
    fun parseGeoJsonPolygonsSupportsSinglePolygonOuterRingOnly() {
        val polygon = org.json.JSONObject(
            """
            {
              "type": "Polygon",
              "coordinates": [
                [[16.70, 50.60], [16.80, 50.60], [16.80, 50.70], [16.70, 50.70], [16.70, 50.60]],
                [[16.72, 50.62], [16.74, 50.62], [16.74, 50.64], [16.72, 50.64], [16.72, 50.62]]
              ]
            }
            """.trimIndent()
        )
        val rings = BoundaryHelper.parseGeoJsonPolygons(polygon)
        org.junit.Assert.assertEquals(1, rings.size)
        org.junit.Assert.assertEquals(5, rings[0].size)
        org.junit.Assert.assertEquals(50.60, rings[0][0].latitude, 1e-6)
    }

    @Test
    fun outdoorContextDistinguishesNonAuthoritativeFromAuthoritativeEmpty() {
        val nonAuth = OutdoorContext(isAuthoritative = false)
        assertFalse(nonAuth.isAuthoritative)
        assertFalse(nonAuth.hasAnyOutdoor)

        val authEmpty = OutdoorContext()
        assertTrue(authEmpty.isAuthoritative)
        assertFalse(authEmpty.hasAnyOutdoor)
    }
}
