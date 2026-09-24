package janush.tech.whereami

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.osmdroid.util.GeoPoint

class HeatMapEngineTest {

    @Test
    fun testRdpSimplificationCollinearPoints() {
        // Line with 10 collinear points between (50.0, 19.0) and (50.01, 19.01)
        val points = (0..10).map { i ->
            GeoPoint(50.0 + i * 0.001, 19.0 + i * 0.001)
        }
        val simplified = HeatMapEngine.simplifyRdp(points, epsilonMeters = 10.0)
        // Perfectly collinear points should be reduced to start and end
        assertEquals(2, simplified.size)
        assertEquals(points.first(), simplified.first())
        assertEquals(points.last(), simplified.last())
    }

    @Test
    fun testRdpSimplificationPreservesSharpCorners() {
        // L-shaped track: (50.0, 19.0) -> (50.0, 19.01) [~700m East] -> (50.01, 19.01) [~1100m North]
        val p1 = GeoPoint(50.0, 19.0)
        val p2 = GeoPoint(50.0, 19.01)
        val p3 = GeoPoint(50.01, 19.01)
        val points = listOf(p1, p2, p3)

        val simplified = HeatMapEngine.simplifyRdp(points, epsilonMeters = 10.0)
        assertEquals(3, simplified.size)
        assertEquals(p2, simplified[1])
    }

    @Test
    fun testCellKeyDeterminism() {
        val key1 = HeatMapEngine.cellKey(50.0001, 19.0001)
        val key2 = HeatMapEngine.cellKey(50.0001, 19.0001)
        val key3 = HeatMapEngine.cellKey(50.0050, 19.0050)

        assertEquals(key1, key2)
        assertTrue(key1 != key3)
    }

    @Test
    fun testTierDetermination() {
        // High max visits scenario (e.g. 15 visits)
        assertEquals(HeatMapTier.TIER_1_BLUE, HeatMapEngine.determineTier(visitCount = 1, maxVisits = 15))
        assertEquals(HeatMapTier.TIER_2_CYAN, HeatMapEngine.determineTier(visitCount = 2, maxVisits = 15))
        assertEquals(HeatMapTier.TIER_3_GREEN, HeatMapEngine.determineTier(visitCount = 3, maxVisits = 15))
        assertEquals(HeatMapTier.TIER_4_YELLOW, HeatMapEngine.determineTier(visitCount = 5, maxVisits = 15))
        assertEquals(HeatMapTier.TIER_5_ORANGE, HeatMapEngine.determineTier(visitCount = 8, maxVisits = 15))
        assertEquals(HeatMapTier.TIER_6_RED, HeatMapEngine.determineTier(visitCount = 11, maxVisits = 15))
        assertEquals(HeatMapTier.TIER_7_MAGENTA, HeatMapEngine.determineTier(visitCount = 15, maxVisits = 15))

        // Single visit scenario
        assertEquals(HeatMapTier.TIER_1_BLUE, HeatMapEngine.determineTier(visitCount = 1, maxVisits = 1))
    }

    @Test
    fun testProcessTracksFrequencyDifferentiatesCommonVsUniqueCorridors() {
        // Track 1: from A to B to C
        // Track 2: from A to B to D
        // Segment A -> B is traversed by BOTH tracks (2 visits).
        // Segment B -> C is only in Track 1 (1 visit).
        // Segment B -> D is only in Track 2 (1 visit).
        val ptA = GeoPoint(50.0, 19.0)
        val ptB = GeoPoint(50.005, 19.0)
        val ptC = GeoPoint(50.010, 19.0)
        val ptD = GeoPoint(50.005, 19.01)

        val track1 = listOf(ptA, ptB, ptC)
        val track2 = listOf(ptA, ptB, ptD)

        val result = HeatMapEngine.processTracks(listOf(track1, track2), epsilonMeters = 5.0)

        assertEquals(2, result.maxVisits)
        val coldPaths = result.tierPolylines[HeatMapTier.TIER_1_BLUE] ?: emptyList()
        val redPaths = result.tierPolylines[HeatMapTier.TIER_6_RED] ?: emptyList()

        assertTrue("Should have cold paths for once-visited branches", coldPaths.isNotEmpty())
        assertTrue("Should have higher-tier paths for shared corridor A->B", redPaths.isNotEmpty())
    }

