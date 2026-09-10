package com.example.whereiam

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import java.util.Locale

class WhereIAmWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (widgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, widgetId)
        }
    }

    companion object {

        const val ACTION_REFRESH = "com.example.whereiam.ACTION_WIDGET_REFRESH"
        const val ACTION_TOGGLE_LIVE = "com.example.whereiam.ACTION_WIDGET_TOGGLE_LIVE"

        /** Called from anywhere in the app to force all widget instances to redraw. */
        fun updateAllWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, WhereIAmWidget::class.java))
            if (ids.isNotEmpty()) {
                val intent = Intent(context, WhereIAmWidget::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                }
                context.sendBroadcast(intent)
            }
        }

        fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, widgetId: Int) {
            val prefs = context.getSharedPreferences("where_i_am_prefs", Context.MODE_PRIVATE)

            val lang = prefs.getString("display_language", "EN") ?: "EN"
            val suffix = when (lang) {
                "PL" -> "pl"
                "NATIVE" -> "native"
                else -> "en"
            }

            val city = prefs.getString("city_$suffix", prefs.getString("city", "--")) ?: "--"
            val hierarchy = prefs.getString("hierarchy_$suffix", "") ?: ""
            val rawState = prefs.getString("state_$suffix", prefs.getString("state", "--")) ?: "--"
            val state = rawState
                .replace("województwo ", "", ignoreCase = true)
                .replace("województwo", "", ignoreCase = true)
                .trim()
            val country = prefs.getString("country_$suffix", prefs.getString("country", "--")) ?: "--"
            val time = prefs.getString("time", "") ?: ""
            val isTracking = prefs.getBoolean("is_tracking", false)

            val stateLine = if (hierarchy.isNotEmpty()) {
                "$hierarchy • $country"
            } else {
                "$state, $country"
            }

            val speedStr: String
            if (prefs.contains("speed")) {
                val speedKmh = prefs.getFloat("speed", 0f) * 3.6f
                val kmhFormatted = String.format(Locale.getDefault(), "%.1f km/h", speedKmh)
                val paceStr = if (speedKmh > 1.0f) {
                    val paceMin = (60f / speedKmh).toInt()
                    val paceSec = ((60f / speedKmh - paceMin) * 60).toInt()
                    String.format(Locale.getDefault(), "%d:%02d/km", paceMin, paceSec)
                } else {
                    "-/km"
                }
                speedStr = "$kmhFormatted ($paceStr)"
            } else {
                speedStr = ""
            }

            val views = RemoteViews(context.packageName, R.layout.widget_layout)
            views.setTextViewText(R.id.widget_city, city)
            views.setTextViewText(R.id.widget_state, stateLine)
            views.setTextViewText(R.id.widget_speed, speedStr)
            views.setTextViewText(R.id.widget_time,
                if (time.isNotEmpty()) "Updated: $time [$lang]" else "")
            views.setTextViewText(R.id.widget_live_btn,
                if (isTracking) "Stop Live" else "Start Live")

            // Refresh button intent
            val refreshIntent = Intent(context, WhereIAmWidget::class.java).apply {
                action = ACTION_REFRESH
            }
            val refreshPending = PendingIntent.getBroadcast(
                context, 1, refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_refresh_btn, refreshPending)

            // Live toggle button intent
            val liveIntent = Intent(context, WhereIAmWidget::class.java).apply {
                action = ACTION_TOGGLE_LIVE
            }
            val livePending = PendingIntent.getBroadcast(
                context, 2, liveIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_live_btn, livePending)

            appWidgetManager.updateAppWidget(widgetId, views)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        when (intent.action) {
            ACTION_REFRESH -> handleRefresh(context)
            ACTION_TOGGLE_LIVE -> handleToggleLive(context)
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleRefresh(context: Context) {
        // Immediately redraw with whatever is cached so the UI doesn't freeze
        updateAllWidgets(context)

        // Fetch a real fresh GPS fix in a background thread
        Thread {
            try {
                val fusedClient = com.google.android.gms.location.LocationServices
                    .getFusedLocationProviderClient(context)

                // getCurrentLocation() forces the chip to produce a new fix,
                // unlike getLastLocation() which returns a potentially stale cache.
                fusedClient.getCurrentLocation(
                    com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, null
                ).addOnSuccessListener { location ->
                    if (location == null) {
                        // No fix — at least refresh the UI with cached data
                        updateAllWidgets(context)
                        return@addOnSuccessListener
                    }

                    // Save fresh speed (0 if chip reports no movement)
                    val rawSpeed = if (location.hasSpeed()) location.speed else 0f
                    val speed = if (rawSpeed < 0.3f) 0f else rawSpeed
                    WidgetHelper.saveLastSpeed(context, speed)
                    WidgetHelper.saveLastCoordinates(context, location.latitude, location.longitude)

                    // Re-geocode with fresh coordinates and current language setting
                    val locationManager = LocationManager(context)
                    val lang = WidgetHelper.getSavedLanguage(context)
                    val multiData = locationManager.resolveMultiLanguageData(
                        location.latitude, location.longitude
                    )
                    WidgetHelper.saveMultiLanguageWidgetData(context, multiData)

                    // Force widget redraw with the fresh data
                    updateAllWidgets(context)
                }
            } catch (_: Exception) {
                updateAllWidgets(context)
            }
        }.start()
    }

    private fun handleToggleLive(context: Context) {
        val prefs = context.getSharedPreferences("where_i_am_prefs", Context.MODE_PRIVATE)
        val isTracking = prefs.getBoolean("is_tracking", false)

        val serviceIntent = Intent(context, LiveTrackingService::class.java)
        if (isTracking) {
            serviceIntent.action = "STOP_TRACKING"
            context.startService(serviceIntent)
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }

        // The service's onCreate/onDestroy will flip is_tracking in prefs and call updateAllWidgets.
        // But we also update immediately for instant UI feedback:
        prefs.edit().putBoolean("is_tracking", !isTracking).commit()
        updateAllWidgets(context)
    }
}
