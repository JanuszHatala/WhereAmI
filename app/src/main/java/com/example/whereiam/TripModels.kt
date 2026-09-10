package com.example.whereiam

import org.osmdroid.util.GeoPoint

data class VisitedPlace(
    val placeName: String,
    val hierarchySubtitle: String,
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
    val distanceAtEntryMeters: Double
)

data class TripRecord(
    val id: Long = 0,
    val title: String = "",
    val startTime: Long,
    val endTime: Long? = null,
    val distanceMeters: Double = 0.0,
    val maxSpeedKmh: Float = 0f,
    val avgSpeedKmh: Float = 0f,
    val isAutoDetected: Boolean = false,
    val points: List<GeoPoint> = emptyList(),
    val placesVisited: List<VisitedPlace> = emptyList()
)

enum class TripMode {
    MANUAL,
    AUTO
}

enum class LocalityCardStyle {
    NORMAL,
    COMPACT
}

enum class ActivityProfile(
    val displayName: String,
    val iconEmoji: String,
    val autoStartSpeedKmh: Float,
    val autoStartDurationMs: Long,
    val autoStopSpeedKmh: Float,
    val autoStopMinutesDefault: Int
) {
    CAR("Driving", "🚗", 10.0f, 10_000L, 8.0f, 5),
    CYCLING("Cycling", "🚴", 7.0f, 10_000L, 4.0f, 5),
    MTB("MTB", "🚵", 6.0f, 10_000L, 3.5f, 5),
    HIKING("Hiking", "🥾", 2.5f, 15_000L, 1.0f, 10),
    RUNNING("Running", "🏃", 5.0f, 10_000L, 2.5f, 5),
    WALKING("Walking", "🚶", 2.5f, 15_000L, 1.5f, 10)
}
