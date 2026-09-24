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
        // High max visits scenario (e.g. 10 visits)
        assertEquals(HeatMapTier.TIER_1_COLD, HeatMapEngine.determineTier(visitCount = 1, maxVisits = 10))
        assertEquals(HeatMapTier.TIER_2_WARM, HeatMapEngine.determineTier(visitCount = 2, maxVisits = 10))
        assertEquals(HeatMapTier.TIER_3_HOT, HeatMapEngine.determineTier(visitCount = 5, maxVisits = 10))
        assertEquals(HeatMapTier.TIER_4_PEAK, HeatMapEngine.determineTier(visitCount = 8, maxVisits = 10))

        // Single visit scenario
        assertEquals(HeatMapTier.TIER_1_COLD, HeatMapEngine.determineTier(visitCount = 1, maxVisits = 1))
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
        // Check that we have both peak (Tier 4 or Warm/Peak depending on maxVisits=2) and cold polylines
        val coldPaths = result.tierPolylines[HeatMapTier.TIER_1_COLD] ?: emptyList()
        val peakPaths = result.tierPolylines[HeatMapTier.TIER_4_PEAK] ?: emptyList()

        assertTrue("Should have cold paths for once-visited branches", coldPaths.isNotEmpty())
        assertTrue("Should have higher-tier paths for shared corridor A->B", peakPaths.isNotEmpty())
    }
}
