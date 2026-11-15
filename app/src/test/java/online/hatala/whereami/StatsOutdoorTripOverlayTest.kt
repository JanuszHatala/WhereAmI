package online.hatala.whereami

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsOutdoorTripOverlayTest {

    @Test
    fun `matchesTripActivityFilter excludes hiking trip with high speed spike under Driving filter`() {
        val hikingWithSpeedSpike = TripRecord(
            id = 1L,
            startTime = 1_700_000_000_000L,
            maxSpeedKmh = 71.3f,
            activityProfile = ActivityProfile.HIKING
        )
        val slowCarTrip = TripRecord(
            id = 2L,
            startTime = 1_700_000_100_000L,
            maxSpeedKmh = 18.0f,
            activityProfile = ActivityProfile.CAR
        )
        val cyclingTrip = TripRecord(
            id = 3L,
            startTime = 1_700_000_200_000L,
            maxSpeedKmh = 42.0f,
            activityProfile = ActivityProfile.CYCLING
        )

        assertFalse(matchesTripActivityFilter(hikingWithSpeedSpike, ActivityProfile.CAR))
        assertFalse(matchesTripActivityFilter(cyclingTrip, ActivityProfile.CAR))
        assertTrue(matchesTripActivityFilter(slowCarTrip, ActivityProfile.CAR))
        assertTrue(matchesTripActivityFilter(hikingWithSpeedSpike, ActivityProfile.HIKING))
        assertTrue(matchesTripActivityFilter(hikingWithSpeedSpike, null))
        assertTrue(matchesTripActivityFilter(slowCarTrip, null))
    }

    @Test
    fun `OutdoorContext forDisplay hides protected areas and ranges when layer is off`() {
        val park = OutdoorArea(
            id = "rel:123",
            name = "Obszar chronionego krajobrazu Wzgórza Niemczańsko-Strzelińskie",
            kind = OutdoorAreaKind.LANDSCAPE_PARK
        )
        val range = OutdoorArea(
            id = "rel:456",
            name = "Góry Sowie",
            kind = OutdoorAreaKind.MOUNTAIN_RANGE
        )
        val valley = OutdoorArea(
            id = "rel:789",
            name = "Dolina Bystrzycy",
            kind = OutdoorAreaKind.VALLEY
        )
        val trail = ActiveTrailInfo(
            osmId = 99L,
            name = "Szklary - Bielawa - Przełęcz Puchacza",
            kind = TrailRouteKind.HIKING,
            color = TrailColor.YELLOW
        )
        val water = OutdoorFeatureInfo(
            osmId = 88L,
            name = "Dopływ z Nowej Wsi Niemczańskiej",
            kind = PlaceKind.WATER_BODY,
            distanceMeters = 163f
        )

        val full = OutdoorContext(
            mountainRange = range,
            protectedAreas = listOf(park),
            valley = valley,
            activeTrails = listOf(trail),
            waterBody = water
        )

        val hidden = full.forDisplay(showOutdoorAreas = false)
        assertNull(hidden.primaryRange)
        assertNull(hidden.parkOrReserve)
        assertNull(hidden.valley)
        assertEquals(trail, hidden.activeTrail)
        assertEquals(water, hidden.nearestWater)
        assertTrue(hidden.hasAnyOutdoor)

        val areasOnly = OutdoorContext(mountainRange = range, protectedAreas = listOf(park))
        assertFalse(areasOnly.forDisplay(showOutdoorAreas = false).hasAnyOutdoor)
        assertTrue(areasOnly.forDisplay(showOutdoorAreas = true).hasAnyOutdoor)
    }

    @Test
    fun `selectedTripStrokeColorHex uses configured single color and distinct high contrast multi palette`() {
        assertEquals(
            "#F59E0B",
            MapProfileConfigHelper.selectedTripStrokeColorHex(0, 1, "#F59E0B")
        )

        val assigned = (0 until 4).map { idx ->
            MapProfileConfigHelper.selectedTripStrokeColorHex(idx, 4, "#EF4444")
        }
        assertEquals(4, assigned.toSet().size)
        // Must exclude emerald/teal shades that blend into OSM forests and Freemap terrain
        assertFalse(assigned.contains("#10B981"))
        assertFalse(assigned.contains("#14B8A6"))
        assertNotEquals("#10B981", assigned[2])

        // Contrast casing must stay strictly wider than core line across all thicknesses
        TrailThickness.entries.forEach { thickness ->
            val core = MapProfileConfigHelper.selectedTripTrackWidthPx(thickness)
            val casing = MapProfileConfigHelper.selectedTripCasingWidthPx(core)
            assertTrue(casing > core)
        }
    }
}
