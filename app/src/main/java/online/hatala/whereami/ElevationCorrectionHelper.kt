package online.hatala.whereami

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
        // The stored rows are the base: a caller's TripRecord may be the in-memory recording, whose points carry
        // raw GPS altitudes. Writing those back under the stored "DEM" labels replaced DEM altitudes with GPS
        // ones on every trip stop (trip 234: GPS curve with DEM spikes, all labelled DEM).
        val (storedPoints, currentSources) = db.getTripPointsWithSources(trip.id)
        val basePoints = storedPoints.ifEmpty { trip.points }
        if (basePoints.isEmpty()) {
            onProgress(0, 0)
            return true
        }

        val indicesToFetch = indicesToFetch(basePoints, currentSources, mode)
        if (indicesToFetch.isEmpty()) {
            onProgress(basePoints.size, basePoints.size)
            return true
        }

        val pointsToQuery = indicesToFetch.map { basePoints[it] }
        val fetchedAltitudes = ElevationRepository.fetchAltitudes(pointsToQuery, db = db, isCancelled = isCancelled) { done, total ->
            onProgress(done, total)
        }

        if (isCancelled()) return false

        val (updatedPoints, updatedSources) = mergeFetched(basePoints, currentSources, indicesToFetch, fetchedAltitudes)
        db.updateTrip(trip.copy(points = updatedPoints), updatedSources)
        return true
    }

    internal fun indicesToFetch(points: List<GeoPoint>, sources: List<String>, mode: OverrideMode): List<Int> = when (mode) {
        OverrideMode.OVERRIDE_ALL -> points.indices.toList()
        OverrideMode.FILL_MISSING -> points.indices.filter { i ->
            val a = points[i].altitude
            a == 0.0 || a.isNaN() || (sources.getOrNull(i) ?: "GPS") != "DEM"
        }
    }

    /** [points] with the fetched altitudes applied (source "DEM"); unfetched points keep altitude and source. */
    internal fun mergeFetched(
        points: List<GeoPoint>,
        sources: List<String>,
        fetchedIndices: List<Int>,
        fetched: List<Double?>
    ): Pair<List<GeoPoint>, List<String>> {
        val updatedPoints = points.toMutableList()
        val updatedSources = if (sources.size == points.size) sources.toMutableList() else MutableList(points.size) { "GPS" }
        fetchedIndices.forEachIndexed { fetchIdx, origIdx ->
            val alt = fetched.getOrNull(fetchIdx)
            if (alt != null) {
                val orig = updatedPoints[origIdx]
                updatedPoints[origIdx] = GeoPoint(orig.latitude, orig.longitude, alt)
                updatedSources[origIdx] = "DEM"
            }
        }
        return updatedPoints to updatedSources
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
