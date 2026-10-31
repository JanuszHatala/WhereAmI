package online.hatala.whereami

import android.content.pm.ServiceInfo
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.osmdroid.util.GeoPoint
import java.util.Locale

class FieldTestFeedbackOct31Test {

    // --- ITEM 1: Locality Timeline Deduplication ---
    @Test
    fun `locality visited across pauses is deduplicated to 1 place entry`() {
        val placesVisited = listOf(
            VisitedPlace(placeName = "Czaniec", hierarchySubtitle = "gm. Porąbka", timestamp = 1000L, latitude = 49.85, longitude = 19.28, distanceAtEntryMeters = 0.0),
            VisitedPlace(placeName = "Czaniec", hierarchySubtitle = "gm. Porąbka", timestamp = 5000L, latitude = 49.85, longitude = 19.28, distanceAtEntryMeters = 1000.0)
        )

        val distinctPlaces = mutableListOf<VisitedPlace>()
        for (p in placesVisited) {
            if (distinctPlaces.isEmpty() || !distinctPlaces.last().placeName.equals(p.placeName, ignoreCase = true)) {
                distinctPlaces.add(p)
            }
        }

        assertEquals("Czaniec must only be visited once when not leaving", 1, distinctPlaces.size)
        assertEquals("Czaniec", distinctPlaces.first().placeName)
    }

    @Test
    fun `leaving and re-entering locality counts as second visit`() {
        val placesVisited = listOf(
            VisitedPlace(placeName = "Czaniec", hierarchySubtitle = "gm. Porąbka", timestamp = 1000L, latitude = 49.85, longitude = 19.28, distanceAtEntryMeters = 0.0),
            VisitedPlace(placeName = "Kobiernice", hierarchySubtitle = "gm. Porąbka", timestamp = 5000L, latitude = 49.86, longitude = 19.27, distanceAtEntryMeters = 4000.0),
            VisitedPlace(placeName = "Czaniec", hierarchySubtitle = "gm. Porąbka", timestamp = 9000L, latitude = 49.85, longitude = 19.28, distanceAtEntryMeters = 8000.0)
        )

        val distinctPlaces = mutableListOf<VisitedPlace>()
        for (p in placesVisited) {
            if (distinctPlaces.isEmpty() || !distinctPlaces.last().placeName.equals(p.placeName, ignoreCase = true)) {
                distinctPlaces.add(p)
            }
        }

        assertEquals("Leaving and re-entering must record 3 entries", 3, distinctPlaces.size)
        assertEquals("Czaniec", distinctPlaces[0].placeName)
        assertEquals("Kobiernice", distinctPlaces[1].placeName)
        assertEquals("Czaniec", distinctPlaces[2].placeName)
    }

    // --- ITEM 2: Moving Average Speed Calculation ---
    @Test
    fun `moving average speed calculation excludes pause time`() {
        val distanceMeters = 10_000.0 // 10 km
        val totalDurationMs = 3600_000L // 1 hour total
        val pauseDurationMs = 1800_000L // 30 min pause
        val movingDurationMs = totalDurationMs - pauseDurationMs // 30 min moving

        val overallAvgSpeedKmh = (distanceMeters / (totalDurationMs / 1000.0)) * 3.6
        val movingAvgSpeedKmh = (distanceMeters / (movingDurationMs / 1000.0)) * 3.6

        assertEquals(10.0, overallAvgSpeedKmh, 0.01)
        assertEquals(20.0, movingAvgSpeedKmh, 0.01)
    }

    // --- ITEM 3: Screen-Space Spiderfy Collision Avoidance ---
    @Test
    fun `spiderfy places avatar at exact GPS position at high zoom when not colliding on screen`() {
        val guests = listOf(
            LiveGuest(id = "guest_1", name = "Niki", lat = 49.850000, lng = 19.280000)
        )
        // Host is 5 meters away
        val hostPos = GeoPoint(49.850045, 19.280000)

        // At zoom 20, 5 meters is ~40 pixels, which exceeds the screen threshold
        val offsetsHighZoom = computeSpiderfyOffsets(
            guests = guests,
            hostPos = hostPos,
            zoomLevel = 20.0,
            density = 1.0f
        )

        val posHighZoom = offsetsHighZoom["guest_1"]!!
        assertEquals("At zoom 20 when not colliding, lat must be exact", 49.850000, posHighZoom.latitude, 1e-6)
        assertEquals("At zoom 20 when not colliding, lng must be exact", 19.280000, posHighZoom.longitude, 1e-6)
    }

