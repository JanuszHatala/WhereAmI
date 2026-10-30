package online.hatala.whereami

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.osmdroid.util.GeoPoint

class ElevationStatsTest {

    @Test
    fun `returns null when fewer than 2 valid altitude points`() {
        val emptyList = emptyList<GeoPoint>()
        assertNull(computeElevationStats(emptyList))

        val singlePoint = listOf(GeoPoint(50.0, 19.0, 350.0))
        assertNull(computeElevationStats(singlePoint))

        val noAltitudePoints = listOf(
            GeoPoint(50.0, 19.0),
            GeoPoint(50.001, 19.001)
        )
        assertNull(computeElevationStats(noAltitudePoints))
    }

    @Test
    fun `calculates ascent descent and min max correctly without smoothing`() {
        // Raw altitudes: 100 -> 120 -> 110 -> 150 -> 130
        // +20, -10, +40, -20 => Total ascent = 60, Total descent = 30
        val points = listOf(
            GeoPoint(50.0, 19.0, 100.0),
            GeoPoint(50.001, 19.001, 120.0),
            GeoPoint(50.002, 19.002, 110.0),
            GeoPoint(50.003, 19.003, 150.0),
            GeoPoint(50.004, 19.004, 130.0)
        )

        val stats = computeElevationStats(points, smoothingWindowSize = 1)
        assertNotNull(stats)
        assertEquals(60.0, stats!!.totalAscentM, 0.001)
        assertEquals(30.0, stats.totalDescentM, 0.001)
        assertEquals(100.0, stats.minAltitudeM!!, 0.001)
        assertEquals(150.0, stats.maxAltitudeM!!, 0.001)
    }

    @Test
    fun `smoothing window reduces small jitter oscillations`() {
        // Alternating micro-oscillations: 200 -> 202 -> 200 -> 202 -> 200
        val points = listOf(
            GeoPoint(50.0, 19.0, 200.0),
            GeoPoint(50.001, 19.001, 202.0),
            GeoPoint(50.002, 19.002, 200.0),
            GeoPoint(50.003, 19.003, 202.0),
            GeoPoint(50.004, 19.004, 200.0)
        )

        val rawStats = computeElevationStats(points, smoothingWindowSize = 1)!!
        val smoothedStats = computeElevationStats(points, smoothingWindowSize = 3)!!

        // Smoothed ascent must be significantly lower than raw jitter ascent
        assertTrue(smoothedStats.totalAscentM < rawStats.totalAscentM)
    }

    @Test
    fun `calculates peak vertical speed in meters per minute`() {
        // Point 1: 100m at t=0
        // Point 2: 160m at t=60s (1 min) -> vertical speed = 60 m/min
        // Point 3: 170m at t=120s (2 min) -> vertical speed = 10 m/min
        val points = listOf(
            GeoPoint(50.0, 19.0, 100.0),
            GeoPoint(50.001, 19.001, 160.0),
            GeoPoint(50.002, 19.002, 170.0)
        )
        val timestamps = listOf(0L, 60_000L, 120_000L)

        val stats = computeElevationStats(points, smoothingWindowSize = 1, timestampsMs = timestamps)
        assertNotNull(stats)
        assertEquals(60.0, stats!!.peakVerticalSpeedMpm, 0.01)
    }
}
