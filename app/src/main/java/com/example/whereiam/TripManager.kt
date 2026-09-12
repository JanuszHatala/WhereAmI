package com.example.whereiam

import android.content.Context
import android.location.Location
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint

class TripManager private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: TripManager? = null

        fun getInstance(context: Context): TripManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TripManager(context.applicationContext).also { INSTANCE = it }
            }
        }

        private const val PREFS_NAME = "where_i_am_trip_prefs"
        private const val KEY_TRIP_MODE = "pref_trip_mode"
        private const val KEY_ACTIVITY_PROFILE = "pref_activity_profile"
        private const val KEY_AUTO_STOP_MINUTES = "pref_auto_stop_minutes"
    }

    private val dbHelper = TripDatabaseHelper(context)
    private val scope = CoroutineScope(Dispatchers.IO)
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _tripMode = MutableStateFlow(
        try {
            TripMode.valueOf(prefs.getString(KEY_TRIP_MODE, TripMode.MANUAL.name) ?: TripMode.MANUAL.name)
        } catch (_: Exception) {
            TripMode.MANUAL
        }
    )
    val tripMode: StateFlow<TripMode> = _tripMode.asStateFlow()

    private val _activityProfile = MutableStateFlow(
        try {
            ActivityProfile.valueOf(prefs.getString(KEY_ACTIVITY_PROFILE, ActivityProfile.CAR.name) ?: ActivityProfile.CAR.name)
        } catch (_: Exception) {
            ActivityProfile.CAR
        }
    )
    val activityProfile: StateFlow<ActivityProfile> = _activityProfile.asStateFlow()

    private val _autoStopMinutes = MutableStateFlow(prefs.getInt(KEY_AUTO_STOP_MINUTES, 5))
    val autoStopMinutes: StateFlow<Int> = _autoStopMinutes.asStateFlow()

    private val _activeTrip = MutableStateFlow<TripRecord?>(null)
    val activeTrip: StateFlow<TripRecord?> = _activeTrip.asStateFlow()

    // Internal state for auto-start / auto-stop
    private var lastMovingTimestamp: Long? = null
    private var movingSinceTimestamp: Long? = null
    private var lastLocation: Location? = null

    // Speed samples for running average
    private var totalSpeedSamples = 0
    private var sumSpeedKmh = 0.0

    private var watchdogJob: kotlinx.coroutines.Job? = null

    init {
        startWatchdog()
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(10_000L) // check every 10s
                val active = _activeTrip.value
                val mode = _tripMode.value
                if (active != null && mode == TripMode.AUTO && lastMovingTimestamp != null) {
                    val now = System.currentTimeMillis()
                    val stationaryDurationMs = now - lastMovingTimestamp!!
                    val timeoutMs = _autoStopMinutes.value * 60 * 1000L
                    if (stationaryDurationMs >= timeoutMs) {
                        TelemetryLogger.logTrip("AUTO_STOP_WATCHDOG", active.id, "Stationary for ${stationaryDurationMs / 1000}s >= ${timeoutMs / 1000}s timeout")
                        stopTrip()
                    }
                }
            }
        }
    }

    fun setTripMode(mode: TripMode) {
        _tripMode.value = mode
        prefs.edit().putString(KEY_TRIP_MODE, mode.name).apply()
        TelemetryLogger.log("SETTINGS", "TripMode changed to ${mode.name}")
    }

    fun setActivityProfile(profile: ActivityProfile) {
        _activityProfile.value = profile
        prefs.edit().putString(KEY_ACTIVITY_PROFILE, profile.name).apply()
        val current = _activeTrip.value
        if (current != null) {
            val updated = current.copy(activityProfile = profile)
            _activeTrip.value = updated
            scope.launch {
                dbHelper.updateTripActivityProfile(current.id, profile)
            }
        }
        TelemetryLogger.log("SETTINGS", "ActivityProfile changed to ${profile.displayName}")
    }

    fun setAutoStopMinutes(minutes: Int) {
        _autoStopMinutes.value = minutes
        prefs.edit().putInt(KEY_AUTO_STOP_MINUTES, minutes).apply()
        TelemetryLogger.log("SETTINGS", "AutoStopMinutes changed to $minutes")
    }

    fun startManualTrip() {
        startTrip(isAuto = false)
    }

    fun stopManualTrip() {
        stopTrip()
    }

    private fun startTrip(isAuto: Boolean) {
        val now = System.currentTimeMillis()
        totalSpeedSamples = 0
        sumSpeedKmh = 0.0
        lastMovingTimestamp = now
        movingSinceTimestamp = null

        val profile = _activityProfile.value
        val trip = TripRecord(
            startTime = now,
            isAutoDetected = isAuto,
            activityProfile = profile
        )
        val id = dbHelper.insertTrip(trip)
        val started = trip.copy(id = id)
        _activeTrip.value = started
        TelemetryLogger.logTrip("STARTED", id, "isAuto=$isAuto, profile=${profile.displayName}")

        // Start LiveTrackingService for background foreground notification & wake lock
        try {
            val intent = android.content.Intent(context, LiveTrackingService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            TelemetryLogger.log("ERROR", "Failed to start LiveTrackingService: ${e.message}")
        }
    }

    private fun stopTrip() {
        val current = _activeTrip.value ?: return
        val now = System.currentTimeMillis()
        val finishedTrip = current.copy(endTime = now)
        dbHelper.updateTrip(finishedTrip)
        TelemetryLogger.logTrip("STOPPED", current.id, "dist=${current.distanceMeters.toInt()}m, places=${current.placesVisited.size}")
        _activeTrip.value = null
        lastLocation = null
        lastMovingTimestamp = null
        movingSinceTimestamp = null

        // Stop foreground service if widget live tracking is not explicitly enabled
        val widgetPrefs = context.getSharedPreferences("where_i_am_prefs", Context.MODE_PRIVATE)
        val isWidgetTracking = widgetPrefs.getBoolean("is_tracking", false)
        if (!isWidgetTracking) {
            try {
                val intent = android.content.Intent(context, LiveTrackingService::class.java).apply {
                    action = "STOP_TRACKING"
                }
                context.startService(intent)
            } catch (_: Exception) {}
        }
    }

    fun onLocationUpdate(
        lat: Double,
        lng: Double,
        speedMs: Float?,
        currentPlace: PlaceInfo?
    ) {
        val now = System.currentTimeMillis()
        val speedKmh = (speedMs ?: 0f) * 3.6f
        val currentProfile = _activityProfile.value
        val isMoving = speedKmh >= currentProfile.autoStopSpeedKmh

        TelemetryLogger.logGps(lat, lng, speedKmh, null, null)

        // ── Auto-Start / Auto-Stop Logic ───────────────────────────────────────
        if (_tripMode.value == TripMode.AUTO) {
            if (_activeTrip.value == null) {
                // Auto-start check based on current profile
                if (speedKmh >= currentProfile.autoStartSpeedKmh) {
                    if (movingSinceTimestamp == null) movingSinceTimestamp = now
                    else if (now - movingSinceTimestamp!! >= currentProfile.autoStartDurationMs) {
                        startTrip(isAuto = true)
                    }
                } else {
                    movingSinceTimestamp = null
                }
            } else {
                // Auto-stop check based on profile threshold
                if (speedKmh >= currentProfile.autoStopSpeedKmh) {
                    // Actively moving: update last moving timestamp
                    lastMovingTimestamp = now
                } else {
                    // Stationary or resting (speed < autoStopSpeedKmh)
                    if (lastMovingTimestamp == null) {
                        lastMovingTimestamp = now
                    }
                    val stationaryDurationMs = now - lastMovingTimestamp!!
                    val timeoutMs = _autoStopMinutes.value * 60 * 1000L
                    if (stationaryDurationMs >= timeoutMs) {
                        stopTrip()
                        return
                    }
                }
            }
        }

        // ── Active Trip Tracking ───────────────────────────────────────────────
        val current = _activeTrip.value ?: return

        var deltaDist = 0.0
        val newLoc = Location("").apply {
            latitude = lat
            longitude = lng
        }
        if (lastLocation != null) {
            val d = lastLocation!!.distanceTo(newLoc).toDouble()
            // Filter GPS jitter if stationary (< 2 meters)
            if (d >= 2.0 || isMoving) {
                deltaDist = d
            }
        }
        lastLocation = newLoc

        // Speed stats
        if (speedKmh > 1.0f) {
            totalSpeedSamples++
            sumSpeedKmh += speedKmh
        }
        val newMaxSpeed = maxOf(current.maxSpeedKmh, speedKmh)
        val newAvgSpeed = if (totalSpeedSamples > 0) (sumSpeedKmh / totalSpeedSamples).toFloat() else current.avgSpeedKmh
        val newDistance = current.distanceMeters + deltaDist

        // New points list (add if moved > 5 meters or first point)
        val newPoints = current.points.toMutableList()
        val newGeoPoint = GeoPoint(lat, lng)
        if (newPoints.isEmpty() || deltaDist >= 5.0) {
            newPoints.add(newGeoPoint)
        }

        // Check for newly entered locality (with transit deduplication to prevent border ping-pong)
        val newPlaces = current.placesVisited.toMutableList()
        if (currentPlace != null && currentPlace.city != "Unknown City" && currentPlace.city != "--") {
            val candidateCity = currentPlace.city
            // Check if locality was recently visited in this trip
            val recentMatch = newPlaces.takeLast(4).find { it.placeName.equals(candidateCity, ignoreCase = true) }
            val isImmediateLast = newPlaces.lastOrNull()?.placeName.equals(candidateCity, ignoreCase = true)

            // If recently visited within last 5 minutes or within 2.5 km, do NOT re-add to prevent border oscillation
            val isRecentPingPong = recentMatch != null && (
                (now - recentMatch.timestamp < 5 * 60 * 1000L) ||
                (newDistance - recentMatch.distanceAtEntryMeters < 2500.0)
            )

            if (!isImmediateLast && !isRecentPingPong) {
                TelemetryLogger.logTrip("LOCALITY_ENTERED", current.id, "Entered $candidateCity at ${newDistance.toInt()}m")
                newPlaces.add(
                    VisitedPlace(
                        placeName = candidateCity,
                        hierarchySubtitle = LocationManager.formatHierarchy(currentPlace),
                        timestamp = now,
                        latitude = lat,
                        longitude = lng,
                        distanceAtEntryMeters = newDistance
                    )
                )
            }
        }

        val updated = current.copy(
            distanceMeters = newDistance,
            maxSpeedKmh = newMaxSpeed,
            avgSpeedKmh = newAvgSpeed,
            points = newPoints,
            placesVisited = newPlaces
        )
        _activeTrip.value = updated

        // Persist progress periodically (every 10 points or place change)
        if (newPoints.size % 10 == 0 || newPlaces.size != current.placesVisited.size) {
            scope.launch {
                dbHelper.updateTrip(updated)
            }
        }
    }

    fun getAllTrips(): List<TripRecord> {
        return dbHelper.getAllTrips()
    }

    fun deleteTrip(id: Long) {
        dbHelper.deleteTrip(id)
    }

    fun renameTrip(id: Long, newTitle: String) {
        dbHelper.renameTrip(id, newTitle)
        if (_activeTrip.value?.id == id) {
            _activeTrip.value = _activeTrip.value?.copy(title = newTitle)
        }
    }

    fun mergeTrips(tripIds: List<Long>): Long {
        return dbHelper.mergeTrips(tripIds)
    }
}
