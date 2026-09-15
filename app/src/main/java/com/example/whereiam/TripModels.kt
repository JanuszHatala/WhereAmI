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

data class TripPause(
    val startTime: Long,
    val endTime: Long? = null,
    val latitude: Double,
    val longitude: Double,
    val durationMs: Long = 0L,
    val pointIndex: Int = 0
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
    val minGpsIntervalMs: Long
) {
    CAR("Driving", "🚗", 10.0f, 10_000L, 8.0f, 5, 5_000L, 2_000L),
    CYCLING("Cycling", "🚴", 7.0f, 10_000L, 4.0f, 5, 15_000L, 5_000L),
    MTB("MTB", "🚵", 6.0f, 10_000L, 3.5f, 5, 15_000L, 5_000L),
    HIKING("Hiking", "🥾", 2.5f, 15_000L, 1.0f, 10, 8_000L, 4_000L),
    RUNNING("Running", "🏃", 5.0f, 10_000L, 2.5f, 5, 6_000L, 3_000L),
    WALKING("Walking", "🚶", 2.5f, 15_000L, 1.5f, 10, 8_000L, 4_000L)
}
