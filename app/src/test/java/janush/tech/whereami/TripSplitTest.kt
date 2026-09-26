package janush.tech.whereami

import org.junit.Assert.assertEquals
import org.junit.Test

class TripSplitTest {

    @Test
    fun testCleanPartSuffix() {
        assertEquals("Kraków -> Zakopane", TripDatabaseHelper.cleanPartSuffix("Kraków -> Zakopane - Part 1"))
        assertEquals("Kraków -> Zakopane", TripDatabaseHelper.cleanPartSuffix("Kraków -> Zakopane - Part 2"))
        assertEquals("Kraków -> Zakopane", TripDatabaseHelper.cleanPartSuffix("Kraków -> Zakopane – Part 3"))
        assertEquals("Kraków -> Zakopane", TripDatabaseHelper.cleanPartSuffix("Kraków -> Zakopane (Part 1)"))
        assertEquals("Morning Ride", TripDatabaseHelper.cleanPartSuffix("Morning Ride"))
        assertEquals("Morning Ride", TripDatabaseHelper.cleanPartSuffix("Morning Ride - Part 1 - Part 2"))
    }

    @Test
    fun testPauseDeleteLogic() {
        val p1 = TripPause(startTime = 1000L, endTime = 1300L, latitude = 50.0, longitude = 19.0, durationMs = 300L)
        val p2 = TripPause(startTime = 2000L, endTime = 2500L, latitude = 50.1, longitude = 19.1, durationMs = 500L)
        val p3 = TripPause(startTime = 3000L, endTime = 3200L, latitude = 50.2, longitude = 19.2, durationMs = 200L)

        val pauses = mutableListOf(p1, p2, p3)
        pauses.removeAt(1) // delete p2

        assertEquals(2, pauses.size)
        assertEquals(p1, pauses[0])
        assertEquals(p3, pauses[1])
    }

    @Test
    fun testPauseMergeLogic() {
        val p1 = TripPause(startTime = 1000L, endTime = 1300L, latitude = 50.0, longitude = 19.0, durationMs = 300L)
        val p2 = TripPause(startTime = 1500L, endTime = 2000L, latitude = 50.2, longitude = 19.2, durationMs = 500L)

        val mergedEndTime = p2.endTime ?: (p2.startTime + p2.durationMs)
        val mergedDuration = (mergedEndTime - p1.startTime).coerceAtLeast(p1.durationMs + p2.durationMs)
        val mergedPause = TripPause(
            startTime = p1.startTime,
            endTime = mergedEndTime,
            latitude = (p1.latitude + p2.latitude) / 2.0,
            longitude = (p1.longitude + p2.longitude) / 2.0,
            durationMs = mergedDuration,
            pointIndex = p1.pointIndex
        )

        assertEquals(1000L, mergedPause.startTime)
        assertEquals(2000L, mergedPause.endTime)
        assertEquals(1000L, mergedPause.durationMs)
        assertEquals(50.1, mergedPause.latitude, 0.0001)
        assertEquals(19.1, mergedPause.longitude, 0.0001)
    }

    @Test
    fun testMergeTripsPauseAndPlaceDistanceLogic() {
        val p1 = TripPause(2000L, 2500L, 50.05, 19.05, 500L, pointIndex = 10)
        val p2 = TripPause(11000L, 11500L, 50.15, 19.15, 500L, pointIndex = 5)

        val trip1 = TripRecord(
            id = 1,
            title = "Trip 1",
            startTime = 1000L,
            endTime = 5000L,
            distanceMeters = 3000.0,
            points = listOf(org.osmdroid.util.GeoPoint(50.0, 19.0), org.osmdroid.util.GeoPoint(50.1, 19.1)),
            placesVisited = listOf(
                VisitedPlace("Town A", "pow. X", 1000L, 50.0, 19.0, 0.0),
                VisitedPlace("Town B", "pow. X", 3000L, 50.1, 19.1, 1500.0)
            ),
            pauses = listOf(p1)
        )
        val trip2 = TripRecord(
            id = 2,
            title = "Trip 2",
            startTime = 15000L, // 10000ms gap after trip1 end
            endTime = 20000L,
            distanceMeters = 2000.0,
            points = listOf(org.osmdroid.util.GeoPoint(50.1, 19.1), org.osmdroid.util.GeoPoint(50.2, 19.2)),
            placesVisited = listOf(
                VisitedPlace("Town B", "pow. X", 15000L, 50.1, 19.1, 0.0),
                VisitedPlace("Town C", "pow. Y", 18000L, 50.2, 19.2, 1200.0)
            ),
            pauses = listOf(p2)
        )

        val sorted = listOf(trip1, trip2)
        val allPoints = mutableListOf<org.osmdroid.util.GeoPoint>()
        val allPlaces = mutableListOf<VisitedPlace>()
        val allPauses = mutableListOf<TripPause>()
        var cumulativeDistanceOffset = 0.0

        for (i in sorted.indices) {
            val trip = sorted[i]
            val pointOffset = allPoints.size

            for (pause in trip.pauses) {
                allPauses.add(pause.copy(pointIndex = pause.pointIndex + pointOffset))
            }
            allPoints.addAll(trip.points)

            for (p in trip.placesVisited) {
                val last = allPlaces.lastOrNull()?.placeName
                if (last == null || !last.equals(p.placeName, ignoreCase = true)) {
                    allPlaces.add(p.copy(distanceAtEntryMeters = cumulativeDistanceOffset + p.distanceAtEntryMeters))
                }
            }

            if (i < sorted.size - 1) {
                val nextTrip = sorted[i + 1]
                val gapStart = trip.endTime ?: (trip.startTime + 60_000L)
                val gapEnd = nextTrip.startTime
                val gapDuration = (gapEnd - gapStart).coerceAtLeast(0L)
                if (gapDuration >= 10_000L) {
                    allPauses.add(
                        TripPause(gapStart, gapEnd, 50.1, 19.1, gapDuration, (allPoints.size - 1).coerceAtLeast(0))
                    )
                }
            }
            cumulativeDistanceOffset += trip.distanceMeters
        }

        // Verify places: Town B deduplicated, Town C has cumulative offset of 3000 + 1200 = 4200m
        assertEquals(3, allPlaces.size)
        assertEquals("Town A", allPlaces[0].placeName)
        assertEquals(0.0, allPlaces[0].distanceAtEntryMeters, 0.001)
        assertEquals("Town B", allPlaces[1].placeName)
        assertEquals(1500.0, allPlaces[1].distanceAtEntryMeters, 0.001)
        assertEquals("Town C", allPlaces[2].placeName)
        assertEquals(4200.0, allPlaces[2].distanceAtEntryMeters, 0.001)

        // Verify pauses: 3 pauses (p1, gap pause, p2)
        assertEquals(3, allPauses.size)
        assertEquals(10, allPauses[0].pointIndex) // p1 unshifted
        assertEquals(10000L, allPauses[1].durationMs) // gap pause
        assertEquals(7, allPauses[2].pointIndex) // p2 shifted by trip1's 2 points: 5 + 2 = 7
    }
}
