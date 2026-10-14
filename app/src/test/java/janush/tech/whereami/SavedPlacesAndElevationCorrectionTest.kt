package janush.tech.whereami

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedPlacesAndElevationCorrectionTest {

    @Test
    fun testSavedPlaceAltitudeAndGeoPoint() {
        val placeWithAlt = SavedPlace(
            id = 1L,
            name = "Kopiec Kościuszki",
            category = PlaceCategory.CUSTOM,
            latitude = 50.055,
            longitude = 19.893,
            altitude = 330.0
        )

        assertEquals(330.0, placeWithAlt.altitude)
        assertEquals(330.0, placeWithAlt.geoPoint.altitude, 0.001)

        val placeWithoutAlt = SavedPlace(
            id = 2L,
            name = "Rynek Główny",
            category = PlaceCategory.FAVORITE,
            latitude = 50.061,
            longitude = 19.937,
            altitude = null
        )

        assertNull(placeWithoutAlt.altitude)
        assertEquals(0.0, placeWithoutAlt.geoPoint.altitude, 0.001)
    }

    @Test
    fun testElevationCorrectionProgressModel() {
        val defaultProgress = ElevationCorrectionProgress()
        assertFalse(defaultProgress.isRunning)
        assertFalse(defaultProgress.isPaused)
        assertFalse(defaultProgress.isDone)
        assertEquals(ElevationCorrectionPhase.IDLE, defaultProgress.phase)
        assertEquals(0, defaultProgress.totalTrips)
        assertEquals(0, defaultProgress.totalPlaces)

        val runningTrips = ElevationCorrectionProgress(
            isRunning = true,
            phase = ElevationCorrectionPhase.TRIPS,
            totalTrips = 5,
            processedTrips = 2,
            totalPoints = 400,
            processedPoints = 150
        )
        assertTrue(runningTrips.isRunning)
        assertFalse(runningTrips.isPaused)
        assertEquals(ElevationCorrectionPhase.TRIPS, runningTrips.phase)
        assertEquals(5, runningTrips.totalTrips)
        assertEquals(2, runningTrips.processedTrips)

        val paused = runningTrips.copy(isPaused = true)
        assertTrue(paused.isPaused)

        val runningPlaces = ElevationCorrectionProgress(
            isRunning = true,
            phase = ElevationCorrectionPhase.SAVED_PLACES,
            totalPlaces = 10,
            processedPlaces = 4
        )
        assertEquals(ElevationCorrectionPhase.SAVED_PLACES, runningPlaces.phase)
        assertEquals(10, runningPlaces.totalPlaces)
        assertEquals(4, runningPlaces.processedPlaces)
    }

    @Test
    fun testElevationCorrectionManagerPauseResumeStopState() {
        // Test pause, resume, and stop methods do not throw in standalone environments
        ElevationCorrectionManager.stop()
        val initial = ElevationCorrectionManager.progress.value
        assertFalse(initial.isRunning)
        assertFalse(initial.isPaused)

        // Calling pause/resume when not running should be a safe no-op
        ElevationCorrectionManager.pause()
        assertFalse(ElevationCorrectionManager.progress.value.isPaused)

        ElevationCorrectionManager.resume()
        assertFalse(ElevationCorrectionManager.progress.value.isPaused)

        ElevationCorrectionManager.stop()
        assertFalse(ElevationCorrectionManager.progress.value.isRunning)
    }
}
