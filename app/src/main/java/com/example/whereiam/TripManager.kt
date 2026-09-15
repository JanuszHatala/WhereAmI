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
    private var autoStartFirstLocation: Location? = null
    private var autoStartFirstTime: Long = 0L
    private var lastLocation: Location? = null
    private var activePause: TripPause? = null

    // Intentional Visit Filter state (Item 9: penetrate > 150m OR stay > 45s)
    private data class PendingPlaceCandidate(
        val place: PlaceInfo,
        val firstSeenTimestamp: Long,
        val entryLatitude: Double,
        val entryLongitude: Double,
        val entryDistanceMeters: Double
    )
    private var pendingCandidate: PendingPlaceCandidate? = null

    // Speed samples for running average
    private var totalSpeedSamples = 0
    private var sumSpeedKmh = 0.0

    private var watchdogJob: kotlinx.coroutines.Job? = null

    init {
        restoreUnclosedTripIfAny()
        startWatchdog()
    }

    private fun restoreUnclosedTripIfAny() {
        scope.launch {
            val unclosed = dbHelper.getActiveOrUnclosedTrip()
            if (unclosed != null && _activeTrip.value == null) {
                _activeTrip.value = unclosed
                lastMovingTimestamp = System.currentTimeMillis()
                lastLocation = unclosed.points.lastOrNull()?.let {
                    Location("").apply {
                        latitude = it.latitude
                        longitude = it.longitude
                    }
                }
                TelemetryLogger.logTrip("RESTORED_ACTIVE", unclosed.id, "Restored active trip from SQLite with ${unclosed.points.size} points")
            }
        }
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(15_000L) // check every 15s
                // In AUTO mode, stop when stationary duration exceeds configured auto-stop timeout
                val active = _activeTrip.value
                val mode = _tripMode.value
                if (active != null && mode == TripMode.AUTO && lastMovingTimestamp != null) {
                    val now = System.currentTimeMillis()
                    val stationaryDurationMs = now - lastMovingTimestamp!!
                    val timeoutMs = (_autoStopMinutes.value.coerceAtLeast(1)) * 60 * 1000L
                    if (stationaryDurationMs >= timeoutMs) {
                        TelemetryLogger.logTrip("AUTO_STOP_WATCHDOG", active.id, "Stationary for ${stationaryDurationMs / 60000}m >= ${timeoutMs / 60000}m timeout")
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
        pendingCandidate = null

        val profile = _activityProfile.value

        // In AUTO mode, check if we can reopen/merge with the previous trip if stationary pause was reasonable (< 35 min and < 1500m)
        if (isAuto) {
            val lastTrip = dbHelper.getAllTrips().firstOrNull()
            if (lastTrip != null && lastTrip.endTime != null) {
                val endedAgoMs = now - lastTrip.endTime
                val lastPt = lastTrip.points.lastOrNull()
                val distFromLastPt = if (lastPt != null && lastLocation != null) {
                    val res = FloatArray(1)
                    Location.distanceBetween(lastPt.latitude, lastPt.longitude, lastLocation!!.latitude, lastLocation!!.longitude, res)
                    res[0]
                } else 0f

                if (endedAgoMs < 35 * 60 * 1000L && distFromLastPt < 1500f) {
                    val pause = TripPause(
                        startTime = lastTrip.endTime,
                        endTime = now,
                        latitude = lastLocation?.latitude ?: (lastPt?.latitude ?: 0.0),
                        longitude = lastLocation?.longitude ?: (lastPt?.longitude ?: 0.0),
                        durationMs = endedAgoMs,
                        pointIndex = lastTrip.points.size
                    )
                    val reopened = lastTrip.copy(
                        endTime = null,
                        pauses = lastTrip.pauses + pause
                    )
                    dbHelper.updateTrip(reopened)
                    _activeTrip.value = reopened
                    TelemetryLogger.logTrip("REOPENED_MERGED", reopened.id, "Reopened trip after ${(endedAgoMs / 60000)}m pause")
                    startLiveTrackingService()
                    return
                }
            }
        }

        val trip = TripRecord(
            startTime = now,
            isAutoDetected = isAuto,
            activityProfile = profile
        )
        val id = dbHelper.insertTrip(trip)
        val started = trip.copy(id = id)
        _activeTrip.value = started
        autoStartFirstLocation = null
        TelemetryLogger.logTrip("STARTED", id, "isAuto=$isAuto, profile=${profile.displayName}")

        startLiveTrackingService()
    }

    private fun startLiveTrackingService() {
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

        // Finalize active pause if any
        val finalPauses = current.pauses.toMutableList()
        activePause?.let {
            val finalized = it.copy(endTime = now, durationMs = now - it.startTime)
            if (finalized.durationMs >= 45_000L) {
                finalPauses.add(finalized)
            }
        }
        activePause = null

        val finishedTrip = current.copy(endTime = now, pauses = finalPauses)
        dbHelper.updateTrip(finishedTrip)
        TelemetryLogger.logTrip("STOPPED", current.id, "dist=${current.distanceMeters.toInt()}m, places=${current.placesVisited.size}, pauses=${finalPauses.size}")
        _activeTrip.value = null
        lastLocation = null
        lastMovingTimestamp = null
        movingSinceTimestamp = null
        autoStartFirstLocation = null
        pendingCandidate = null

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

        val newLoc = Location("").apply {
            latitude = lat
            longitude = lng
        }

        // ── Auto-Start / Auto-Stop Logic ───────────────────────────────────────
        if (_tripMode.value == TripMode.AUTO) {
            if (_activeTrip.value == null) {
                // Sensitive auto-start: triggered by sustained speed OR cumulative displacement >= 25m
                val candidateSpeed = speedKmh >= (currentProfile.autoStartSpeedKmh * 0.7f)
                if (candidateSpeed) {
                    if (autoStartFirstLocation == null) {
                        autoStartFirstLocation = newLoc
                        autoStartFirstTime = now
                        movingSinceTimestamp = now
                    } else {
                        val elapsed = now - autoStartFirstTime
                        val distMoved = autoStartFirstLocation!!.distanceTo(newLoc)
                        if ((speedKmh >= currentProfile.autoStartSpeedKmh && elapsed >= currentProfile.autoStartDurationMs) ||
                            (distMoved >= 25.0 && elapsed >= 8_000L)
                        ) {
                            startTrip(isAuto = true)
                            autoStartFirstLocation = null
                            movingSinceTimestamp = null
                        } else if (elapsed > 45_000L) {
                            autoStartFirstLocation = newLoc
                            autoStartFirstTime = now
                        }
                    }
                } else if (speedKmh < 1.0f && autoStartFirstLocation != null && (now - autoStartFirstTime) > 15_000L) {
                    autoStartFirstLocation = null
                    movingSinceTimestamp = null
                }
            } else {
                // Auto-stop check: stop when stationary duration exceeds configured auto-stop timeout
                if (speedKmh >= currentProfile.autoStopSpeedKmh) {
                    lastMovingTimestamp = now
                } else {
                    if (lastMovingTimestamp == null) {
                        lastMovingTimestamp = now
                    }
                    val stationaryDurationMs = now - lastMovingTimestamp!!
                    val timeoutMs = (_autoStopMinutes.value.coerceAtLeast(1)) * 60 * 1000L
                    if (stationaryDurationMs >= timeoutMs) {
                        stopTrip()
                        return
                    }
                }
            }
        }

        // ── Active Trip Tracking ───────────────────────────────────────────────
        val current = _activeTrip.value ?: return

        // ── Pause Detection & Tracking ─────────────────────────────────────────
        val updatedPauses = current.pauses.toMutableList()
        if (isMoving) {
            lastMovingTimestamp = now
            if (activePause != null) {
                val finalized = activePause!!.copy(
                    endTime = now,
                    durationMs = now - activePause!!.startTime
                )
                if (finalized.durationMs >= 45_000L) {
                    updatedPauses.add(finalized)
                    TelemetryLogger.logTrip("PAUSE_RECORDED", current.id, "Pause recorded: ${finalized.durationMs / 1000}s")
                }
                activePause = null
            }
        } else {
            if (lastMovingTimestamp == null) lastMovingTimestamp = now
            val stationaryMs = now - lastMovingTimestamp!!
            if (stationaryMs >= 90_000L) {
                if (activePause == null) {
                    activePause = TripPause(
                        startTime = lastMovingTimestamp!!,
                        latitude = lat,
                        longitude = lng,
                        durationMs = stationaryMs,
                        pointIndex = current.points.size
                    )
                } else {
                    activePause = activePause!!.copy(durationMs = now - activePause!!.startTime)
                }
            }
        }

        var deltaDist = 0.0
        if (lastLocation != null) {
            val d = lastLocation!!.distanceTo(newLoc).toDouble()
            // Filter GPS jitter if stationary (< 2.5 meters and not moving)
            if (d >= 2.5 || isMoving) {
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

        // New points list: measure displacement from LAST COMMITTED POINT (not just 1-sec fix)
        // to prevent dropping continuous movement at slow speeds (< 18 km/h).
        val newPoints = current.points.toMutableList()
        val newGeoPoint = GeoPoint(lat, lng)
        val lastCommittedPoint = newPoints.lastOrNull()
        val distFromLastCommitted = if (lastCommittedPoint != null) {
            val results = FloatArray(1)
            Location.distanceBetween(lastCommittedPoint.latitude, lastCommittedPoint.longitude, lat, lng, results)
            results[0].toDouble()
        } else {
            Double.MAX_VALUE
        }

        if (newPoints.isEmpty() || distFromLastCommitted >= 5.0) {
            newPoints.add(newGeoPoint)
        }

        // Check for newly entered locality (with intentional visit filter: penetrate > 150m OR stay > 45s)
        val newPlaces = current.placesVisited.toMutableList()
        if (currentPlace != null && currentPlace.city != "Unknown City" && currentPlace.city != "--") {
            val candidateCity = currentPlace.city
            val isImmediateLast = newPlaces.lastOrNull()?.placeName.equals(candidateCity, ignoreCase = true)

            if (isImmediateLast) {
                // Already inside currently committed place; reset any pending border candidate
                pendingCandidate = null
            } else if (newPlaces.isEmpty()) {
                // First locality of a trip is committed immediately at trip start
                TelemetryLogger.logTrip("LOCALITY_INITIAL", current.id, "Initial trip start at $candidateCity")
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
                pendingCandidate = null
            } else {
                // Check if we are already evaluating this candidate locality
                val candidate = pendingCandidate
                if (candidate != null && candidate.place.city.equals(candidateCity, ignoreCase = true)) {
                    val distResults = FloatArray(1)
                    Location.distanceBetween(candidate.entryLatitude, candidate.entryLongitude, lat, lng, distResults)
                    val displacementMeters = distResults[0]
                    val durationMs = now - candidate.firstSeenTimestamp

                    val isPenetrated = displacementMeters >= 150f
                    val isSustainedStay = durationMs >= 45_000L

                    val recentMatch = newPlaces.takeLast(4).find { it.placeName.equals(candidateCity, ignoreCase = true) }
                    val isRecentPingPong = recentMatch != null && (
                        (now - recentMatch.timestamp < 5 * 60 * 1000L) ||
                        (newDistance - recentMatch.distanceAtEntryMeters < 2500.0)
                    )

                    if ((isPenetrated || isSustainedStay) && !isRecentPingPong) {
                        TelemetryLogger.logTrip(
                            "LOCALITY_COMMITTED",
                            current.id,
                            "Intentional visit to $candidateCity committed: disp=${displacementMeters.toInt()}m, dur=${durationMs / 1000}s"
                        )
                        newPlaces.add(
                            VisitedPlace(
                                placeName = candidateCity,
                                hierarchySubtitle = LocationManager.formatHierarchy(candidate.place),
                                timestamp = candidate.firstSeenTimestamp,
                                latitude = candidate.entryLatitude,
                                longitude = candidate.entryLongitude,
                                distanceAtEntryMeters = candidate.entryDistanceMeters
                            )
                        )
                        pendingCandidate = null
                    } else if (isRecentPingPong) {
                        pendingCandidate = null
                    }
                } else {
                    // New candidate locality observed! Begin qualification timer/distance
                    pendingCandidate = PendingPlaceCandidate(
                        place = currentPlace,
                        firstSeenTimestamp = now,
                        entryLatitude = lat,
                        entryLongitude = lng,
                        entryDistanceMeters = newDistance
                    )
                }
            }
        }

        val updated = current.copy(
            distanceMeters = newDistance,
            maxSpeedKmh = newMaxSpeed,
            avgSpeedKmh = newAvgSpeed,
            points = newPoints,
            placesVisited = newPlaces,
            pauses = updatedPauses
        )
        _activeTrip.value = updated

        // Persist progress periodically (every 10 points, pause recorded, or place change)
        if (newPoints.size % 10 == 0 || newPlaces.size != current.placesVisited.size || updatedPauses.size != current.pauses.size) {
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

    fun splitTripAtPause(tripId: Long, pauseIndex: Int): Pair<Long, Long>? {
        val result = dbHelper.splitTripAtPause(tripId, pauseIndex)
        if (result != null) {
            val active = _activeTrip.value
            if (active?.id == tripId) {
                _activeTrip.value = dbHelper.getTripsByIds(listOf(result.second)).firstOrNull()
            }
        }
        return result
    }
}
