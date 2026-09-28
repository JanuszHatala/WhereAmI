package janush.tech.whereami

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.osmdroid.util.GeoPoint

class TripPauseAndTitleTest {

    @Test
    fun testTripTitleSanitizationAndMaxLength() {
        val longTitleWithNewlines = "   Kraków Main Station to   \n  Zakopane Mountain Resort via Nowy Targ and Rabka Zdrój with extra long descriptions that exceed the limit  \r\n"
        
        val singleLine = longTitleWithNewlines.replace("\r", " ")
            .replace("\n", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(MAX_TRIP_TITLE_LENGTH)

        assertTrue("Title length should not exceed MAX_TRIP_TITLE_LENGTH ($MAX_TRIP_TITLE_LENGTH)", singleLine.length <= MAX_TRIP_TITLE_LENGTH)
        assertEquals(50, singleLine.length)
        assertTrue("Title should not contain newlines", !singleLine.contains("\n") && !singleLine.contains("\r"))
        assertEquals("Kraków Main Station to Zakopane Mountain Resort vi", singleLine)
    }

    @Test
    fun testTripPauseJsonSerializationAndDeserialization() {
        val pause = TripPause(
            startTime = 1727500000000L,
            endTime = 1727500600000L,
            latitude = 49.8225,
            longitude = 19.0441,
            durationMs = 600000L,
            pointIndex = 42,
            distanceMeters = 12543.8
        )

        val jsonArray = TripDatabaseHelper.pausesToJson(listOf(pause))
        assertTrue("JSON array should contain pause dist", jsonArray.contains("\"dist\":12543.8"))

        val deserialized = TripDatabaseHelper.jsonToPauses(jsonArray)
        assertEquals(1, deserialized.size)
        val restored = deserialized[0]
        assertEquals(pause.startTime, restored.startTime)
        assertEquals(pause.endTime, restored.endTime)
        assertEquals(pause.latitude, restored.latitude, 0.0001)
        assertEquals(pause.longitude, restored.longitude, 0.0001)
        assertEquals(pause.durationMs, restored.durationMs)
        assertEquals(pause.pointIndex, restored.pointIndex)
        assertEquals(12543.8, restored.distanceMeters, 0.1)
    }

    @Test
    fun testLegacyTripPauseJsonBackwardCompatibility() {
        // Legacy JSON without "dist"
        val legacyJson = """[{"start":1727500000000,"end":1727500300000,"lat":49.8,"lng":19.0,"dur":300000,"idx":10}]"""
        val deserialized = TripDatabaseHelper.jsonToPauses(legacyJson)

        assertEquals(1, deserialized.size)
        val pause = deserialized[0]
        assertEquals(1727500000000L, pause.startTime)
        assertEquals(10, pause.pointIndex)
        assertEquals(0.0, pause.distanceMeters, 0.0001)
    }

    @Test
    fun testCalculatePauseDistanceMeters() {
        val tripPoints = listOf(
            GeoPoint(49.8000, 19.0000),
            GeoPoint(49.8010, 19.0000),
            GeoPoint(49.8020, 19.0000),
            GeoPoint(49.8030, 19.0000)
        )
        val cumulative = calculateCumulativeDistances(tripPoints)

        // Scenario 1: Pause already has explicit non-zero distanceMeters
        val pauseWithExplicitDistance = TripPause(
            startTime = 1000L,
            endTime = 2000L,
            latitude = 49.8,
            longitude = 19.0,
            durationMs = 1000L,
            pointIndex = 2,
            distanceMeters = 8450.0
        )
        val trip1 = TripRecord(
            id = 1,
            title = "Test Trip",
            startTime = 1000L,
            endTime = 2000L,
            distanceMeters = 9000.0,
            points = tripPoints,
            pauses = listOf(pauseWithExplicitDistance)
        )

        val dist1 = calculatePauseDistanceMeters(trip1, pauseWithExplicitDistance, cumulative)
        assertEquals(8450.0, dist1, 0.001)

        // Scenario 2: Legacy pause with distanceMeters == 0.0, interpolated from points and pointIndex
        val legacyPause = TripPause(
            startTime = 1020L,
            endTime = 1050L,
            latitude = 49.8020,
            longitude = 19.0000,
            durationMs = 30000L,
            pointIndex = 2,
            distanceMeters = 0.0
        )
        val trip2 = TripRecord(
            id = 2,
            title = "Legacy Trip",
            startTime = 1000L,
            endTime = 2000L,
            distanceMeters = 9000.0,
            points = tripPoints,
            pauses = listOf(legacyPause)
        )
        val dist2 = calculatePauseDistanceMeters(trip2, legacyPause, cumulative)
        assertTrue("Interpolated distance should be greater than 200m", dist2 > 200.0)
        assertEquals(cumulative[2], dist2, 0.001)

        // Scenario 3: Empty points fallback
        val emptyTrip = TripRecord(
            id = 3,
            title = "Empty Trip",
            startTime = 1000L,
            endTime = 2000L,
            distanceMeters = 0.0,
            points = emptyList(),
            pauses = listOf(legacyPause)
        )
        val dist3 = calculatePauseDistanceMeters(emptyTrip, legacyPause)
        assertEquals(0.0, dist3, 0.0001)
    }
}
