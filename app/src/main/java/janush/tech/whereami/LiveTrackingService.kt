package janush.tech.whereami

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class LiveTrackingService : Service() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private lateinit var locationManager: LocationManager

    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private val NOTIF_ID = 2
    private val CHANNEL_ID = "live_tracking_channel"

    companion object {
        const val ACTION_PAUSE_RESUME = "janush.tech.whereami.ACTION_PAUSE_RESUME"
        const val ACTION_SYNC_NOW = "janush.tech.whereami.ACTION_SYNC_NOW"
        const val ACTION_STOP = "janush.tech.whereami.ACTION_STOP"
        const val ACTION_ENTER_STANDBY = "janush.tech.whereami.ACTION_ENTER_STANDBY"
        const val ACTION_OPEN_LIVE_SHARING = "janush.tech.whereami.ACTION_OPEN_LIVE_SHARING"
        const val EXTRA_OPEN_LIVE_SHARING = "extra_open_live_sharing"
    }

    private var lastPlaceName: String = "In Transit"
    private var lastSpeedKmh: Float = 0f

    override fun onCreate() {
        super.onCreate()
        locationManager = LocationManager.getInstance(this)

        val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        wakeLock = powerManager.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "WhereAmI:LiveTrackingWakeLock")

        val hasTrip = TripManager.getInstance(this).activeTrip.value != null
        val liveSession = LiveSharingManager.getInstance(this).currentSession.value
        val hasLive = liveSession != null && liveSession.isActive
        updateWakeLock(hasTrip || hasLive)

        try {
            startForegroundService()
        } catch (e: Exception) {
            TelemetryLogger.log("ERROR", "LiveTrackingService startForeground failed: ${e.message}")
            stopSelf()
            return
        }

        startTracking()
    }

    private fun updateWakeLock(shouldHold: Boolean) {
        try {
            if (shouldHold) {
                if (wakeLock == null) {
                    val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                    wakeLock = powerManager.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "WhereAmI:LiveTrackingWakeLock")
                }
                if (wakeLock?.isHeld != true) {
                    wakeLock?.acquire(3 * 60 * 60 * 1000L) // 3h safe timeout while active
                    TelemetryLogger.log("POWER", "LiveTrackingWakeLock acquired (active trip or live sharing)")
                }
            } else {
                if (wakeLock?.isHeld == true) {
                    wakeLock?.release()
                    TelemetryLogger.log("POWER", "LiveTrackingWakeLock released (standby or stopped)")
                }
            }
        } catch (e: Exception) {
            TelemetryLogger.log("ERROR", "Error updating LiveTrackingWakeLock: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "STOP_TRACKING", ACTION_STOP -> {
                LiveSharingManager.getInstance(this).stopSession()
                val hasTrip = TripManager.getInstance(this).activeTrip.value != null
                if (hasTrip) {
                    updateNotification()
                } else {
                    TripManager.getInstance(this).setTripMode(TripMode.MANUAL)
                    updateWakeLock(false)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    } else {
                        @Suppress("DEPRECATION")
                        stopForeground(true)
                    }
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            ACTION_ENTER_STANDBY -> {
                updateWakeLock(false)
                updateNotification()
                return START_NOT_STICKY
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
                updateNotification()
                return START_NOT_STICKY
            }
            ACTION_SYNC_NOW -> {
                val sharingMgr = LiveSharingManager.getInstance(this)
                sharingMgr.syncNow()
                updateNotification()
                return START_NOT_STICKY
            }
        }

        val hasTrip = TripManager.getInstance(this).activeTrip.value != null
        val liveSession = LiveSharingManager.getInstance(this).currentSession.value
        val hasLive = liveSession != null && liveSession.isActive
        val isAuto = TripManager.getInstance(this).tripMode.value == TripMode.AUTO

        if (!hasTrip && !hasLive) {
            if (!isAuto) {
                updateWakeLock(false)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
                return START_NOT_STICKY
            } else {
                updateWakeLock(false)
                updateNotification()
                return START_STICKY
            }
        }

        updateWakeLock(true)
        updateNotification()
        return START_STICKY
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

        val initialTitle = if (hasTrip || hasLive) "WhereAmI Active Tracking" else "WhereAmI • Auto-detect Standby"
        val initialText = if (hasTrip || hasLive) "Recording trip & background location active" else "Ready to auto-record (standby • low power)"
        val notification = buildNotification(initialTitle, initialText)
        startForeground(NOTIF_ID, notification)
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

        val contentIntent = android.app.PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                if (isLiveActive) {
                    action = ACTION_OPEN_LIVE_SHARING
                    putExtra(EXTRA_OPEN_LIVE_SHARING, true)
                }
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

        if (isLiveActive) {
            // 1. Pause / Resume Action
            val pauseIntent = android.app.PendingIntent.getService(
                this,
                1,
                Intent(this, LiveTrackingService::class.java).apply { action = ACTION_PAUSE_RESUME },
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val pauseLabel = if (isPaused) "▶ Resume" else "⏸ Pause"
            val pauseColor = if (isPaused) "#10B981" else "#F59E0B"
            builder.addAction(0, formatActionTitle(pauseLabel, pauseColor), pauseIntent)

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
            builder.addAction(0, formatActionTitle("🔗 Share", "#38BDF8"), shareIntent)

            // 3. Stop Action
            val stopIntent = android.app.PendingIntent.getService(
                this,
                3,
                Intent(this, LiveTrackingService::class.java).apply { action = ACTION_STOP },
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, formatActionTitle("🛑 Stop", "#EF4444"), stopIntent)
        } else if (activeTrip == null && isAuto) {
            // Standby mode action: allow user to stop standby auto-recording directly from notification
            val stopIntent = android.app.PendingIntent.getService(
                this,
                3,
                Intent(this, LiveTrackingService::class.java).apply { action = ACTION_STOP },
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, formatActionTitle("🛑 Disable Auto-start", "#EF4444"), stopIntent)
        }

        return builder.build()
    }

    private fun updateNotification(overrideText: String? = null) {
        val activeTrip = TripManager.getInstance(this).activeTrip.value
        val liveSession = LiveSharingManager.getInstance(this).currentSession.value
        val isLiveActive = liveSession != null && liveSession.isActive
        val isAuto = TripManager.getInstance(this).tripMode.value == TripMode.AUTO
        val profile = TripManager.getInstance(this).activityProfile.value

        val title = when {
            isLiveActive && !liveSession.title.isNullOrBlank() -> "🔴 ${liveSession.title}"
            activeTrip != null -> "WhereAmI Active Tracking"
            isAuto -> "WhereAmI • Auto-detect Standby"
            else -> "WhereAmI Tracking"
        }

        val text = when {
            !overrideText.isNullOrBlank() -> overrideText
            activeTrip != null -> {
                val distKm = activeTrip.distanceMeters / 1000.0
                val statusTag = when {
                    isLiveActive && liveSession.isPaused -> " • [PAUSED]"
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
                "Live Sharing Active$pauseTag • $lastPlaceName • ${liveSession.getFormattedRemaining()}"
            }
            isAuto -> {
                val speedFormatted = if (profile.autoStartSpeedKmh % 1f == 0f) ">${profile.autoStartSpeedKmh.toInt()}" else ">%.1f".format(profile.autoStartSpeedKmh)
                "Ready for ${profile.displayName} ($speedFormatted km/h) • Low power"
            }
            else -> "Recording trip & background location active"
        }

        val notification = buildNotification(title, text)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIF_ID, notification)
    }

    private fun startTracking() {
        val prefs = getSharedPreferences("where_am_i_prefs", Context.MODE_PRIVATE)
        val langStr = prefs.getString("display_language", DisplayLanguage.EN.name)
        val lang = try { DisplayLanguage.valueOf(langStr ?: DisplayLanguage.EN.name) } catch (_: Exception) { DisplayLanguage.EN }

        // 1. Observe location stream for real-time telemetry display
        serviceScope.launch {
            locationManager.getLocationUpdates(lang).collectLatest { locationData ->
                lastPlaceName = locationData.primaryPlace?.city ?: "In Transit"
                lastSpeedKmh = (locationData.speedMs ?: 0f) * 3.6f
                updateNotification()
            }
        }

        // 2. Observe active trip changes to maintain wake lock and notification lifecycle
        serviceScope.launch {
            TripManager.getInstance(this@LiveTrackingService).activeTrip.collectLatest { trip ->
                val liveSession = LiveSharingManager.getInstance(this@LiveTrackingService).currentSession.value
                val hasLive = liveSession != null && liveSession.isActive
                val isAuto = TripManager.getInstance(this@LiveTrackingService).tripMode.value == TripMode.AUTO

                if (trip != null || hasLive) {
                    updateWakeLock(true)
                    updateNotification()
                } else if (isAuto) {
                    updateWakeLock(false)
                    updateNotification()
                } else {
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
        }

        // 3. Observe live sharing changes
        serviceScope.launch {
            LiveSharingManager.getInstance(this@LiveTrackingService).currentSession.collectLatest { liveSession ->
                val trip = TripManager.getInstance(this@LiveTrackingService).activeTrip.value
                val hasLive = liveSession != null && liveSession.isActive
                val isAuto = TripManager.getInstance(this@LiveTrackingService).tripMode.value == TripMode.AUTO

                if (trip != null || hasLive) {
                    updateWakeLock(true)
                    updateNotification()
                } else if (isAuto) {
                    updateWakeLock(false)
                    updateNotification()
                } else {
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
        }
    }

    override fun onDestroy() {
        super.onDestroy()
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
