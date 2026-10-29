package janush.tech.whereami

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint
import janush.tech.whereami.tracking.AdaptiveTrackSampler
import janush.tech.whereami.tracking.SmoothedPoint
import janush.tech.whereami.tracking.TrackPointRow
import janush.tech.whereami.tracking.TrackSpikeGuard
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
        /** Hard cap for CAR/CYCLING/MTB auto-start confirmation (seconds). Matches profile defaults. */
        const val VEHICLE_AUTO_START_MAX_SEC = 10
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
        /** Durability vs battery: small batches on the IO writer only (never per-fix, never with a wake lock). */
        private const val ROW_FLUSH_COUNT = 5
        private const val ROW_FLUSH_INTERVAL_MS = 3_000L
        /** Smoothed points are 3 s old when emitted; accept those that predate the trip start by this much. */
        private const val TRACK_LEAD_IN_MS = 5_000L
        private const val MIN_STEP_DISTANCE_M = 0.5f
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
        getAutoStopMinutesForProfile(_activityProfile.value)
    )
    val autoStopMinutes: StateFlow<Int> = _autoStopMinutes.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _autoResumeOnMotion = MutableStateFlow(prefs.getBoolean(KEY_AUTO_RESUME_ON_MOTION, true))
    val autoResumeOnMotion: StateFlow<Boolean> = _autoResumeOnMotion.asStateFlow()

    private var isManualPauseSticky: Boolean = prefs.getBoolean(KEY_MANUAL_PAUSE_STICKY, false)
    private var autoResumeStartDistance: Double = 0.0
    private var autoResumeStartTime: Long = 0L
    private val resumeMotionGate = ResumeMotionGate()
    /** Foot auto-start: strict accuracy (indoor multipath must not start walks). */
    private val footAutoStartGate = ResumeMotionGate()
    /**
     * Vehicle auto-start: softer accuracy / one-miss grace
     * (field trip 243 started ~244 m from home under a 30 s window + 25 m resets).
     * Indoor multipath still fails the net-displacement floor (trip 239).
     */
    private val vehicleAutoStartGate = ResumeMotionGate.forVehicleAutoStart()

    private fun autoStartGateFor(profile: ActivityProfile): ResumeMotionGate =
        if (profile.isVehicleProfile) vehicleAutoStartGate else footAutoStartGate

    private fun resetAutoStartGates() {
        footAutoStartGate.reset()
        vehicleAutoStartGate.reset()
    }

    // ── Track point persistence (track_points table) ────────────────────────────
    // All geometry writes go through one single-thread dispatcher so appends, metadata writes and the
    // final stop write stay strictly ordered without ever touching the main thread.
    private val dbWriter = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "wai-trip-writer").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val rowLock = Any()
    private val pendingRows = ArrayList<TrackPointRow>()
    private var persistedPointCount = 0
    private var lastRowFlushMs = 0L
    private var lastSmoothedForDistance: SmoothedPoint? = null
    private val trackSampler = AdaptiveTrackSampler()

    /** Elapsed-realtime timestamp of the newest committed track vertex (the live map draws past it). */
    @Volatile
    var lastCommittedElapsedMs: Long = -1L
        private set
    private var lastCommittedUtcMs: Long = -1L
    private var lastRejectedSmoothedPoint: SmoothedPoint? = null

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
        migrateInflatedVehicleAutoStartIfNeeded(profile, defaultSec)
        val stored = prefs.getInt(KEY_AUTO_START_PREFIX + profile.name, defaultSec)
        // Vehicles: cap confirmation at 10 s (profile default) so leftover 15–30 s prefs do not miss
        // hundreds of metres (field trips 243/246/247). Foot profiles keep the stored value.
        return if (profile.isVehicleProfile) stored.coerceAtMost(VEHICLE_AUTO_START_MAX_SEC) else stored
    }

    private fun migrateInflatedVehicleAutoStartIfNeeded(profile: ActivityProfile, defaultSec: Int) {
        if (!profile.isVehicleProfile) return
        val key = KEY_AUTO_START_PREFIX + profile.name
        // v10 supersedes v15: Round 111 found migrated-to-15 still lagged ~250 m with Live Sharing.
        val migKey = "pref_auto_start_sec_migrated_v10_" + profile.name
        if (prefs.getBoolean(migKey, false)) return
        val stored = if (prefs.contains(key)) prefs.getInt(key, defaultSec) else defaultSec
        val editor = prefs.edit().putBoolean(migKey, true)
        if (stored > VEHICLE_AUTO_START_MAX_SEC) {
            editor.putInt(key, VEHICLE_AUTO_START_MAX_SEC)
            TelemetryLogger.log("SETTINGS", "Migrated AutoStartSeconds for ${profile.name} from $stored → $VEHICLE_AUTO_START_MAX_SEC")
        }
        editor.apply()
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

    fun getPauseDwellThresholdMs(profile: ActivityProfile): Long = when (profile) {
        ActivityProfile.CAR -> 240_000L // 4.0 min
        ActivityProfile.CYCLING, ActivityProfile.MTB -> 180_000L // 3.0 min
        else -> 210_000L // 3.5 min
    }

    fun setAutoStartSpeedForProfile(profile: ActivityProfile, speedKmh: Float) {
        val clamped = speedKmh.coerceIn(1.0f, 150.0f)
        prefs.edit().putFloat(KEY_AUTO_START_SPEED_PREFIX + profile.name, clamped).apply()
        TelemetryLogger.log("SETTINGS", "AutoStartSpeed for ${profile.name} changed to $clamped km/h")
    }

    fun getAutoStopMinutesForProfile(profile: ActivityProfile): Int {
        val defaultMin = profile.autoStopMinutesDefault
        migrateExtendedAutoStopIfNeeded(profile, defaultMin)
        return prefs.getInt(KEY_AUTO_STOP_PREFIX + profile.name, defaultMin)
    }

    private fun migrateExtendedAutoStopIfNeeded(profile: ActivityProfile, defaultMin: Int) {
        val migKey = "pref_auto_stop_migrated_v122_" + profile.name
        if (prefs.getBoolean(migKey, false)) return
        val key = KEY_AUTO_STOP_PREFIX + profile.name
        val stored = if (prefs.contains(key)) prefs.getInt(key, defaultMin) else defaultMin
        val editor = prefs.edit().putBoolean(migKey, true)
        if (prefs.contains(KEY_AUTO_STOP_MINUTES)) {
            editor.remove(KEY_AUTO_STOP_MINUTES)
        }
        // If stored value is from the old legacy short presets (<= 20 min) or missing, upgrade to new profile default
        if (stored <= 20 || !prefs.contains(key)) {
            editor.putInt(key, defaultMin)
            TelemetryLogger.log("SETTINGS", "Migrated AutoStopMinutes for ${profile.name} from $stored → $defaultMin")
        }
        editor.apply()
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
    private var autoStopAlarmPendingIntent: PendingIntent? = null

    fun scheduleAutoStopAlarm(tripId: Long, timeoutMs: Long) {
        cancelAutoStopAlarm()
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, LiveTrackingService::class.java).apply {
            action = LiveTrackingService.ACTION_AUTO_STOP_DEADLINE
            putExtra("trip_id", tripId)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pi = PendingIntent.getService(context, 8881, intent, flags)
        autoStopAlarmPendingIntent = pi
        val triggerAtMs = System.currentTimeMillis() + timeoutMs
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
            }
            TelemetryLogger.logTrip("AUTO_STOP_ALARM", tripId, "Scheduled exact auto-stop alarm in ${timeoutMs / 60000}m")
        } catch (e: Exception) {
            TelemetryLogger.log("ERROR", "Failed to schedule exact auto-stop alarm: ${e.message}")
        }
    }

    fun cancelAutoStopAlarm() {
        autoStopAlarmPendingIntent?.let {
            try {
                val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                am?.cancel(it)
                TelemetryLogger.log("POWER", "Auto-stop exact alarm cancelled")
            } catch (_: Exception) {}
            autoStopAlarmPendingIntent = null
        }
    }

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
        if (activePause == null) {
            activePause = TripPause(
                startTime = now,
                latitude = lat,
                longitude = lng,
                pointIndex = current.points.size,
                distanceMeters = current.distanceMeters
            )
        }
        val pause = activePause!!
        pauseWarningNotified = false
        resumeMotionGate.reset()
        vehicleAutoStartGate.reset()
        prefs.edit()
            .putBoolean(KEY_IS_PAUSED, true)
            .putLong(KEY_PAUSE_START_TIME, pause.startTime)
            .putFloat(KEY_PAUSE_LAT, pause.latitude.toFloat())
            .putFloat(KEY_PAUSE_LNG, pause.longitude.toFloat())
            .putInt(KEY_PAUSE_POINT_INDEX, pause.pointIndex)
            .putFloat(KEY_PAUSE_DIST, pause.distanceMeters.toFloat())
            .apply()
        val eventTag = if (isManual) "PAUSE_MANUAL" else "PAUSE_AUTO"
        TelemetryLogger.logTrip(eventTag, current.id, "Trip paused (manual=$isManual, sticky=$isManualPauseSticky) at point ${pause.pointIndex}")
        janush.tech.whereami.tracking.LifecycleDiagnosticsRecorder.log(
            "TRIP_PAUSE",
            mapOf("tripId" to current.id, "isManual" to isManual, "pointIndex" to pause.pointIndex)
        )
        val timeoutMs = (_autoStopMinutes.value.coerceAtLeast(1)) * 60 * 1000L
        scheduleAutoStopAlarm(current.id, timeoutMs)
    }

    fun resumeTrip(isManual: Boolean = true) {
        val current = _activeTrip.value ?: return
        if (!_isPaused.value) return
        _isPaused.value = false
        cancelAutoStopAlarm()
        if (isManual) {
            isManualPauseSticky = false
            prefs.edit().remove(KEY_MANUAL_PAUSE_STICKY).apply()
        } else {
            autoResumeStartDistance = current.distanceMeters
            autoResumeStartTime = System.currentTimeMillis()
        }
        janush.tech.whereami.tracking.LifecycleDiagnosticsRecorder.log(
            "TRIP_RESUME",
            mapOf("tripId" to current.id, "isManual" to isManual)
        )
        val now = System.currentTimeMillis()
        if (activePause != null) {
            val finalized = activePause!!.copy(
                endTime = now,
                durationMs = (now - activePause!!.startTime).coerceAtLeast(1000L)
            )
            val updatedPauses = current.pauses.toMutableList()
            tryCoalescePause(updatedPauses, finalized, now, current.id)
            _activeTrip.value = current.copy(pauses = updatedPauses)
            val eventTag = if (isManual) "RESUME_MANUAL" else "RESUME_AUTO"
            TelemetryLogger.logTrip(eventTag, current.id, "Trip resumed (manual=$isManual, sticky=$isManualPauseSticky) after ${finalized.durationMs / 1000}s")
            activePause = null
        }
        pauseAnchorLocation = null
        pauseAnchorFirstStationaryTime = null
        pauseAnchorPointIndex = null
        pauseAnchorDistanceMeters = null
        movingSinceInPauseTimestamp = null
        pauseWarningNotified = false
        resumeMotionGate.reset()
        vehicleAutoStartGate.reset()
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
    private var lastLocation: Location? = null
    private var activePause: TripPause? = null

    // Robust rest-stop cluster tracking (150s stationary dwell + 12m spatial cluster gate)
    private var pauseAnchorLocation: Location? = null
    private var pauseAnchorFirstStationaryTime: Long? = null
    private var pauseAnchorPointIndex: Int? = null
    private var pauseAnchorDistanceMeters: Double? = null
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
                val lastTrackUtc = dbHelper.getLastTrackPointUtcMs(unclosed.id)
                val lastActivityUtc = TripRecovery.lastActivityUtcMs(
                    lastTrackPointUtcMs = lastTrackUtc,
                    lastPlaceVisitUtcMs = unclosed.placesVisited.lastOrNull()?.timestamp,
                    startTimeUtcMs = unclosed.startTime
                )
                val now = System.currentTimeMillis()
                val wasPaused = prefs.getBoolean(KEY_IS_PAUSED, false)

                // Abandoned AUTO trip past auto-stop idle window (clock = last track point, not start/place).
                if (TripRecovery.shouldCloseAsZombie(
                        tripModeAuto = _tripMode.value == TripMode.AUTO,
                        wasPaused = wasPaused,
                        nowUtcMs = now,
                        lastActivityUtcMs = lastActivityUtc,
                        autoStopMinutes = _autoStopMinutes.value
                    )
                ) {
                    val closed = unclosed.copy(endTime = lastActivityUtc)
                    dbHelper.updateTripMeta(closed)
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
                    TelemetryLogger.logTrip("CLOSED_ZOMBIE", unclosed.id, "Closed zombie trip abandoned ${(now - lastActivityUtc)/60000}m ago")
                } else {
                    _activeTrip.value = unclosed
                    synchronized(rowLock) {
                        pendingRows.clear()
                        persistedPointCount = unclosed.points.size
                    }
                    lastSmoothedForDistance = null
                    trackSampler.reset()
                    lastCommittedElapsedMs = -1L
                    // Resume watchdog from last recorded activity so a long gap before death can still auto-stop.
                    lastMovingTimestamp = lastActivityUtc
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
                            val timeoutMs = (_autoStopMinutes.value.coerceAtLeast(1)) * 60 * 1000L
                            val elapsedPause = System.currentTimeMillis() - pauseStartTime
                            val remainingTimeoutMs = (timeoutMs - elapsedPause).coerceAtLeast(10_000L)
                            scheduleAutoStopAlarm(unclosed.id, remainingTimeoutMs)
                            TelemetryLogger.logTrip("PAUSE_RESTORED", unclosed.id, "Restored paused state after process death: pause started ${(System.currentTimeMillis() - pauseStartTime) / 60000}m ago")
                        }
                    }
                    startWatchdog()
                    // Option A: recording is active again; re-arm FGS when the platform allows (foreground / sticky).
                    startLiveTrackingService()
                    try {
                        AppStateManager.getInstance(context).recalculateState()
                    } catch (_: Exception) {}
                    TelemetryLogger.logTrip(
                        "RESTORED_ACTIVE",
                        unclosed.id,
                        "Restored active trip from SQLite with ${unclosed.points.size} points (isPaused=${_isPaused.value}, lastActivityAgeMin=${(now - lastActivityUtc)/60000})"
                    )
                }
            }
        }
    }

    /**
     * Best-effort durability flush (process kill, screen off, shutdown). Uses the existing IO writer;
     * never acquires a wake lock.
     */
    fun flushPendingForSurvival() {
        val trip = _activeTrip.value ?: return
        persistTripAsync(trip, force = true, alwaysMeta = true)
    }

    private fun stopWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    private fun startWatchdog() {
        if (watchdogJob?.isActive == true) return
        watchdogJob = scope.launch {
            while (true) {
                val delayMs = if (_isPaused.value) 60_000L else 15_000L
                kotlinx.coroutines.delay(delayMs)
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
                // In AUTO mode, stop when stationary duration exceeds configured auto-stop timeout (both active and paused)
                if (mode == TripMode.AUTO) {
                    val now = System.currentTimeMillis()
                    val stationaryStartTime = if (_isPaused.value) {
                        activePause?.startTime ?: lastMovingTimestamp ?: now
                    } else {
                        lastMovingTimestamp
                    }
                    if (stationaryStartTime != null) {
                        val stationaryDurationMs = now - stationaryStartTime
                        val timeoutMs = (_autoStopMinutes.value.coerceAtLeast(1)) * 60 * 1000L
                        if (stationaryDurationMs >= timeoutMs) {
                            if (isManualPauseSticky && !_isPaused.value) {
                                TelemetryLogger.logTrip("STICKY_PAUSE_RETURN", active.id, "Stationary for ${stationaryDurationMs / 60000}m with sticky pause active: returning to Pause.")
                                pauseTrip(isManual = false)
                            } else {
                                TelemetryLogger.logTrip("AUTO_STOP_WATCHDOG", active.id, "Stationary for ${stationaryDurationMs / 60000}m >= ${timeoutMs / 60000}m timeout (isPaused=${_isPaused.value})")
                                stopTrip(isManualStop = false)
                                break
                            }
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
        AppStateManager.getInstance(context).onProfileChanged(profile)
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
        synchronized(rowLock) {
            pendingRows.clear()
            persistedPointCount = 0
        }
        lastSmoothedForDistance = null
        trackSampler.reset()
        lastCommittedElapsedMs = -1L
        lastCommittedUtcMs = -1L
        lastRejectedSmoothedPoint = null

        val profile = _activityProfile.value

        val trip = TripRecord(
            startTime = now,
            isAutoDetected = isAuto,
            activityProfile = profile
        )
        val id = dbHelper.insertTrip(trip)
        val started = trip.copy(id = id)
        _activeTrip.value = started
        janush.tech.whereami.tracking.LifecycleDiagnosticsRecorder.log(
            "TRIP_START",
            mapOf("tripId" to id, "isAuto" to isAuto, "profile" to profile.name)
        )
        resetAutoStartGates()
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

    internal fun stopTrip(isManualStop: Boolean = false) {
        cancelAutoStopAlarm()
        if (_activeTrip.value == null) return
        // Drain the fixed-lag smoother so the last ~3 s of the trip are part of the recording.
        try {
            onSmoothedPoints(LocationManager.getInstance(context).flushTracking(), drainSampler = true)
        } catch (_: Exception) {}
        val current = _activeTrip.value ?: return
        val now = System.currentTimeMillis()

        val shouldTrimTail = !isManualStop || _isPaused.value
        val trimPause = activePause
        val trimEndTime = if (shouldTrimTail && trimPause != null) {
            trimPause.startTime
        } else if (shouldTrimTail) {
            lastMovingTimestamp ?: now
        } else {
            now
        }
        val trimPointIndex = if (shouldTrimTail && trimPause != null) {
            trimPause.pointIndex.coerceIn(0, current.points.size)
        } else {
            current.points.size
        }
        val trimDistanceMeters = if (shouldTrimTail && trimPause != null) {
            trimPause.distanceMeters.coerceAtMost(current.distanceMeters)
        } else {
            current.distanceMeters
        }
        val trimmedPoints = current.points.take(trimPointIndex)

        if (shouldTrimTail && trimPointIndex < current.points.size) {
            synchronized(rowLock) {
                pendingRows.clear()
            }
            try {
                val dbRows = dbHelper.getTrackPointRows(current.id)
                if (dbRows.size > trimPointIndex) {
                    dbHelper.replaceTrackPoints(current.id, dbRows.take(trimPointIndex))
                    persistedPointCount = trimPointIndex
                }
            } catch (e: Exception) {
                TelemetryLogger.log("ERROR", "Failed to trim track_points table: ${e.message}")
            }
            TelemetryLogger.logTrip(
                "TAIL_TRIMMED",
                current.id,
                "Trimmed stationary tail: pts ${current.points.size} -> $trimPointIndex, dist ${current.distanceMeters.toInt()}m -> ${trimDistanceMeters.toInt()}m, end $now -> $trimEndTime"
            )
        }

        val updatedPauses = current.pauses.toMutableList()
        // If stopping without tail trim while resting at a place, finalize the active pause
        if (!shouldTrimTail && activePause != null && (now - activePause!!.startTime) >= 45_000L) {
            val finalized = activePause!!.copy(
                endTime = now,
                durationMs = now - activePause!!.startTime
            )
            tryCoalescePause(updatedPauses, finalized, now, current.id)
            TelemetryLogger.logTrip("PAUSE_FINALIZED", current.id, "Finalized manual stop pause: ${finalized.durationMs / 1000}s")
        }
        activePause = null

        // Auto-stop waiting or stopping at home is NOT a rest pause: sanitize only for auto-stop or tail-trimmed stops
        val sanitizedPauses = if (!shouldTrimTail) {
            updatedPauses
        } else {
            sanitizeTrailingPauses(updatedPauses, trimmedPoints.size, trimEndTime, trimmedPoints.lastOrNull())
        }

        val durationMs = (trimEndTime - current.startTime).coerceAtLeast(0L)
        val profile = current.activityProfile
        val isTrivialTrip = trimDistanceMeters < profile.minValidDistanceMeters && durationMs < profile.minValidDurationMs
        val scribblePts = trimmedPoints.map { TripScribble.LatLng(it.latitude, it.longitude) }
        val isScribbleTrip = TripScribble.isIndoorScribble(
            isAuto = current.isAutoDetected,
            durationMs = durationMs,
            pathDistanceM = trimDistanceMeters,
            points = scribblePts,
            profile = current.activityProfile
        )

        if (isTrivialTrip || isScribbleTrip) {
            // Discard empty accidental starts or indoor multipath nests (trip 239: 0.72 km / 7 m net)
            synchronized(rowLock) { pendingRows.clear() }
            scope.launch(dbWriter) { dbHelper.deleteTrip(current.id) }
            val reason = if (isScribbleTrip) {
                "DISCARDED_SCRIBBLE"
            } else {
                "DISCARDED_TRIVIAL"
            }
            TelemetryLogger.logTrip(
                reason,
                current.id,
                "Trip discarded: dist=${trimDistanceMeters.toInt()}m duration=${durationMs / 1000}s auto=${current.isAutoDetected} scribble=$isScribbleTrip trivial=$isTrivialTrip"
            )
        } else {
            val finishedTrip = current.copy(
                endTime = trimEndTime,
                distanceMeters = trimDistanceMeters,
                points = trimmedPoints,
                pauses = sanitizedPauses
            )
            persistTripBlocking(finishedTrip)
            TelemetryLogger.logTrip("STOPPED", current.id, "dist=${trimDistanceMeters.toInt()}m, places=${current.placesVisited.size}, pauses=${sanitizedPauses.size}")
            try {
                CacheManager.getInstance(context).onTripFinished()
            } catch (_: Exception) {}

            // DEM mode: every saved trip gets DEM altitudes for the points recorded without a cached DEM value,
            // with the shared progress (trip details "Correcting…" and the notification). Points already DEM
            // cost nothing; when offline the trip details still offer the correction.
            if (finishedTrip.points.isNotEmpty() && MapProfileConfigHelper.getAltitudeSourceMode(context) == AltitudeSourceMode.DEM) {
                ElevationCorrectionManager.startSingleTrip(context, finishedTrip.id, OverrideMode.FILL_MISSING, notifyCompletion = false)
            }
        }
        _activeTrip.value = null
        janush.tech.whereami.tracking.LifecycleDiagnosticsRecorder.log(
            "TRIP_STOP",
            mapOf(
                "tripId" to current.id,
                "isManualStop" to isManualStop,
                "durationMs" to durationMs,
                "distanceM" to trimDistanceMeters,
                "isTrivial" to isTrivialTrip,
                "isScribble" to isScribbleTrip
            )
        )
        if (!isTrivialTrip && !isScribbleTrip) {
            // Road-snapped alternate geometry (CAR, online, not while recording); retried later when offline.
            janush.tech.whereami.tracking.PostTripMatcher.schedule(context, force = true)
        }
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
        resetAutoStartGates()
        pauseAnchorLocation = null
        pauseAnchorFirstStationaryTime = null
        pauseAnchorPointIndex = null
        pauseAnchorDistanceMeters = null
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
                // Same gate as pause-resume: every sample accurate + sustained speed + real travel.
                // The old "25 m jump in 8 s" shortcut auto-started indoors (trip 239 scribble).
                val targetSpeed = getAutoStartSpeedForProfile(currentProfile)
                val autoStartDurationMs = getAutoStartSecondsForProfile(currentProfile) * 1000L
                val requiredDisplacement = when (currentProfile) {
                    ActivityProfile.CAR -> 25.0
                    ActivityProfile.CYCLING, ActivityProfile.MTB -> 20.0
                    else -> 12.0
                }
                val gate = autoStartGateFor(currentProfile)
                if (gate.offer(
                        now, lat, lng, speedKmh, accuracy,
                        targetSpeed, autoStartDurationMs, requiredDisplacement
                    )
                ) {
                    startTrip(isAuto = true)
                    resetAutoStartGates()
                    movingSinceTimestamp = null
                }
            } else {
                val active = _activeTrip.value ?: return
                if (_isPaused.value) {
                    // Check auto-resume on motion if enabled with sustained locomotion confirmation
                    val targetSpeed = getAutoStartSpeedForProfile(currentProfile)
                    if (_autoResumeOnMotion.value) {
                        val pauseAgeMs = activePause?.let { now - it.startTime } ?: 0L
                        val (requiredDurationMs, requiredDisplacement) = when {
                            currentProfile == ActivityProfile.CAR && pauseAgeMs <= 180_000L -> Pair(6_000L, 12.0)
                            currentProfile == ActivityProfile.CAR && pauseAgeMs <= 600_000L -> Pair(12_000L, 18.0)
                            currentProfile == ActivityProfile.CAR -> Pair(20_000L, 25.0)
                            pauseAgeMs <= 180_000L -> Pair(5_000L, 8.0)
                            pauseAgeMs <= 600_000L -> Pair(10_000L, 12.0)
                            else -> Pair(currentProfile.autoStartDurationMs.coerceAtMost(30_000L), 15.0)
                        }
                        val resumed = if (currentProfile == ActivityProfile.CAR) {
                            vehicleAutoStartGate.offer(
                                now, lat, lng, speedKmh, accuracy,
                                targetSpeed, requiredDurationMs, requiredDisplacement
                            )
                        } else {
                            resumeMotionGate.offer(
                                now, lat, lng, speedKmh, accuracy,
                                targetSpeed, requiredDurationMs, requiredDisplacement
                            )
                        }
                        if (resumed) {
                            resumeTrip(isManual = false)
                            TelemetryLogger.logTrip(
                                "AUTO_RESUME",
                                active.id,
                                "Confirmed accurate motion at >= ${targetSpeed.toInt()} km/h (window=${requiredDurationMs / 1000}s, disp=${requiredDisplacement}m, pauseAge=${pauseAgeMs / 1000}s) while paused: auto-resumed trip"
                            )
                        }
                    }

                    // Auto-stop check while paused: stop when stationary duration exceeds configured auto-stop timeout
                    val stationaryStartTime = activePause?.startTime ?: lastMovingTimestamp ?: now
                    val stationaryDurationMs = now - stationaryStartTime
                    val timeoutMs = (_autoStopMinutes.value.coerceAtLeast(1)) * 60 * 1000L
                    if (stationaryDurationMs >= timeoutMs) {
                        TelemetryLogger.logTrip("AUTO_STOP_PAUSED", active.id, "Stationary for ${stationaryDurationMs / 60000}m >= ${timeoutMs / 60000}m timeout while paused")
                        stopTrip(isManualStop = false)
                        return
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
                                stopTrip(isManualStop = false)
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
                    val dwellThreshold = getPauseDwellThresholdMs(currentProfile)
                    val finalized = activePause!!.copy(
                        endTime = pauseEndTime,
                        durationMs = (pauseEndTime - activePause!!.startTime).coerceAtLeast(dwellThreshold)
                    )

                    tryCoalescePause(updatedPauses, finalized, now, current.id)

                    activePause = null
                    pauseAnchorLocation = null
                    pauseAnchorFirstStationaryTime = null
                    pauseAnchorPointIndex = null
                    pauseAnchorDistanceMeters = null
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
                    pauseAnchorPointIndex = current.points.size
                    pauseAnchorDistanceMeters = current.distanceMeters
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
                pauseAnchorPointIndex = current.points.size
                pauseAnchorDistanceMeters = current.distanceMeters
            }
            if (lastMovingTimestamp == null) {
                lastMovingTimestamp = now
            }

            val stationaryStartTime = pauseAnchorFirstStationaryTime ?: lastMovingTimestamp!!
            val stationaryMs = now - stationaryStartTime
            val dwellThreshold = getPauseDwellThresholdMs(currentProfile)

            // Entry threshold: require sustained stationary dwell >= dwellThreshold
            if (stationaryMs >= dwellThreshold) {
                if (activePause == null) {
                    activePause = TripPause(
                        startTime = stationaryStartTime,
                        latitude = pauseAnchorLocation?.latitude ?: lat,
                        longitude = pauseAnchorLocation?.longitude ?: lng,
                        durationMs = stationaryMs,
                        pointIndex = pauseAnchorPointIndex ?: current.points.size,
                        distanceMeters = pauseAnchorDistanceMeters ?: current.distanceMeters
                    )
                    TelemetryLogger.logTrip("PAUSE_DISCOVERED", current.id, "Discovered pause after ${stationaryMs / 1000}s stationary dwell")
                } else {
                    activePause = activePause!!.copy(durationMs = now - activePause!!.startTime)
                }

                // In TripMode.AUTO, automatically engage full auto-pause state!
                if (_tripMode.value == TripMode.AUTO && !_isPaused.value) {
                    pauseTrip(isManual = false)
                }
            }
        }

        lastLocation = newLoc

        // Speed stats (live Doppler/kalman speed; geometry and distance come from smoothed points)
        if (speedKmh > 1.0f) {
            totalSpeedSamples++
            sumSpeedKmh += speedKmh
        }
        val newMaxSpeed = maxOf(current.maxSpeedKmh, speedKmh)
        val newAvgSpeed = if (totalSpeedSamples > 0) (sumSpeedKmh / totalSpeedSamples).toFloat() else current.avgSpeedKmh

        // Check for newly entered locality (with intentional visit filter: penetrate > 150m OR stay > 45s)
        val newPlaces = current.placesVisited.toMutableList()
        updateVisitedPlaces(
            lat = lat,
            lng = lng,
            currentDistance = current.distanceMeters,
            currentPlace = currentPlace,
            placesList = newPlaces,
            tripId = current.id,
            now = now
        )

        val updated = current.copy(
            maxSpeedKmh = newMaxSpeed,
            avgSpeedKmh = newAvgSpeed,
            placesVisited = newPlaces,
            pauses = updatedPauses
        )
        _activeTrip.value = updated
        checkPermanentContinuation(updated, speedKmh, now)

        // Persist metadata when something structural changed (place entered, pause recorded)
        if (newPlaces.size != current.placesVisited.size || updatedPauses.size != current.pauses.size) {
            persistTripAsync(updated, force = false)
        }
    }

    /**
     * Fixed-lag RTS smoothed track points (3 s behind the live fix). This is the single source of the
     * recorded geometry and of the trip distance: zig-zags from multipath are already removed, so
     * polylines are smooth and distance is not inflated by GPS jitter.
     *
     * Must be called from the same thread as [onLocationUpdate] (the location callback).
     */
    fun onSmoothedPoints(points: List<SmoothedPoint>, drainSampler: Boolean = false) {
        if (points.isEmpty() && !drainSampler) return
        val current = _activeTrip.value ?: return
        if (_isPaused.value) {
            lastSmoothedForDistance = null
            return
        }
        val newPoints = ArrayList<GeoPoint>(current.points.size + points.size + 1)
        newPoints.addAll(current.points)
        var distance = current.distanceMeters
        var committed = 0
        val res = FloatArray(1)

        fun appendPoint(p: SmoothedPoint) {
            val alt = p.altitudeM?.takeIf { !it.isNaN() && it != 0.0 }
            newPoints.add(if (alt != null) GeoPoint(p.lat, p.lng, alt) else GeoPoint(p.lat, p.lng))
            val row = TrackPointRow.from(p, alt, if (alt != null && alt > 0.0) "GPS" else null)
            synchronized(rowLock) { pendingRows.add(row) }
            lastCommittedElapsedMs = p.tElapsedMs
            lastCommittedUtcMs = p.tUtcMs
            committed++
        }

        fun commit(p: SmoothedPoint) {
            val last = newPoints.lastOrNull()
            if (last != null && last.latitude == p.lat && last.longitude == p.lng) return
            // Last-line defence: do not store multipath tips (trip 240 auto-stop 386 m chord;
            // trip 244 cycling 59.6 m / 172 deg reverse after a GNSS gap near home).
            if (last != null) {
                val prevUtc = lastCommittedUtcMs.takeIf { it > 0L } ?: p.tUtcMs
                val curProfile = _activeTrip.value?.activityProfile ?: activityProfile.value
                val (profMinJump, profMaxImplied) = TrackSpikeGuard.gatesForProfile(curProfile.name)
                val twoPoint = TrackSpikeGuard.isSpikeStep(
                    last.latitude, last.longitude, prevUtc,
                    p.lat, p.lng, p.tUtcMs,
                    p.speedMps, p.isStationary,
                    profMinJump, profMaxImplied
                )
                val prev2 = if (newPoints.size >= 2) newPoints[newPoints.size - 2] else null
                val threePoint = prev2 != null && TrackSpikeGuard.isReversalSpike(
                    prev2.latitude, prev2.longitude,
                    last.latitude, last.longitude, prevUtc,
                    p.lat, p.lng, p.tUtcMs
                )
                if (twoPoint || threePoint) {
                    val prevRejected = lastRejectedSmoothedPoint
                    if (prevRejected != null && !p.isStationary && p.tUtcMs > prevRejected.tUtcMs &&
                        !TrackSpikeGuard.isSpikeStep(
                            prevRejected.lat, prevRejected.lng, prevRejected.tUtcMs,
                            p.lat, p.lng, p.tUtcMs, p.speedMps, p.isStationary,
                            profMinJump, profMaxImplied
                        )
                    ) {
                        // Two mutually consistent moving points after a gap/turn: recover instead of deadlocking.
                        lastRejectedSmoothedPoint = null
                        appendPoint(prevRejected)
                        appendPoint(p)
                        return
                    }
                    lastRejectedSmoothedPoint = p
                    TelemetryLogger.logTrip(
                        "SPIKE_REJECTED",
                        current.id,
                        "Skipped ${TrackSpikeGuard.distanceM(last.latitude, last.longitude, p.lat, p.lng).toInt()}m tip at ${p.speedMps} m/s"
                    )
                    return
                }
                lastRejectedSmoothedPoint = null
                // A->B->C lateral V: drop middle B (in-memory or overwrite in DB) then continue to commit C.
                if (prev2 != null &&
                    TrackSpikeGuard.isLateralVSpike(
                        prev2.latitude, prev2.longitude,
                        last.latitude, last.longitude,
                        p.lat, p.lng
                    )
                ) {
                    newPoints.removeAt(newPoints.size - 1)
                    synchronized(rowLock) {
                        if (pendingRows.isNotEmpty()) {
                            pendingRows.removeAt(pendingRows.lastIndex)
                        } else if (persistedPointCount > 0) {
                            persistedPointCount--
                        }
                    }
                    TelemetryLogger.logTrip(
                        "SPIKE_REJECTED",
                        current.id,
                        "Dropped lateral V middle before committing tip"
                    )
                }
            }
            appendPoint(p)
        }

        for (p in points) {
            if (p.tUtcMs < current.startTime - TRACK_LEAD_IN_MS) continue

            // Distance: exact smoothed path length while moving (stationary micro-motion is not distance)
            val prev = lastSmoothedForDistance
            if (prev != null && !p.isStationary) {
                Location.distanceBetween(prev.lat, prev.lng, p.lat, p.lng, res)
                if (res[0] >= MIN_STEP_DISTANCE_M) distance += res[0]
            }
            lastSmoothedForDistance = p

            // Polyline commit: speed-adaptive line simplification (see AdaptiveTrackSampler)
            val sampled = trackSampler.offer(p)
            sampled.forEach(::commit)
            if (sampled.isEmpty() && p.isStationary && newPoints.isNotEmpty()) {
                lastCommittedElapsedMs = maxOf(lastCommittedElapsedMs, p.tElapsedMs)
                lastCommittedUtcMs = maxOf(lastCommittedUtcMs, p.tUtcMs)
            }
        }
        if (drainSampler) trackSampler.drain().forEach(::commit)

        if (committed == 0 && distance == current.distanceMeters) return
        val updated = current.copy(points = newPoints, distanceMeters = distance)
        _activeTrip.value = updated
        if (committed > 0) persistTripAsync(updated, force = false, alwaysMeta = false)
    }

    private fun takePendingBatch(force: Boolean): Pair<Int, List<TrackPointRow>>? {
        synchronized(rowLock) {
            if (pendingRows.isEmpty()) return null
            val now = android.os.SystemClock.elapsedRealtime()
            if (!force && pendingRows.size < ROW_FLUSH_COUNT && now - lastRowFlushMs < ROW_FLUSH_INTERVAL_MS) return null
            val batch = ArrayList(pendingRows)
            pendingRows.clear()
            val start = persistedPointCount
            persistedPointCount += batch.size
            lastRowFlushMs = now
            return start to batch
        }
    }

    /** Runs on [dbWriter]: DEM substitution happens here, never on the location (main) thread. */
    private fun writeBatch(tripId: Long, startSeq: Int, batch: List<TrackPointRow>) {
        val demMode = MapProfileConfigHelper.getAltitudeSourceMode(context) == AltitudeSourceMode.DEM
        val rows = batch.map { r ->
            val dem = if (demMode) dbHelper.getCachedElevation(r.lat, r.lng) else null
            if (dem != null) r.copy(altitudeM = dem, altSource = "DEM") else r
        }
        dbHelper.appendTrackPoints(tripId, startSeq, rows)
    }

    private fun persistTripAsync(trip: TripRecord, force: Boolean, alwaysMeta: Boolean = true): kotlinx.coroutines.Job? {
        val batch = takePendingBatch(force)
        if (batch == null && !alwaysMeta) return null
        return scope.launch(dbWriter) {
            try {
                if (batch != null) writeBatch(trip.id, batch.first, batch.second)
                dbHelper.updateTripMeta(trip)
            } catch (e: Exception) {
                TelemetryLogger.log("ERROR", "Trip persist failed: ${e.message}")
            }
        }
    }

    /** Final write at trip stop. Ordered behind all queued appends; callers rely on it being durable. */
    private fun persistTripBlocking(trip: TripRecord) {
        val batch = takePendingBatch(force = true)
        kotlinx.coroutines.runBlocking(dbWriter) {
            try {
                if (batch != null) writeBatch(trip.id, batch.first, batch.second)
                dbHelper.updateTripMeta(trip)
            } catch (e: Exception) {
                TelemetryLogger.log("ERROR", "Final trip persist failed: ${e.message}")
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
            persistTripAsync(updated, force = false)
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

