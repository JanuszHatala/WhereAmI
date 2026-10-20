package janush.tech.whereami

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.osmdroid.util.GeoPoint

class TripTimeRepairTest {

    @Test
    fun testTripSelectionFilterModeLogic() {
        val trips = listOf(
            TripRecord(id = 1L, startTime = 1000L),
            TripRecord(id = 2L, startTime = 2000L),
            TripRecord(id = 3L, startTime = 3000L),
            TripRecord(id = 4L, startTime = 4000L)
        )
        val selectedIds = setOf(2L, 4L)

        // 1. ALL mode returns all trips
        val allTrips = when (TripSelectionFilterMode.ALL) {
            TripSelectionFilterMode.ALL -> trips
            TripSelectionFilterMode.SELECTED_ONLY -> trips.filter { selectedIds.contains(it.id) }
            TripSelectionFilterMode.UNSELECTED_ONLY -> trips.filter { !selectedIds.contains(it.id) }
        }
        assertEquals(4, allTrips.size)

        // 2. SELECTED_ONLY returns only trips 2 and 4
        val selectedOnly = when (TripSelectionFilterMode.SELECTED_ONLY) {
            TripSelectionFilterMode.ALL -> trips
            TripSelectionFilterMode.SELECTED_ONLY -> trips.filter { selectedIds.contains(it.id) }
            TripSelectionFilterMode.UNSELECTED_ONLY -> trips.filter { !selectedIds.contains(it.id) }
        }
        assertEquals(2, selectedOnly.size)
        assertEquals(listOf(2L, 4L), selectedOnly.map { it.id })

        // 3. UNSELECTED_ONLY returns trips 1 and 3
        val unselectedOnly = when (TripSelectionFilterMode.UNSELECTED_ONLY) {
            TripSelectionFilterMode.ALL -> trips
            TripSelectionFilterMode.SELECTED_ONLY -> trips.filter { selectedIds.contains(it.id) }
            TripSelectionFilterMode.UNSELECTED_ONLY -> trips.filter { !selectedIds.contains(it.id) }
        }
        assertEquals(2, unselectedOnly.size)
        assertEquals(listOf(1L, 3L), unselectedOnly.map { it.id })
    }

    @Test
    fun testTruncatedTripDurationCalculation() {
        // Test values matching actual Trip #214
        val distMeters = 1890.167125
        val avgSpeedKmh = 4.7047758f
        val speedMps = avgSpeedKmh * (1000.0 / 3600.0)

        val durationSec = (distMeters / speedMps).toLong()
        assertEquals(1446L, durationSec) // 24 minutes and 6 seconds

        val startTime = 1792165557653L // 17:45:57
        val recordedEndTime = 1792165558854L // 17:45:58 (1.2s!)
        val actualDurationMs = recordedEndTime - startTime

        assertEquals(1201L, actualDurationMs)

        // Truncated duration detector
        val isTruncated = distMeters > 50.0 && actualDurationMs < 15_000L
        assertTrue(isTruncated)

        // Reconstructed end time
        val reconstructedEndTime = startTime + (durationSec * 1000L)
        assertEquals(1792165557653L + 1446_000L, reconstructedEndTime)
        assertEquals(1446_000L, reconstructedEndTime - startTime)
    }

    @Test
    fun testShortTrip213DurationCalculation() {
        // Test values matching actual Trip #213
        val distMeters = 167.7865
        val avgSpeedKmh = 20.6954f
        val speedMps = avgSpeedKmh * (1000.0 / 3600.0)

        val durationSec = (distMeters / speedMps).toLong()
        assertEquals(29L, durationSec) // 29 seconds

        val startTime = 1792129636480L
        val recordedEndTime = 1792129636479L // -1ms
        val actualDurationMs = recordedEndTime - startTime

        val isTruncated = distMeters > 50.0 && actualDurationMs < 15_000L
        assertTrue(isTruncated)

        val reconstructedEndTime = startTime + (durationSec * 1000L).coerceAtLeast(15_000L)
        assertTrue(reconstructedEndTime - startTime >= 15_000L)
    }

