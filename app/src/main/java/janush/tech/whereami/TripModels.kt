package janush.tech.whereami

import org.osmdroid.util.GeoPoint

enum class PlaceKind(val iconEmoji: String, val displayName: String) {
    LOCALITY("🏙️", "Locality"),
    TRAIL("🥾", "Trail"),
    MOUNTAIN_PASS("🏔️", "Mountain Pass"),
    PEAK("⛰️", "Peak"),
    POI("📍", "Point of Interest")
}

data class VisitedPlace(
    val placeName: String,
    val hierarchySubtitle: String,
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
    val distanceAtEntryMeters: Double,
    val placeKind: PlaceKind = PlaceKind.LOCALITY
)

const val MAX_TRIP_TITLE_LENGTH = 50

data class TripPause(
    val startTime: Long,
    val endTime: Long? = null,
    val latitude: Double,
    val longitude: Double,
    val durationMs: Long = 0L,
    val pointIndex: Int = 0,
    val distanceMeters: Double = 0.0
)

fun geoDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
    val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    return r * c
}

fun calculatePauseDistanceMeters(trip: TripRecord, pause: TripPause, cumulativeDistances: DoubleArray? = null): Double {
    if (pause.distanceMeters > 0.0) return pause.distanceMeters
    if (trip.points.isEmpty()) return 0.0
    val idx = pause.pointIndex.coerceIn(0, trip.points.size - 1)
    if (cumulativeDistances != null && idx < cumulativeDistances.size) {
        return cumulativeDistances[idx]
    }
    var cum = 0.0
    for (i in 1..idx) {
        val p1 = trip.points[i - 1]
        val p2 = trip.points[i]
        cum += geoDistanceMeters(p1.latitude, p1.longitude, p2.latitude, p2.longitude)
    }
    return cum
}

fun calculateCumulativeDistances(points: List<GeoPoint>): DoubleArray {
    if (points.isEmpty()) return DoubleArray(0)
    val arr = DoubleArray(points.size)
    var sum = 0.0
    arr[0] = 0.0
    for (i in 1 until points.size) {
        sum += geoDistanceMeters(points[i - 1].latitude, points[i - 1].longitude, points[i].latitude, points[i].longitude)
        arr[i] = sum
    }
    return arr
}

data class TripRecord(
    val id: Long = 0,
    val title: String = "",
    val startTime: Long,
    val endTime: Long? = null,
    val distanceMeters: Double = 0.0,
    val maxSpeedKmh: Float = 0f,
    val avgSpeedKmh: Float = 0f,
    val isAutoDetected: Boolean = false,
    val activityProfile: ActivityProfile = ActivityProfile.CAR,
    val points: List<GeoPoint> = emptyList(),
    val placesVisited: List<VisitedPlace> = emptyList(),
    val pauses: List<TripPause> = emptyList()
)

enum class TripMode {
    MANUAL,
    AUTO
}

enum class LocalityCardStyle {
    NORMAL,
    COMPACT
}

enum class BatteryPowerPolicy(val displayName: String, val description: String) {
    SMART_AUTO(
        "Smart Auto",
        "Battery optimized when unplugged; full performance when charging"
    ),
    BATTERY_SAVER(
        "Battery Saver (Minimal)",
        "Forces strict battery optimization and longer intervals even when charging"
    ),
    HIGH_PERFORMANCE(
        "High Performance",
        "High frequency GPS and telemetry for maximum precision"
    )
}

enum class ActivityProfile(
    val displayName: String,
    val iconEmoji: String,
    val autoStartSpeedKmh: Float,
    val autoStartDurationMs: Long,
    val autoStopSpeedKmh: Float,
    val autoStopMinutesDefault: Int,
    val gpsIntervalMs: Long,
    val minGpsIntervalMs: Long,
    val minValidDistanceMeters: Double = 50.0,
    val minValidDurationMs: Long = 20_000L,
    val chargingLiveSyncIntervalMs: Long = 6_000L,
    val chargingBreadcrumbDisplacementMeters: Float = 12.0f
) {
    CAR("Driving", "🚗", 10.0f, 10_000L, 8.0f, 5, 2_000L, 1_000L, minValidDistanceMeters = 80.0, minValidDurationMs = 25_000L, chargingLiveSyncIntervalMs = 5_000L, chargingBreadcrumbDisplacementMeters = 15.0f),
    CYCLING("Cycling", "🚴", 7.0f, 10_000L, 4.0f, 5, 2_000L, 1_000L, minValidDistanceMeters = 50.0, minValidDurationMs = 20_000L, chargingLiveSyncIntervalMs = 6_000L, chargingBreadcrumbDisplacementMeters = 12.0f),
    MTB("MTB", "🚵", 6.0f, 10_000L, 3.5f, 5, 2_000L, 1_000L, minValidDistanceMeters = 40.0, minValidDurationMs = 20_000L, chargingLiveSyncIntervalMs = 6_000L, chargingBreadcrumbDisplacementMeters = 12.0f),
    HIKING("Hiking", "🥾", 2.5f, 15_000L, 1.0f, 10, 3_000L, 1_500L, minValidDistanceMeters = 30.0, minValidDurationMs = 20_000L, chargingLiveSyncIntervalMs = 10_000L, chargingBreadcrumbDisplacementMeters = 8.0f),
    RUNNING("Running", "🏃", 5.0f, 10_000L, 2.5f, 5, 2_000L, 1_000L, minValidDistanceMeters = 30.0, minValidDurationMs = 20_000L, chargingLiveSyncIntervalMs = 8_000L, chargingBreadcrumbDisplacementMeters = 12.0f),
    WALKING("Walking", "🚶", 2.5f, 15_000L, 1.5f, 10, 3_000L, 1_500L, minValidDistanceMeters = 25.0, minValidDurationMs = 20_000L, chargingLiveSyncIntervalMs = 10_000L, chargingBreadcrumbDisplacementMeters = 8.0f)
}
