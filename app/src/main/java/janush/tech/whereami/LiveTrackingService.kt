package janush.tech.whereami

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class LiveTrackingService : Service() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private lateinit var locationManager: LocationManager

    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private val NOTIF_ID = 2
    private val CHANNEL_ID = "live_tracking_channel"

    companion object {
        const val NOTIF_SLEEP_ID = 2 // Unified into single persistent notification NOTIF_ID
        const val ACTION_PAUSE_RESUME = "janush.tech.whereami.ACTION_PAUSE_RESUME"
        const val ACTION_SYNC_NOW = "janush.tech.whereami.ACTION_SYNC_NOW"
        const val ACTION_STOP = "janush.tech.whereami.ACTION_STOP"
        const val ACTION_DISABLE_AUTO_START = "janush.tech.whereami.ACTION_DISABLE_AUTO_START"
        const val ACTION_ENTER_STANDBY = "janush.tech.whereami.ACTION_ENTER_STANDBY"
        const val ACTION_STANDBY_SLEEP = "janush.tech.whereami.ACTION_STANDBY_SLEEP"
        const val ACTION_STANDBY_ACTIVATE = "janush.tech.whereami.ACTION_STANDBY_ACTIVATE"
        const val ACTION_OPEN_LIVE_SHARING = "janush.tech.whereami.ACTION_OPEN_LIVE_SHARING"
        const val EXTRA_OPEN_LIVE_SHARING = "extra_open_live_sharing"
        const val ACTION_PAUSE_WARNING = "janush.tech.whereami.ACTION_PAUSE_WARNING"
        const val ACTION_TRIP_PAUSE = "janush.tech.whereami.ACTION_TRIP_PAUSE"
        const val ACTION_TRIP_RESUME = "janush.tech.whereami.ACTION_TRIP_RESUME"
        const val ACTION_TRIP_STOP = "janush.tech.whereami.ACTION_TRIP_STOP"

        fun computeNotificationSignature(
            title: String,
            text: String,
            isSleeping: Boolean,
            isLiveActive: Boolean,
            isLivePaused: Boolean,
            isAuto: Boolean,
            hasActiveTrip: Boolean,
            isTripPaused: Boolean
        ): String {
            return "$title|$text|sleep=$isSleeping|live=$isLiveActive|livePaused=$isLivePaused|auto=$isAuto|trip=$hasActiveTrip|tripPaused=$isTripPaused"
        }
    }

    private var lastPlaceName: String = "In Transit"
    private var lastSpeedKmh: Float = 0f

    override fun onCreate() {
        super.onCreate()
        locationManager = LocationManager.getInstance(this)

        val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        wakeLock = powerManager.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "WhereAmI:LiveTrackingWakeLock").also {
            it.setReferenceCounted(false)
        }

        val hasTrip = TripManager.getInstance(this).activeTrip.value != null
        // Strict WakeLock scoping (AGENTS.md Rule 2.A):
        // Only acquire wake lock during active trip recording, NEVER during Live Sharing alone.
        updateWakeLock(hasTrip)

        try {
            startForegroundService()
        } catch (e: Exception) {
            TelemetryLogger.log("ERROR", "LiveTrackingService startForeground failed: ${e.message}")
            stopSelf()
            return
        }

        startTracking()
    }

    private var wakeLockAcquiredElapsedMs = 0L

    private fun updateWakeLock(shouldHold: Boolean) {
        try {
            if (shouldHold && WakeLockPolicy.shouldHoldTripWakeLock(true)) {
                if (wakeLock == null) {
                    val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                    wakeLock = powerManager.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "WhereAmI:LiveTrackingWakeLock").also {
                        it.setReferenceCounted(false)
                    }
                }
                val now = android.os.SystemClock.elapsedRealtime()
                val heldFor = if (wakeLock?.isHeld == true) now - wakeLockAcquiredElapsedMs else Long.MAX_VALUE
                if (WakeLockPolicy.shouldRefreshHeldLock(wakeLock?.isHeld == true, heldFor)) {
                    wakeLock?.acquire(WakeLockPolicy.TRIP_WAKE_LOCK_SLICE_MS)
                    wakeLockAcquiredElapsedMs = now
                    TelemetryLogger.log("POWER", "LiveTrackingWakeLock acquired/refreshed (active trip, ${WakeLockPolicy.TRIP_WAKE_LOCK_SLICE_MS / 60_000}m slice)")
                }
            } else {
                if (wakeLock?.isHeld == true) {
                    wakeLock?.release()
                    TelemetryLogger.log("POWER", "LiveTrackingWakeLock released (standby or stopped)")
                }
                wakeLockAcquiredElapsedMs = 0L
            }
        } catch (e: Exception) {
            TelemetryLogger.log("ERROR", "Error updating LiveTrackingWakeLock: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TRIP_PAUSE -> {
                TripManager.getInstance(this).pauseTrip(isManual = true)
                updateNotification(force = true)
                return START_STICKY
            }
            ACTION_TRIP_RESUME -> {
                TripManager.getInstance(this).resumeTrip()
                updateNotification(force = true)
                return START_STICKY
            }
            ACTION_TRIP_STOP -> {
                TripManager.getInstance(this).stopManualTrip()
                return START_STICKY
            }
            ACTION_PAUSE_WARNING -> {
                val pauseDurationMs = intent.getLongExtra("pause_duration_ms", 0L)
                val hours = pauseDurationMs / 3600000L
                val minutes = (pauseDurationMs % 3600000L) / 60000L
                val timeStr = if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
                updateNotification(overrideText = "Trip paused for $timeStr • Tap Resume or Stop", force = true)
                return START_STICKY
            }
            ACTION_DISABLE_AUTO_START -> {
                TripManager.getInstance(this).setTripMode(TripMode.MANUAL)
                LiveSharingManager.getInstance(this).stopSession()
                stopLocationTracking()
                updateWakeLock(false)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
                return START_NOT_STICKY
            }
            "STOP_TRACKING", ACTION_STOP -> {
                LiveSharingManager.getInstance(this).stopSession()
                val isSleeping = AppStateManager.getInstance(this).isStandbySleeping.value
                val hasTrip = TripManager.getInstance(this).activeTrip.value != null
                if (isSleeping && !hasTrip) {
                    // Update sleep notification in-place: Live Sharing stopped, show standard Standby Sleep
                    updateNotification(force = true)
                    return START_STICKY
                }
                if (hasTrip) {
                    updateNotification()
                    return START_STICKY
                } else {
                    val isAuto = TripManager.getInstance(this).tripMode.value == TripMode.AUTO
                    if (isAuto) {
                        stopLocationTracking()
                        updateWakeLock(false)
                        updateNotification(force = true)
                        return START_STICKY
                    } else {
                        stopLocationTracking()
                        updateWakeLock(false)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                        } else {
                            @Suppress("DEPRECATION")
                            stopForeground(true)
                        }
                        stopSelf()
                        return START_NOT_STICKY
                    }
                }
            }
            ACTION_ENTER_STANDBY -> {
                evaluateServiceState()
                return START_STICKY
            }
            ACTION_STANDBY_SLEEP -> {
                val appStateMgr = AppStateManager.getInstance(this)
                if (!appStateMgr.isStandbySleeping.value) {
                    appStateMgr.enterStandbySleep(isManual = true)
                }
                evaluateServiceState()
                return START_STICKY
            }
            ACTION_STANDBY_ACTIVATE -> {
                val appStateMgr = AppStateManager.getInstance(this)
                if (appStateMgr.isStandbySleeping.value) {
                    appStateMgr.wakeStandbySleep(isExplicitUserAction = true)
                }
                evaluateServiceState()
                return START_STICKY
            }
            ACTION_PAUSE_RESUME -> {
                val sharingMgr = LiveSharingManager.getInstance(this)
                val sess = sharingMgr.currentSession.value
                if (sess != null && sess.isActive) {
                    if (sess.isPaused) {
                        sharingMgr.resumeSession()
                    } else {
                        sharingMgr.pauseSession()
                    }
                }
                updateNotification(force = true)
                return START_STICKY
            }
            ACTION_SYNC_NOW -> {
                val sharingMgr = LiveSharingManager.getInstance(this)
                sharingMgr.syncNow()
                updateNotification(force = true)
                return START_NOT_STICKY
            }
        }

        evaluateServiceState()
        val hasTrip = TripManager.getInstance(this).activeTrip.value != null
        val liveSession = LiveSharingManager.getInstance(this).currentSession.value
        val hasLive = liveSession != null && liveSession.isActive
        val isAuto = TripManager.getInstance(this).tripMode.value == TripMode.AUTO

        return if (hasTrip || hasLive || isAuto) START_STICKY else START_NOT_STICKY
    }

    private fun startForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Live Tracking Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val hasTrip = TripManager.getInstance(this).activeTrip.value != null
        val liveSession = LiveSharingManager.getInstance(this).currentSession.value
        val hasLive = liveSession != null && liveSession.isActive
        val isAuto = TripManager.getInstance(this).tripMode.value == TripMode.AUTO
        val isSleeping = AppStateManager.getInstance(this).isStandbySleeping.value && !hasTrip

        val initialTitle = when {
            isSleeping && hasLive && isAuto -> "WhereAmI Sleeping • Live Sharing (1h sync)"
            isSleeping && hasLive -> "WhereAmI Sleeping • Live Sharing (1h sync)"
            isSleeping -> "WhereAmI • Sleeping"
            hasTrip || hasLive -> "WhereAmI Active Tracking"
            else -> "WhereAmI • Auto-record Standby"
        }
        val initialText = when {
            isSleeping && hasLive && isAuto -> "Auto-record sleeping • Live Sharing active (1h sync) • Tap to open app"
            isSleeping && hasLive -> "Overnight hibernation • Live Sharing active (1h sync) • Tap to open app"
            isSleeping -> "Motion monitoring paused • Tap to wake or open app"
            hasTrip || hasLive -> "Recording trip & background location active"
            else -> "Ready to auto-record (standby • low power)"
        }
        val notification = buildNotification(initialTitle, initialText)
        startForeground(NOTIF_ID, notification)
        lastNotificationPostTime = SystemClock.elapsedRealtime()
        lastNotificationTitle = initialTitle
        lastNotificationText = initialText
        lastNotificationSignature = computeNotificationSignature(
            title = initialTitle,
            text = initialText,
            isSleeping = isSleeping,
            isLiveActive = hasLive,
            isLivePaused = liveSession?.isPaused == true,
            isAuto = isAuto,
            hasActiveTrip = hasTrip,
            isTripPaused = TripManager.getInstance(this).isPaused.value
        )
    }

    private fun formatActionTitle(text: String, colorHex: String): CharSequence {
        return androidx.core.text.HtmlCompat.fromHtml("<font color='$colorHex'>$text</font>", androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY)
    }

    private fun buildNotification(title: String, text: String): Notification {
        val liveSession = LiveSharingManager.getInstance(this).currentSession.value
        val isLiveActive = liveSession != null && liveSession.isActive
        val isPaused = liveSession?.isPaused == true
        val activeTrip = TripManager.getInstance(this).activeTrip.value
        val isAuto = TripManager.getInstance(this).tripMode.value == TripMode.AUTO
        val isSleeping = AppStateManager.getInstance(this).isStandbySleeping.value && activeTrip == null

        val contentIntent = android.app.PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                this.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_location)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        if (isSleeping) {
            // Action 1: Wake Action
            val activateIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.app.PendingIntent.getForegroundService(
                    this,
                    5,
                    Intent(this, LiveTrackingService::class.java).apply { action = ACTION_STANDBY_ACTIVATE },
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
            } else {
                android.app.PendingIntent.getService(
                    this,
                    5,
                    Intent(this, LiveTrackingService::class.java).apply { action = ACTION_STANDBY_ACTIVATE },
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
            }
            builder.addAction(0, formatActionTitle("Wake", "#10B981"), activateIntent)

            if (isLiveActive) {
                // Action 2: Mute / Resume Live Sharing stream while staying in Sleep Mode
                val pauseIntent = android.app.PendingIntent.getService(
                    this,
                    1,
                    Intent(this, LiveTrackingService::class.java).apply { action = ACTION_PAUSE_RESUME },
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
                val (streamLabel, streamColor) = if (isPaused) {
                    Pair("Resume Stream", "#10B981")
                } else {
                    Pair("Mute Stream", "#F59E0B")
                }
                builder.addAction(0, formatActionTitle(streamLabel, streamColor), pauseIntent)
            }
        } else if (isLiveActive) {
            // 1. Mute / Resume Live Sharing stream
            val pauseIntent = android.app.PendingIntent.getService(
                this,
                1,
                Intent(this, LiveTrackingService::class.java).apply { action = ACTION_PAUSE_RESUME },
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val (streamLabel, streamColor) = if (isPaused) {
                Pair("Resume Stream", "#10B981")
            } else {
                Pair("Mute Stream", "#F59E0B")
            }
            builder.addAction(0, formatActionTitle(streamLabel, streamColor), pauseIntent)

            // 2. Quick Share Action
            val sendIntent = Intent().apply {
                action = Intent.ACTION_SEND
                val shareUrl = liveSession.getViewerUrl()
                val shareText = if (!shareUrl.isNullOrBlank()) {
                    "Track my live trip on WhereAmI: $shareUrl"
                } else {
                    val lastLoc = LocationManager.getInstance(this@LiveTrackingService).lastLocationSnapshot
                    if (lastLoc != null) {
                        "WhereAmI Location: https://maps.google.com/?q=${lastLoc.lat},${lastLoc.lng}"
                    } else "WhereAmI Active Tracking"
                }
                putExtra(Intent.EXTRA_TEXT, shareText)
                type = "text/plain"
            }
            val chooserIntent = Intent.createChooser(sendIntent, "Share Location via").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val shareIntent = android.app.PendingIntent.getActivity(
                this,
                4,
                chooserIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, formatActionTitle("Share", "#38BDF8"), shareIntent)

            // 3. Sleep Action (for overnight live sharing hibernation without recording)
            if (activeTrip == null) {
                val sleepIntent = android.app.PendingIntent.getService(
                    this,
                    6,
                    Intent(this, LiveTrackingService::class.java).apply { action = ACTION_STANDBY_SLEEP },
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(0, formatActionTitle("Sleep", "#94A3B8"), sleepIntent)
            }
        } else if (activeTrip == null && isAuto) {
            // 1. Sleep Action (enter standby hibernation immediately)
            val sleepIntent = android.app.PendingIntent.getService(
                this,
                6,
                Intent(this, LiveTrackingService::class.java).apply { action = ACTION_STANDBY_SLEEP },
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, formatActionTitle("Sleep", "#94A3B8"), sleepIntent)
        } else if (activeTrip != null) {
            val isTripPaused = TripManager.getInstance(this).isPaused.value
            val tripIntent = android.app.PendingIntent.getService(
                this,
                if (isTripPaused) 10 else 9,
                Intent(this, LiveTrackingService::class.java).apply {
                    action = if (isTripPaused) ACTION_TRIP_RESUME else ACTION_TRIP_PAUSE
                },
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val (tripLabel, tripColor) = if (isTripPaused) {
                Pair("Resume Trip", "#10B981")
            } else {
                Pair("Pause Trip", "#F59E0B")
            }
            builder.addAction(0, formatActionTitle(tripLabel, tripColor), tripIntent)

            // Quick Share if Live Sharing is also on during active trip
            if (isLiveActive && liveSession != null) {
                val sendIntent = Intent().apply {
                    action = Intent.ACTION_SEND
                    val shareUrl = liveSession.getViewerUrl()
                    val shareText = if (!shareUrl.isNullOrBlank()) {
                        "Track my live trip on WhereAmI: $shareUrl"
                    } else {
                        val lastLoc = LocationManager.getInstance(this@LiveTrackingService).lastLocationSnapshot
                        if (lastLoc != null) {
                            "WhereAmI Location: https://maps.google.com/?q=${lastLoc.lat},${lastLoc.lng}"
                        } else "WhereAmI Active Tracking"
                    }
                    putExtra(Intent.EXTRA_TEXT, shareText)
                    type = "text/plain"
                }
                val chooserIntent = Intent.createChooser(sendIntent, "Share Location via").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val shareIntent = android.app.PendingIntent.getActivity(
                    this,
                    4,
                    chooserIntent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(0, formatActionTitle("Share", "#38BDF8"), shareIntent)
            }
        }

        return builder.build()
    }

    @Volatile
    private var lastNotificationPostTime = 0L
    @Volatile
    private var lastNotificationTitle: String? = null
    @Volatile
    private var lastNotificationText: String? = null
    @Volatile
    private var lastNotificationSignature: String? = null

    private fun updateNotification(overrideText: String? = null, force: Boolean = false) {
        val activeTrip = TripManager.getInstance(this).activeTrip.value
        val liveSession = LiveSharingManager.getInstance(this).currentSession.value
        val isLiveActive = liveSession != null && liveSession.isActive
        val isPaused = liveSession?.isPaused == true
        val isAuto = TripManager.getInstance(this).tripMode.value == TripMode.AUTO
        val profile = TripManager.getInstance(this).activityProfile.value
        val isSleeping = AppStateManager.getInstance(this).isStandbySleeping.value && activeTrip == null
        val isTripPaused = TripManager.getInstance(this).isPaused.value

        val title = when {
            isSleeping && isLiveActive && isAuto -> "WhereAmI Sleeping • Live Sharing (1h sync)"
            isSleeping && isLiveActive -> "WhereAmI Sleeping • Live Sharing (1h sync)"
            isSleeping -> "WhereAmI • Sleeping"
            isLiveActive && !liveSession.title.isNullOrBlank() -> liveSession.title
            isLiveActive && isAuto -> "WhereAmI • Live & Auto-record"
            isLiveActive -> "WhereAmI • Live Sharing"
            activeTrip != null -> "WhereAmI Active Tracking"
            isAuto -> "WhereAmI • Auto-record Standby"
            else -> "WhereAmI Tracking"
        }

        val text = when {
            !overrideText.isNullOrBlank() -> overrideText
            isSleeping && isLiveActive && isAuto -> {
                val liveStatus = if (isPaused) "Live Sharing muted" else "Live Sharing active (1h sync)"
                "Auto-record sleeping • $liveStatus • Tap to open app"
            }
            isSleeping && isLiveActive -> {
                val liveStatus = if (isPaused) "Live Sharing muted" else "Live Sharing active (1h sync)"
                "Overnight hibernation • $liveStatus • Tap to open app"
            }
            isSleeping -> "Motion monitoring paused • Tap to wake or open app"
            activeTrip != null -> {
                val distKm = activeTrip.distanceMeters / 1000.0
                val statusTag = when {
                    isTripPaused -> " • [PAUSED]"
                    isLiveActive && liveSession.isPaused -> " • [LIVE PAUSED]"
                    isLiveActive -> " • [LIVE]"
                    else -> ""
                }
                val timeTag = if (isLiveActive) " • ${liveSession.getFormattedRemaining()}" else ""
                String.format(
                    java.util.Locale.getDefault(),
                    "%s • %.1f km (%.1f km/h)%s%s",
                    lastPlaceName,
                    distKm,
                    lastSpeedKmh,
                    statusTag,
                    timeTag
                )
            }
            isLiveActive -> {
                val pauseTag = if (liveSession.isPaused) " [PAUSED]" else ""
                val place = getEffectivePlaceName()
                val autoTag = if (isAuto) {
                    val startSpeed = TripManager.getInstance(this).getAutoStartSpeedForProfile(profile)
                    val speedFormatted = if (startSpeed % 1f == 0f) ">${startSpeed.toInt()}" else ">%.1f".format(startSpeed)
                    " • Auto-record: ${profile.displayName} ($speedFormatted km/h)"
                } else ""
                "Live Sharing Active$pauseTag$autoTag • $place"
            }
            isAuto -> {
                val startSpeed = TripManager.getInstance(this).getAutoStartSpeedForProfile(profile)
                val speedFormatted = if (startSpeed % 1f == 0f) ">${startSpeed.toInt()}" else ">%.1f".format(startSpeed)
                "Ready for ${profile.displayName} ($speedFormatted km/h) • Low power"
            }
            else -> "Recording trip & background location active"
        }

        val signature = computeNotificationSignature(
            title = title,
            text = text,
            isSleeping = isSleeping,
            isLiveActive = isLiveActive,
            isLivePaused = isPaused,
            isAuto = isAuto,
            hasActiveTrip = activeTrip != null,
            isTripPaused = isTripPaused
        )

        // Never re-post an identical notification (even when force=true) to avoid NotificationManagerService:post wakelocks
        if (signature == lastNotificationSignature) {
            return
        }

        val now = SystemClock.elapsedRealtime()
        val contentChanged = title != lastNotificationTitle || text != lastNotificationText
        val timeElapsed = (now - lastNotificationPostTime) >= 10_000L
        val minForceElapsed = (now - lastNotificationPostTime) >= 3_000L

        if ((force && minForceElapsed) || (contentChanged && timeElapsed)) {
            val notification = buildNotification(title, text)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIF_ID, notification)
            lastNotificationPostTime = now
            lastNotificationTitle = title
            lastNotificationText = text
            lastNotificationSignature = signature
        }
    }

    private fun getEffectivePlaceName(): String {
        if (lastPlaceName.isNotBlank() && lastPlaceName != "In Transit") {
            return lastPlaceName
        }
        val currentCity = LocationManager.getInstance(this).lastLocationSnapshot?.multiPlace?.let {
            it.pl.takeIf { p -> p.isValid() }?.city ?: it.en.city
        }
        if (!currentCity.isNullOrBlank()) {
            lastPlaceName = currentCity
            return currentCity
        }
        val prefs = getSharedPreferences("where_am_i_prefs", Context.MODE_PRIVATE)
        val saved = prefs.getString("last_city", null)
        if (!saved.isNullOrBlank()) {
            lastPlaceName = saved
            return saved
        }
        return "Broadcasting live location"
    }

    private var locationUpdatesJob: Job? = null

    private fun startLocationTracking() {
        if (locationUpdatesJob?.isActive == true) return
        val prefs = getSharedPreferences("where_am_i_prefs", Context.MODE_PRIVATE)
        val langStr = prefs.getString("display_language", DisplayLanguage.EN.name)
        val lang = try { DisplayLanguage.valueOf(langStr ?: DisplayLanguage.EN.name) } catch (_: Exception) { DisplayLanguage.EN }

        locationUpdatesJob = serviceScope.launch {
            locationManager.getLocationUpdates(lang).collectLatest { locationData ->
                lastPlaceName = locationData.primaryPlace?.city ?: "In Transit"
                lastSpeedKmh = (locationData.speedMs ?: 0f) * 3.6f
                updateNotification()
            }
        }
        TelemetryLogger.log("POWER", "LiveTrackingService: location updates collector started for notification metrics")
    }

    private fun stopLocationTracking() {
        if (locationUpdatesJob != null) {
            locationUpdatesJob?.cancel()
            locationUpdatesJob = null
            TelemetryLogger.log("POWER", "LiveTrackingService: location updates collector stopped (standby/idle)")
        }
    }

    private fun evaluateServiceState() {
        val trip = TripManager.getInstance(this).activeTrip.value
        val liveSession = LiveSharingManager.getInstance(this).currentSession.value
        val hasLive = liveSession != null && liveSession.isActive
        val hasUnpausedLive = liveSession != null && liveSession.isActive && !liveSession.isPaused
        val isAuto = TripManager.getInstance(this).tripMode.value == TripMode.AUTO
        val isSleeping = AppStateManager.getInstance(this).isStandbySleeping.value

        if (trip != null) {
            startLocationTracking()
            // Strict WakeLock scoping: ONLY hold CPU wake lock during active trip recording.
            updateWakeLock(true)
            updateNotification(force = true)
        } else if (isSleeping) {
            // Standby / Live Sharing Sleep engaged: shutdown location tracking and GPS, release wake lock, update notification in-place
            stopLocationTracking()
            updateWakeLock(false)
            locationManager.stopLocationUpdates()
            if (hasUnpausedLive) {
                LiveSharingManager.getInstance(this).startStationaryHeartbeat()
            }
            updateNotification(force = true)
        } else if (hasLive) {
            // Live sharing active without active trip recording:
            // Keep location stream active only when unpaused; detach when muted/paused to prevent idle subscriber leaks.
            if (hasUnpausedLive) {
                startLocationTracking()
            } else {
                stopLocationTracking()
            }
            // Release CPU wake lock (no partial wake lock held during LIVE_ONLY)
            updateWakeLock(false)
            updateNotification(force = true)
        } else if (isAuto) {
            stopLocationTracking()
            updateWakeLock(false)
            updateNotification(force = true)
        } else {
            stopLocationTracking()
            updateWakeLock(false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
        }
    }

    private fun startTracking() {
        // Observe trip, live share, tripMode, and sleep changes to dynamically switch between
        // Active Tracking, Zero-CPU Standby FGS, Sleep Hibernation, and Complete Stop (MANUAL mode)
        serviceScope.launch {
            TripManager.getInstance(this@LiveTrackingService).activeTrip.collectLatest { trip ->
                evaluateServiceState()
                // Refresh the trip wake-lock slice ONLY while an active trip is recording
                if (trip != null) {
                    while (true) {
                        kotlinx.coroutines.delay(WakeLockPolicy.TRIP_WAKE_LOCK_SLICE_MS / 3)
                        if (TripManager.getInstance(this@LiveTrackingService).activeTrip.value != null) {
                            updateWakeLock(true)
                        } else {
                            break
                        }
                    }
                }
            }
        }
        serviceScope.launch {
            LiveSharingManager.getInstance(this@LiveTrackingService).currentSession
                .distinctUntilChanged { old, new ->
                    old?.id == new?.id &&
                        old?.isActive == new?.isActive &&
                        old?.isPaused == new?.isPaused &&
                        old?.title == new?.title
                }
                .collectLatest {
                    evaluateServiceState()
                }
        }
        serviceScope.launch {
            TripManager.getInstance(this@LiveTrackingService).tripMode.collectLatest {
                evaluateServiceState()
            }
        }
        serviceScope.launch {
            AppStateManager.getInstance(this@LiveTrackingService).isStandbySleeping.collectLatest {
                evaluateServiceState()
            }
        }
        serviceScope.launch {
            TripManager.getInstance(this@LiveTrackingService).isPaused.collectLatest {
                updateNotification(force = true)
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        TripManager.getInstance(this).flushPendingForSurvival()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        TripManager.getInstance(this).flushPendingForSurvival()
        super.onDestroy()
        stopLocationTracking()
        serviceJob.cancel()
        updateWakeLock(false)
        val prefs = getSharedPreferences("where_am_i_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("is_tracking", false).apply()
        TelemetryLogger.log("POWER", "LiveTrackingService destroyed, wake lock released")
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