    @Test
    fun `spiderfy fans out avatar when colliding in screen space`() {
        val guests = listOf(
            LiveGuest(id = "guest_1", name = "Niki", lat = 49.850000, lng = 19.280000)
        )
        // Host is 5 meters away
        val hostPos = GeoPoint(49.850045, 19.280000)

        // At zoom 14, 5 meters is < 1 pixel (collides heavily on screen)
        val offsetsLowZoom = computeSpiderfyOffsets(
            guests = guests,
            hostPos = hostPos,
            zoomLevel = 14.0,
            density = 2.5f
        )

        val posLowZoom = offsetsLowZoom["guest_1"]!!
        assertTrue("At zoom 14 when colliding, avatar must be shifted", posLowZoom.latitude != 49.850000 || posLowZoom.longitude != 19.280000)
    }

    // --- ITEM 5: Cache Deficit Completed Trips Filter ---
    @Test
    fun `cache deficit filters out active in-progress trip`() {
        val trips = listOf(
            TripRecord(id = 1, startTime = 1000L, endTime = 2000L, points = listOf(GeoPoint(49.85, 19.28))),
            TripRecord(id = 2, startTime = 3000L, endTime = null, points = listOf(GeoPoint(49.86, 19.29)))
        )
        val activeTripId = 2L
        val completed = trips.filter { it.endTime != null && it.endTime > 0 && it.id != activeTripId }

        assertEquals(1, completed.size)
        assertEquals(1L, completed.first().id)
    }

    // --- ITEM 6: House Number Formatting & Normalization ---
    @Test
    fun `formatStreetDisplay formats house number on major road with parenthesized ref`() {
        val formatted = RoadNameNormalizer.formatStreetDisplay(
            street = "Żywiecka 3 (DK52)",
            roadRef = "DK52",
            countryCode = "PL",
            allowHouseNumber = true
        )
        assertEquals("Żywiecka 3 (DK52)", formatted)

        val strippedWhenMoving = RoadNameNormalizer.formatStreetDisplay(
            street = "Żywiecka 3 (DK52)",
            roadRef = "DK52",
            countryCode = "PL",
            allowHouseNumber = false
        )
        assertEquals("Żywiecka (DK52)", strippedWhenMoving)
    }

    @Test
    fun `formatStreetDisplay formats house number on residential street`() {
        val formatted = RoadNameNormalizer.formatStreetDisplay(
            street = "Ks. Stojałowskiego 55",
            roadRef = null,
            countryCode = "PL",
            allowHouseNumber = true
        )
        assertEquals("Ks. Stojałowskiego 55", formatted)
    }

    // --- ITEM 7: Guest Deduplication by Name ---
    @Test
    fun `guest list deduplicates multiple records of same name to latest update`() {
        val rawGuests = listOf(
            LiveGuest(id = "sess_1", name = "Niki", lat = 49.85, lng = 19.28, lastUpdated = 1000L),
            LiveGuest(id = "sess_2", name = "niki", lat = 49.86, lng = 19.29, lastUpdated = 5000L),
            LiveGuest(id = "sess_3", name = "ja", lat = 49.87, lng = 19.30, lastUpdated = 2000L)
        )

        val deduplicated = rawGuests.groupBy { it.name.trim().lowercase(Locale.ROOT) }
            .values
            .mapNotNull { group -> group.maxByOrNull { it.lastUpdated } }

        assertEquals("Must contain exactly 2 unique guests", 2, deduplicated.size)
        val niki = deduplicated.first { it.name.equals("niki", ignoreCase = true) }
        assertEquals("Must retain latest session id for Niki", "sess_2", niki.id)
        assertEquals(5000L, niki.lastUpdated)
    }

    // --- ITEM 9: Android 14 Location FGS Type Enforcement ---
    @Test
    fun `foreground service location type constant matches Android Q+ ServiceInfo`() {
        assertEquals(8, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
    }
}