    @Test
    fun testNormalTripIsNotConsideredTruncated() {
        val distMeters = 5000.0
        val avgSpeedKmh = 30.0f
        val expectedDurationMs = ((distMeters / (avgSpeedKmh * (1000.0 / 3600.0))) * 1000L).toLong() // 600_000 ms

        val startTime = 1000L
        val recordedEndTime = startTime + expectedDurationMs // 10 minutes

        val actualDurationMs = recordedEndTime - startTime
        val isTruncated = distMeters > 50.0 && (actualDurationMs < 15_000L || actualDurationMs < expectedDurationMs / 5)
        assertFalse(isTruncated)
    }

    @Test
    fun testElevationMovingAverageSmoothing() {
        // Integer DEM steps: e.g. 319, 319, 321, 321, 326, 328, 337
        val rawAltitudes = listOf(319.0, 319.0, 319.0, 321.0, 321.0, 326.0, 328.0, 337.0, 337.0)
        val rawPairs = rawAltitudes.mapIndexed { idx, alt -> (idx * 10.0) to alt }

        val windowSize = 5.coerceAtMost(rawPairs.size)
        val halfWin = windowSize / 2
        val smoothed = rawPairs.indices.map { i ->
            val startIdx = (i - halfWin).coerceAtLeast(0)
            val endIdx = (i + halfWin).coerceAtMost(rawPairs.size - 1)
            var sumAlt = 0.0
            var count = 0
            for (k in startIdx..endIdx) {
                sumAlt += rawPairs[k].second
                count++
            }
            rawPairs[i].first to (sumAlt / count)
        }

        assertEquals(rawPairs.size, smoothed.size)
        // Check that sharp jump from 328 to 337 is smoothed out
        val jumpIdx = 6 // 328.0 before 337.0
        val smoothedVal = smoothed[jumpIdx].second
        assertTrue("Smoothed value at transition should be between 326 and 337", smoothedVal > 326.0 && smoothedVal < 337.0)

        // First and last distances are preserved
        assertEquals(rawPairs.first().first, smoothed.first().first, 0.001)
        assertEquals(rawPairs.last().first, smoothed.last().first, 0.001)
    }

    @Test
    fun testSmoothElevationProfileGaussianResampling() {
        // Empty or 1-point list should return empty
        assertTrue(smoothElevationProfile(emptyList()).isEmpty())
        assertTrue(smoothElevationProfile(listOf(org.osmdroid.util.GeoPoint(50.0, 20.0, 300.0))).isEmpty())

        // Create a realistic track with a 328m -> 337m cliff step
        val points = mutableListOf<org.osmdroid.util.GeoPoint>()
        for (i in 0..100) {
            // ~5m per point along latitude
            val lat = 50.0 + (i * 0.000045)
            val alt = if (i < 50) 320.0 else 337.0
            points.add(org.osmdroid.util.GeoPoint(lat, 20.0, alt))
        }

        val smoothed = smoothElevationProfile(points, numSamples = 60)
        assertTrue("Should have resampled output points", smoothed.size >= 20)

        // Verify distance strictly increases
        for (i in 0 until smoothed.size - 1) {
            assertTrue("Distance must strictly increase", smoothed[i + 1].first > smoothed[i].first)
        }

        // Verify bounds start at 0 and reach max distance
        assertEquals(0.0, smoothed.first().first, 0.001)
        val distances = calculateCumulativeDistances(points)
        assertEquals(distances.last(), smoothed.last().first, 0.001)

        // Verify that the cliff from 320m to 337m at mid-distance is smooth:
        // Raw slope is 17m / 5m = 3.40 (340%). Gaussian smoothed slope should be under 0.35 (down by 10x).
        for (i in 0 until smoothed.size - 1) {
            val dDist = smoothed[i + 1].first - smoothed[i].first
            val dAlt = kotlin.math.abs(smoothed[i + 1].second - smoothed[i].second)
            val slope = dAlt / dDist
            assertTrue("Smoothed slope should be gradual (< 0.35, down from raw 3.40), was $slope", slope < 0.35)
        }

        // Midpoint altitude should be nicely centered between 320 and 337
        val midSample = smoothed[smoothed.size / 2]
        assertTrue("Midpoint altitude should be smoothly interpolated, was ${midSample.second}",
            midSample.second > 323.0 && midSample.second < 334.0)
    }
}