    @Test
    fun testParallelTracksWithinCorridorAreGroupedTogether() {
        // Two parallel tracks 15 meters apart (e.g. opposing lanes or GPS offset on the same road)
        // At lat 50.0, 1 deg lon ~= 71438m. 15m ~= 0.00021 deg lon.
        val trackLane1 = listOf(
            GeoPoint(50.000, 19.00000),
            GeoPoint(50.002, 19.00000),
            GeoPoint(50.005, 19.00000)
        )
        val trackLane2 = listOf(
            GeoPoint(50.000, 19.00021),
            GeoPoint(50.002, 19.00021),
            GeoPoint(50.005, 19.00021)
        )

        val result = HeatMapEngine.processTracks(listOf(trackLane1, trackLane2), epsilonMeters = 5.0)

        // The corridor buffer (~32-35m) must group them together so both tracks see 2 visits
        assertEquals(2, result.maxVisits)
        val redPaths = result.tierPolylines[HeatMapTier.TIER_6_RED] ?: emptyList()
        val bluePaths = result.tierPolylines[HeatMapTier.TIER_1_BLUE] ?: emptyList()

        assertTrue("Both parallel tracks should be grouped as peak tier visits in the corridor", redPaths.isNotEmpty())
        assertTrue("No segments should remain cold since both tracks share the 15m corridor", bluePaths.isEmpty())
    }

    @Test
    fun testConsolidateCorridorsEliminatesDuplicateOverlappingPolylines() {
        val track1 = listOf(
            GeoPoint(50.000, 19.000),
            GeoPoint(50.002, 19.000),
            GeoPoint(50.005, 19.000)
        )
        val track2 = listOf(
            GeoPoint(50.000, 19.0001),
            GeoPoint(50.002, 19.0001),
            GeoPoint(50.005, 19.0001)
        )

        val consolidated = HeatMapEngine.processTracks(
            listOf(track1, track2),
            HeatMapOptions(consolidateCorridors = true, epsilonMeters = 5.0)
        )
        val raw = HeatMapEngine.processTracks(
            listOf(track1, track2),
            HeatMapOptions(consolidateCorridors = false, epsilonMeters = 5.0)
        )

        val consolidatedSegments = consolidated.tierPolylines.values.flatten()
        val rawSegments = raw.tierPolylines.values.flatten()

        // With consolidation ON, the duplicate second track is merged into the single corridor backbone
        assertEquals(1, consolidatedSegments.size)
        // With consolidation OFF, both tracks are drawn as separate lines
        assertEquals(2, rawSegments.size)
    }

    @Test
    fun testMinVisitsFilterSuppressesSingleVisits() {
        val ptA = GeoPoint(50.0, 19.0)
        val ptB = GeoPoint(50.005, 19.0)
        val ptC = GeoPoint(50.010, 19.0)
        val ptD = GeoPoint(50.005, 19.01)

        val track1 = listOf(ptA, ptB, ptC)
        val track2 = listOf(ptA, ptB, ptD)

        // Filter with minVisits = 2 (only frequent routes)
        val filtered = HeatMapEngine.processTracks(
            listOf(track1, track2),
            HeatMapOptions(minVisits = 2, epsilonMeters = 5.0)
        )

        val bluePaths = filtered.tierPolylines[HeatMapTier.TIER_1_BLUE] ?: emptyList()
        val redPaths = filtered.tierPolylines[HeatMapTier.TIER_6_RED] ?: emptyList()

        // Single-visit branches (B->C and B->D) should be filtered out
        assertTrue("Single-visit branches should be filtered out", bluePaths.isEmpty())
        // Shared 2-visit corridor (A->B) should remain visible
        assertTrue("Frequent corridor should remain visible", redPaths.isNotEmpty())
    }

    @Test
    fun testRoundaboutContinuityNotFragmentedByCorridorConsolidation() {
        // A roundabout circle: 8 points around a center
        // Center: 50.000, 19.000, radius ~ 20 meters (~0.0002 deg lat/lon)
        val centerLat = 50.00000
        val centerLon = 19.00000
        val radius = 0.0002
        val angles = listOf(0.0, 45.0, 90.0, 135.0, 180.0, 225.0, 270.0, 315.0, 360.0)
        val roundaboutTrack = angles.map { deg ->
            val rad = Math.toRadians(deg)
            GeoPoint(centerLat + radius * Math.cos(rad), centerLon + radius * Math.sin(rad))
        }

        val result = HeatMapEngine.processTracks(
            listOf(roundaboutTrack),
            HeatMapOptions(consolidateCorridors = true, epsilonMeters = 1.0)
        )

        // All segments of this track must be preserved as a single continuous polyline
        val totalSegments = result.tierPolylines.values.flatten()
        assertEquals("Roundabout must not self-fragment into pieces under corridor consolidation", 1, totalSegments.size)
        // Ensure the full ring points are preserved
        val ringPolyline = totalSegments.first()
        assertTrue("Ring polyline should retain all waypoints without dropped gaps", ringPolyline.size >= 8)
    }
}
