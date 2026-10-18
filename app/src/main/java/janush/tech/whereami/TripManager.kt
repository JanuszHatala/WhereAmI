package janush.tech.whereami

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

        private const val PREFS_NAME = "where_am_i_trip_prefs"
        private const val KEY_TRIP_MODE = "pref_trip_mode"
        private const val KEY_ACTIVITY_PROFILE = "pref_activity_profile"
        private const val KEY_AUTO_STOP_MINUTES = "pref_auto_stop_minutes"
        private const val KEY_AUTO_START_PREFIX = "pref_auto_start_sec_"
        private const val KEY_AUTO_START_SPEED_PREFIX = "pref_auto_start_speed_"
        private const val KEY_AUTO_STOP_PREFIX = "pref_auto_stop_min_"
        private const val KEY_TRIP_MODE_PREFIX = "pref_trip_mode_"
        private const val KEY_AUTO_RESUME_ON_MOTION = "pref_auto_resume_on_motion"
        private const val KEY_IS_PAUSED = "pref_trip_is_paused"
        private const val KEY_PAUSE_START_TIME = "pref_trip_pause_start_time"
        private const val KEY_PAUSE_LAT = "pref_trip_pause_lat"
        private const val KEY_PAUSE_LNG = "pref_trip_pause_lng"
        private const val KEY_PAUSE_POINT_INDEX = "pref_trip_pause_point_index"
        private const val KEY_PAUSE_DIST = "pref_trip_pause_dist"
        private const val PAUSE_WARNING_TIMEOUT_MS = 60 * 60 * 1000L // 1 hour
        const val KEY_MANUAL_PAUSE_STICKY = "pref_trip_manual_pause_sticky"

        val POLISH_CITY_CENTROIDS = mapOf(
            "kraków" to Pair(50.064, 19.945),
            "warszawa" to Pair(52.229, 21.012),
            "katowice" to Pair(50.264, 19.023),
            "wrocław" to Pair(51.107, 17.038),
            "bielsko-biała" to Pair(49.822, 19.044),
            "gdańsk" to Pair(54.352, 18.646),
            "poznań" to Pair(52.406, 16.925),
            "łódź" to Pair(51.759, 19.456),
            "szczecin" to Pair(53.428, 14.553),
            "lublin" to Pair(51.246, 22.568),
            "bydgoszcz" to Pair(53.123, 18.008),
            "białystok" to Pair(53.132, 23.168),
            "częstochowa" to Pair(50.811, 19.120),
            "radom" to Pair(51.402, 21.147),
            "sosnowiec" to Pair(50.286, 19.104),
            "toruń" to Pair(53.013, 18.598),
            "kielce" to Pair(50.866, 20.628),
            "gliwice" to Pair(50.294, 18.671),
            "zabrze" to Pair(50.324, 18.785),
            "olsztyn" to Pair(53.778, 20.480),
            "rzeszów" to Pair(50.041, 21.999),
            "opole" to Pair(50.675, 17.921)
        )
    }

    private val dbHelper = TripDatabaseHelper(context)
    private val scope = CoroutineScope(Dispatchers.IO)
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _activityProfile = MutableStateFlow(
        try {
            ActivityProfile.valueOf(prefs.getString(KEY_ACTIVITY_PROFILE, ActivityProfile.CAR.name) ?: ActivityProfile.CAR.name)
        } catch (_: Exception) {
            ActivityProfile.CAR
        }
    )
    val activityProfile: StateFlow<ActivityProfile> = _activityProfile.asStateFlow()

    private val _tripMode = MutableStateFlow(getTripModeForProfile(_activityProfile.value))
    val tripMode: StateFlow<TripMode> = _tripMode.asStateFlow()

    private val _autoStopMinutes = MutableStateFlow(
        prefs.getInt(KEY_AUTO_STOP_PREFIX + _activityProfile.value.name, 
            prefs.getInt(KEY_AUTO_STOP_MINUTES, _activityProfile.value.autoStopMinutesDefault))
    )
    val autoStopMinutes: StateFlow<Int> = _autoStopMinutes.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _autoResumeOnMotion = MutableStateFlow(prefs.getBoolean(KEY_AUTO_RESUME_ON_MOTION, true))
    val autoResumeOnMotion: StateFlow<Boolean> = _autoResumeOnMotion.asStateFlow()

    private var isManualPauseSticky: Boolean = prefs.getBoolean(KEY_MANUAL_PAUSE_STICKY, false)
    private var autoResumeStartDistance: Double = 0.0
    private var autoResumeStartTime: Long = 0L
    private var pauseAutoResumeFirstLocation: Location? = null
    private var pauseAutoResumeFirstTime: Long = 0L
    private val activeTripPointSources = java.util.Collections.synchronizedList(mutableListOf<String>())

    fun isManualPauseStickyActive(): Boolean = isManualPauseSticky

    fun setAutoResumeOnMotion(enabled: Boolean) {
        _autoResumeOnMotion.value = enabled
        prefs.edit().putBoolean(KEY_AUTO_RESUME_ON_MOTION, enabled).apply()
        TelemetryLogger.log("SETTINGS", "AutoResumeOnMotion set to $enabled")
    }

    fun getTripModeForProfile(profile: ActivityProfile): TripMode {
        val saved = prefs.getString(KEY_TRIP_MODE_PREFIX + profile.name, null)
        if (saved != null) {
            try { return TripMode.valueOf(saved) } catch (_: Exception) {}
        }
        val legacy = prefs.getString(KEY_TRIP_MODE, null)
        if (legacy != null) {
            try { return TripMode.valueOf(legacy) } catch (_: Exception) {}
        }
        return when (profile) {
            ActivityProfile.CAR, ActivityProfile.CYCLING, ActivityProfile.MTB -> TripMode.AUTO
            ActivityProfile.HIKING, ActivityProfile.RUNNING, ActivityProfile.WALKING -> TripMode.MANUAL
        }
    }

    fun setTripModeForProfile(profile: ActivityProfile, mode: TripMode) {
        prefs.edit().putString(KEY_TRIP_MODE_PREFIX + profile.name, mode.name).apply()
        if (_activityProfile.value == profile) {
            setTripMode(mode)
        }
        TelemetryLogger.log("SETTINGS", "TripMode for ${profile.name} changed to ${mode.name}")
    }

    fun getAutoStartSecondsForProfile(profile: ActivityProfile): Int {
        val defaultSec = (profile.autoStartDurationMs / 1000L).toInt()
        return prefs.getInt(KEY_AUTO_START_PREFIX + profile.name, defaultSec)
    }

    fun setAutoStartSecondsForProfile(profile: ActivityProfile, seconds: Int) {
        val clamped = seconds.coerceIn(3, 86400) // up to 24 hours
        prefs.edit().putInt(KEY_AUTO_START_PREFIX + profile.name, clamped).apply()
        TelemetryLogger.log("SETTINGS", "AutoStartSeconds for ${profile.name} changed to $clamped")
    }

    fun getAutoStartSpeedForProfile(profile: ActivityProfile): Float {
        val defaultSpeed = profile.autoStartSpeedKmh
        return prefs.getFloat(KEY_AUTO_START_SPEED_PREFIX + profile.name, defaultSpeed)
    }

    fun setAutoStartSpeedForProfile(profile: ActivityProfile, speedKmh: Float) {
        val clamped = speedKmh.coerceIn(1.0f, 150.0f)
        prefs.edit().putFloat(KEY_AUTO_START_SPEED_PREFIX + profile.name, clamped).apply()
        TelemetryLogger.log("SETTINGS", "AutoStartSpeed for ${profile.name} changed to $clamped km/h")
    }

    fun getAutoStopMinutesForProfile(profile: ActivityProfile): Int {
        val defaultMin = profile.autoStopMinutesDefault
        return prefs.getInt(KEY_AUTO_STOP_PREFIX + profile.name, defaultMin)
    }

    fun setAutoStopMinutesForProfile(profile: ActivityProfile, minutes: Int) {
        val clamped = minutes.coerceIn(1, 1440) // up to 24 hours (1440 min)
        prefs.edit().putInt(KEY_AUTO_STOP_PREFIX + profile.name, clamped).apply()
        if (_activityProfile.value == profile) {
            _autoStopMinutes.value = clamped
        }
        TelemetryLogger.log("SETTINGS", "AutoStopMinutes for ${profile.name} changed to $clamped")
    }

    private val _activeTrip = MutableStateFlow<TripRecord?>(null)
    val activeTrip: StateFlow<TripRecord?> = _activeTrip.asStateFlow()

    private var pauseWarningNotified = false

    fun pauseTrip(isManual: Boolean = true) {
        val current = _activeTrip.value ?: return
        if (_isPaused.value) return
        _isPaused.value = true
        if (isManual) {
            isManualPauseSticky = true
            prefs.edit().putBoolean(KEY_MANUAL_PAUSE_STICKY, true).apply()
        }
        val now = System.currentTimeMillis()
        val loc = lastLocation
        val lat = loc?.latitude ?: current.points.lastOrNull()?.latitude ?: 0.0
        val lng = loc?.longitude ?: current.points.lastOrNull()?.longitude ?: 0.0
        activePause = TripPause(
            startTime = now,
            latitude = lat,
            longitude = lng,
            pointIndex = current.points.size,
            distanceMeters = current.distanceMeters
        )
        pauseWarningNotified = false
        pauseAutoResumeFirstLocation = null
        pauseAutoResumeFirstTime = 0L
        prefs.edit()
            .putBoolean(KEY_IS_PAUSED, true)
            .putLong(KEY_PAUSE_START_TIME, now)
            .putFloat(KEY_PAUSE_LAT, lat.toFloat())
            .putFloat(KEY_PAUSE_LNG, lng.toFloat())
            .putInt(KEY_PAUSE_POINT_INDEX, current.points.size)
            .putFloat(KEY_PAUSE_DIST, current.distanceMeters.toFloat())
            .apply()
        val eventTag = if (isManual) "PAUSE_MANUAL" else "PAUSE_STICKY_RESTORED"
        TelemetryLogger.logTrip(eventTag, current.id, "Trip paused (manual=$isManual, sticky=$isManualPauseSticky) at point ${current.points.size}")
    }

    fun resumeTrip(isManual: Boolean = true) {
        val current = _activeTrip.value ?: return
        if (!_isPaused.value) return
        _isPaused.value = false
        if (isManual) {
            isManualPauseSticky = false
            prefs.edit().remove(KEY_MANUAL_PAUSE_STICKY).apply()
        } else {
            autoResumeStartDistance = current.distanceMeters
            autoResumeStartTime = System.currentTimeMillis()
        }
        val now = System.currentTimeMillis()
        if (activePause != null) {
            val finalized = activePause!!.copy(
                endTime = now,
                durationMs = (now - activePause!!.startTime).coerceAtLeast(1000L)
            )
            val updatedPauses = current.pauses.toMutableList()
            updatedPauses.add(finalized)
            _activeTrip.value = current.copy(pauses = updatedPauses)
            val eventTag = if (isManual) "RESUME_MANUAL" else "RESUME_AUTO"
            TelemetryLogger.logTrip(eventTag, current.id, "Trip resumed (manual=$isManual, sticky=$isManualPauseSticky) after ${finalized.durationMs / 1000}s")
            activePause = null
        }
        pauseWarningNotified = false
        pauseAutoResumeFirstLocation = null
        pauseAutoResumeFirstTime = 0L
        prefs.edit()
            .remove(KEY_IS_PAUSED)
            .remove(KEY_PAUSE_START_TIME)
            .remove(KEY_PAUSE_LAT)
            .remove(KEY_PAUSE_LNG)
            .remove(KEY_PAUSE_POINT_INDEX)
            .remove(KEY_PAUSE_DIST)
            .apply()
        lastMovingTimestamp = now
    }

    private fun checkPermanentContinuation(current: TripRecord, speedKmh: Float, now: Long) {
        if (!isManualPauseSticky) return
        val profile = _activityProfile.value
        val distSinceResume = current.distanceMeters - autoResumeStartDistance
        val timeSinceResume = now - autoResumeStartTime

        val (requiredDist, requiredSpeed, requiredSpeedDuration) = when (profile) {
            ActivityProfile.CAR -> Triple(400.0, 20.0f, 45_000L)
            ActivityProfile.CYCLING, ActivityProfile.MTB -> Triple(250.0, 12.0f, 45_000L)
            ActivityProfile.HIKING, ActivityProfile.RUNNING, ActivityProfile.WALKING -> Triple(150.0, 4.0f, 180_000L)
        }

        val isDistanceMet = distSinceResume >= requiredDist
        val isSustainedSpeedMet = speedKmh >= requiredSpeed && timeSinceResume >= requiredSpeedDuration

        if (isDistanceMet || isSustainedSpeedMet) {
            isManualPauseSticky = false
            prefs.edit().remove(KEY_MANUAL_PAUSE_STICKY).apply()
            TelemetryLogger.logTrip("STICKY_PAUSE_CLEARED", current.id, "Permanent trip continuation confirmed (${distSinceResume.toInt()}m, ${speedKmh.toInt()} km/h). Sticky pause cleared.")
        }
    }

    // Internal state for auto-start / auto-stop
    private var lastMovingTimestamp: Long? = null
    private var movingSinceTimestamp: Long? = null
    private var autoStartFirstLocation: Location? = null
    private var autoStartFirstTime: Long = 0L
    private var lastLocation: Location? = null
    private var activePause: TripPause? = null

    // Robust rest-stop cluster tracking (150s stationary dwell + 12m spatial cluster gate)
    private var pauseAnchorLocation: Location? = null
    private var pauseAnchorFirstStationaryTime: Long? = null
    private var movingSinceInPauseTimestamp: Long? = null

    // Intentional Visit Filter state (Item 9: penetrate > 150m OR stay > 45s)
    private data class PendingPlaceCandidate(
        val place: PlaceInfo,
        val placeName: String,
        val placeKind: PlaceKind,
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
    }

    private fun restoreUnclosedTripIfAny() {
        scope.launch {
            val unclosed = dbHelper.getActiveOrUnclosedTrip()
            if (unclosed != null && _activeTrip.value == null) {
                val lastPointTime = unclosed.placesVisited.lastOrNull()?.timestamp ?: unclosed.startTime
                val now = System.currentTimeMillis()
                val timeoutMs = _autoStopMinutes.value * 60 * 1000L
                val wasPaused = prefs.getBoolean(KEY_IS_PAUSED, false)
                
                // MAP-R05: If trip was abandoned during process death and exceeded timeout, close it instead of resuming (unless paused!)
                if (_tripMode.value == TripMode.AUTO && (now - lastPointTime > timeoutMs) && !wasPaused) {
                    val closed = unclosed.copy(endTime = lastPointTime)
                    dbHelper.updateTrip(closed)
                    isManualPauseSticky = false
                    prefs.edit()
                        .remove(KEY_IS_PAUSED)
                        .remove(KEY_PAUSE_START_TIME)
                        .remove(KEY_PAUSE_LAT)
                        .remove(KEY_PAUSE_LNG)
                        .remove(KEY_PAUSE_POINT_INDEX)
                        .remove(KEY_PAUSE_DIST)
                        .remove(KEY_MANUAL_PAUSE_STICKY)
                        .apply()
                    TelemetryLogger.logTrip("CLOSED_ZOMBIE", unclosed.id, "Closed zombie trip abandoned ${(now - lastPointTime)/60000}m ago")
                } else {
                    _activeTrip.value = unclosed
                    val (_, restoredSources) = dbHelper.getTripPointsWithSources(unclosed.id)
                    activeTripPointSources.clear()
                    activeTripPointSources.addAll(restoredSources)
                    lastMovingTimestamp = System.currentTimeMillis() // Reset watchdog timer on restore
                    lastLocation = unclosed.points.lastOrNull()?.let {
                        Location("").apply {
                            latitude = it.latitude
                            longitude = it.longitude
                        }
                    }
                    isManualPauseSticky = prefs.getBoolean(KEY_MANUAL_PAUSE_STICKY, false)
                    if (wasPaused) {
                        val pauseStartTime = prefs.getLong(KEY_PAUSE_START_TIME, 0L)
                        if (pauseStartTime > 0L) {
                            activePause = TripPause(
                                startTime = pauseStartTime,
                                latitude = prefs.getFloat(KEY_PAUSE_LAT, 0f).toDouble(),
                                longitude = prefs.getFloat(KEY_PAUSE_LNG, 0f).toDouble(),
                                durationMs = System.currentTimeMillis() - pauseStartTime,
                                pointIndex = prefs.getInt(KEY_PAUSE_POINT_INDEX, unclosed.points.size),
                                distanceMeters = prefs.getFloat(KEY_PAUSE_DIST, unclosed.distanceMeters.toFloat()).toDouble()
                            )
                            _isPaused.value = true
                            TelemetryLogger.logTrip("PAUSE_RESTORED", unclosed.id, "Restored paused state after process death: pause started ${(System.currentTimeMillis() - pauseStartTime) / 60000}m ago")
                        }
                    }
                    startWatchdog()
                    TelemetryLogger.logTrip("RESTORED_ACTIVE", unclosed.id, "Restored active trip from SQLite with ${unclosed.points.size} points (isPaused=${_isPaused.value})")
                }
            }
        }
    }

    private fun stopWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    private fun startWatchdog() {
        if (watchdogJob?.isActive == true) return
        watchdogJob = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(15_000L) // check every 15s
                val active = _activeTrip.value ?: break
                val mode = _tripMode.value
                // Fix 2: Check if paused trip has exceeded 1-hour warning threshold
                if (_isPaused.value && activePause != null) {
                    val pauseDurationMs = System.currentTimeMillis() - activePause!!.startTime
                    if (pauseDurationMs >= PAUSE_WARNING_TIMEOUT_MS && !pauseWarningNotified) {
                        pauseWarningNotified = true
                        TelemetryLogger.logTrip("PAUSE_WARNING_1H", active.id, "Trip paused for ${pauseDurationMs / 60000}m - warning user via notification")
                        notifyPauseWarning(active, pauseDurationMs)
                    }
                }
                // In AUTO mode, stop when stationary duration exceeds configured auto-stop timeout (unless paused)
                if (mode == TripMode.AUTO && !_isPaused.value && lastMovingTimestamp != null) {
                    val now = System.currentTimeMillis()
                    val stationaryDurationMs = now - lastMovingTimestamp!!
                    val timeoutMs = (_autoStopMinutes.value.coerceAtLeast(1)) * 60 * 1000L
                    if (stationaryDurationMs >= timeoutMs) {
                        if (isManualPauseSticky) {
                            TelemetryLogger.logTrip("STICKY_PAUSE_RETURN", active.id, "Stationary for ${stationaryDurationMs / 60000}m with sticky pause active: returning to Pause.")
                            pauseTrip(isManual = false)
                        } else {
                            TelemetryLogger.logTrip("AUTO_STOP_WATCHDOG", active.id, "Stationary for ${stationaryDurationMs / 60000}m >= ${timeoutMs / 60000}m timeout")
                            stopTrip()
                            break
                        }
                    }
                }
            }
        }
    }

    private fun notifyPauseWarning(trip: TripRecord, pauseDurationMs: Long) {
        try {
            val intent = android.content.Intent(context, LiveTrackingService::class.java).apply {
                action = LiveTrackingService.ACTION_PAUSE_WARNING
                putExtra("trip_id", trip.id)
                putExtra("pause_duration_ms", pauseDurationMs)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (_: Exception) {}
    }

    fun setTripMode(mode: TripMode) {
        if (_tripMode.value == mode) return
        _tripMode.value = mode
        prefs.edit().putString(KEY_TRIP_MODE, mode.name).apply()
        TelemetryLogger.log("SETTINGS", "TripMode changed to ${mode.name}")

        val hasTrip = _activeTrip.value != null
        val liveSession = LiveSharingManager.getInstance(context).currentSession.value
        val hasLive = liveSession != null && liveSession.isActive

        // True Zero-Idle Standby: Transition background service to standby (AUTO) or stop completely (MANUAL)
        if (!hasTrip && !hasLive) {
            try {
                val intent = android.content.Intent(context, LiveTrackingService::class.java).apply {
                    action = if (mode == TripMode.AUTO) {
                        LiveTrackingService.ACTION_ENTER_STANDBY
                    } else {
                        LiveTrackingService.ACTION_STOP
                    }
                }
                if (mode == TripMode.AUTO && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {}
        }
        AppStateManager.getInstance(context).recalculateState()
    }

    fun setActivityProfile(profile: ActivityProfile) {
        _activityProfile.value = profile
        prefs.edit().putString(KEY_ACTIVITY_PROFILE, profile.name).apply()
        _autoStopMinutes.value = getAutoStopMinutesForProfile(profile)
        val profileTripMode = getTripModeForProfile(profile)
        setTripMode(profileTripMode)
        val current = _activeTrip.value
        if (current != null) {
            val updated = current.copy(activityProfile = profile)
            _activeTrip.value = updated
            scope.launch {
                dbHelper.updateTripActivityProfile(current.id, profile)
            }
        }
        TelemetryLogger.log("SETTINGS", "ActivityProfile changed to ${profile.displayName} with mode=${profileTripMode.name}")
    }

    fun setAutoStopMinutes(minutes: Int) {
        setAutoStopMinutesForProfile(_activityProfile.value, minutes)
    }

    fun startManualTrip() {
        startTrip(isAuto = false)
    }

    fun stopManualTrip() {
        stopTrip(isManualStop = true)
    }

    private fun startTrip(isAuto: Boolean) {
        val now = System.currentTimeMillis()
        totalSpeedSamples = 0
        sumSpeedKmh = 0.0
        lastMovingTimestamp = now
        movingSinceTimestamp = null
        pendingCandidate = null
        activeTripPointSources.clear()

        val profile = _activityProfile.value

        val trip = TripRecord(
            startTime = now,
            isAutoDetected = isAuto,
            activityProfile = profile
        )
        val id = dbHelper.insertTrip(trip)
        val started = trip.copy(id = id)
        _activeTrip.value = started
        autoStartFirstLocation = null
        pauseAnchorLocation = null
        pauseAnchorFirstStationaryTime = null
        movingSinceInPauseTimestamp = null
        pauseWarningNotified = false
        isManualPauseSticky = false
        prefs.edit()
            .remove(KEY_IS_PAUSED)
            .remove(KEY_PAUSE_START_TIME)
            .remove(KEY_PAUSE_LAT)
            .remove(KEY_PAUSE_LNG)
            .remove(KEY_PAUSE_POINT_INDEX)
            .remove(KEY_PAUSE_DIST)
            .remove(KEY_MANUAL_PAUSE_STICKY)
            .apply()
        TelemetryLogger.logTrip("STARTED", id, "isAuto=$isAuto, profile=${profile.displayName}")

        // Clear live sharing trail buffer so new trip starts with a clean live path
        LiveSharingManager.getInstance(context).resetSessionTrail()

        startWatchdog()
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

    private fun stopTrip(isManualStop: Boolean = false) {
        val current = _activeTrip.value ?: return
        val now = System.currentTimeMillis()

        val updatedPauses = current.pauses.toMutableList()
        // If the user manually stopped the trip while resting at a place, finalize the active pause
        if (isManualStop && activePause != null && (now - activePause!!.startTime) >= 45_000L) {
            val finalized = activePause!!.copy(
                endTime = now,
                durationMs = now - activePause!!.startTime
            )
            tryCoalescePause(updatedPauses, finalized, now, current.id)
            TelemetryLogger.logTrip("PAUSE_FINALIZED", current.id, "Finalized manual stop pause: ${finalized.durationMs / 1000}s")
        }
        activePause = null

        // Auto-stop waiting or stopping at home is NOT a rest pause: sanitize only for auto-stop
        val sanitizedPauses = if (isManualStop) {
            updatedPauses
        } else {
            sanitizeTrailingPauses(updatedPauses, current.points.size, now, current.points.lastOrNull())
        }

        val durationMs = now - current.startTime
        val profile = current.activityProfile
        val isTrivialTrip = current.distanceMeters < profile.minValidDistanceMeters && durationMs < profile.minValidDurationMs

        if (isTrivialTrip) {
            // Discard "empty" accidental / instant start-stop trip (e.g. 0m in 1s or stationary jitter)
            dbHelper.deleteTrip(current.id)
            TelemetryLogger.logTrip("DISCARDED_TRIVIAL", current.id, "Trip discarded: dist=${current.distanceMeters.toInt()}m < ${profile.minValidDistanceMeters}m and duration=${durationMs / 1000}s < ${profile.minValidDurationMs / 1000}s")
        } else {
            val finishedTrip = current.copy(endTime = now, pauses = sanitizedPauses)
            val sourcesSnapshot = activeTripPointSources.toList()
            dbHelper.updateTrip(finishedTrip, sourcesSnapshot)
            val hadGpsFallback = sourcesSnapshot.any { it != "DEM" }
            activeTripPointSources.clear()
            TelemetryLogger.logTrip("STOPPED", current.id, "dist=${current.distanceMeters.toInt()}m, places=${current.placesVisited.size}, pauses=${sanitizedPauses.size}")
            try {
                CacheManager.getInstance(context).onTripFinished()
            } catch (_: Exception) {}

            if (finishedTrip.points.isNotEmpty() && hadGpsFallback && MapProfileConfigHelper.getAltitudeSourceMode(context) == AltitudeSourceMode.DEM) {
                scope.launch {
                    try {
                        ElevationCorrectionHelper.correctTrip(
                            trip = finishedTrip,
                            mode = OverrideMode.FILL_MISSING,
                            db = dbHelper
                        ) { _, _ -> }
                    } catch (_: Exception) {}
                }
            }
        }
        _activeTrip.value = null
        _isPaused.value = false
        pauseWarningNotified = false
        stopWatchdog()
        isManualPauseSticky = false
        prefs.edit()
            .remove(KEY_IS_PAUSED)
            .remove(KEY_PAUSE_START_TIME)
            .remove(KEY_PAUSE_LAT)
            .remove(KEY_PAUSE_LNG)
            .remove(KEY_PAUSE_POINT_INDEX)
            .remove(KEY_PAUSE_DIST)
            .remove(KEY_MANUAL_PAUSE_STICKY)
            .apply()
        lastLocation = null
        lastMovingTimestamp = null
        movingSinceTimestamp = null
        autoStartFirstLocation = null
        pauseAnchorLocation = null
        pauseAnchorFirstStationaryTime = null
        movingSinceInPauseTimestamp = null
        pendingCandidate = null

        // Stop foreground service or transition to low-power standby
        val liveSession = LiveSharingManager.getInstance(context).currentSession.value
        val hasLive = liveSession != null && liveSession.isActive
        if (!hasLive) {
            try {
                val intent = android.content.Intent(context, LiveTrackingService::class.java).apply {
                    action = if (_tripMode.value == TripMode.AUTO) {
                        LiveTrackingService.ACTION_ENTER_STANDBY
                    } else {
                        LiveTrackingService.ACTION_STOP
                    }
                }
                context.startService(intent)
            } catch (_: Exception) {}
        }
        AppStateManager.getInstance(context).onTripEnded()
    }

    private fun sanitizeTrailingPauses(
        pauses: List<TripPause>,
        tripPointsCount: Int,
        tripEndTime: Long,
        destinationPoint: GeoPoint? = null
    ): List<TripPause> {
        if (pauses.isEmpty()) return pauses
        val result = pauses.toMutableList()
        val last = result.last()
        val isAtEnd = last.pointIndex >= (tripPointsCount - 5).coerceAtLeast(0)
        val endsNearTripEnd = (tripEndTime - (last.endTime ?: last.startTime)) <= 180_000L || (last.endTime ?: 0L) >= tripEndTime
        val isNearDestination = if (destinationPoint != null) {
            val dist = FloatArray(1)
            Location.distanceBetween(last.latitude, last.longitude, destinationPoint.latitude, destinationPoint.longitude, dist)
            dist[0] <= 100.0f
        } else false

        if (isAtEnd || (endsNearTripEnd && isNearDestination)) {
            TelemetryLogger.logTrip("PAUSE_TRIMMED", 0L, "Discarded trailing arrival pause at trip destination: dur=${last.durationMs / 1000}s, idx=${last.pointIndex}/$tripPointsCount")
            result.removeAt(result.size - 1)
        }
        return result
    }

    private fun tryCoalescePause(
        pauses: MutableList<TripPause>,
        candidate: TripPause,
        now: Long,
        tripId: Long
    ) {
        val prevPause = pauses.lastOrNull()
        val canCoalesce = if (prevPause != null) {
            val prevEnd = prevPause.endTime ?: (prevPause.startTime + prevPause.durationMs)
            val gapMs = candidate.startTime - prevEnd
            val dPrev = FloatArray(1)
            Location.distanceBetween(
                prevPause.latitude,
                prevPause.longitude,
                candidate.latitude,
                candidate.longitude,
                dPrev
            )
            // Coalesce if overlapping (gapMs <= 0) or close proximity adjacent (gapMs in 0..60s) within 30m
            gapMs <= 60_000L && dPrev[0] <= 30.0f
        } else false

        if (canCoalesce && prevPause != null) {
            val prevEnd = prevPause.endTime ?: (prevPause.startTime + prevPause.durationMs)
            val candEnd = candidate.endTime ?: (candidate.startTime + candidate.durationMs)
            val combinedEnd = maxOf(prevEnd, candEnd)
            val combinedStart = minOf(prevPause.startTime, candidate.startTime)
            val merged = prevPause.copy(
                startTime = combinedStart,
                endTime = combinedEnd,
                durationMs = (combinedEnd - combinedStart).coerceAtLeast(prevPause.durationMs + candidate.durationMs)
            )
            pauses[pauses.size - 1] = merged
            TelemetryLogger.logTrip("PAUSE_COALESCED", tripId, "Merged pause: ${merged.durationMs / 1000}s")
        } else {
            pauses.add(candidate)
            TelemetryLogger.logTrip("PAUSE_RECORDED", tripId, "Pause recorded: ${candidate.durationMs / 1000}s")
        }
    }

    fun onLocationUpdate(
        lat: Double,
        lng: Double,
        speedMs: Float?,
        currentPlace: PlaceInfo?,
        accuracy: Float? = null,
        altitude: Double? = null
    ) {
        val now = System.currentTimeMillis()
        val speedKmh = (speedMs ?: 0f) * 3.6f
        val currentProfile = _activityProfile.value
        val isMoving = speedKmh >= currentProfile.autoStopSpeedKmh

        TelemetryLogger.logGps(lat, lng, speedKmh, null, null)

        val newLoc = Location("").apply {
            latitude = lat
            longitude = lng
            if (accuracy != null) this.accuracy = accuracy
        }

        // ── Auto-Start / Auto-Stop Logic ───────────────────────────────────────
        if (_tripMode.value == TripMode.AUTO) {
            if (_activeTrip.value == null) {
                // MAP-R02: Gate auto-start behind strict accuracy (<= 25m) to prevent indoor multipath jumps
                val isAccurate = (accuracy ?: 0f) <= 25f
                
                // Sensitive auto-start: triggered by sustained speed OR cumulative displacement >= 25m
                val targetSpeed = getAutoStartSpeedForProfile(currentProfile)
                val candidateSpeed = speedKmh >= (targetSpeed * 0.7f)
                if (isAccurate && candidateSpeed) {
                    if (autoStartFirstLocation == null) {
                        autoStartFirstLocation = newLoc
                        autoStartFirstTime = now
                        movingSinceTimestamp = now
                    } else {
                        val elapsed = now - autoStartFirstTime
                        val distMoved = autoStartFirstLocation!!.distanceTo(newLoc)
                        val autoStartDurationMs = getAutoStartSecondsForProfile(currentProfile) * 1000L
                        if ((speedKmh >= targetSpeed && elapsed >= autoStartDurationMs) ||
                            (distMoved >= 25.0 && elapsed >= 8_000L && speedKmh >= (targetSpeed * 0.7f))
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
                val active = _activeTrip.value ?: return
                if (_isPaused.value) {
                    // Suppress auto-stop completely while paused!
                    // Check auto-resume on motion if enabled with sustained locomotion confirmation
                    val targetSpeed = getAutoStartSpeedForProfile(currentProfile)
                    if (_autoResumeOnMotion.value && speedKmh >= targetSpeed) {
                        if (pauseAutoResumeFirstLocation == null) {
                            pauseAutoResumeFirstLocation = Location(newLoc)
                            pauseAutoResumeFirstTime = now
                        } else {
                            val elapsed = now - pauseAutoResumeFirstTime
                            val distMoved = newLoc.distanceTo(pauseAutoResumeFirstLocation!!)
                            val requiredDurationMs = currentProfile.autoStartDurationMs.coerceAtMost(30_000L)
                            val requiredDisplacement = if (currentProfile == ActivityProfile.CAR) 30.0 else 15.0
                            if (elapsed >= requiredDurationMs && distMoved >= requiredDisplacement) {
                                resumeTrip(isManual = false)
                                TelemetryLogger.logTrip("AUTO_RESUME", active.id, "Confirmed motion while paused (${elapsed/1000}s, ${distMoved.toInt()}m): auto-resumed trip")
                            }
                        }
                    } else if (speedKmh < 1.0f && pauseAutoResumeFirstLocation != null && (now - pauseAutoResumeFirstTime) > 15_000L) {
                        pauseAutoResumeFirstLocation = null
                        pauseAutoResumeFirstTime = 0L
                    }
                } else {
                    // Check if permanent continuation threshold reached to clear isManualPauseSticky
                    checkPermanentContinuation(active, speedKmh, now)

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
                            if (isManualPauseSticky) {
                                TelemetryLogger.logTrip("STICKY_PAUSE_RETURN", active.id, "Stationary for ${stationaryDurationMs / 60000}m with sticky pause active: returning to Pause.")
                                pauseTrip(isManual = false)
                                return
                            } else {
                                stopTrip()
                                return
                            }
                        }
                    }
                }
            }
        }

        // ── Active Trip Tracking ───────────────────────────────────────────────
        val current = _activeTrip.value ?: return

        // If trip is manually paused, keep activePause duration updated and do not accumulate points/distance
        if (_isPaused.value) {
            if (activePause != null) {
                activePause = activePause!!.copy(durationMs = (now - activePause!!.startTime).coerceAtLeast(1000L))
            }
            return
        }

        // ── Pause Detection & Tracking ─────────────────────────────────────────
        val updatedPauses = current.pauses.toMutableList()

        // 1. Spatial cluster anchor maintenance:
        // Check displacement from current pauseAnchorLocation
        val distFromAnchor = if (pauseAnchorLocation != null) {
            val res = FloatArray(1)
            Location.distanceBetween(
                pauseAnchorLocation!!.latitude,
                pauseAnchorLocation!!.longitude,
                lat,
                lng,
                res
            )
            res[0].toDouble()
        } else {
            0.0
        }

        if (isMoving || distFromAnchor > 12.0) {
            // Speed indicates locomotion OR displacement broke out of the 12m stationary cluster
            if (activePause != null) {
                // Exit debounce: require sustained movement for >= 45s before finalizing the pause
                if (movingSinceInPauseTimestamp == null) {
                    movingSinceInPauseTimestamp = now
                }
                val movingDurationMs = now - movingSinceInPauseTimestamp!!
                if (movingDurationMs >= 45_000L) {
                    // Finalize the pause up to the start of resumed motion
                    val pauseEndTime = movingSinceInPauseTimestamp!!
                    val finalized = activePause!!.copy(
                        endTime = pauseEndTime,
                        durationMs = (pauseEndTime - activePause!!.startTime).coerceAtLeast(150_000L)
                    )

                    tryCoalescePause(updatedPauses, finalized, now, current.id)

                    activePause = null
                    pauseAnchorLocation = null
                    pauseAnchorFirstStationaryTime = null
                    movingSinceInPauseTimestamp = null
                    if (isMoving) {
                        lastMovingTimestamp = now
                    }
                }
            } else {
                // Not in active pause: reset anchor if moved outside 12m
                if (distFromAnchor > 12.0) {
                    pauseAnchorLocation = newLoc
                    pauseAnchorFirstStationaryTime = now
                }
                movingSinceInPauseTimestamp = null
                if (isMoving) {
                    lastMovingTimestamp = now
                }
            }
        } else {
            // Stationary: inside <= 12m cluster and speed < autoStopSpeedKmh
            movingSinceInPauseTimestamp = null
            if (pauseAnchorLocation == null) {
                pauseAnchorLocation = newLoc
                pauseAnchorFirstStationaryTime = now
            }
            if (lastMovingTimestamp == null) {
                lastMovingTimestamp = now
            }

            val stationaryStartTime = pauseAnchorFirstStationaryTime ?: lastMovingTimestamp!!
            val stationaryMs = now - stationaryStartTime

            // Entry threshold: require sustained stationary dwell >= 150s (2.5 min)
            if (stationaryMs >= 150_000L) {
                if (activePause == null) {
                    activePause = TripPause(
                        startTime = stationaryStartTime,
                        latitude = pauseAnchorLocation?.latitude ?: lat,
                        longitude = pauseAnchorLocation?.longitude ?: lng,
                        durationMs = stationaryMs,
                        pointIndex = current.points.size,
                        distanceMeters = current.distanceMeters
                    )
                    TelemetryLogger.logTrip("PAUSE_DISCOVERED", current.id, "Discovered pause after ${stationaryMs / 1000}s stationary dwell")
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
        val mode = MapProfileConfigHelper.getAltitudeSourceMode(context)
        val cachedDem = if (mode == AltitudeSourceMode.DEM) {
            dbHelper.getCachedElevation(lat, lng)
        } else null

        val effectiveAlt = cachedDem ?: altitude
        val altSource = if (cachedDem != null) "DEM" else if (altitude != null && altitude > 0.0) "GPS" else null
        val newGeoPoint = if (effectiveAlt != null && !effectiveAlt.isNaN() && effectiveAlt != 0.0) {
            GeoPoint(lat, lng, effectiveAlt)
        } else {
            GeoPoint(lat, lng)
        }
        val lastCommittedPoint = newPoints.lastOrNull()
        val distFromLastCommitted = if (lastCommittedPoint != null) {
            val results = FloatArray(1)
            Location.distanceBetween(lastCommittedPoint.latitude, lastCommittedPoint.longitude, lat, lng, results)
            results[0].toDouble()
        } else {
            Double.MAX_VALUE
        }

        // Prevent duplicate points or micro-oscillations
        if (lastCommittedPoint != null && 
            lastCommittedPoint.latitude == lat && 
            lastCommittedPoint.longitude == lng) {
            return
        }

        // Suppress stationary polyline jitter: do not commit micro jitter points when vehicle/hiker is completely stopped.
        // True physical locomotion (speed >= 1.5 km/h) or displacement >= 15m commits points at high fidelity
        val isKinematicallyMoving = speedKmh >= 1.5f || isMoving
        val isLocallyMoving = isKinematicallyMoving || distFromLastCommitted >= 15.0
        val commitThreshold = when (currentProfile) {
            ActivityProfile.WALKING, ActivityProfile.HIKING -> 2.0
            ActivityProfile.RUNNING, ActivityProfile.MTB, ActivityProfile.CYCLING -> 3.5
            ActivityProfile.CAR -> 5.0
        }
        if (newPoints.isEmpty() || (distFromLastCommitted >= commitThreshold && isLocallyMoving)) {
            newPoints.add(newGeoPoint)
            activeTripPointSources.add(altSource ?: "GPS")
        }

        // Check for newly entered locality (with intentional visit filter: penetrate > 150m OR stay > 45s)
        val newPlaces = current.placesVisited.toMutableList()
        updateVisitedPlaces(
            lat = lat,
            lng = lng,
            currentDistance = newDistance,
            currentPlace = currentPlace,
            placesList = newPlaces,
            tripId = current.id,
            now = now
        )

        val updated = current.copy(
            distanceMeters = newDistance,
            maxSpeedKmh = newMaxSpeed,
            avgSpeedKmh = newAvgSpeed,
            points = newPoints,
            placesVisited = newPlaces,
            pauses = updatedPauses
        )
        _activeTrip.value = updated
        checkPermanentContinuation(updated, speedKmh, now)

        // Persist progress periodically (every 10 points, pause recorded, or place change)
        if (newPoints.size % 10 == 0 || newPlaces.size != current.placesVisited.size || updatedPauses.size != current.pauses.size) {
            val sourcesSnapshot = activeTripPointSources.toList()
            scope.launch {
                dbHelper.updateTrip(updated, sourcesSnapshot)
            }
        }
    }

    fun getAllTrips(): List<TripRecord> {
        return dbHelper.getAllTrips()
    }

    fun queryTrips(
        searchQuery: String? = null,
        startTimeMin: Long? = null,
        startTimeMax: Long? = null,
        activityProfile: ActivityProfile? = null
    ): List<TripRecord> {
        return dbHelper.queryTrips(searchQuery, startTimeMin, startTimeMax, activityProfile)
    }

    fun deleteTrip(id: Long) {
        dbHelper.deleteTrip(id)
    }

    fun onLocalityEnriched(currentPlace: PlaceInfo?) {
        val current = _activeTrip.value ?: return
        val lastLoc = lastLocation ?: return
        val now = System.currentTimeMillis()
        val placesList = current.placesVisited.toMutableList()
        val changed = updateVisitedPlaces(
            lat = lastLoc.latitude,
            lng = lastLoc.longitude,
            currentDistance = current.distanceMeters,
            currentPlace = currentPlace,
            placesList = placesList,
            tripId = current.id,
            now = now
        )
        if (changed) {
            val updated = current.copy(placesVisited = placesList)
            _activeTrip.value = updated
            scope.launch {
                dbHelper.updateTrip(updated)
            }
        }
    }

    private fun detectOutdoorPlaceKind(name: String): PlaceKind {
        val lower = name.lowercase(java.util.Locale.ROOT)
        return when {
            lower.contains("przełęcz") || lower.contains("pass") || lower.contains("siodło") || lower.contains("karb") -> PlaceKind.MOUNTAIN_PASS
            lower.contains("szczyt") || lower.contains("wierch") || lower.contains("turnia") || lower.contains("kopa") || lower.contains("góra") || lower.contains("peak") -> PlaceKind.PEAK
            lower.contains("perć") || lower.contains("szlak") || lower.contains("ścieżka") || lower.contains("trail") -> PlaceKind.TRAIL
            lower.contains("dolina") || lower.contains("polana") || lower.contains("hala") || lower.contains("schronisko") || lower.contains("staw") || lower.contains("betlejemka") -> PlaceKind.POI
            else -> PlaceKind.LOCALITY
        }
    }

    private fun updateVisitedPlaces(
        lat: Double,
        lng: Double,
        currentDistance: Double,
        currentPlace: PlaceInfo?,
        placesList: MutableList<VisitedPlace>,
        tripId: Long,
        now: Long
    ): Boolean {
        if (currentPlace == null) return false

        val isOutdoorProfile = _activityProfile.value in setOf(ActivityProfile.HIKING, ActivityProfile.WALKING, ActivityProfile.MTB)
        val streetCandidate = currentPlace.street?.trim()?.takeIf {
            it.isNotBlank() &&
            !it.startsWith("ul. ") && !it.startsWith("al. ") && !it.startsWith("pl. ") && !it.startsWith("os. ") &&
            detectOutdoorPlaceKind(it) != PlaceKind.LOCALITY
        }

        val candidateName: String
        val candidateKind: PlaceKind
        if (isOutdoorProfile && streetCandidate != null) {
            candidateName = streetCandidate
            candidateKind = detectOutdoorPlaceKind(streetCandidate)
        } else {
            if (currentPlace.city == "Unknown City" || currentPlace.city == "--") return false
            candidateName = currentPlace.city
            candidateKind = detectOutdoorPlaceKind(currentPlace.city)
        }

        val isImmediateLast = placesList.lastOrNull()?.placeName.equals(candidateName, ignoreCase = true)

        if (isImmediateLast) {
            pendingCandidate = null
            return false
        }
        if (placesList.isEmpty()) {
            TelemetryLogger.logTrip("LOCALITY_INITIAL", tripId, "Initial trip start at $candidateName ($candidateKind)")
            placesList.add(
                VisitedPlace(
                    placeName = candidateName,
                    hierarchySubtitle = LocationManager.formatHierarchy(currentPlace),
                    timestamp = now,
                    latitude = lat,
                    longitude = lng,
                    distanceAtEntryMeters = currentDistance,
                    placeKind = candidateKind
                )
            )
            pendingCandidate = null
            return true
        }

        val candidate = pendingCandidate
        if (candidate != null && candidate.placeName.equals(candidateName, ignoreCase = true)) {
            val distResults = FloatArray(1)
            Location.distanceBetween(candidate.entryLatitude, candidate.entryLongitude, lat, lng, distResults)
            val displacementMeters = distResults[0]
            val durationMs = now - candidate.firstSeenTimestamp

            // Rule 1: Centroid distance check for known Polish county cities
            val centroid = POLISH_CITY_CENTROIDS[candidateName.lowercase(java.util.Locale.ROOT)]
            if (centroid != null) {
                val distKm = distanceBetweenKm(lat, lng, centroid.first, centroid.second)
                if (distKm > 25.0) {
                    TelemetryLogger.logTrip("LOCALITY_REJECTED", tripId, "Rejected candidate $candidateName: $distKm km from city centroid")
                    pendingCandidate = null
                    return false
                }
            }

            // Rule 2: County city contradiction check (e.g. city is Kraków, but powiat is Powiat Bielsko-Biała)
            val candidatePowiat = currentPlace.powiat ?: candidate.place.powiat
            if (candidateName.equals("kraków", ignoreCase = true) && candidatePowiat?.contains("bielsko", ignoreCase = true) == true) {
                TelemetryLogger.logTrip("LOCALITY_REJECTED", tripId, "Rejected Kraków due to contradictory powiat: $candidatePowiat")
                pendingCandidate = null
                return false
            }

            // Rule 3: Speed-dependent intentional visit threshold
            // When moving at speed (CAR, CYCLING, MTB), 150m is traversed in ~7s.
            // Require sustained dwell (>= 30s) OR (displacement >= 300m AND duration >= 15s)
            val isFastMode = _activityProfile.value in setOf(ActivityProfile.CAR, ActivityProfile.CYCLING, ActivityProfile.MTB)
            val isPenetrated = if (isFastMode) {
                displacementMeters >= 300f && durationMs >= 15_000L
            } else {
                displacementMeters >= 150f
            }
            val isSustainedStay = durationMs >= (if (isFastMode) 30_000L else 45_000L)

            val recentMatch = placesList.takeLast(4).find { it.placeName.equals(candidateName, ignoreCase = true) }
            val isRecentPingPong = recentMatch != null && (
                (now - recentMatch.timestamp < 5 * 60 * 1000L) ||
                (currentDistance - recentMatch.distanceAtEntryMeters < 2500.0)
            )

            if ((isPenetrated || isSustainedStay) && !isRecentPingPong) {
                TelemetryLogger.logTrip(
                    "LOCALITY_COMMITTED",
                    tripId,
                    "Intentional visit to $candidateName ($candidateKind) committed: disp=${displacementMeters.toInt()}m, dur=${durationMs / 1000}s"
                )
                placesList.add(
                    VisitedPlace(
                        placeName = candidateName,
                        hierarchySubtitle = LocationManager.formatHierarchy(candidate.place),
                        timestamp = candidate.firstSeenTimestamp,
                        latitude = candidate.entryLatitude,
                        longitude = candidate.entryLongitude,
                        distanceAtEntryMeters = candidate.entryDistanceMeters,
                        placeKind = candidateKind
                    )
                )
                pendingCandidate = null
                return true
            } else if (isRecentPingPong) {
                pendingCandidate = null
                return false
            }
        } else {
            pendingCandidate = PendingPlaceCandidate(
                place = currentPlace,
                placeName = candidateName,
                placeKind = candidateKind,
                firstSeenTimestamp = now,
                entryLatitude = lat,
                entryLongitude = lng,
                entryDistanceMeters = currentDistance
            )
        }
        return false
    }

    private fun distanceBetweenKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val res = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, res)
        return res[0] / 1000.0
    }

    data class MaintenanceResult(val totalCleanedPlaces: Int, val affectedTripsCount: Int)

    fun reanalyzeAllTripPlaces(): MaintenanceResult {
        val allTrips = dbHelper.getAllTrips()
        var totalCleaned = 0
        var tripsAffected = 0

        for (trip in allTrips) {
            val places = trip.placesVisited
            if (places.isEmpty()) continue

            val cleaned = mutableListOf<VisitedPlace>()
            var removedInTrip = 0

            for (i in places.indices) {
                val p = places[i]
                val nameLower = p.placeName.lowercase(java.util.Locale.ROOT)

                // 1. Centroid distance check for known Polish county cities
                val centroid = POLISH_CITY_CENTROIDS[nameLower]
                if (centroid != null) {
                    val distKm = distanceBetweenKm(p.latitude, p.longitude, centroid.first, centroid.second)
                    if (distKm > 25.0) {
                        TelemetryLogger.logTrip("MAINTENANCE_REMOVED", trip.id, "Removed false place ${p.placeName}: $distKm km from city centroid")
                        removedInTrip++
                        continue
                    }
                }

                // 2. Sandwiched ping-pong blip check (< 30s stay between same locality)
                if (i < places.size - 1 && cleaned.isNotEmpty()) {
                    val nextP = places[i + 1]
                    val prevP = cleaned.last()
                    val durationSec = (nextP.timestamp - p.timestamp) / 1000.0
                    if (durationSec < 30.0 && prevP.placeName.equals(nextP.placeName, ignoreCase = true)) {
                        TelemetryLogger.logTrip("MAINTENANCE_REMOVED", trip.id, "Removed sandwiched ping-pong blip ${p.placeName} (${durationSec.toInt()}s)")
                        removedInTrip++
                        continue
                    }
                }

                // 3. Coalesce consecutive identical places
                if (cleaned.isNotEmpty() && cleaned.last().placeName.equals(p.placeName, ignoreCase = true)) {
                    continue
                }

                cleaned.add(p)
            }

            if (removedInTrip > 0) {
                dbHelper.cleanTripPlaces(trip.id, cleaned)
                totalCleaned += removedInTrip
                tripsAffected++
                if (_activeTrip.value?.id == trip.id) {
                    _activeTrip.value = _activeTrip.value?.copy(placesVisited = cleaned)
                }
            }
        }

        TelemetryLogger.log("MAINTENANCE", "Reanalyzed trips: cleaned $totalCleaned false places across $tripsAffected trips")
        return MaintenanceResult(totalCleaned, tripsAffected)
    }

    fun renameTrip(id: Long, newTitle: String) {
        dbHelper.renameTrip(id, newTitle)
        if (_activeTrip.value?.id == id) {
            _activeTrip.value = _activeTrip.value?.copy(title = newTitle)
        }
    }

    fun mergeTrips(
        tripIds: List<Long>,
        customTitle: String? = null,
        targetProfile: ActivityProfile? = null
    ): Long {
        return dbHelper.mergeTrips(tripIds, customTitle, targetProfile)
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

    fun deleteTripPause(tripId: Long, pauseIndex: Int): Boolean {
        val result = dbHelper.deleteTripPause(tripId, pauseIndex)
        if (result) {
            val active = _activeTrip.value
            if (active?.id == tripId) {
                _activeTrip.value = dbHelper.getTrip(tripId)
            }
        }
        return result
    }

    fun mergeTripPauses(tripId: Long, pauseIndex: Int): Boolean {
        val result = dbHelper.mergeTripPauses(tripId, pauseIndex)
        if (result) {
            val active = _activeTrip.value
            if (active?.id == tripId) {
                _activeTrip.value = dbHelper.getTrip(tripId)
            }
        }
        return result
    }
}

