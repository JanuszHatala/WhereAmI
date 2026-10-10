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

    companion object {
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

        fun computeBurstCooldownMs(falseBursts: Int): Long {
            return when {
                falseBursts <= 0 -> 0L
                falseBursts == 1 -> 60_000L
                falseBursts == 2 -> 120_000L
                else -> 180_000L
            }
        }

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

    private var isAppInForeground = true
    private var isAutoMediaActive = false

    private var motionBurstJob: Job? = null
    private var motionBurstWakeLock: android.os.PowerManager.WakeLock? = null
    private var motionRearmJob: Job? = null
    internal var consecutiveFalseBursts: Int = 0

    init {
        registerBatteryReceiver()
        monitorSubsystems()
        setupMotionWakeListener()
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
                when (intent?.action) {
                    Intent.ACTION_POWER_CONNECTED -> _isCharging.value = true
                    Intent.ACTION_POWER_DISCONNECTED -> _isCharging.value = false
                    Intent.ACTION_BATTERY_CHANGED -> {
                        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                        _isCharging.value = (status == BatteryManager.BATTERY_STATUS_CHARGING ||
                                status == BatteryManager.BATTERY_STATUS_FULL)
                    }
                }
                recalculateState()
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

    fun setAppForegroundState(inForeground: Boolean) {
        isAppInForeground = inForeground
        if (inForeground) {
            consecutiveFalseBursts = 0
            motionRearmJob?.cancel()
            ensureAutoStandbyServiceRunning()
        }
        recalculateState()
    }

    fun ensureAutoStandbyServiceRunning() {
        val tripManager = TripManager.getInstance(context)
        val hasTrip = tripManager.activeTrip.value != null
        val liveSession = LiveSharingManager.getInstance(context).currentSession.value
        val hasLive = liveSession != null && liveSession.isActive
        val isAuto = tripManager.tripMode.value == TripMode.AUTO

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
        GpsFilterEngine.getInstance().reset()

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

                    if (tripManager.activeTrip.value != null || _currentMode.value != AppLifecycleMode.IDLE) {
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

                // Guaranteed cleanup on normal exit OR cancellation: power down GPS and apply progressive cooldown
                if (tripManager.activeTrip.value == null && _currentMode.value == AppLifecycleMode.IDLE) {
                    locManager.stopLocationUpdates()
                    consecutiveFalseBursts++
                    val cooldownMs = computeBurstCooldownMs(consecutiveFalseBursts)
                    TelemetryLogger.log("POWER", "Motion burst terminated without trip auto-start. Powering down GPS and scheduling re-arm in ${cooldownMs / 1000}s (streak=$consecutiveFalseBursts).")
                    scheduleMotionWakeRearm(cooldownMs)
                }
            }
        }
    }

    private var liveOnlyWatcherJob: Job? = null

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
                if (isPhysicalStationary || isSpeedStationary) {
                    if (stationarySinceMs == 0L) {
                        stationarySinceMs = now
                    }
                    val stationaryDuration = now - stationarySinceMs
                    // After 30 seconds of verified stationary dwell, relax GPS sampling to stationary cadence
                    if (stationaryDuration >= 30_000L && !isStationaryCadenceApplied) {
                        TelemetryLogger.log("POWER", "LIVE_ONLY: Verified stationary for ${stationaryDuration / 1000}s. Relaxing GPS cadence and arming MotionWakeManager.")
                        val stationaryInterval = if (_isCharging.value) 30_000L else 60_000L
                        val stationaryMinInterval = if (_isCharging.value) 15_000L else 30_000L
                        locManager.updateSamplingInterval(stationaryInterval, stationaryMinInterval)
                        motionManager.arm()
                        LiveSharingManager.getInstance(context).startStationaryHeartbeat()
                        isStationaryCadenceApplied = true
                    }
                } else {
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

        if (mode != AppLifecycleMode.LIVE_ONLY) {
            liveOnlyWatcherJob?.cancel()
            LiveSharingManager.getInstance(context).stopStationaryHeartbeat()
        }

        when (mode) {
            AppLifecycleMode.IDLE -> {
                if (oldMode != AppLifecycleMode.IDLE) {
                    motionBurstJob?.cancel()
                    if (motionBurstWakeLock?.isHeld == true) {
                        try {
                            motionBurstWakeLock?.release()
                            TelemetryLogger.log("POWER", "MotionBurstWakeLock released on IDLE entry")
                        } catch (_: Exception) {}
                    }
                    // Completely stop GPS when transitioning to IDLE from another mode
                    locManager.stopLocationUpdates()
                }

                val tripManager = TripManager.getInstance(context)
                val hasTrip = tripManager.activeTrip.value != null
                val liveSession = LiveSharingManager.getInstance(context).currentSession.value
                val hasLive = liveSession != null && liveSession.isActive
                val isAuto = tripManager.tripMode.value == TripMode.AUTO

                // In MANUAL mode with no trip and no live share: stop the service completely.
                // In AUTO mode: DO NOT STOP the service! LiveTrackingService stays running in
                // zero-CPU standby mode with the persistent standby notification ("Ready to auto-record"),
                // protecting the process from being killed and holding the FGS token for GPS bursts.
                if (!hasTrip && !hasLive && !isAuto) {
                    try {
                        val stopIntent = Intent(context, LiveTrackingService::class.java).apply {
                            action = LiveTrackingService.ACTION_STOP
                        }
                        context.startService(stopIntent)
                    } catch (_: Exception) {}
                }

                if (isAuto) {
                    if (motionRearmJob?.isActive != true) {
                        motionManager.arm()
                        TelemetryLogger.log("POWER", "App state IDLE: GPS off, LiveTrackingService standby active, motion sensor armed for AUTO auto-start.")
                    } else {
                        TelemetryLogger.log("POWER", "App state IDLE: GPS off, cooldown active before motion sensor arm.")
                    }
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
                if (policy == BatteryPowerPolicy.BATTERY_SAVER && !charging) {
                    locManager.updateSamplingInterval(3000L, 1500L)
                } else {
                    locManager.updateSamplingInterval(1500L, 1000L)
                }
            }
            AppLifecycleMode.TRIP_RECORDING -> {
                motionBurstJob?.cancel()
                consecutiveFalseBursts = 0
                motionRearmJob?.cancel()
                motionManager.disarm()
                val profile = TripManager.getInstance(context).activeTrip.value?.activityProfile
                    ?: TripManager.getInstance(context).activityProfile.value
                
                var interval = profile.gpsIntervalMs
                var minInterval = profile.minGpsIntervalMs

                if (policy == BatteryPowerPolicy.HIGH_PERFORMANCE || (policy == BatteryPowerPolicy.SMART_AUTO && charging)) {
                    // Relax restrictions when plugged in or performance requested
                    interval = (interval / 2).coerceAtLeast(1000L)
                    minInterval = (minInterval / 2).coerceAtLeast(1000L)
                } else if (policy == BatteryPowerPolicy.BATTERY_SAVER) {
                    interval = (interval * 1.5).toLong()
                    minInterval = (minInterval * 1.5).toLong()
                }
                locManager.updateSamplingInterval(interval, minInterval)
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
