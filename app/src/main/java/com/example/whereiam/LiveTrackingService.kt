package com.example.whereiam

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
        locationManager = LocationManager(this)

        val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        wakeLock = powerManager.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "WhereIAm:LiveTrackingWakeLock").apply {
            acquire(6 * 60 * 60 * 1000L) // 6h max safe timeout
        }
        
        try {
            startForegroundService()
        } catch (e: Exception) {
            TelemetryLogger.log("ERROR", "LiveTrackingService startForeground failed: ${e.message}")
            stopSelf()
            return
        }
        
        val prefs = getSharedPreferences("where_i_am_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("is_tracking", true).apply()

        startTracking()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP_TRACKING") {
            stopSelf()
            return START_NOT_STICKY
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

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WhereIAm Active Tracking")
            .setContentText("Recording trip & background location active")
            .setSmallIcon(R.drawable.ic_stat_location)
            .setOngoing(true)
            .build()

        startForeground(NOTIF_ID, notification)
    }

    private fun updateNotification(text: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WhereIAm Active Tracking")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_location)
            .setOngoing(true)
            .build()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIF_ID, notification)
    }

    private fun startTracking() {
        val prefs = getSharedPreferences("where_i_am_prefs", Context.MODE_PRIVATE)
        val langStr = prefs.getString("display_language", DisplayLanguage.EN.name)
        val lang = try { DisplayLanguage.valueOf(langStr ?: DisplayLanguage.EN.name) } catch (_: Exception) { DisplayLanguage.EN }
        serviceScope.launch {
            locationManager.getLocationUpdates(lang).collectLatest { locationData ->
                val activeTrip = TripManager.getInstance(this@LiveTrackingService).activeTrip.value
                val liveSession = LiveSharingManager.getInstance(this@LiveTrackingService).currentSession.value
                val isLiveActive = liveSession != null && liveSession.isActive

                if (activeTrip != null) {
                    val distKm = activeTrip.distanceMeters / 1000.0
                    val speedKmh = (locationData.speedMs ?: 0f) * 3.6f
                    val place = locationData.primaryPlace?.city ?: "In Transit"
                    val statusText = if (isLiveActive) " • [LIVE]" else ""
                    updateNotification(String.format(java.util.Locale.getDefault(), "%s • %.1f km (%.1f km/h)%s", place, distKm, speedKmh, statusText))
                } else if (isLiveActive) {
                    val place = locationData.primaryPlace?.city ?: "In Transit"
                    updateNotification("Live Sharing Active • $place")
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
        
        val prefs = getSharedPreferences("where_i_am_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("is_tracking", false).apply()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
