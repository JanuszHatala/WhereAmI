package janush.tech.whereami

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class AppLifecycleMode(val displayName: String) {
    IDLE("Idle / Suspended"),
    FOREGROUND_VIEW("Map View"),
    LIVE_ONLY("Live Sharing"),
    TRIP_RECORDING("Recording Trip"),
    ANDROID_AUTO("Android Auto")
}

class AppStateManager private constructor(private val context: Context) {

    private val _isStandbySleeping = MutableStateFlow(false)
    val isStandbySleeping: StateFlow<Boolean> = _isStandbySleeping.asStateFlow()

    private var standbyInactivityJob: Job? = null
    @Volatile
    private var lastSleepStateTransitionMs = 0L

    companion object {
        /** Screen-off trip recording: Fused batches fixes so the CPU wakes once per batch. */
        private const val SCREEN_OFF_BATCH_DELAY_MS = 20_000L
        private const val SCREEN_OFF_BATCH_DELAY_SAVER_MS = 30_000L

        @Volatile
        private var INSTANCE: AppStateManager? = null

        fun getInstance(context: Context): AppStateManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AppStateManager(context.applicationContext).also { INSTANCE = it }
            }
        }

        private const val PREFS_NAME = "where_am_i_power_prefs"
        private const val KEY_POWER_POLICY = "power_policy"

        const val MOTION_BURST_MIN_DURATION_MS = 15_000L
        const val MOTION_BURST_COOLDOWN_BASE_MS = 60_000L
        const val STANDBY_HIBERNATION_TIMEOUT_MS = 90 * 60 * 1000L // 90 minutes stationary inactivity
        const val SLEEP_STATE_TRANSITION_DEBOUNCE_MS = 3_000L

        fun shouldRecalculateOnChargingChange(previousCharging: Boolean, newCharging: Boolean): Boolean {
            return previousCharging != newCharging
        }

        fun shouldKeepSleepHeartbeatAlive(
            mode: AppLifecycleMode,
            isStandbySleeping: Boolean,
            hasActiveLiveSession: Boolean
        ): Boolean {
            return mode == AppLifecycleMode.IDLE && isStandbySleeping && hasActiveLiveSession
        }

        fun computeBurstCooldownMs(falseBursts: Int): Long {
            return when {
                falseBursts <= 0 -> 0L
                falseBursts == 1 -> 60_000L
                falseBursts == 2 -> 120_000L
                else -> 180_000L
            }
        }

        /**
         * Modes that may keep evaluating vehicle/foot auto-start after a significant-motion wake.
         * LIVE_ONLY must be included: with Live Sharing active the lifecycle is never IDLE, and
         * aborting the confirmation burst there left only the sparse 15 s moving cadence (field
         * trips 247/248: ~250 m lag with pref already at 15 s).
         */
        fun isMotionBurstEvaluationMode(mode: AppLifecycleMode): Boolean =
            mode == AppLifecycleMode.IDLE || mode == AppLifecycleMode.LIVE_ONLY

        fun shouldEarlyAbortBurst(
            elapsedMs: Long,
            minDurationMs: Long = MOTION_BURST_MIN_DURATION_MS,
            currentSpeedMs: Float,
            targetCandidateSpeedMs: Float,
            displacementMeters: Double,
            isPhysicallyStationary: Boolean,
            isCarProfile: Boolean = false
        ): Boolean {
            if (elapsedMs < minDurationMs) return false
            if (currentSpeedMs >= targetCandidateSpeedMs) return false
            val isSpeedStationary = currentSpeedMs < 1.0f // < 3.6 km/h
            val isNoDisplacement = displacementMeters < 12.0
            if (isPhysicallyStationary || (isSpeedStationary && isNoDisplacement)) {
                return true
            }
            if (isCarProfile && elapsedMs >= 25_000L && currentSpeedMs < 2.0f && displacementMeters < 25.0) {
                return true
            }
            return false
        }
    }

    private val scope = CoroutineScope(Dispatchers.Main)
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _isCharging = MutableStateFlow(false)
    val isCharging: StateFlow<Boolean> = _isCharging.asStateFlow()

    private val _powerPolicy = MutableStateFlow(
        try {
            BatteryPowerPolicy.valueOf(
                prefs.getString(KEY_POWER_POLICY, BatteryPowerPolicy.SMART_AUTO.name)
                    ?: BatteryPowerPolicy.SMART_AUTO.name
            )
        } catch (_: Exception) {
            BatteryPowerPolicy.SMART_AUTO
        }
    )
    val powerPolicy: StateFlow<BatteryPowerPolicy> = _powerPolicy.asStateFlow()

    private val _currentMode = MutableStateFlow(AppLifecycleMode.FOREGROUND_VIEW)
    val currentMode: StateFlow<AppLifecycleMode> = _currentMode.asStateFlow()

    private val _isAppInForegroundFlow = MutableStateFlow(true)
    val isAppInForegroundFlow: StateFlow<Boolean> = _isAppInForegroundFlow.asStateFlow()
    private var isAppInForeground = true
    private var isAutoMediaActive = false

    private var motionBurstJob: Job? = null
    private var motionBurstWakeLock: android.os.PowerManager.WakeLock? = null
    private var motionRearmJob: Job? = null
    internal var consecutiveFalseBursts: Int = 0

    init {
        INSTANCE = this
        registerBatteryReceiver()
        monitorSubsystems()
        setupMotionWakeListener()
        updateStandbyInactivityWatcherState()
    }

    private fun registerBatteryReceiver() {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        if (bm != null) {
            _isCharging.value = bm.isCharging
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val prevCharging = _isCharging.value
                val newCharging = when (intent?.action) {
                    Intent.ACTION_POWER_CONNECTED -> true
                    Intent.ACTION_POWER_DISCONNECTED -> false
                    Intent.ACTION_BATTERY_CHANGED -> {
                        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                        if (status == -1) prevCharging
                        else (status == BatteryManager.BATTERY_STATUS_CHARGING ||
                                status == BatteryManager.BATTERY_STATUS_FULL)
                    }
                    else -> prevCharging
                }
                if (shouldRecalculateOnChargingChange(prevCharging, newCharging)) {
                    _isCharging.value = newCharging
                    recalculateState()
                }
            }
        }
        val stickyIntent = context.registerReceiver(receiver, filter)
        if (stickyIntent != null) {
            val status = stickyIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            if (status != -1) {
                _isCharging.value = (status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL)
            }
        }
        recalculateState()
    }

    fun setPowerPolicy(policy: BatteryPowerPolicy) {
        _powerPolicy.value = policy
        prefs.edit().putString(KEY_POWER_POLICY, policy.name).apply()
        TelemetryLogger.log("POWER", "Battery power policy set to: ${policy.name}")
        recalculateState()
    }

    fun enterStandbySleep(isManual: Boolean = false) {
        if (_isStandbySleeping.value) return
        val now = SystemClock.elapsedRealtime()
        if (!isManual && (now - lastSleepStateTransitionMs < SLEEP_STATE_TRANSITION_DEBOUNCE_MS)) {
            TelemetryLogger.log("POWER", "enterStandbySleep dropped: transition debounce active (${now - lastSleepStateTransitionMs}ms < ${SLEEP_STATE_TRANSITION_DEBOUNCE_MS}ms)")
            return
        }
        val tripManager = TripManager.getInstance(context)
        val hasTrip = tripManager.activeTrip.value != null

        // Inactive sleep only forbidden during active trip recording
        if (hasTrip) {
            TelemetryLogger.log("POWER", "Cannot enter standby sleep: active trip recording in progress")
            return
        }

        lastSleepStateTransitionMs = now
        _isStandbySleeping.value = true
        if (isManual) {
            AutoSleepManager.getInstance(context).onManualSleepEntered()
        } else {
            AutoSleepManager.getInstance(context).onSleepEntered()
        }
        stopStandbyInactivityWatcher()
        motionRearmJob?.cancel()
        MotionWakeManager.getInstance(context).disarm()
        TelemetryLogger.log("POWER", "Entering standby sleep mode (motion sensor disarmed, stopping GPS/standby FGS, manual=$isManual).")

        recalculateState()
    }

    fun wakeStandbySleep(isExplicitUserAction: Boolean = false) {
        if (!_isStandbySleeping.value) return
        val now = SystemClock.elapsedRealtime()
        if (!isExplicitUserAction && (now - lastSleepStateTransitionMs < SLEEP_STATE_TRANSITION_DEBOUNCE_MS)) {
            TelemetryLogger.log("POWER", "wakeStandbySleep dropped: transition debounce active (${now - lastSleepStateTransitionMs}ms < ${SLEEP_STATE_TRANSITION_DEBOUNCE_MS}ms)")
            return
        }

        lastSleepStateTransitionMs = now
        _isStandbySleeping.value = false
        AutoSleepManager.getInstance(context).onSleepWoken()
        TelemetryLogger.log("POWER", "Waking up from standby sleep mode (explicitUser=$isExplicitUserAction).")

        if (isExplicitUserAction) {
            AutoSleepManager.getInstance(context).onUserExplicitWake()
        }

        consecutiveFalseBursts = 0
        motionRearmJob?.cancel()

        val tripManager = TripManager.getInstance(context)
        if (tripManager.tripMode.value == TripMode.AUTO && tripManager.activeTrip.value == null) {
            MotionWakeManager.getInstance(context).arm()
            TelemetryLogger.log("POWER", "Standby sleep wake: MotionWakeManager re-armed for AUTO auto-start.")
        }

        updateStandbyInactivityWatcherState()
        recalculateState()
    }

    fun updateStandbyInactivityWatcherState() {
        val tripManager = TripManager.getInstance(context)
        val liveSession = LiveSharingManager.getInstance(context).currentSession.value
        val hasLive = liveSession != null && liveSession.isActive
        val hasTrip = tripManager.activeTrip.value != null
        val isAuto = tripManager.tripMode.value == TripMode.AUTO

        val shouldWatch = !isAppInForeground && !hasTrip && !_isStandbySleeping.value && (isAuto || hasLive)
        if (shouldWatch) {
            startStandbyInactivityWatcher()
        } else {
            stopStandbyInactivityWatcher()
        }
    }

    fun onTripEnded() {
        updateStandbyInactivityWatcherState()
        AutoSleepManager.getInstance(context).evaluateAutoSleep()
        recalculateState()
    }

    private fun stopStandbyInactivityWatcher() {
        standbyInactivityJob?.cancel()
        standbyInactivityJob = null
    }

    private fun startStandbyInactivityWatcher() {
        if (standbyInactivityJob?.isActive == true) return
        standbyInactivityJob = scope.launch {
            var stationarySinceMs = 0L
            while (isActive) {
                delay(60_000L) // Check every minute
                AutoSleepManager.getInstance(context).evaluateAutoSleep()
                val tripManager = TripManager.getInstance(context)
                val liveSession = LiveSharingManager.getInstance(context).currentSession.value
                val hasLive = liveSession != null && liveSession.isActive
                val hasTrip = tripManager.activeTrip.value != null
                val isAuto = tripManager.tripMode.value == TripMode.AUTO

                // Evaluate inactivity sleep when screen off (not in foreground), no active trip, not already sleeping
                val shouldWatch = !isAppInForeground && !hasTrip && !_isStandbySleeping.value && (isAuto || hasLive)
                if (shouldWatch) {
                    val isStationary = StationaryDetector.getInstance(context).isPhysicallyStationary.value
                    val currentSpeed = LocationManager.getInstance(context).getLastValidSpeedMs()
                    val isSpeedZero = currentSpeed < 0.35f

                    val now = SystemClock.elapsedRealtime()
                    if (isStationary || isSpeedZero) {
                        if (stationarySinceMs == 0L) {
                            stationarySinceMs = now
                        }
                        val inactiveDuration = now - stationarySinceMs
                        // AGENTS.md: after 90 minutes verified stationary rest, hibernate (any time of day).
                        if (inactiveDuration >= STANDBY_HIBERNATION_TIMEOUT_MS) {
                            TelemetryLogger.log("POWER", "Stationary inactivity reached ${inactiveDuration / 60_000}m. Auto-entering standby sleep.")
                            enterStandbySleep()
                            stationarySinceMs = 0L
                            break
                        }
                    } else {
                        stationarySinceMs = 0L
                    }
                } else {
                    stationarySinceMs = 0L
                    break
                }
            }
        }
    }

    fun setAppForegroundState(inForeground: Boolean) {
        isAppInForeground = inForeground
        _isAppInForegroundFlow.value = inForeground
        if (inForeground) {
            janush.tech.whereami.tracking.PostTripMatcher.schedule(context)
            consecutiveFalseBursts = 0
            motionRearmJob?.cancel()
            // AGENTS.md: standby sleep wakes on app resume (MainActivity.onStart) for 90m inactivity hibernation.
            // Do NOT wake if the user manually entered sleep, or if currently inside the Bedtime/DND sleep window.
            val autoSleepMgr = AutoSleepManager.getInstance(context)
            val isBedtimeOrDnd = autoSleepMgr.isCurrentTimeInBedtimeWindow() || autoSleepMgr.isDndActive()
            if (_isStandbySleeping.value && !autoSleepMgr.isManualSleepActive() && !isBedtimeOrDnd) {
                wakeStandbySleep(isExplicitUserAction = false)
            } else if (!_isStandbySleeping.value) {
                autoSleepMgr.checkAndRecoverAutoPausedLiveShare()
            }
            ensureAutoStandbyServiceRunning()
        } else {
            // Best-effort durability: flush pending track points when the UI goes background.
            TripManager.getInstance(context).flushPendingForSurvival()
            AutoSleepManager.getInstance(context).evaluateAutoSleep()
        }
        updateStandbyInactivityWatcherState()
        recalculateState()
    }

    fun ensureAutoStandbyServiceRunning() {
        val tripManager = TripManager.getInstance(context)
        val hasTrip = tripManager.activeTrip.value != null
        val liveSession = LiveSharingManager.getInstance(context).currentSession.value
        val hasLive = liveSession != null && liveSession.isActive
        val isAuto = tripManager.tripMode.value == TripMode.AUTO

        // Do not auto-restart standby FGS if user/system entered standby sleep (unless active trip recording)
        if (_isStandbySleeping.value && !hasTrip) {
            return
        }

        val shouldServiceRun = (isAuto || hasLive || hasTrip)

        // Only start FGS while in foreground (PROC_STATE_TOP), where Android 14+ guarantees success
        if (shouldServiceRun && isAppInForeground) {
            try {
                val intent = Intent(context, LiveTrackingService::class.java).apply {
                    action = LiveTrackingService.ACTION_ENTER_STANDBY
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                TelemetryLogger.log("POWER", "Started LiveTrackingService in foreground (isAuto=$isAuto, hasLive=$hasLive, hasTrip=$hasTrip)")
            } catch (e: Exception) {
                TelemetryLogger.log("ERROR", "Failed to start LiveTrackingService: ${e.message}")
            }
        }
    }

    fun setAutoMediaActive(active: Boolean) {
        isAutoMediaActive = active
        recalculateState()
    }

    private fun monitorSubsystems() {
        scope.launch {
            TripManager.getInstance(context).activeTrip.collect { trip ->
                if (trip != null) {
                    consecutiveFalseBursts = 0
                    motionRearmJob?.cancel()
                }
                if (isAppInForeground) {
                    ensureAutoStandbyServiceRunning()
                }
                updateStandbyInactivityWatcherState()
                AutoSleepManager.getInstance(context).evaluateAutoSleep()
                recalculateState()
            }
        }
        scope.launch {
            LiveSharingManager.getInstance(context).currentSession.collect { session ->
                if (session != null && session.isActive) {
                    consecutiveFalseBursts = 0
                    motionRearmJob?.cancel()
                }
                if (isAppInForeground) {
                    ensureAutoStandbyServiceRunning()
                }
                updateStandbyInactivityWatcherState()
                AutoSleepManager.getInstance(context).evaluateAutoSleep()
                recalculateState()
            }
        }
        scope.launch {
            TripManager.getInstance(context).tripMode.collect { mode ->
                consecutiveFalseBursts = 0
                motionRearmJob?.cancel()
                if (isAppInForeground) {
                    ensureAutoStandbyServiceRunning()
                }
                updateStandbyInactivityWatcherState()
                AutoSleepManager.getInstance(context).evaluateAutoSleep()
                recalculateState()
            }
        }
    }

    fun scheduleMotionWakeRearm(cooldownMs: Long) {
        motionRearmJob?.cancel()
        val motionManager = MotionWakeManager.getInstance(context)
        val tripManager = TripManager.getInstance(context)

        motionRearmJob = scope.launch {
            try {
                TelemetryLogger.log("POWER", "Motion wake re-arm delayed by ${cooldownMs / 1000}s cooldown (streak=$consecutiveFalseBursts)")
                delay(cooldownMs)
                if (_currentMode.value == AppLifecycleMode.IDLE &&
                    tripManager.tripMode.value == TripMode.AUTO &&
                    tripManager.activeTrip.value == null
                ) {
                    motionManager.arm()
                    TelemetryLogger.log("POWER", "MotionWakeManager re-armed successfully after ${cooldownMs / 1000}s cooldown")
                }
            } catch (_: Exception) {}
        }
    }

    private fun setupMotionWakeListener() {
        val motionWakeManager = MotionWakeManager.getInstance(context)
        scope.launch {
            motionWakeManager.motionEvents.collect {
                handleMotionWake()
            }
        }
    }

    fun handleMotionWake() {
        val tripManager = TripManager.getInstance(context)
        val currentMode = _currentMode.value
        StationaryDetector.getInstance(context).unlatchStationary()

        if (currentMode == AppLifecycleMode.LIVE_ONLY) {
            TelemetryLogger.log("POWER", "Significant motion wake in LIVE_ONLY: Resuming GPS updates.")
            LiveSharingManager.getInstance(context).stopStationaryHeartbeat()
            startLiveOnlyWatcher()
            if (tripManager.tripMode.value != TripMode.AUTO || tripManager.activeTrip.value != null) {
                return
            }
        }

        if (tripManager.tripMode.value != TripMode.AUTO) return
        if (tripManager.activeTrip.value != null) return

        val profile = tripManager.activityProfile.value
        val burstDurationMs = (profile.autoStartDurationMs + 30_000L).coerceAtLeast(60_000L)

        TelemetryLogger.log("POWER", "Significant motion wake: starting ${burstDurationMs / 1000}s GPS burst for ${profile.displayName} auto-start evaluation")

        // Reset GPS filter anchor so overnight static anchor doesn't reject new fixes
        LocationManager.getInstance(context).resetTracking()

        // Acquire scoped wake lock for confirmation burst so CPU doesn't suspend while screen is off
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (motionBurstWakeLock == null) {
                motionBurstWakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "WhereAmI:MotionBurstWakeLock")
            }
            if (motionBurstWakeLock?.isHeld == false) {
                motionBurstWakeLock?.acquire(burstDurationMs + 5000L)
                TelemetryLogger.log("POWER", "MotionBurstWakeLock acquired for ${burstDurationMs / 1000}s")
            }
        } catch (e: Exception) {
            TelemetryLogger.log("ERROR", "Failed to acquire MotionBurstWakeLock: ${e.message}")
        }

        val locManager = LocationManager.getInstance(context)
        // Request GPS updates during confirmation window
        locManager.updateSamplingInterval(2500L, 1000L)

        motionBurstJob?.cancel()
        motionBurstJob = scope.launch {
            var anchorFix: LocationFix? = null
            // Actively collect raw location stream during burst to ensure fused provider remains engaged
            val collectorJob = launch {
                locManager.getLocationRaw().collect { fix ->
                    if (anchorFix == null && fix.lat != 0.0 && fix.lng != 0.0) {
                        anchorFix = fix
                    }
                }
            }
            try {
                val stepMs = 2000L
                var elapsedMs = 0L
                val targetCandidateSpeedMs = (tripManager.getAutoStartSpeedForProfile(profile) * 0.7f) / 3.6f
                val isCarProfile = profile == ActivityProfile.CAR

                while (elapsedMs < burstDurationMs) {
                    delay(stepMs)
                    elapsedMs += stepMs

                    if (tripManager.activeTrip.value != null ||
                        !isMotionBurstEvaluationMode(_currentMode.value)
                    ) {
                        break
                    }

                    val isPhysicalStationary = StationaryDetector.getInstance(context).isPhysicallyStationary.value
                    val currentSpeed = locManager.getLastValidSpeedMs()
                    val lastSnap = locManager.lastLocationSnapshot

                    var displacementMeters = 0.0
                    if (anchorFix != null && lastSnap != null && lastSnap.lat != 0.0 && lastSnap.lng != 0.0) {
                        val results = FloatArray(1)
                        android.location.Location.distanceBetween(
                            anchorFix.lat, anchorFix.lng,
                            lastSnap.lat, lastSnap.lng,
                            results
                        )
                        displacementMeters = results[0].toDouble()
                    }

                    if (shouldEarlyAbortBurst(
                            elapsedMs = elapsedMs,
                            minDurationMs = MOTION_BURST_MIN_DURATION_MS,
                            currentSpeedMs = currentSpeed,
                            targetCandidateSpeedMs = targetCandidateSpeedMs,
                            displacementMeters = displacementMeters,
                            isPhysicallyStationary = isPhysicalStationary,
                            isCarProfile = isCarProfile
                        )
                    ) {
                        TelemetryLogger.log(
                            "POWER",
                            "Motion burst early abort at ${elapsedMs / 1000}s: verified stationary/indoor (speed=${String.format(java.util.Locale.US, "%.1f", currentSpeed * 3.6f)} km/h, disp=${String.format(java.util.Locale.US, "%.1f", displacementMeters)}m, stationary=$isPhysicalStationary). Powering down GPS."
                        )
                        break
                    }
                }
            } finally {
                collectorJob.cancel()
                if (motionBurstWakeLock?.isHeld == true) {
                    try {
                        motionBurstWakeLock?.release()
                        TelemetryLogger.log("POWER", "MotionBurstWakeLock released")
                    } catch (_: Exception) {}
                }

                // Guaranteed cleanup on normal exit OR cancellation when no trip started.
                if (tripManager.activeTrip.value == null) {
                    when (_currentMode.value) {
                        AppLifecycleMode.IDLE -> {
                            locManager.stopLocationUpdates()
                            consecutiveFalseBursts++
                            val cooldownMs = computeBurstCooldownMs(consecutiveFalseBursts)
                            TelemetryLogger.log("POWER", "Motion burst terminated without trip auto-start. Powering down GPS and scheduling re-arm in ${cooldownMs / 1000}s (streak=$consecutiveFalseBursts).")
                            scheduleMotionWakeRearm(cooldownMs)
                        }
                        AppLifecycleMode.LIVE_ONLY -> {
                            // Restore Live Sharing cadence (watcher GPS-offs again when stationary + screen off).
                            TelemetryLogger.log("POWER", "Motion burst ended in LIVE_ONLY without auto-start; restoring live cadence.")
                            startLiveOnlyWatcher()
                        }
                        else -> Unit
                    }
                }
            }
        }
    }

    private var liveOnlyWatcherJob: Job? = null
    private var foregroundWatcherJob: Job? = null

    private fun startForegroundCadenceWatcher() {
        foregroundWatcherJob?.cancel()
        val locManager = LocationManager.getInstance(context)
        val tripManager = TripManager.getInstance(context)
        val isAuto = tripManager.tripMode.value == TripMode.AUTO
        val policy = _powerPolicy.value
        val charging = _isCharging.value

        // Configure moving and stationary intervals: 1 Hz (1000ms) when watching screen in foreground
        val (movingInterval, movingMinInterval) = when {
            policy == BatteryPowerPolicy.BATTERY_SAVER && !charging -> 3000L to 1500L
            else -> 1000L to 1000L
        }
        val (stationaryInterval, stationaryMinInterval) = when {
            isAuto -> 3000L to 1500L
            else -> 10000L to 5000L
        }

        locManager.updateSamplingInterval(movingInterval, movingMinInterval)

        foregroundWatcherJob = scope.launch {
            var stationarySinceMs = 0L
            var isStationaryCadenceApplied = false
            while (isActive) {
                delay(3000L)
                if (_currentMode.value != AppLifecycleMode.FOREGROUND_VIEW) break
                val tripActive = TripManager.getInstance(context).activeTrip.value != null
                if (tripActive) break

                val isPhysicalStationary = StationaryDetector.getInstance(context).isPhysicallyStationary.value
                val currentSpeed = LocationManager.getInstance(context).getLastValidSpeedMs()
                val isSpeedStationary = currentSpeed < 0.35f

                val now = SystemClock.elapsedRealtime()
                if (isPhysicalStationary || isSpeedStationary) {
                    if (stationarySinceMs == 0L) {
                        stationarySinceMs = now
                    }
                    val stationaryDuration = now - stationarySinceMs
                    // After 20 seconds of verified stationary dwell, relax GPS sampling
                    if (stationaryDuration >= 20_000L && !isStationaryCadenceApplied) {
                        TelemetryLogger.log("POWER", "FOREGROUND_VIEW: Stationary for ${stationaryDuration / 1000}s. Relaxing cadence (isAuto=$isAuto, interval=${stationaryInterval}ms).")
                        locManager.updateSamplingInterval(stationaryInterval, stationaryMinInterval)
                        isStationaryCadenceApplied = true
                    }
                } else {
                    stationarySinceMs = 0L
                    if (isStationaryCadenceApplied) {
                        TelemetryLogger.log("POWER", "FOREGROUND_VIEW: Motion detected. Restoring moving cadence (isAuto=$isAuto, interval=${movingInterval}ms).")
                        locManager.updateSamplingInterval(movingInterval, movingMinInterval)
                        isStationaryCadenceApplied = false
                    }
                }
            }
        }
    }

    private fun startLiveOnlyWatcher() {
        liveOnlyWatcherJob?.cancel()
        val locManager = LocationManager.getInstance(context)
        val motionManager = MotionWakeManager.getInstance(context)
        val charging = _isCharging.value

        // When moving, sample at conservative interval
        val interval = if (charging) 6_000L else 15_000L
        val minInterval = if (charging) 3_000L else 8_000L
        locManager.updateSamplingInterval(interval, minInterval)

        liveOnlyWatcherJob = scope.launch {
            var stationarySinceMs = 0L
            var isStationaryCadenceApplied = false
            while (isActive) {
                delay(5_000L)
                if (_currentMode.value != AppLifecycleMode.LIVE_ONLY) break
                val tripActive = TripManager.getInstance(context).activeTrip.value != null
                if (tripActive) break

                val isPhysicalStationary = StationaryDetector.getInstance(context).isPhysicallyStationary.value
                val currentSpeed = LocationManager.getInstance(context).getLastValidSpeedMs()
                val isSpeedStationary = currentSpeed < 0.35f // < ~1.2 km/h

                val now = SystemClock.elapsedRealtime()
                // While a motion-wake confirmation burst is running, do not re-apply stationary
                // GPS-off: TTFF after cold start often looks "stationary" (speed≈0) for 10–25 s and
                // would otherwise kill the burst mid-drive (trips 247/248 with Live Sharing).
                if (motionBurstJob?.isActive == true) {
                    stationarySinceMs = 0L
                    continue
                }
                if (isPhysicalStationary || isSpeedStationary) {
                    if (stationarySinceMs == 0L) {
                        stationarySinceMs = now
                    }
                    val stationaryDuration = now - stationarySinceMs
                    // After 30 seconds of verified stationary dwell, relax GPS sampling to stationary cadence
                    if (stationaryDuration >= 30_000L && !isStationaryCadenceApplied) {
                        TelemetryLogger.log("POWER", "LIVE_ONLY: Verified stationary for ${stationaryDuration / 1000}s. Arming MotionWakeManager.")
                        motionManager.arm()
                        LiveSharingManager.getInstance(context).startStationaryHeartbeat()
                        isStationaryCadenceApplied = true

                        // Zero-idle drain: continuous GNSS (even at 30–60 s) holds the radio/GPS lock and
                        // blocks Deep Doze (field 2026-10-22: ~10 min screen-off GNSS via FGS while Live
                        // Sharing stayed up after an early alarm wake). Power down GPS whenever the
                        // screen is off; MotionWakeManager restores cadence on locomotion. Presence
                        // continues via HTTP heartbeat without a location subscription.
                        if (!isAppInForeground) {
                            TelemetryLogger.log(
                                "POWER",
                                "LIVE_ONLY: Screen off: Shutting down GPS updates to allow sustained Doze (charging=${_isCharging.value})."
                            )
                            locManager.stopLocationUpdates(preserveStationaryState = true)
                        } else if (_isCharging.value) {
                            locManager.updateSamplingInterval(30_000L, 15_000L)
                        } else {
                            locManager.updateSamplingInterval(60_000L, 30_000L)
                        }

                        // Immediately evaluate auto-sleep (bedtime schedule or DND)
                        AutoSleepManager.getInstance(context).evaluateAutoSleep()
                    }
                } else {
                    if (locManager.isGpsStopped && isStationaryCadenceApplied && !isAppInForeground) {
                        // GPS powered down with screen off; keep resting until MotionWakeManager interrupt
                        delay(10_000L)
                        continue
                    }
                    stationarySinceMs = 0L
                    if (isStationaryCadenceApplied) {
                        // Locomotion resumed! Restore conservative moving interval
                        TelemetryLogger.log("POWER", "LIVE_ONLY: Locomotion resumed. Restoring moving GPS cadence (${interval / 1000}s).")
                        val movingInterval = if (_isCharging.value) 6_000L else 15_000L
                        val movingMinInterval = if (_isCharging.value) 3_000L else 8_000L
                        locManager.updateSamplingInterval(movingInterval, movingMinInterval)
                        LiveSharingManager.getInstance(context).stopStationaryHeartbeat()
                        isStationaryCadenceApplied = false
                    }
                }
            }
        }
    }

    fun recalculateState() {
        val tripActive = TripManager.getInstance(context).activeTrip.value != null
        val liveSession = LiveSharingManager.getInstance(context).currentSession.value
        val liveActive = liveSession != null && liveSession.isActive && !liveSession.isPaused

        val oldMode = _currentMode.value
        val newMode = when {
            tripActive -> AppLifecycleMode.TRIP_RECORDING
            isAutoMediaActive -> AppLifecycleMode.ANDROID_AUTO
            _isStandbySleeping.value -> AppLifecycleMode.IDLE
            isAppInForeground -> AppLifecycleMode.FOREGROUND_VIEW
            liveActive -> AppLifecycleMode.LIVE_ONLY
            else -> AppLifecycleMode.IDLE
        }

        _currentMode.value = newMode
        applyModeToLocationEngine(newMode, oldMode)
    }

    private fun applyModeToLocationEngine(mode: AppLifecycleMode, oldMode: AppLifecycleMode = mode) {
        val locManager = LocationManager.getInstance(context)
        val motionManager = MotionWakeManager.getInstance(context)
        val policy = _powerPolicy.value
        val charging = _isCharging.value
        val liveSession = LiveSharingManager.getInstance(context).currentSession.value
        val hasLive = liveSession != null && liveSession.isActive

        if (mode != AppLifecycleMode.LIVE_ONLY) {
            liveOnlyWatcherJob?.cancel()
            if (!shouldKeepSleepHeartbeatAlive(mode, _isStandbySleeping.value, hasLive)) {
                LiveSharingManager.getInstance(context).stopStationaryHeartbeat()
            } else {
                LiveSharingManager.getInstance(context).startStationaryHeartbeat()
            }
        }
        if (mode != AppLifecycleMode.FOREGROUND_VIEW) {
            foregroundWatcherJob?.cancel()
        }

        when (mode) {
            AppLifecycleMode.IDLE -> {
                motionBurstJob?.cancel()
                if (motionBurstWakeLock?.isHeld == true) {
                    try {
                        motionBurstWakeLock?.release()
                        TelemetryLogger.log("POWER", "MotionBurstWakeLock released on IDLE entry")
                    } catch (_: Exception) {}
                }
                // Completely stop GPS when entering IDLE mode
                locManager.stopLocationUpdates(preserveStationaryState = true)

                val tripManager = TripManager.getInstance(context)
                val hasTrip = tripManager.activeTrip.value != null
                val isAuto = tripManager.tripMode.value == TripMode.AUTO

                // In MANUAL mode with no trip and no live share: stop the service completely.
                // (When sleeping, StateFlow observation in LiveTrackingService handles the standby sleep transition).
                if (!_isStandbySleeping.value && !hasTrip && !hasLive && !isAuto && oldMode != AppLifecycleMode.IDLE) {
                    try {
                        val stopIntent = Intent(context, LiveTrackingService::class.java).apply {
                            action = LiveTrackingService.ACTION_STOP
                        }
                        context.startService(stopIntent)
                    } catch (_: Exception) {}
                }

                if (isAuto && !_isStandbySleeping.value) {
                    if (motionRearmJob?.isActive != true) {
                        motionManager.arm()
                        TelemetryLogger.log("POWER", "App state IDLE: GPS off, LiveTrackingService standby active, motion sensor armed for AUTO auto-start.")
                    } else {
                        TelemetryLogger.log("POWER", "App state IDLE: GPS off, cooldown active before motion sensor arm.")
                    }
                } else if (_isStandbySleeping.value) {
                    motionRearmJob?.cancel()
                    motionManager.disarm()
                    TelemetryLogger.log("POWER", "App state IDLE: Standby sleep active, motion sensor disarmed.")
                } else {
                    consecutiveFalseBursts = 0
                    motionRearmJob?.cancel()
                    motionManager.disarm()
                    TelemetryLogger.log("POWER", "App state IDLE: GPS and service powered down completely (MANUAL mode).")
                }
            }
            AppLifecycleMode.FOREGROUND_VIEW -> {
                motionBurstJob?.cancel()
                consecutiveFalseBursts = 0
                motionRearmJob?.cancel()
                motionManager.disarm()
                ensureAutoStandbyServiceRunning()
                startForegroundCadenceWatcher()
            }
            AppLifecycleMode.TRIP_RECORDING -> {
                motionBurstJob?.cancel()
                consecutiveFalseBursts = 0
                motionRearmJob?.cancel()
                motionManager.disarm()
                val profile = TripManager.getInstance(context).activeTrip.value?.activityProfile
                    ?: TripManager.getInstance(context).activityProfile.value
                
                // Foreground: 1 Hz for silky-smooth tracking. Screen off: fast movers (car/bike) still sample at
                // 1 Hz so turns are not cut into chords, but fixes are BATCHED (one CPU wake per ~20 s) and every
                // fix is processed by the tracking engine. Slow profiles keep their profile cadence.
                val isFastMover = profile == ActivityProfile.CAR || profile == ActivityProfile.CYCLING || profile == ActivityProfile.MTB
                var interval = if (isAppInForeground || isFastMover) 1000L else profile.gpsIntervalMs
                var minInterval = if (isAppInForeground || isFastMover) 1000L else profile.minGpsIntervalMs
                var maxDelay = if (isAppInForeground) 0L else SCREEN_OFF_BATCH_DELAY_MS

                if (policy == BatteryPowerPolicy.BATTERY_SAVER && !isAppInForeground) {
                    interval = (interval * 2).coerceAtLeast(2000L)
                    minInterval = (minInterval * 2).coerceAtLeast(1500L)
                    maxDelay = SCREEN_OFF_BATCH_DELAY_SAVER_MS
                }
                locManager.updateSamplingInterval(interval, minInterval, maxDelay)
            }
            AppLifecycleMode.LIVE_ONLY -> {
                motionBurstJob?.cancel()
                consecutiveFalseBursts = 0
                motionRearmJob?.cancel()
                startLiveOnlyWatcher()
            }
            AppLifecycleMode.ANDROID_AUTO -> {
                motionBurstJob?.cancel()
                consecutiveFalseBursts = 0
                motionRearmJob?.cancel()
                motionManager.disarm()
                locManager.updateSamplingInterval(3000L, 1000L)
            }
        }
    }
}
