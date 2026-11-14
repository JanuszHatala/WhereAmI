package online.hatala.whereami

import android.content.ContextWrapper
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files

class TrailVectorRepositoryTest {

    private var server: HttpServer? = null
    private lateinit var tempCacheDir: File

    @Before
    fun setUp() {
        tempCacheDir = Files.createTempDirectory("trail_vec_test").toFile()
        TrailVectorRepository.resetCachesForTest()
        OutdoorContextResolver.resetCooldownsForTest()
        DataAcquisitionManager.reportSuccess()
    }

    @After
    fun tearDown() {
        server?.stop(0)
        server = null
        tempCacheDir.deleteRecursively()
        TrailVectorRepository.resetCachesForTest()
        OutdoorContextResolver.resetCooldownsForTest()
        DataAcquisitionManager.reportSuccess()
    }

    private fun fakeContext() = object : ContextWrapper(null) {
        override fun getCacheDir(): File = tempCacheDir
    }

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
        assertEquals(2.0f, TrailThickness.THICK.widthMultiplier, 0.001f)
        assertEquals(2.5f, TrailThickness.EXTRA_THICK.widthMultiplier, 0.001f)
        assertTrue(TrailThickness.EXTRA_THICK.widthMultiplier > TrailThickness.THICK.widthMultiplier)
        assertTrue(TrailThickness.THICK.widthMultiplier > TrailThickness.NORMAL.widthMultiplier)
    }

    @Test
    fun computeStrokeWidthDp_tapersSmoothlyAcrossAllZoomLevelsAndMatchesStandardBoldTargets() {
        val z17Normal = VectorTrailOverlay.computeStrokeWidthDp(17.0, TrailThickness.NORMAL)
        val z17Thick = VectorTrailOverlay.computeStrokeWidthDp(17.0, TrailThickness.THICK)
        val z17Bold = VectorTrailOverlay.computeStrokeWidthDp(17.0, TrailThickness.EXTRA_THICK)
        val z15Normal = VectorTrailOverlay.computeStrokeWidthDp(15.2, TrailThickness.NORMAL)
        val z13Normal = VectorTrailOverlay.computeStrokeWidthDp(13.8, TrailThickness.NORMAL)
        val z11Normal = VectorTrailOverlay.computeStrokeWidthDp(11.8, TrailThickness.NORMAL)
        val z9Normal = VectorTrailOverlay.computeStrokeWidthDp(9.5, TrailThickness.NORMAL)
        val z7Normal = VectorTrailOverlay.computeStrokeWidthDp(7.0, TrailThickness.NORMAL)

        assertEquals(2.8f, z17Normal, 0.001f)
        assertEquals(z17Normal * 2.0f, z17Thick, 0.001f)
        assertEquals(7.0f, z17Bold, 0.001f)
        assertTrue(z17Normal > z15Normal && z15Normal > z13Normal && z13Normal > z11Normal)
        assertTrue(z11Normal > z9Normal && z9Normal > z7Normal && z7Normal > 0.5f)
    }

    @Test
    fun toTileKeyAndMercatorConversionsHandleWorldwideAndNegativeCoords() {
        assertEquals("tile_-1_-1", TrailVectorRepository.toTileKey(-0.01, -0.01))
        assertEquals("tile_0_0", TrailVectorRepository.toTileKey(0.01, 0.01))

        assertEquals(2239 to 1376, TrailVectorRepository.latLonToTileZ12(50.713, 16.835))
        assertEquals(687 to 1583, TrailVectorRepository.latLonToTileZ12(37.7456, -119.5936))
        assertEquals(1218 to 2695, TrailVectorRepository.latLonToTileZ12(-49.3315, -72.8863))

        val pt = TrailVectorRepository.mercatorToLatLon(1869583.140451771, 6571638.24)
        assertEquals(50.71, pt.latitude, 0.05)
        assertEquals(16.79, pt.longitude, 0.05)
    }

    @Test
    fun parseWaymarkedShieldColorAndRefHandleWorldwideFormatsWithoutTextColorConfusion() {
        assertEquals(TrailColor.YELLOW, TrailVectorRepository.parseWaymarkedShieldColor("osmc_REG_white_yellow-bar"))
        assertEquals(TrailColor.YELLOW, TrailVectorRepository.parseWaymarkedShieldColor("osmc_LOC_white_triangle_yellow"))
        assertEquals(
            TrailColor.YELLOW,
            TrailVectorRepository.parseWaymarkedShieldColor("osmc_REG_white_yellow-diamond_0054004d0042_black")
        )
        assertEquals(
            TrailColor.YELLOW,
            TrailVectorRepository.parseWaymarkedShieldColor("osmc_LOC_yellow_0052005700310035_black")
        )
        assertEquals(TrailColor.RED, TrailVectorRepository.parseWaymarkedShieldColor("cbox_LOC_red"))
        assertEquals(TrailColor.BLUE, TrailVectorRepository.parseWaymarkedShieldColor("ctb_LOC_615CF3_00560048"))
        assertEquals("TJ", TrailVectorRepository.parseWaymarkedShieldRef("ref_LOC_0054004a"))
        assertEquals("GBTMB", TrailVectorRepository.parseWaymarkedShieldRef("ref_LOC_004700420054004d0042"))
    }

    @Test
    fun parseWaymarkedTileJsonHandlesLineStringMultiLineStringNullShieldsAndEducationalMeta() {
        val tileJson = """
        {
          "type": "FeatureCollection",
          "features": [
            {
              "type": "Feature",
              "geometry": {"type": "Point", "coordinates": [1874000.0, 6571000.0]},
              "properties": {"type": "guidepost", "name": "Trailhead"}
            },
            {
              "type": "Feature",
              "geometry": {
                "type": "MultiLineString",
                "coordinates": [
                  [[1874000.0, 6570500.0], [1874100.0, 6570600.0]],
                  [[1874200.0, 6570700.0], [1874300.0, 6570800.0]]
                ]
              },
              "properties": {
                "type": "way",
                "top_relations": [12311554, 4557808],
                "shields": ["ref_LOC_0054004a", "osmc_REG_white_blue-bar"]
              }
            },
            {
              "type": "Feature",
              "geometry": {
                "type": "LineString",
                "coordinates": [[1874400.0, 6570900.0], [1874500.0, 6571000.0]]
              },
              "properties": {
                "type": "way",
                "top_relations": [555],
                "shields": null
              }
            }
          ]
        }
        """.trimIndent()

        val relMeta = mapOf(
            "hiking:12311554" to TrailVectorRepository.parseWaymarkedRelationDetailsJson(
                12311554L,
                """{"id":12311554,"name":"Botanical Path","tags":{"route":"hiking","educational":"yes"}}"""
            )!!,
            "hiking:4557808" to TrailVectorRepository.parseWaymarkedRelationDetailsJson(
                4557808L,
                """{"id":4557808,"name":"Blue Crest Trail","tags":{"route":"hiking","colour":"blue","osmc:symbol":"blue:white:blue_bar"}}"""
            )!!
        )

        val segs = TrailVectorRepository.parseWaymarkedTileJson(
            jsonStr = tileJson,
            subdomain = "hiking",
            relationMeta = relMeta,
            tileX = 2239,
            tileY = 1376
        )

        assertEquals(5, segs.size)
        val eduSegs = segs.filter { it.kind == TrailRouteKind.EDUCATIONAL }
        assertEquals(2, eduSegs.size)
        assertEquals("Botanical Path", eduSegs.first().name)
        assertEquals(12311554L, eduSegs.first().id / 100_000L)

        val blueSegs = segs.filter { it.color == TrailColor.BLUE }
        assertEquals(2, blueSegs.size)
        assertEquals("Blue Crest Trail", blueSegs.first().name)
        assertEquals(TrailRouteKind.HIKING, blueSegs.first().kind)
    }

    @Test
    fun getTrailsForBoundingBoxFetchesWaymarkedAndServesDiskCacheOffline() = runBlocking {
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/tiles/") { ex ->
                val json = if (ex.requestURI.path.contains("/hiking/")) {
                    """
                    {
                      "type": "FeatureCollection",
                      "features": [
                        {
                          "type": "Feature",
                          "geometry": {
                            "type": "LineString",
                            "coordinates": [[1874000.0, 6570500.0], [1874200.0, 6570700.0]]
                          },
                          "properties": {
                            "type": "way",
                            "top_relations": [7001],
                            "shields": ["osmc_REG_white_green-bar"]
                          }
                        }
                      ]
                    }
                    """.trimIndent()
                } else {
                    """{"type":"FeatureCollection","features":[]}"""
                }
                val bytes = json.toByteArray()
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            createContext("/details/") { ex ->
                val bytes = """{"id":7001,"name":"Valley Green Loop","tags":{"route":"hiking","colour":"green"}}""".toByteArray()
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            start()
        }
        server = srv
        val base = "http://127.0.0.1:${srv.address.port}"
        val ctx = fakeContext()
        val bbox = BoundingBox(50.716, 16.840, 50.710, 16.830)

        val onlineTrails = TrailVectorRepository.getTrailsForBoundingBox(
            context = ctx,
            bbox = bbox,
            allowNetwork = true,
            requestedKinds = setOf(TrailRouteKind.HIKING),
            waymarkedTileUrlTemplate = "$base/tiles/{sub}/12/{x}/{y}.json",
            waymarkedDetailsUrlTemplate = "$base/details/{sub}/{id}"
        )

        assertEquals(1, onlineTrails.size)
        assertEquals("Valley Green Loop", onlineTrails[0].name)
        assertEquals(TrailColor.GREEN, onlineTrails[0].color)

        val offlineTrails = TrailVectorRepository.getTrailsForBoundingBox(
            context = ctx,
            bbox = bbox,
            allowNetwork = false,
            requestedKinds = setOf(TrailRouteKind.HIKING),
            waymarkedTileUrlTemplate = "$base/tiles/{sub}/12/{x}/{y}.json",
            waymarkedDetailsUrlTemplate = "$base/details/{sub}/{id}"
        )
        assertEquals(1, offlineTrails.size)
        assertEquals("Valley Green Loop", offlineTrails[0].name)
    }

    @Test
    fun getTrailsForBoundingBoxFallbackCentersOsmMapOnViewportEdgeAndNeverHardcodesTileCenter() = runBlocking {
        var capturedQuery: String? = null
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/tiles/") { ex ->
                val b = "Upstream Error".toByteArray()
                ex.sendResponseHeaders(503, b.size.toLong())
                ex.responseBody.use { it.write(b) }
            }
            createContext("/overpass") { ex ->
                val b = "Overpass Timeout".toByteArray()
                ex.sendResponseHeaders(504, b.size.toLong())
                ex.responseBody.use { it.write(b) }
            }
            createContext("/api/0.6/map") { ex ->
                capturedQuery = ex.requestURI.query
                val xml = """
                    <osm version="0.6">
                      <node id="1" lat="50.7120" lon="16.8330"/>
                      <node id="2" lat="50.7140" lon="16.8370"/>
                      <way id="10"><nd ref="1"/><nd ref="2"/></way>
                      <relation id="42">
                        <member type="way" ref="10" role=""/>
                        <tag k="type" v="route"/>
                        <tag k="route" v="hiking"/>
                        <tag k="name" v="Viewport Edge Trail"/>
                        <tag k="colour" v="blue"/>
                      </relation>
                    </osm>
                """.trimIndent()
                val bytes = xml.toByteArray()
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            start()
        }
        server = srv
        val base = "http://127.0.0.1:${srv.address.port}"
        // Viewport near northern edge of tile [50.64, 50.72] where old tile center (50.68 ± 0.015) missed 50.713
        val edgeViewport = BoundingBox(50.718, 16.842, 50.708, 16.828)

        val fallbackTrails = TrailVectorRepository.getTrailsForBoundingBox(
            context = fakeContext(),
            bbox = edgeViewport,
            allowNetwork = true,
            requestedKinds = setOf(TrailRouteKind.HIKING),
            waymarkedTileUrlTemplate = "$base/tiles/{sub}/12/{x}/{y}.json",
            waymarkedDetailsUrlTemplate = "$base/details/{sub}/{id}",
            overpassEndpoints = listOf("$base/overpass"),
            osmMapBaseUrl = "$base/api/0.6/map"
        )

        assertNotNull(capturedQuery)
        val bboxParam = capturedQuery!!.substringAfter("bbox=").substringBefore("&")
        val parts = bboxParam.split(",").map { it.toDouble() }
        val minLon = parts[0]
        val minLat = parts[1]
        val maxLon = parts[2]
        val maxLat = parts[3]
        assertTrue("Fallback minLat $minLat..$maxLat must cover viewport center 50.713", 50.713 in minLat..maxLat)
        assertTrue("Fallback minLon $minLon..$maxLon must cover viewport center 16.835", 16.835 in minLon..maxLon)

        assertEquals(1, fallbackTrails.size)
        assertEquals("Viewport Edge Trail", fallbackTrails[0].name)
        assertEquals(TrailColor.BLUE, fallbackTrails[0].color)
    }

    @Test
    fun uncoloredTrailSegmentsResolveVibrantKindAccentInsteadOfGray() {
        val edu = TrailVectorSegment(id = 1L, name = "Edu", kind = TrailRouteKind.EDUCATIONAL, color = TrailColor.NONE, points = emptyList())
        val cyc = TrailVectorSegment(id = 2L, name = "Cyc", kind = TrailRouteKind.CYCLING, color = TrailColor.NONE, points = emptyList())
        val mtb = TrailVectorSegment(id = 3L, name = "MTB", kind = TrailRouteKind.MTB, color = TrailColor.NONE, points = emptyList())
        val blue = TrailVectorSegment(id = 4L, name = "Blue", kind = TrailRouteKind.HIKING, color = TrailColor.BLUE, points = emptyList())

        assertFalse(VectorTrailOverlay.resolveStrokeColorHex(edu).equals("#94A3B8", ignoreCase = true))
        assertFalse(VectorTrailOverlay.resolveStrokeColorHex(cyc).equals("#94A3B8", ignoreCase = true))
        assertFalse(VectorTrailOverlay.resolveStrokeColorHex(mtb).equals("#94A3B8", ignoreCase = true))
        assertEquals(TrailColor.BLUE.hexColor, VectorTrailOverlay.resolveStrokeColorHex(blue))
    }

    @Test
    fun wideZoomLoadsCachedTilesAndMacroRoutesAndRouteFetcherRespectsProximity() = runBlocking {
        var macroHitCount = 0
        var waterFallbackHitCount = 0
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/tiles/") { ex ->
                val json = """
                {
                  "type": "FeatureCollection",
                  "features": [
                    {
                      "type": "Feature",
                      "geometry": { "type": "LineString", "coordinates": [[1874000.0, 6570500.0], [1874200.0, 6570700.0]] },
                      "properties": { "type": "way", "top_relations": [8001], "shields": ["osmc_REG_white_red-bar"] }
                    }
                  ]
                }
                """.trimIndent()
                val b = json.toByteArray()
                ex.sendResponseHeaders(200, b.size.toLong())
                ex.responseBody.use { it.write(b) }
            }
            createContext("/details/") { ex ->
                val b = """{"id":8001,"name":"Local Valley Path","tags":{"route":"hiking","colour":"red"}}""".toByteArray()
                ex.sendResponseHeaders(200, b.size.toLong())
                ex.responseBody.use { it.write(b) }
            }
            createContext("/overpass") { ex ->
                val rawQuery = ex.requestURI.rawQuery ?: ""
                val decodedQuery = java.net.URLDecoder.decode(rawQuery, "UTF-8")
                val req = decodedQuery + ex.requestBody.bufferedReader().readText()
                val json = if (req.contains("iwn|nwn|icn|ncn")) {
                    macroHitCount++
                    """
                    {
                      "elements": [
                        {
                          "type": "relation",
                          "id": 9100,
                          "tags": { "type": "route", "route": "hiking", "network": "iwn", "name": "European E8", "colour": "red" },
                          "members": [
                            { "type": "way", "ref": 9101, "geometry": [{"lat":50.5,"lon":16.5},{"lat":50.8,"lon":16.9}] }
                          ]
                        }
                      ]
                    }
                    """.trimIndent()
                } else if (req.contains("way(555)")) {
                    waterFallbackHitCount++
                    """
                    {
                      "elements": [
                        { "type": "way", "id": 555, "geometry": [{"lat":50.713,"lon":16.835},{"lat":50.715,"lon":16.838}] }
                      ]
                    }
                    """.trimIndent()
                } else {
                    """{"elements":[]}"""
                }
                val b = json.toByteArray()
                ex.sendResponseHeaders(200, b.size.toLong())
                ex.responseBody.use { it.write(b) }
            }
            start()
        }
        server = srv
        val base = "http://127.0.0.1:${srv.address.port}"
        val ctx = fakeContext()

        // 1. Prime a local z=12 tile
        val localBbox = BoundingBox(50.716, 16.840, 50.710, 16.830)
        val localTrails = TrailVectorRepository.getTrailsForBoundingBox(
            context = ctx,
            bbox = localBbox,
            allowNetwork = true,
            requestedKinds = setOf(TrailRouteKind.HIKING),
            waymarkedTileUrlTemplate = "$base/tiles/{sub}/12/{x}/{y}.json",
            waymarkedDetailsUrlTemplate = "$base/details/{sub}/{id}",
            overpassEndpoints = listOf("$base/overpass")
        )
        assertTrue(localTrails.any { it.name == "Local Valley Path" })

        // 2. Zoom way out (wide viewport > 1.5 deg span): cached local tile PLUS macro route must both appear
        val wideBbox = BoundingBox(51.8, 17.8, 50.0, 15.8)
        val wideTrails = TrailVectorRepository.getTrailsForBoundingBox(
            context = ctx,
            bbox = wideBbox,
            allowNetwork = true,
            requestedKinds = setOf(TrailRouteKind.HIKING),
            waymarkedTileUrlTemplate = "$base/tiles/{sub}/12/{x}/{y}.json",
            waymarkedDetailsUrlTemplate = "$base/details/{sub}/{id}",
            overpassEndpoints = listOf("$base/overpass")
        )
        assertTrue(macroHitCount >= 1)
        assertTrue("Expected Local Valley Path in wideTrails=${wideTrails.map { it.name }}", wideTrails.any { it.name == "Local Valley Path" })
        assertTrue("Expected European E8 in wideTrails=${wideTrails.map { it.name }}", wideTrails.any { it.name == "European E8" })

        // 3. Waterway click resolution works via preferRelation = false without route tags
        val riverLines = TrailVectorRepository.fetchTrailRouteByOsmId(
            context = ctx,
            osmId = 555L,
            preferRelation = false,
            nearLatLng = GeoPoint(50.714, 16.836),
            overpassEndpoints = listOf("$base/overpass")
        )
        assertEquals(1, waterFallbackHitCount)
        assertEquals(1, riverLines.size)
        assertEquals(2, riverLines[0].size)
    }
}
