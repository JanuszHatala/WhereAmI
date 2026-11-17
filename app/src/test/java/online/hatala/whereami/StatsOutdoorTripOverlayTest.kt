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
    fun `OutdoorContext forDisplay gates areas water features and trails strictly by map layer switches`() {
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
        val peak = OutdoorFeatureInfo(
            osmId = 77L,
            name = "Wielka Sowa",
            kind = PlaceKind.PEAK,
            elevationMeters = 1015,
            distanceMeters = 400f
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
            nearestFeatures = listOf(peak),
            waterBody = water
        )

        // Both switches OFF -> nothing shown in Locality Card
        val allOff = full.forDisplay(showOutdoorAreas = false, showHikingTrails = false)
        assertFalse(allOff.hasAnyOutdoor)
        assertNull(allOff.primaryRange)
        assertNull(allOff.parkOrReserve)
        assertNull(allOff.valley)
        assertNull(allOff.activeTrail)
        assertNull(allOff.nearestPeak)
        assertNull(allOff.nearestWater)

        // Only Hiking Trails ON -> trail pill visible, areas/water/peaks hidden
        val trailsOnly = full.forDisplay(showOutdoorAreas = false, showHikingTrails = true)
        assertEquals(trail, trailsOnly.activeTrail)
        assertNull(trailsOnly.parkOrReserve)
        assertNull(trailsOnly.nearestWater)
        assertNull(trailsOnly.nearestPeak)
        assertTrue(trailsOnly.hasAnyOutdoor)

        // Only Outdoor Areas ON -> areas/peaks/water visible, trail pill hidden
        val areasOnly = full.forDisplay(showOutdoorAreas = true, showHikingTrails = false)
        assertEquals(park, areasOnly.parkOrReserve)
        assertEquals(water, areasOnly.nearestWater)
        assertEquals(peak, areasOnly.nearestPeak)
        assertNull(areasOnly.activeTrail)
        assertTrue(areasOnly.hasAnyOutdoor)

        // Both ON -> everything visible
        val allOn = full.forDisplay(showOutdoorAreas = true, showHikingTrails = true)
        assertEquals(park, allOn.parkOrReserve)
        assertEquals(trail, allOn.activeTrail)
        assertEquals(water, allOn.nearestWater)
    }

    @Test
    fun `computeOutdoorFetchDecision disables Overpass network when outdoor layers are off unless recording outdoor trip`() {
        // Driving with both outdoor layers OFF -> skip Overpass & network even in foreground
        val drivingForegroundOff = LocationManager.computeOutdoorFetchDecision(
            showOutdoorAreasOverlay = false,
            showHikingOverlay = false,
            isRealTimeNeeded = true,
            hasActiveTrip = false,
            activeTripIsOutdoorProfile = false,
            activeTripPaused = false
        )
        assertFalse(drivingForegroundOff.shouldResolve)
        assertFalse(drivingForegroundOff.allowNetwork)

        // Active driving trip with both layers OFF -> still skip Overpass & network
        val drivingRecordingOff = LocationManager.computeOutdoorFetchDecision(
            showOutdoorAreasOverlay = false,
            showHikingOverlay = false,
            isRealTimeNeeded = true,
            hasActiveTrip = true,
            activeTripIsOutdoorProfile = false,
            activeTripPaused = false
        )
        assertFalse(drivingRecordingOff.shouldResolve)
        assertFalse(drivingRecordingOff.allowNetwork)

        // Hiking Trails ON in foreground -> allow resolution & network
        val trailsOnForeground = LocationManager.computeOutdoorFetchDecision(
            showOutdoorAreasOverlay = false,
            showHikingOverlay = true,
            isRealTimeNeeded = true,
            hasActiveTrip = false,
            activeTripIsOutdoorProfile = false,
            activeTripPaused = false
        )
        assertTrue(trailsOnForeground.shouldResolve)
        assertTrue(trailsOnForeground.allowNetwork)

        // Active unpaused HIKING trip with screen off -> keep outdoor POI collection alive for trip history
        val hikingRecordingScreenOff = LocationManager.computeOutdoorFetchDecision(
            showOutdoorAreasOverlay = false,
            showHikingOverlay = false,
            isRealTimeNeeded = false,
            hasActiveTrip = true,
            activeTripIsOutdoorProfile = true,
            activeTripPaused = false
        )
        assertTrue(hikingRecordingScreenOff.shouldResolve)
        assertTrue(hikingRecordingScreenOff.allowNetwork)

        // Paused HIKING trip with both UI switches OFF and screen off -> conserve battery
        val hikingPausedOff = LocationManager.computeOutdoorFetchDecision(
            showOutdoorAreasOverlay = false,
            showHikingOverlay = false,
            isRealTimeNeeded = false,
            hasActiveTrip = true,
            activeTripIsOutdoorProfile = true,
            activeTripPaused = true
        )
        assertFalse(hikingPausedOff.shouldResolve)
        assertFalse(hikingPausedOff.allowNetwork)
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

    @Test
    fun `computeOutdoorFetchDecision respects explicit recordOutdoorPlacesEnabled override`() {
        // Explicit override false disables background Overpass even if activity profile were outdoor
        val disabledBySetting = LocationManager.computeOutdoorFetchDecision(
            showOutdoorAreasOverlay = false,
            showHikingOverlay = false,
            isRealTimeNeeded = false,
            hasActiveTrip = true,
            activeTripIsOutdoorProfile = true,
            activeTripPaused = false,
            recordOutdoorPlacesEnabled = false
        )
        assertFalse(disabledBySetting.shouldResolve)
        assertFalse(disabledBySetting.allowNetwork)

        // Explicit override true enables recording outdoor places even on Driving profile
        val enabledBySetting = LocationManager.computeOutdoorFetchDecision(
            showOutdoorAreasOverlay = false,
            showHikingOverlay = false,
            isRealTimeNeeded = false,
            hasActiveTrip = true,
            activeTripIsOutdoorProfile = false,
            activeTripPaused = false,
            recordOutdoorPlacesEnabled = true
        )
        assertTrue(enabledBySetting.shouldResolve)
        assertTrue(enabledBySetting.allowNetwork)
    }

    @Test
    fun `appBuildEnv distinguishes Play Store install from debug or sideloaded ADB install`() {
        assertFalse(AppBuildEnv.isLocalDevInstall(installerPackageName = "com.android.vending", isDebuggable = false))
        assertTrue(AppBuildEnv.isLocalDevInstall(installerPackageName = "com.android.shell", isDebuggable = false))
        assertTrue(AppBuildEnv.isLocalDevInstall(installerPackageName = null, isDebuggable = false))
        assertTrue(AppBuildEnv.isLocalDevInstall(installerPackageName = "com.android.vending", isDebuggable = true))
    }
}
