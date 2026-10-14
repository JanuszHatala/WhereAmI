package janush.tech.whereami

import org.osmdroid.util.GeoPoint

enum class OverrideMode {
    OVERRIDE_ALL,
    FILL_MISSING
}

data class ElevationCorrectionProgress(
    val isRunning: Boolean = false,
    val processedTrips: Int = 0,
    val totalTrips: Int = 0,
    val processedPoints: Int = 0,
    val totalPoints: Int = 0,
    val isDone: Boolean = false
)

object ElevationCorrectionHelper {

    suspend fun correctTrip(
        trip: TripRecord,
        mode: OverrideMode,
        db: TripDatabaseHelper,
        onProgress: (Int, Int) -> Unit
    ): Boolean {
        if (trip.points.isEmpty()) {
            onProgress(0, 0)
            return true
        }

        val indicesToFetch = when (mode) {
            OverrideMode.OVERRIDE_ALL -> trip.points.indices.toList()
            OverrideMode.FILL_MISSING -> trip.points.indices.filter { i ->
                val a = trip.points[i].altitude
                a == 0.0 || a.isNaN()
            }
        }

        if (indicesToFetch.isEmpty()) {
            onProgress(trip.points.size, trip.points.size)
            return true
        }

        val pointsToQuery = indicesToFetch.map { trip.points[it] }
        val fetchedAltitudes = ElevationRepository.fetchAltitudes(pointsToQuery) { done, total ->
            onProgress(done, total)
        }

        val updatedPoints = trip.points.toMutableList()
        indicesToFetch.forEachIndexed { fetchIdx, origIdx ->
            val alt = fetchedAltitudes.getOrNull(fetchIdx)
            if (alt != null) {
                val orig = updatedPoints[origIdx]
                updatedPoints[origIdx] = GeoPoint(orig.latitude, orig.longitude, alt)
            }
        }

        db.updateTrip(trip.copy(points = updatedPoints))
        return true
    }
}
