package janush.tech.whereami

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

    init {
        registerBatteryReceiver()
        monitorSubsystems()
        setupMotionWakeListener()
    }

    private fun registerBatteryReceiver() {
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
        context.registerReceiver(receiver, filter)
    }

    fun setPowerPolicy(policy: BatteryPowerPolicy) {
        _powerPolicy.value = policy
        prefs.edit().putString(KEY_POWER_POLICY, policy.name).apply()
        TelemetryLogger.log("POWER", "Battery power policy set to: ${policy.name}")
        recalculateState()
    }

    fun setAppForegroundState(inForeground: Boolean) {
        isAppInForeground = inForeground
        recalculateState()
    }

    fun setAutoMediaActive(active: Boolean) {
        isAutoMediaActive = active
        recalculateState()
    }

    private fun monitorSubsystems() {
        scope.launch {
            TripManager.getInstance(context).activeTrip.collect {
                recalculateState()
            }
        }
        scope.launch {
            LiveSharingManager.getInstance(context).currentSession.collect {
                recalculateState()
            }
        }
        scope.launch {
            TripManager.getInstance(context).tripMode.collect {
                recalculateState()
            }
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

    private fun handleMotionWake() {
        val tripManager = TripManager.getInstance(context)
        if (tripManager.tripMode.value != TripMode.AUTO) return
        if (tripManager.activeTrip.value != null) return

        val profile = tripManager.activityProfile.value
        val burstDurationMs = (profile.autoStartDurationMs + 20_000L).coerceAtLeast(35_000L)

        TelemetryLogger.log("POWER", "Significant motion wake: starting ${burstDurationMs / 1000}s GPS burst for ${profile.displayName} auto-start evaluation")

        val locManager = LocationManager.getInstance(context)
        // Request GPS updates during confirmation window
        locManager.updateSamplingInterval(2500L, 1000L)

        motionBurstJob?.cancel()
        motionBurstJob = scope.launch {
            delay(burstDurationMs)
            // If burst expired without trip starting and app is still in IDLE, power down GPS and re-arm sensor
            if (tripManager.activeTrip.value == null && _currentMode.value == AppLifecycleMode.IDLE) {
                TelemetryLogger.log("POWER", "Motion burst expired without trip auto-start. Powering down GPS and re-arming motion sensor.")
                locManager.stopLocationUpdates()
                MotionWakeManager.getInstance(context).arm()
            }
        }
    }

    fun recalculateState() {
        val tripActive = TripManager.getInstance(context).activeTrip.value != null
        val liveSession = LiveSharingManager.getInstance(context).currentSession.value
        val liveActive = liveSession != null && liveSession.isActive && !liveSession.isPaused

        val newMode = when {
            tripActive -> AppLifecycleMode.TRIP_RECORDING
            liveActive -> AppLifecycleMode.LIVE_ONLY
            isAutoMediaActive -> AppLifecycleMode.ANDROID_AUTO
            isAppInForeground -> AppLifecycleMode.FOREGROUND_VIEW
            else -> AppLifecycleMode.IDLE
        }

        _currentMode.value = newMode
        applyModeToLocationEngine(newMode)
    }

    private fun applyModeToLocationEngine(mode: AppLifecycleMode) {
        val locManager = LocationManager.getInstance(context)
        val motionManager = MotionWakeManager.getInstance(context)
        val policy = _powerPolicy.value
        val charging = _isCharging.value

        when (mode) {
            AppLifecycleMode.IDLE -> {
                motionBurstJob?.cancel()
                // Completely stop GPS when nothing is active in background!
                locManager.stopLocationUpdates()

                val tripManager = TripManager.getInstance(context)
                if (tripManager.tripMode.value == TripMode.AUTO) {
                    motionManager.arm()
                    TelemetryLogger.log("POWER", "App state IDLE: GPS off, hardware MotionWakeManager armed for AUTO auto-start.")
                } else {
                    motionManager.disarm()
                    TelemetryLogger.log("POWER", "App state IDLE: GPS powered down completely (MANUAL mode).")
                }
            }
            AppLifecycleMode.FOREGROUND_VIEW -> {
                motionBurstJob?.cancel()
                motionManager.disarm()
                if (policy == BatteryPowerPolicy.BATTERY_SAVER && !charging) {
                    locManager.updateSamplingInterval(3000L, 1500L)
                } else {
                    locManager.updateSamplingInterval(1500L, 1000L)
                }
            }
            AppLifecycleMode.TRIP_RECORDING -> {
                motionBurstJob?.cancel()
                motionManager.disarm()
                val profile = TripManager.getInstance(context).activeTrip.value?.activityProfile
                    ?: TripManager.getInstance(context).activityProfile.value
                
                var interval = profile.gpsIntervalMs
                var minInterval = profile.minGpsIntervalMs

                if (policy == BatteryPowerPolicy.HIGH_PERFORMANCE || (policy == BatteryPowerPolicy.SMART_AUTO && charging)) {
                    // Relax restrictions when plugged in or performance requested
                    interval = (interval / 2).coerceAtLeast(2000L)
                    minInterval = (minInterval / 2).coerceAtLeast(1000L)
                } else if (policy == BatteryPowerPolicy.BATTERY_SAVER) {
                    interval = (interval * 1.5).toLong()
                    minInterval = (minInterval * 1.5).toLong()
                }
                locManager.updateSamplingInterval(interval, minInterval)
            }
            AppLifecycleMode.LIVE_ONLY -> {
                motionBurstJob?.cancel()
                motionManager.disarm()
                // Collect breadcrumbs at steady 10s (charging: 6s) so batch upload has complete trail
                val interval = if (charging) 6_000L else 12_000L
                val minInterval = if (charging) 3_000L else 6_000L
                locManager.updateSamplingInterval(interval, minInterval)
            }
            AppLifecycleMode.ANDROID_AUTO -> {
                motionBurstJob?.cancel()
                motionManager.disarm()
                locManager.updateSamplingInterval(3000L, 1000L)
            }
        }
    }
}
