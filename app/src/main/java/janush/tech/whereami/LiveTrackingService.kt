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

    override fun onCreate() {
        super.onCreate()
        locationManager = LocationManager.getInstance(this)

        val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        wakeLock = powerManager.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "WhereAmI:LiveTrackingWakeLock").apply {
            acquire(6 * 60 * 60 * 1000L) // 6h max safe timeout
        }
        
        try {
            startForegroundService()
        } catch (e: Exception) {
            TelemetryLogger.log("ERROR", "LiveTrackingService startForeground failed: ${e.message}")
            stopSelf()
            return
        }
        
        val prefs = getSharedPreferences("where_am_i_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("is_tracking", true).apply()

        startTracking()
    }

    companion object {
        const val ACTION_PAUSE_RESUME = "janush.tech.whereami.ACTION_PAUSE_RESUME"
        const val ACTION_SYNC_NOW = "janush.tech.whereami.ACTION_SYNC_NOW"
        const val ACTION_STOP = "janush.tech.whereami.ACTION_STOP"
        const val ACTION_OPEN_LIVE_SHARING = "janush.tech.whereami.ACTION_OPEN_LIVE_SHARING"
        const val EXTRA_OPEN_LIVE_SHARING = "extra_open_live_sharing"
    }

    private var lastPlaceName: String = "In Transit"
    private var lastSpeedKmh: Float = 0f

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "STOP_TRACKING", ACTION_STOP -> {
                LiveSharingManager.getInstance(this).stopSession()
                val hasTrip = TripManager.getInstance(this).activeTrip.value != null
                if (hasTrip) {
                    updateNotification()
                } else {
                    stopSelf()
                }
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

        // If neither trip nor live sharing is active, avoid running zombie service
        val hasTrip = TripManager.getInstance(this).activeTrip.value != null
        val liveSession = LiveSharingManager.getInstance(this).currentSession.value
        val hasLive = liveSession != null && liveSession.isActive
        if (!hasTrip && !hasLive) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY // Avoid aggressive system restart loops
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

        val notification = buildNotification("WhereAmI Active Tracking", "Recording trip & background location active")
        startForeground(NOTIF_ID, notification)
    }

    private fun formatActionTitle(text: String, colorHex: String): CharSequence {
        return androidx.core.text.HtmlCompat.fromHtml("<font color='$colorHex'>$text</font>", androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY)
    }

    private fun buildNotification(title: String, text: String): Notification {
        val liveSession = LiveSharingManager.getInstance(this).currentSession.value
        val isLiveActive = liveSession != null && liveSession.isActive
        val isPaused = liveSession?.isPaused == true

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
            // 1. Pause / Resume Action (Amber / Emerald Green)
            val pauseIntent = android.app.PendingIntent.getService(
                this,
                1,
                Intent(this, LiveTrackingService::class.java).apply { action = ACTION_PAUSE_RESUME },
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val pauseLabel = if (isPaused) "▶ Resume" else "⏸ Pause"
            val pauseColor = if (isPaused) "#10B981" else "#F59E0B"
            builder.addAction(0, formatActionTitle(pauseLabel, pauseColor), pauseIntent)

            // 2. Quick Share Action (Cyan / Blue)
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

            // 3. Stop Action (Vibrant Red 🛑) - capped at 3 actions total so Android OS won't drop it
            val stopIntent = android.app.PendingIntent.getService(
                this,
                3,
                Intent(this, LiveTrackingService::class.java).apply { action = ACTION_STOP },
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, formatActionTitle("🛑 Stop", "#EF4444"), stopIntent)
        }

        return builder.build()
    }

    private fun updateNotification(overrideText: String? = null) {
        val activeTrip = TripManager.getInstance(this).activeTrip.value
        val liveSession = LiveSharingManager.getInstance(this).currentSession.value
        val isLiveActive = liveSession != null && liveSession.isActive

        val title = if (isLiveActive && !liveSession.title.isNullOrBlank()) {
            "🔴 ${liveSession.title}"
        } else {
            "WhereAmI Active Tracking"
        }

        val text = if (!overrideText.isNullOrBlank()) {
            overrideText
        } else if (activeTrip != null) {
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
        } else if (isLiveActive) {
            val pauseTag = if (liveSession.isPaused) " [PAUSED]" else ""
            "Live Sharing Active$pauseTag • $lastPlaceName • ${liveSession.getFormattedRemaining()}"
        } else {
            "Recording trip & background location active"
        }

        val notification = buildNotification(title, text)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIF_ID, notification)
    }

    private fun startTracking() {
        val prefs = getSharedPreferences("where_am_i_prefs", Context.MODE_PRIVATE)
        val langStr = prefs.getString("display_language", DisplayLanguage.EN.name)
        val lang = try { DisplayLanguage.valueOf(langStr ?: DisplayLanguage.EN.name) } catch (_: Exception) { DisplayLanguage.EN }
        serviceScope.launch {
            locationManager.getLocationUpdates(lang).collectLatest { locationData ->
                val activeTrip = TripManager.getInstance(this@LiveTrackingService).activeTrip.value
                val liveSession = LiveSharingManager.getInstance(this@LiveTrackingService).currentSession.value
                val isLiveActive = liveSession != null && liveSession.isActive

                lastPlaceName = locationData.primaryPlace?.city ?: "In Transit"
                lastSpeedKmh = (locationData.speedMs ?: 0f) * 3.6f

                if (activeTrip != null || isLiveActive) {
                    updateNotification()
                } else {
                    stopSelf()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}
        
        val prefs = getSharedPreferences("where_am_i_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("is_tracking", false).apply()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}


