package online.hatala.whereami

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.osmdroid.util.GeoPoint

class FieldTestFeedbackNov01Test {

    @Test
    fun testDistinctVisitedPlacesCounting() {
        // Case 1: Entering Czaniec, pausing, finishing trip in Czaniec
        val placesTrip1 = listOf(
            VisitedPlace(placeName = "Bielsko-Biała", hierarchySubtitle = "", timestamp = 1000L, latitude = 49.82, longitude = 19.04, distanceAtEntryMeters = 0.0),
            VisitedPlace(placeName = "Kozy", hierarchySubtitle = "", timestamp = 2000L, latitude = 49.84, longitude = 19.14, distanceAtEntryMeters = 6390.0),
            VisitedPlace(placeName = "Bujaków", hierarchySubtitle = "", timestamp = 3000L, latitude = 49.85, longitude = 19.20, distanceAtEntryMeters = 11500.0),
            VisitedPlace(placeName = "Kobiernice", hierarchySubtitle = "", timestamp = 4000L, latitude = 49.86, longitude = 19.25, distanceAtEntryMeters = 13220.0),
            VisitedPlace(placeName = "Czaniec", hierarchySubtitle = "", timestamp = 5000L, latitude = 49.85, longitude = 19.28, distanceAtEntryMeters = 15590.0)
        )
        val collapsed1 = placesTrip1.map { it.placeName.trim().lowercase() }.fold(mutableListOf<String>()) { acc, name ->
            if (name.isNotEmpty() && (acc.isEmpty() || acc.last() != name)) acc.add(name)
            acc
        }
        assertEquals(5, collapsed1.size)
        assertEquals(listOf("bielsko-biała", "kozy", "bujaków", "kobiernice", "czaniec"), collapsed1)

        // Case 2: Out-and-back trip: Czaniec -> Porąbka -> Czaniec
        val placesTrip2 = listOf(
            VisitedPlace(placeName = "Czaniec", hierarchySubtitle = "", timestamp = 1000L, latitude = 49.85, longitude = 19.28, distanceAtEntryMeters = 0.0),
            VisitedPlace(placeName = "Porąbka", hierarchySubtitle = "", timestamp = 2000L, latitude = 49.82, longitude = 19.22, distanceAtEntryMeters = 5000.0),
            VisitedPlace(placeName = "Czaniec", hierarchySubtitle = "", timestamp = 3000L, latitude = 49.85, longitude = 19.28, distanceAtEntryMeters = 10000.0)
        )
        val collapsed2 = placesTrip2.map { it.placeName.trim().lowercase() }.fold(mutableListOf<String>()) { acc, name ->
            if (name.isNotEmpty() && (acc.isEmpty() || acc.last() != name)) acc.add(name)
            acc
        }
        // Czaniec left and re-entered is counted second time: total 3
        assertEquals(3, collapsed2.size)
    }

    @Test
    fun testScreenSpaceSpiderfyZoomedInNoCollision() {
        val guest1 = LiveGuest(id = "g1", name = "Niki", lat = 49.8502, lng = 19.2875, lastUpdated = 1000L, colorIndex = 0)
        val guest2 = LiveGuest(id = "g2", name = "ja", lat = 49.8505, lng = 19.2880, lastUpdated = 1000L, colorIndex = 1)
        val hostPos = GeoPoint(49.8495, 19.2865)

        // At zoom 19.5 (close zoom), these points are well separated (> 50m apart, > 100dp on screen)
        val offsets = computeSpiderfyOffsets(
            guests = listOf(guest1, guest2),
            hostPos = hostPos,
            obstaclePoints = emptyList(),
            zoomLevel = 19.5,
            density = 2.5f
        )

        // Since no collision on screen, both guests should stay at exact GPS coordinates (offset = 0)
        assertEquals(guest1.lat, offsets["g1"]?.latitude ?: 0.0, 1e-6)
        assertEquals(guest1.lng, offsets["g1"]?.longitude ?: 0.0, 1e-6)
        assertEquals(guest2.lat, offsets["g2"]?.latitude ?: 0.0, 1e-6)
        assertEquals(guest2.lng, offsets["g2"]?.longitude ?: 0.0, 1e-6)
    }

    @Test
    fun testScreenSpaceSpiderfyZoomedOutCollidingObstacle() {
        // Two guests almost at the same coordinates
        val guest1 = LiveGuest(id = "g1", name = "Niki", lat = 49.85000, lng = 19.28000, lastUpdated = 1000L, colorIndex = 0)
        val guest2 = LiveGuest(id = "g2", name = "ja", lat = 49.85001, lng = 19.28001, lastUpdated = 1000L, colorIndex = 1)

        // At zoom 15, they are within ~1-2 pixels on screen -> definite collision
        val offsets = computeSpiderfyOffsets(
            guests = listOf(guest1, guest2),
            hostPos = null,
            obstaclePoints = emptyList(),
            zoomLevel = 15.0,
            density = 2.5f
        )

        val pos1 = offsets["g1"]!!
        val pos2 = offsets["g2"]!!

        // They must not occupy the exact same display coordinates
        assertNotEquals(pos1.latitude, pos2.latitude, 1e-5)
    }
}
