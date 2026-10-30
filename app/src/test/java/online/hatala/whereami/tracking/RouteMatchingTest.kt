package online.hatala.whereami.tracking

import org.junit.Assert.*
import org.junit.Test

class RouteMatchingTest {
    private fun row(lat: Double, lng: Double, flags: Int = 0) = TrackPointRow(null, lat, lng, null, null, null, null, null, flags)

    /** ~N metres of due-north travel per step. */
    private fun northLine(n: Int, stepM: Double = 10.0): List<MatchPoint> =
        (0 until n).map { MatchPoint(50.0 + it * stepM / 111_320.0, 19.0) }

    @Test
    fun selectPoints_dropsStationary_thinsToSpacing_keepsEndpoints() {
        val rows = (0 until 200).map { row(50.0 + it * 1.0 / 111_320.0, 19.0) } + // 1 m steps, 199 m
            listOf(row(50.01, 19.0, TrackPointRow.FLAG_STATIONARY))
        val pts = RouteMatching.selectPoints(rows)
        val expected = (199 / RouteMatching.MIN_POINT_SPACING_M).toInt() + 1
        assertTrue("size=${pts.size}", pts.size in expected..expected + 2)
        assertEquals(rows[0].lat, pts.first().lat, 0.0)
        assertEquals(rows[199].lat, pts.last().lat, 0.0)
        assertTrue("stationary row excluded", pts.none { it.lat == 50.01 })
    }

    @Test
    fun selectPoints_capsTotalPoints() {
        val rows = (0 until 20_000).map { row(50.0 + it * 15.0 / 111_320.0, 19.0) }
        assertTrue(RouteMatching.selectPoints(rows).size <= RouteMatching.MAX_TOTAL_POINTS)
    }

    @Test
    fun chunks_shareBoundaryPoint_andRespectMax() {
        val c = RouteMatching.chunks(200, max = 90)
        assertEquals(0..89, c[0])
        assertEquals(89..178, c[1])
        assertEquals(178..199, c[2])
        assertTrue(RouteMatching.chunks(1).isEmpty())
        assertEquals(listOf(0..1), RouteMatching.chunks(2))
    }

    @Test
    fun chunks_defaultStaysWithinPublicServerLimit() {
        // router.project-osrm.org rejects 11+ trace coordinates with "TooBig".
        assertTrue(RouteMatching.MAX_COORDS_PER_REQUEST <= 10)
        assertTrue(RouteMatching.chunks(95).all { it.last - it.first + 1 <= 10 })
    }

    @Test
    fun buildUrl_usesLngLatOrderAndRadiuses() {
        val url = RouteMatching.buildUrl("https://x", listOf(MatchPoint(50.5, 19.25), MatchPoint(50.6, 19.35)))
        assertTrue(url.startsWith("https://x/match/v1/driving/19.250000,50.500000;19.350000,50.600000?"))
        assertTrue(url.contains("radiuses=25;25"))
        assertTrue(url.contains("geometries=geojson"))
    }

    @Test
    fun parse_acceptsSingleConfidentMatching_andFlipsLngLat() {
        val body = """{"code":"Ok","matchings":[{"confidence":0.9,"geometry":{"coordinates":[[19.0,50.0],[19.1,50.1]]}}]}"""
        val g = RouteMatching.parseMatchResponse(body)!!
        assertEquals(50.0, g[0].lat, 1e-9)
        assertEquals(19.0, g[0].lng, 1e-9)
    }

    @Test
    fun parse_rejectsLowConfidenceMultipleMatchingsAndErrors() {
        assertNull(RouteMatching.parseMatchResponse("""{"code":"Ok","matchings":[{"confidence":0.2,"geometry":{"coordinates":[[1,2],[3,4]]}}]}"""))
        assertNull(RouteMatching.parseMatchResponse("""{"code":"Ok","matchings":[{"confidence":0.9,"geometry":{"coordinates":[[1,2],[3,4]]}},{"confidence":0.9,"geometry":{"coordinates":[[1,2],[3,4]]}}]}"""))
        assertNull(RouteMatching.parseMatchResponse("""{"code":"NoMatch"}"""))
        assertNull(RouteMatching.parseMatchResponse("garbage"))
    }

    @Test
    fun isAcceptable_rejectsSnapOntoParallelRoadFarAway() {
        val raw = northLine(30)
        val nearSnap = raw.map { MatchPoint(it.lat, it.lng + 10.0 / 71_000.0) } // ~10 m east
        val farSnap = raw.map { MatchPoint(it.lat, it.lng + 120.0 / 71_000.0) } // ~120 m east
        assertTrue(RouteMatching.isAcceptable(raw, nearSnap))
        assertFalse(RouteMatching.isAcceptable(raw, farSnap))
    }

    @Test
    fun matchTrack_stitchesChunks_withoutDuplicatingBoundary() {
        val pts = northLine(200)
        val api = RouteMatchApi { raw -> ChunkResult.Matched(raw.map { MatchPoint(it.lat, it.lng + 5.0 / 71_000.0) }) }
        val out = RouteMatching.matchTrack(pts, api) as TripMatchOutcome.Matched
        // 3 chunks sharing 2 boundary points: 200 raw points -> 200 stitched points
        assertEquals(200, out.geometry.size)
        assertEquals(1.0, out.matchedChunkFraction, 0.0)
    }

    @Test
    fun matchTrack_transientFailure_requestsRetry_deterministicFailureFallsBackToRaw() {
        val pts = northLine(200)
        assertEquals(TripMatchOutcome.Retry, RouteMatching.matchTrack(pts, { ChunkResult.Transient }))
        var call = 0
        val mostlyOk = RouteMatchApi { raw ->
            call++
            if (call % 3 == 2) ChunkResult.NoMatch else ChunkResult.Matched(raw)
        }
        val partial = RouteMatching.matchTrack(pts, mostlyOk)
        // ~2 of 3 chunks matched = 0.67 < 0.8 -> rejected; raw stays the only geometry
        assertEquals(TripMatchOutcome.Rejected, partial)
    }

    @Test
    fun matchTrack_abortsBetweenChunks_whenShouldContinueFalse() {
        var calls = 0
        val api = RouteMatchApi { calls++; ChunkResult.Matched(it) }
        val out = RouteMatching.matchTrack(northLine(200), api, shouldContinue = { calls < 1 })
        assertEquals(TripMatchOutcome.Retry, out)
        assertEquals(1, calls)
    }

    @Test
    fun matchTrack_tooFewPoints_isRejectedWithoutNetwork() {
        var calls = 0
        val out = RouteMatching.matchTrack(northLine(2), { calls++; ChunkResult.NoMatch })
        assertEquals(TripMatchOutcome.Rejected, out)
        assertEquals(0, calls)
    }

    @Test
    fun geometryJson_roundTrips() {
        val g = listOf(MatchPoint(50.123456, 19.654321), MatchPoint(50.2, 19.7))
        val back = RouteMatching.geometryFromJson(RouteMatching.geometryToJson(g))!!
        assertEquals(2, back.size)
        assertEquals(50.123456, back[0].latitude, 1e-9)
        assertNull(RouteMatching.geometryFromJson("[]"))
    }
}
