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

    @Test
    fun testVisitedPlaceKindSerialization() {
        val places = listOf(
            VisitedPlace(
                placeName = "Kęty",
                hierarchySubtitle = "gm. Kęty, pow. oświęcimski",
                timestamp = 1727500000000L,
                latitude = 49.88,
                longitude = 19.22,
                distanceAtEntryMeters = 1200.0,
                placeKind = PlaceKind.LOCALITY
            ),
            VisitedPlace(
                placeName = "Orla Perć",
                hierarchySubtitle = "Szlak czerwony",
                timestamp = 1727503600000L,
                latitude = 49.23,
                longitude = 20.02,
                distanceAtEntryMeters = 5400.0,
                placeKind = PlaceKind.TRAIL
            ),
            VisitedPlace(
                placeName = "Przełęcz Zawrat",
                hierarchySubtitle = "Tatry Wysokie",
                timestamp = 1727507200000L,
                latitude = 49.21,
                longitude = 20.01,
                distanceAtEntryMeters = 7800.0,
                placeKind = PlaceKind.MOUNTAIN_PASS
            ),
            VisitedPlace(
                placeName = "Kozi Wierch",
                hierarchySubtitle = "2291 m n.p.m.",
                timestamp = 1727510800000L,
                latitude = 49.215,
                longitude = 20.025,
                distanceAtEntryMeters = 9200.0,
                placeKind = PlaceKind.PEAK
            )
        )

        val jsonStr = TripDatabaseHelper.placesToJson(places)
        assertTrue(jsonStr.contains("\"kind\":\"TRAIL\""))
        assertTrue(jsonStr.contains("\"kind\":\"MOUNTAIN_PASS\""))
        assertTrue(jsonStr.contains("\"kind\":\"PEAK\""))
        assertTrue(jsonStr.contains("\"name\":\"Orla Perć\""))

        val restored = TripDatabaseHelper.jsonToPlaces(jsonStr)
        assertEquals(4, restored.size)
        assertEquals(PlaceKind.LOCALITY, restored[0].placeKind)
        assertEquals(PlaceKind.TRAIL, restored[1].placeKind)
        assertEquals("Orla Perć", restored[1].placeName)
        assertEquals(PlaceKind.MOUNTAIN_PASS, restored[2].placeKind)
        assertEquals(PlaceKind.PEAK, restored[3].placeKind)
    }

    @Test
    fun testPauseRecalculationFormulas() {
        // Simulating a trip: 10km distance, 3 hours total, 1 hour pause
        val distanceMeters = 10000.0
        val startTime = 1000000L
        val endTime = startTime + 3 * 3600 * 1000L // 3h = 10800000 ms
        val pauseDurationMs = 1 * 3600 * 1000L    // 1h = 3600000 ms

        val totalDurationMs = ((endTime) - startTime).coerceAtLeast(0L)
        val movingDurationHours = (totalDurationMs - pauseDurationMs).coerceAtLeast(1000L) / 3600000.0
        val movingAvgSpeed = ((distanceMeters / 1000.0) / movingDurationHours).toFloat()

        // 10 km in 2 hours moving = 5.0 km/h
        assertEquals(2.0, movingDurationHours, 0.001)
        assertEquals(5.0f, movingAvgSpeed, 0.01f)

        // If pause is removed, moving time becomes 3 hours, moving speed becomes 3.33 km/h
        val totalNoPauseDurationMs = totalDurationMs
        val movingHoursNoPause = (totalNoPauseDurationMs - 0L).coerceAtLeast(1000L) / 3600000.0
        val speedNoPause = ((distanceMeters / 1000.0) / movingHoursNoPause).toFloat()
        assertEquals(3.0, movingHoursNoPause, 0.001)
        assertEquals(3.333f, speedNoPause, 0.01f)
    }

    @Test
    fun testManualPauseResumeCreation() {
        val pauseStart = 1000L
        val pauseEnd = 4000L
        val pause = TripPause(
            startTime = pauseStart,
            endTime = pauseEnd,
            latitude = 49.8225,
            longitude = 19.0441,
            durationMs = pauseEnd - pauseStart,
            pointIndex = 5,
            distanceMeters = 1500.0
        )
        assertEquals(3000L, pause.durationMs)
        assertEquals(1500.0, pause.distanceMeters, 0.001)
        assertEquals(5, pause.pointIndex)
    }

    @Test
    fun testCustomAutoLimits() {
        // Auto-start: 1 second to 86,400 seconds (24h)
        val validCustomStartSec = 120
        val clampedStartSec = validCustomStartSec.coerceIn(1, 86400)
        assertEquals(120, clampedStartSec)

        val overflowStartSec = 100000.coerceIn(1, 86400)
        assertEquals(86400, overflowStartSec)

        // Auto-stop: 1 minute to 1440 minutes (24h)
        val validCustomStopMin = 45
        val clampedStopMin = validCustomStopMin.coerceIn(1, 1440)
        assertEquals(45, clampedStopMin)

        val overflowStopMin = 3000.coerceIn(1, 1440)
        assertEquals(1440, overflowStopMin)
    }
}

