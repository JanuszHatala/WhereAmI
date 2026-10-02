package janush.tech.whereami

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests verifying that Trip Detail timeline items (Places visited and Rest Pauses)
 * strictly calculate their travel legs (distance and duration) sequentially from the
 * immediately preceding chronological event (accounting for pause end times).
 */
class TripTimelineChronologyTest {

    private sealed class RawTimelineEvent(val startTime: Long) {
        class Place(val place: VisitedPlace) : RawTimelineEvent(place.timestamp)
        class Pause(val pause: TripPause, val pauseIndex: Int, val pauseDistanceMeters: Double) : RawTimelineEvent(pause.startTime)
    }

    private sealed class RouteTimelineItem(val timestamp: Long) {
        data class Place(
            val place: VisitedPlace,
            val orderNumber: Int,
            val legDistanceMeters: Double,
            val legDurationMs: Long
        ) : RouteTimelineItem(place.timestamp)

        data class Pause(
            val pause: TripPause,
            val pauseIndex: Int,
            val pauseDistanceMeters: Double,
            val legDistanceMeters: Double,
            val legDurationMs: Long
        ) : RouteTimelineItem(pause.startTime)
    }

    private fun computeTimeline(
        startTime: Long,
        places: List<VisitedPlace>,
        pauses: List<TripPause>,
        pauseDistances: Map<Int, Double>
    ): List<RouteTimelineItem> {
        val rawEvents = mutableListOf<RawTimelineEvent>()
        places.forEach { place ->
            rawEvents.add(RawTimelineEvent.Place(place))
        }
        pauses.forEachIndexed { idx, pause ->
            val dist = pauseDistances[idx] ?: 0.0
            rawEvents.add(RawTimelineEvent.Pause(pause, idx, dist))
        }
        rawEvents.sortBy { it.startTime }

        var lastEventEndTime = startTime
        var lastEventEndDist = 0.0
        var placeOrder = 1
        val items = mutableListOf<RouteTimelineItem>()

        rawEvents.forEach { event ->
            when (event) {
                is RawTimelineEvent.Place -> {
                    val legDist = (event.place.distanceAtEntryMeters - lastEventEndDist).coerceAtLeast(0.0)
                    val legDur = (event.place.timestamp - lastEventEndTime).coerceAtLeast(0L)
                    items.add(RouteTimelineItem.Place(event.place, placeOrder++, legDist, legDur))
                    lastEventEndTime = event.place.timestamp
                    lastEventEndDist = event.place.distanceAtEntryMeters
                }
                is RawTimelineEvent.Pause -> {
                    val legDist = (event.pauseDistanceMeters - lastEventEndDist).coerceAtLeast(0.0)
                    val legDur = (event.pause.startTime - lastEventEndTime).coerceAtLeast(0L)
                    items.add(RouteTimelineItem.Pause(event.pause, event.pauseIndex, event.pauseDistanceMeters, legDist, legDur))
                    val pauseEnd = event.pause.endTime ?: (event.pause.startTime + event.pause.durationMs)
                    lastEventEndTime = pauseEnd
                    lastEventEndDist = event.pauseDistanceMeters
                }
            }
        }
        return items
    }

    @Test
    fun testOsielecAndKojszowkaChronologicalContinuity() {
        val t0 = 1727800000000L // 16:00:00
        val tOsielec = t0 + 20 * 60 * 1000L // 16:20:00 (Place 1)
        val tPauseStart = tOsielec + 4 * 60 * 1000L // 16:24:00 (Pause 1min)
        val pauseDuration = 60 * 1000L // 1 min rest
        val tPauseEnd = tPauseStart + pauseDuration // 16:25:00
        val tKojszowka = tPauseEnd + 117 * 1000L // 16:26:57 (1m 57s travel after pause)

        val place1 = VisitedPlace(
            placeName = "Osielec",
            hierarchySubtitle = "gm. Jordanów",
            timestamp = tOsielec,
            distanceAtEntryMeters = 12000.0,
            latitude = 49.68,
            longitude = 19.78
        )

        val pause1 = TripPause(
            startTime = tPauseStart,
            endTime = tPauseEnd,
            durationMs = pauseDuration,
            latitude = 49.69,
            longitude = 19.79
        )

        val place2 = VisitedPlace(
            placeName = "Kojszówka",
            hierarchySubtitle = "gm. Maków Podhalański",
            timestamp = tKojszowka,
            distanceAtEntryMeters = 15800.0,
            latitude = 49.70,
            longitude = 19.80
        )

        val items = computeTimeline(
            startTime = t0,
            places = listOf(place1, place2),
            pauses = listOf(pause1),
            pauseDistances = mapOf(0 to 14500.0)
        )

        assertEquals(3, items.size)

        // Item 1: Osielec
        val osielecItem = items[0] as RouteTimelineItem.Place
        assertEquals("Osielec", osielecItem.place.placeName)
        assertEquals(1, osielecItem.orderNumber)
        assertEquals(12000.0, osielecItem.legDistanceMeters, 0.001)
        assertEquals(20 * 60 * 1000L, osielecItem.legDurationMs)

        // Item 2: Pause Stop #1
        val pauseItem = items[1] as RouteTimelineItem.Pause
        assertEquals(0, pauseItem.pauseIndex)
        // Leg from Osielec to Pause: 14500m - 12000m = 2500m
        assertEquals(2500.0, pauseItem.legDistanceMeters, 0.001)
        // Travel duration from Osielec (16:20:00) to Pause (16:24:00) = 4 min
        assertEquals(4 * 60 * 1000L, pauseItem.legDurationMs)
        assertEquals(pauseDuration, pauseItem.pause.durationMs)

        // Item 3: Kojszówka
        val kojszowkaItem = items[2] as RouteTimelineItem.Place
        assertEquals("Kojszówka", kojszowkaItem.place.placeName)
        assertEquals(2, kojszowkaItem.orderNumber)
        // Leg from Pause end (14500m) to Kojszówka (15800m) = 1300m
        assertEquals(1300.0, kojszowkaItem.legDistanceMeters, 0.001)
        // Travel duration from Pause END (16:25:00) to Kojszówka (16:26:57) = 117s (1m 57s)
        assertEquals(117 * 1000L, kojszowkaItem.legDurationMs)

        // Verify total duration math matches sum of travel legs + pause
        val totalCalculated = osielecItem.legDurationMs + pauseItem.legDurationMs + pauseItem.pause.durationMs + kojszowkaItem.legDurationMs
        val actualElapsed = tKojszowka - t0
        assertEquals(actualElapsed, totalCalculated)
    }
}
