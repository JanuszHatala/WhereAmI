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
}
