package janush.tech.whereami

import org.osmdroid.util.GeoPoint

enum class OverrideMode {
    OVERRIDE_ALL,
    FILL_MISSING
}

enum class ElevationCorrectionPhase {
    IDLE,
    TRIPS,
    SAVED_PLACES,
    COMPLETED
}

data class ElevationCorrectionProgress(
    val isRunning: Boolean = false,
    val isPaused: Boolean = false,
    val isDone: Boolean = false,
    val phase: ElevationCorrectionPhase = ElevationCorrectionPhase.IDLE,
    val processedTrips: Int = 0,
    val totalTrips: Int = 0,
    val processedPoints: Int = 0,
    val totalPoints: Int = 0,
    val processedPlaces: Int = 0,
    val totalPlaces: Int = 0,
    val singleTripId: Long? = null,
    val statusMessage: String = ""
)

object ElevationCorrectionHelper {

    suspend fun correctTrip(
        trip: TripRecord,
        mode: OverrideMode,
        db: TripDatabaseHelper,
        isCancelled: () -> Boolean = { false },
        onProgress: (Int, Int) -> Unit
    ): Boolean {
        if (trip.points.isEmpty()) {
            onProgress(0, 0)
            return true
        }

        val (_, currentSources) = db.getTripPointsWithSources(trip.id)
        val indicesToFetch = when (mode) {
            OverrideMode.OVERRIDE_ALL -> trip.points.indices.toList()
            OverrideMode.FILL_MISSING -> trip.points.indices.filter { i ->
                val a = trip.points[i].altitude
                val src = currentSources.getOrNull(i) ?: "GPS"
                a == 0.0 || a.isNaN() || src != "DEM"
            }
        }

        if (indicesToFetch.isEmpty()) {
            onProgress(trip.points.size, trip.points.size)
            return true
        }

        val pointsToQuery = indicesToFetch.map { trip.points[it] }
        val fetchedAltitudes = ElevationRepository.fetchAltitudes(pointsToQuery, db = db, isCancelled = isCancelled) { done, total ->
            onProgress(done, total)
        }

        if (isCancelled()) return false

        val updatedPoints = trip.points.toMutableList()
        val updatedSources = if (currentSources.size == trip.points.size) currentSources.toMutableList() else MutableList(trip.points.size) { "GPS" }
        indicesToFetch.forEachIndexed { fetchIdx, origIdx ->
            val alt = fetchedAltitudes.getOrNull(fetchIdx)
            if (alt != null) {
                val orig = updatedPoints[origIdx]
                updatedPoints[origIdx] = GeoPoint(orig.latitude, orig.longitude, alt)
                updatedSources[origIdx] = "DEM"
            }
        }

        db.updateTrip(trip.copy(points = updatedPoints), updatedSources)
        return true
    }

    suspend fun correctSavedPlaces(
        places: List<SavedPlace>,
        mode: OverrideMode,
        db: TripDatabaseHelper,
        isCancelled: () -> Boolean = { false },
        onProgress: (Int, Int) -> Unit
    ): Boolean {
        if (places.isEmpty()) {
            onProgress(0, 0)
            return true
        }

        val placesToFetch = when (mode) {
            OverrideMode.OVERRIDE_ALL -> places.filter { it.altitudeSource != "CUSTOM" }
            OverrideMode.FILL_MISSING -> places.filter { p ->
                val a = p.altitude
                (a == null || a == 0.0 || a.isNaN() || p.altitudeSource == "GPS") && p.altitudeSource != "CUSTOM"
            }
        }

        if (placesToFetch.isEmpty()) {
            onProgress(places.size, places.size)
            return true
        }

        val pointsToQuery = placesToFetch.map { it.geoPoint }
        val fetchedAltitudes = ElevationRepository.fetchAltitudes(pointsToQuery, db = db, isCancelled = isCancelled) { done, total ->
            onProgress(done, total)
        }

        if (isCancelled()) return false

        placesToFetch.forEachIndexed { fetchIdx, place ->
            val alt = fetchedAltitudes.getOrNull(fetchIdx)
            if (alt != null && alt > 0.0) {
                db.updateSavedPlace(place.copy(altitude = alt, altitudeSource = "DEM"))
            }
        }

        return true
    }
}
