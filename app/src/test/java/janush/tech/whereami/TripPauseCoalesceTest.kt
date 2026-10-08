package janush.tech.whereami

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripPauseCoalesceTest {

    private fun computeDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val earthRadius = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return (earthRadius * c).toFloat()
    }

    private fun simulateCoalesce(
        existingPauses: List<TripPause>,
        candidate: TripPause
    ): List<TripPause> {
        val pauses = existingPauses.toMutableList()
        val prevPause = pauses.lastOrNull()
        val canCoalesce = if (prevPause != null) {
            val prevEnd = prevPause.endTime ?: (prevPause.startTime + prevPause.durationMs)
            val gapMs = candidate.startTime - prevEnd
            val dist = computeDistanceMeters(
                prevPause.latitude,
                prevPause.longitude,
                candidate.latitude,
                candidate.longitude
            )
            // Coalesce if overlapping (gapMs <= 0) or close proximity adjacent (gapMs in 0..60s) within 30m
            gapMs <= 60_000L && dist <= 30.0f
        } else false

        if (canCoalesce && prevPause != null) {
            val prevEnd = prevPause.endTime ?: (prevPause.startTime + prevPause.durationMs)
            val candEnd = candidate.endTime ?: (candidate.startTime + candidate.durationMs)
            val combinedEnd = maxOf(prevEnd, candEnd)
            val combinedStart = minOf(prevPause.startTime, candidate.startTime)
            val merged = prevPause.copy(
                startTime = combinedStart,
                endTime = combinedEnd,
                durationMs = (combinedEnd - combinedStart).coerceAtLeast(prevPause.durationMs + candidate.durationMs)
            )
            pauses[pauses.size - 1] = merged
        } else {
            pauses.add(candidate)
        }
        return pauses
    }

    @Test
    fun testOverlappingPausesAtSameLocationCoalesceIntoSinglePause() {
        // Reproduce Trip #161 telemetry scenario:
        // Pause 1: 14:34:16 to 14:44:07 (~9.8 min) at (49.8501051, 19.2451824)
        // Pause 2: 14:34:10 to 14:44:39 (~10.4 min) at (49.8501051, 19.2451824)
        val p1 = TripPause(
            startTime = 1791376456144L,
            endTime = 1791377047079L,
            latitude = 49.8501051,
            longitude = 19.2451824,
            durationMs = 590935L,
            pointIndex = 1622,
            distanceMeters = 18643.9
        )

        val p2 = TripPause(
            startTime = 1791376450222L,
            endTime = 1791377079124L,
            latitude = 49.8501051,
            longitude = 19.2451824,
            durationMs = 628902L,
            pointIndex = 1626,
            distanceMeters = 18675.2
        )

        val result = simulateCoalesce(listOf(p1), p2)

        assertEquals("Overlapping pauses at same cluster must coalesce into 1 pause", 1, result.size)
        val merged = result.first()
        assertEquals("Merged start should be earliest start", 1791376450222L, merged.startTime)
        assertEquals("Merged end should be latest end", 1791377079124L, merged.endTime)
        assertTrue("Merged duration must span entire window", merged.durationMs >= 628902L)
    }

    @Test
    fun testAdjacentPausesWithin60SecondsAnd30MetersCoalesce() {
        // Stop #1 ends at t = 100_000
        val p1 = TripPause(
            startTime = 10_000L,
            endTime = 100_000L,
            latitude = 49.8500,
            longitude = 19.2450,
            durationMs = 90_000L
        )

        // Stop #2 starts at t = 130_000 (30s later, within 60s) at 10m away
        val p2 = TripPause(
            startTime = 130_000L,
            endTime = 200_000L,
            latitude = 49.85008,
            longitude = 19.24508,
            durationMs = 70_000L
        )

        val result = simulateCoalesce(listOf(p1), p2)

        assertEquals("Adjacent pauses within 60s and 30m must coalesce", 1, result.size)
        assertEquals(10_000L, result[0].startTime)
        assertEquals(200_000L, result[0].endTime)
    }

    @Test
    fun testDistantPausesBeyond30MetersDoNotCoalesce() {
        val p1 = TripPause(
            startTime = 10_000L,
            endTime = 100_000L,
            latitude = 49.8500,
            longitude = 19.2450,
            durationMs = 90_000L
        )

        // Stop #2 is 500m away
        val p2 = TripPause(
            startTime = 120_000L,
            endTime = 200_000L,
            latitude = 49.8550,
            longitude = 19.2450,
            durationMs = 80_000L
        )

        val result = simulateCoalesce(listOf(p1), p2)

        assertEquals("Pauses > 30m apart must remain distinct", 2, result.size)
    }

    @Test
    fun testPausesBeyond60SecondsGapDoNotCoalesce() {
        val p1 = TripPause(
            startTime = 10_000L,
            endTime = 100_000L,
            latitude = 49.8500,
            longitude = 19.2450,
            durationMs = 90_000L
        )

        // Stop #2 starts 5 minutes later (gap = 300s > 60s)
        val p2 = TripPause(
            startTime = 400_000L,
            endTime = 500_000L,
            latitude = 49.8500,
            longitude = 19.2450,
            durationMs = 100_000L
        )

        val result = simulateCoalesce(listOf(p1), p2)

        assertEquals("Pauses with gap > 60s must remain distinct", 2, result.size)
    }
}
