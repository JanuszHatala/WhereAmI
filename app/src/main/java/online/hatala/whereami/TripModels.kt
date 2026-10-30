package online.hatala.whereami

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
    val hierarchySubtitle: String = "",
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
    val distanceAtEntryMeters: Double = 0.0,
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

/**
 * Computes a smooth, distance-resampled elevation profile using a continuous spatial Gaussian kernel.
 * Eliminates 1m integer DEM quantization staircases, spiky multipath noise, and flat mesa artifacts
 * while faithfully preserving genuine terrain grade and elevation extremes.
 *
 * @param points Raw GeoPoint track list.
 * @param numSamples Number of uniform distance-spaced evaluation samples for vector chart rendering (default 100).
 * @return List of (distanceMeters, altitudeMeters) pairs, guaranteed to be distance-ordered and smooth.
 */
fun smoothElevationProfile(
    points: List<GeoPoint>,
    numSamples: Int = 100
): List<Pair<Double, Double>> {
    if (points.size < 2) return emptyList()
    val distances = calculateCumulativeDistances(points)
    val rawPairs = ArrayList<Pair<Double, Double>>(points.size)
    for (i in points.indices) {
        val a = points[i].altitude
        if (!a.isNaN() && a != 0.0) {
            val d = if (i < distances.size) distances[i] else 0.0
            rawPairs.add(d to a)
        }
    }
    if (rawPairs.size < 2) return emptyList()

    val maxDist = distances.lastOrNull() ?: rawPairs.last().first
    if (maxDist <= 0.0) return emptyList()

    // Ensure boundary coverage
    if (rawPairs.first().first > 0.0) {
        rawPairs.add(0, 0.0 to rawPairs.first().second)
    }
    if (rawPairs.last().first < maxDist) {
        rawPairs.add(maxDist to rawPairs.last().second)
    }

    val effectiveSamples = numSamples.coerceIn(20, 150)
    // Gaussian filter bandwidth: 2.5% of trip distance, bounded between 20m and 80m.
    val sigma = (maxDist * 0.025).coerceIn(20.0, 80.0)
    val twoSigmaSq = 2.0 * sigma * sigma
    val windowDist = 3.5 * sigma

    val result = ArrayList<Pair<Double, Double>>(effectiveSamples)
    for (s in 0 until effectiveSamples) {
        val targetDist = (s.toDouble() / (effectiveSamples - 1).toDouble()) * maxDist
        var weightSum = 0.0
        var altSum = 0.0
        for (j in rawPairs.indices) {
            val pair = rawPairs[j]
            val diff = pair.first - targetDist
            if (kotlin.math.abs(diff) <= windowDist) {
                val w = kotlin.math.exp(-(diff * diff) / twoSigmaSq)
                weightSum += w
                altSum += w * pair.second
            }
        }
        val smoothedAlt = if (weightSum > 0.0) {
            altSum / weightSum
        } else {
            rawPairs.minByOrNull { kotlin.math.abs(it.first - targetDist) }?.second ?: rawPairs.first().second
        }
        result.add(targetDist to smoothedAlt)
    }
    return result
}

data class ElevationStats(
    val totalAscentM: Double,
    val totalDescentM: Double,
    val minAltitudeM: Double?,
    val maxAltitudeM: Double?,
    val peakVerticalSpeedMpm: Double = 0.0
)

fun computeElevationStats(
    points: List<GeoPoint>,
    smoothingWindowSize: Int = 5,
    timestampsMs: List<Long>? = null
): ElevationStats? {
    val validIndices = points.indices.filter {
        val a = points[it].altitude
        !a.isNaN() && a != 0.0
    }
    if (validIndices.size < 2) return null

    val rawAlts = validIndices.map { points[it].altitude }
    val smoothed = rawAlts.windowed(smoothingWindowSize.coerceAtLeast(1), 1, partialWindows = true) { it.average() }

    var ascent = 0.0
    var descent = 0.0
    for (i in 1 until smoothed.size) {
        val diff = smoothed[i] - smoothed[i - 1]
        if (diff > 0.0) ascent += diff else descent += -diff
    }

    var peakVSpeedMpm = 0.0
    if (timestampsMs != null && timestampsMs.size == points.size) {
        for (i in 1 until validIndices.size) {
            val idx1 = validIndices[i - 1]
            val idx2 = validIndices[i]
            val a1 = points[idx1].altitude
            val a2 = points[idx2].altitude
            val dtMin = (timestampsMs[idx2] - timestampsMs[idx1]) / 60000.0
            if (dtMin > 0.0) {
                val speed = kotlin.math.abs(a2 - a1) / dtMin
                if (speed > peakVSpeedMpm) peakVSpeedMpm = speed
            }
        }
    }

    return ElevationStats(
        totalAscentM = ascent,
        totalDescentM = descent,
        minAltitudeM = rawAlts.minOrNull(),
        maxAltitudeM = rawAlts.maxOrNull(),
        peakVerticalSpeedMpm = peakVSpeedMpm
    )
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

enum class TripSelectionFilterMode(val label: String) {
    ALL("All"),
    SELECTED_ONLY("Selected"),
    UNSELECTED_ONLY("Unselected")
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
    CAR("Driving", "🚗", 10.0f, 10_000L, 8.0f, 45, 2_000L, 1_000L, minValidDistanceMeters = 80.0, minValidDurationMs = 25_000L, chargingLiveSyncIntervalMs = 5_000L, chargingBreadcrumbDisplacementMeters = 15.0f),
    CYCLING("Cycling", "🚴", 7.0f, 10_000L, 4.0f, 60, 2_000L, 1_000L, minValidDistanceMeters = 50.0, minValidDurationMs = 20_000L, chargingLiveSyncIntervalMs = 6_000L, chargingBreadcrumbDisplacementMeters = 12.0f),
    MTB("MTB", "🚵", 6.0f, 10_000L, 3.5f, 60, 2_000L, 1_000L, minValidDistanceMeters = 40.0, minValidDurationMs = 20_000L, chargingLiveSyncIntervalMs = 6_000L, chargingBreadcrumbDisplacementMeters = 12.0f),
    HIKING("Hiking", "🥾", 2.5f, 15_000L, 1.0f, 90, 3_000L, 1_500L, minValidDistanceMeters = 30.0, minValidDurationMs = 20_000L, chargingLiveSyncIntervalMs = 10_000L, chargingBreadcrumbDisplacementMeters = 8.0f),
    RUNNING("Running", "🏃", 5.0f, 10_000L, 2.5f, 30, 2_000L, 1_000L, minValidDistanceMeters = 30.0, minValidDurationMs = 20_000L, chargingLiveSyncIntervalMs = 8_000L, chargingBreadcrumbDisplacementMeters = 12.0f),
    WALKING("Walking", "🚶", 2.5f, 15_000L, 1.5f, 90, 3_000L, 1_500L, minValidDistanceMeters = 25.0, minValidDurationMs = 20_000L, chargingLiveSyncIntervalMs = 10_000L, chargingBreadcrumbDisplacementMeters = 8.0f);

    /** CAR / CYCLING / MTB — indoor multipath scribble discard applies only here. */
    val isVehicleProfile: Boolean
        get() = this == CAR || this == CYCLING || this == MTB

    /** On-foot profiles often out-and-back near home; never treat as indoor GPS nests. */
    val isFootProfile: Boolean
        get() = this == WALKING || this == HIKING || this == RUNNING
}
