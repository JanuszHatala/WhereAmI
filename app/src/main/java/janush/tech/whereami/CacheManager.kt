package janush.tech.whereami

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.osmdroid.config.Configuration
import java.io.File
import java.util.Locale
import org.json.JSONObject

/**
 * Manages all offline caches (Map Tiles, Locality Boundaries, Persistent Spatial Reverse-Geocache)
 * with strict battery-safety controls:
 * - When not charging, background pre-fetching is blocked by default to prevent battery drain.
 * - Mobile data pre-fetching is blocked by default to prevent consuming user cellular data.
 * - Zero wake-locks or background polling when there is nothing to process.
 */
class CacheManager private constructor(private val context: Context) {

    companion object {
        const val PREFS_NAME = "where_am_i_cache_prefs"
        const val KEY_ALLOW_ON_BATTERY = "allow_prefetch_on_battery"
        const val KEY_ALLOW_MOBILE_DATA = "allow_prefetch_mobile_data"
        const val KEY_AUTO_START_ON_WIFI = "auto_start_on_wifi"
        const val KEY_AUTO_START_ON_CHARGER = "auto_start_on_charger"
        const val KEY_NOTIFY_WHEN_AVAILABLE = "notify_when_available"
        const val NOTIF_AVAILABLE_ID = 3002
        const val NOTIF_CHANNEL_AVAILABLE = "cache_updates_available"
        const val ACTION_OPEN_CACHE_MANAGER = "janush.tech.whereami.ACTION_OPEN_CACHE_MANAGER"
        const val EXTRA_OPEN_CACHE_MANAGER = "extra_open_cache_manager"

        @Volatile
        private var instance: CacheManager? = null

        fun getInstance(context: Context): CacheManager {
            return instance ?: synchronized(this) {
                instance ?: CacheManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var allowOnBattery: Boolean
        get() = prefs.getBoolean(KEY_ALLOW_ON_BATTERY, false)
        set(value) = prefs.edit().putBoolean(KEY_ALLOW_ON_BATTERY, value).apply()

    var allowMobileData: Boolean
        get() = prefs.getBoolean(KEY_ALLOW_MOBILE_DATA, false)
        set(value) = prefs.edit().putBoolean(KEY_ALLOW_MOBILE_DATA, value).apply()

    var autoStartOnWifi: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START_ON_WIFI, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_START_ON_WIFI, value).apply()

    var autoStartOnCharger: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START_ON_CHARGER, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_START_ON_CHARGER, value).apply()

    var notifyWhenAvailable: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_WHEN_AVAILABLE, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFY_WHEN_AVAILABLE, value).apply()

    // ── Metric Computations ───────────────────────────────────────────────────

    suspend fun getTileCacheBytes(): Long = withContext(Dispatchers.IO) {
        val tileDir = Configuration.getInstance().osmdroidTileCache
        computeDirSize(tileDir)
    }

    suspend fun getBoundaryCacheBytes(): Long = withContext(Dispatchers.IO) {
        val boundaryDir = File(context.cacheDir, "boundaries")
        computeDirSize(boundaryDir)
    }

    suspend fun getBoundaryCacheCount(): Int = withContext(Dispatchers.IO) {
        val boundaryDir = File(context.cacheDir, "boundaries")
        if (boundaryDir.exists()) boundaryDir.listFiles()?.size ?: 0 else 0
    }

    suspend fun getSpatialCacheBytes(): Long = withContext(Dispatchers.IO) {
        SpatialCacheHelper.getInstance(context).getStorageBytes()
    }

    suspend fun getSpatialCacheCount(): Long = withContext(Dispatchers.IO) {
        SpatialCacheHelper.getInstance(context).getRecordCount()
    }

    private fun computeDirSize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        var size = 0L
        dir.walkTopDown().forEach { file ->
            if (file.isFile) size += file.length()
        }
        return size
    }

    // ── Cache Clearing Actions ────────────────────────────────────────────────

    suspend fun clearTileCache(): Unit = withContext(Dispatchers.IO) {
        val tileDir = Configuration.getInstance().osmdroidTileCache
        tileDir?.walkBottomUp()?.forEach { file ->
            if (file != tileDir) file.delete()
        }
    }

    suspend fun clearBoundaryCache(): Unit = withContext(Dispatchers.IO) {
        val boundaryDir = File(context.cacheDir, "boundaries")
        boundaryDir.walkBottomUp().forEach { file ->
            if (file != boundaryDir) file.delete()
        }
        BoundaryHelper.clearMemoryCache()
    }

    suspend fun clearSpatialCache(): Unit = withContext(Dispatchers.IO) {
        SpatialCacheHelper.getInstance(context).clearAll()
    }

    // ── Battery & Connectivity Safety Checks ──────────────────────────────────

    fun isDeviceCharging(): Boolean {
        try {
            if (AppStateManager.getInstance(context).isCharging.value) return true
        } catch (_: Exception) {}
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        if (bm?.isCharging == true) return true
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = context.registerReceiver(null, filter)
        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
    }

    fun isUnmeteredWifi(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    // ── Persistent Route Corridor Pre-fetch Engine ───────────────────────────

    sealed class PrefetchState {
        object Idle : PrefetchState()
        data class Running(
            val pass: Int,
            val passName: String,
            val current: Int,
            val total: Int,
            val added: Int
        ) : PrefetchState()
        data class Paused(
            val pass: Int,
            val passName: String,
            val current: Int,
            val total: Int,
            val added: Int
        ) : PrefetchState()
        data class Completed(
            val addedRoutes: Int,
            val addedBoundaries: Int
        ) : PrefetchState()
        data class Blocked(val reason: String) : PrefetchState()
        data class Error(val message: String) : PrefetchState()
    }

    private val managerJob = kotlinx.coroutines.SupervisorJob()
    private val managerScope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + managerJob)
    private var prefetchJob: kotlinx.coroutines.Job? = null
    @Volatile private var isPrefetchPaused: Boolean = false

    private val _prefetchState = kotlinx.coroutines.flow.MutableStateFlow<PrefetchState>(PrefetchState.Idle)
    val prefetchState: StateFlow<PrefetchState> = _prefetchState.asStateFlow()

    data class CacheDeficit(val missingRoutes: Int, val missingBoundaries: Int)
    private val _cacheDeficit = kotlinx.coroutines.flow.MutableStateFlow<CacheDeficit?>(null)
    val cacheDeficit: StateFlow<CacheDeficit?> = _cacheDeficit.asStateFlow()

    data class BoundaryTarget(
        val name: String,
        val countryCode: String,
        val municipality: String? = null,
        val lat: Double? = null,
        val lng: Double? = null
    )

    fun getDistinctBoundaryTargets(): List<BoundaryTarget> {
        val targets = mutableMapOf<String, BoundaryTarget>()
        val junkNames = setOf("likwidacja", "serwis", "powiat", "koło", "unknown city", "--")

        // 1. From Trips placesVisited
        try {
            val dbHelper = TripDatabaseHelper(context)
            val allTrips = dbHelper.getAllTrips()
            for (trip in allTrips) {
                for (p in trip.placesVisited) {
                    val name = p.placeName.trim()
                    if (name.isBlank() || junkNames.contains(name.lowercase(Locale.ROOT)) || name.contains(",") || name.contains("°")) {
                        continue
                    }
                    var mun: String? = null
                    if (!p.hierarchySubtitle.isNullOrBlank()) {
                        val parts = p.hierarchySubtitle.split("•", ",")
                        for (part in parts) {
                            val trimmed = part.trim()
                            if (trimmed.startsWith("gm.", ignoreCase = true) || trimmed.startsWith("gmina", ignoreCase = true) || trimmed.startsWith("okres", ignoreCase = true)) {
                                mun = trimmed
                                break
                            }
                        }
                    }
                    var cc = "pl"
                    if (p.latitude in 47.7..49.65 && p.longitude in 16.8..22.6) {
                        if (p.latitude < 49.38 && (p.longitude in 18.8..20.2)) {
                            cc = "sk"
                        }
                    }
                    val key = "${name.lowercase(Locale.ROOT)}_$cc"
                    if (!targets.containsKey(key)) {
                        targets[key] = BoundaryTarget(
                            name = name,
                            countryCode = cc,
                            municipality = mun,
                            lat = p.latitude,
                            lng = p.longitude
                        )
                    }
                }
            }
        } catch (_: Exception) {}

        // 2. From Spatial Cache
        try {
            val spatialHelper = SpatialCacheHelper.getInstance(context)
            val db = spatialHelper.readableDatabase
            val cursor = db.rawQuery(
                "SELECT city, latitude, longitude, native_json FROM spatial_cache WHERE city IS NOT NULL AND city != 'Unknown City' AND city != '--' GROUP BY city",
                null
            )
            while (cursor.moveToNext()) {
                val name = cursor.getString(0)?.trim() ?: continue
                if (name.isBlank() || junkNames.contains(name.lowercase(Locale.ROOT)) || name.contains(",") || name.contains("°")) {
                    continue
                }
                val lat = cursor.getDouble(1)
                val lng = cursor.getDouble(2)
                val nativeJson = cursor.getString(3)
                var cc = "pl"
                var mun: String? = null
                if (!nativeJson.isNullOrBlank()) {
                    try {
                        val obj = JSONObject(nativeJson)
                        cc = obj.optString("countryCode", "pl").takeIf { it.isNotBlank() }?.lowercase(Locale.ROOT) ?: "pl"
                        mun = obj.optString("gmina").takeIf { it.isNotBlank() }
                    } catch (_: Exception) {}
                }
                val key = "${name.lowercase(Locale.ROOT)}_$cc"
                if (!targets.containsKey(key)) {
                    targets[key] = BoundaryTarget(
                        name = name,
                        countryCode = cc,
                        municipality = mun,
                        lat = lat,
                        lng = lng
                    )
                }
            }
            cursor.close()
        } catch (_: Exception) {}

        return targets.values.toList()
    }

    private val NOTIF_CHANNEL_PREFETCH = "prefetch_channel"
    private val NOTIF_PREFETCH_ID = 3001
    @Volatile private var lastNotifPostTimeMs = 0L

    private fun ensurePrefetchChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                NOTIF_CHANNEL_PREFETCH,
                "Offline Data Pre-fetch",
                android.app.NotificationManager.IMPORTANCE_LOW
            )
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
            manager?.createNotificationChannel(channel)
        }
    }

    private fun formatActionTitle(text: String, colorHex: String): CharSequence {
        return androidx.core.text.HtmlCompat.fromHtml("<font color='$colorHex'>$text</font>", androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY)
    }

    private fun updateNotification(passName: String, current: Int, total: Int, isPaused: Boolean, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && !isPaused && current < total && (now - lastNotifPostTimeMs < 2500L)) {
            return
        }
        lastNotifPostTimeMs = now

        ensurePrefetchChannel()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager ?: return

        val contentIntent = android.app.PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                action = ACTION_OPEN_CACHE_MANAGER
                putExtra(EXTRA_OPEN_CACHE_MANAGER, true)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val pauseResumeIntent = if (isPaused) {
            android.app.PendingIntent.getBroadcast(
                context,
                101,
                Intent(context, CacheNotificationReceiver::class.java).apply {
                    action = CacheNotificationReceiver.ACTION_RESUME
                },
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            android.app.PendingIntent.getBroadcast(
                context,
                102,
                Intent(context, CacheNotificationReceiver::class.java).apply {
                    action = CacheNotificationReceiver.ACTION_PAUSE
                },
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
        }

        val stopIntent = android.app.PendingIntent.getBroadcast(
            context,
            103,
            Intent(context, CacheNotificationReceiver::class.java).apply {
                action = CacheNotificationReceiver.ACTION_STOP
            },
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val percent = if (total > 0) (current.toLong() * 100 / total).toInt().coerceIn(0, 100) else 0

        val title = if (isPaused) "WhereAmI Pre-fetch (Paused) - $percent%" else "WhereAmI Pre-fetch - $percent%"
        val contentText = "$passName: $current / $total"

        val pauseResumeActionTitle = androidx.core.text.HtmlCompat.fromHtml(
            if (isPaused) "<font color='#10B981'>▶ Resume</font>" else "<font color='#F59E0B'>⏸ Pause</font>",
            androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY
        )
        val stopActionTitle = androidx.core.text.HtmlCompat.fromHtml(
            "<font color='#EF4444'>⏹ Stop</font>",
            androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY
        )

        val builder = androidx.core.app.NotificationCompat.Builder(context, NOTIF_CHANNEL_PREFETCH)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_stat_location)
            .setContentIntent(contentIntent)
            .setProgress(total, current, false)
            .setOngoing(!isPaused)
            .setOnlyAlertOnce(true)
            .addAction(0, pauseResumeActionTitle, pauseResumeIntent)
            .addAction(0, stopActionTitle, stopIntent)
        manager.notify(NOTIF_PREFETCH_ID, builder.build())
    }

    private fun dismissNotification() {
        lastNotifPostTimeMs = 0L
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
        manager?.cancel(NOTIF_PREFETCH_ID)
    }

    fun startPrefetch(isUserInitiated: Boolean = true) {
        if (_prefetchState.value is PrefetchState.Running) return

        if (!isUserInitiated) {
            val isRecording = TripManager.getInstance(context).activeTrip.value != null
            val isLiveSharing = LiveSharingManager.getInstance(context).currentSession.value?.isActive == true
            if (isRecording || isLiveSharing) {
                TelemetryLogger.log("CACHE", "Auto-prefetch suppressed during active trip recording or live sharing.")
                return
            }
        }

        val notifManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
        notifManager?.cancel(NOTIF_AVAILABLE_ID)

        if (!allowOnBattery && !isDeviceCharging()) {
            _prefetchState.value = PrefetchState.Blocked("Device is not charging. Connect charger or enable 'Allow on battery'.")
            return
        }
        if (!allowMobileData && !isUnmeteredWifi()) {
            _prefetchState.value = PrefetchState.Blocked("Not on Wi-Fi. Connect to Wi-Fi or enable 'Allow on mobile data'.")
            return
        }

        isPrefetchPaused = false
        prefetchJob?.cancel()

        prefetchJob = managerScope.launch {
            try {
                val dbHelper = TripDatabaseHelper(context)
                val allTrips = dbHelper.getAllTrips()
                val spatialHelper = SpatialCacheHelper.getInstance(context)
                val locManager = LocationManager.getInstance(context)

                // 1. Ensure legacy 3-decimal cache keys are migrated so actual trip data is recognized
                spatialHelper.migrateLegacyKeys()

                // Calculate total unique points in all trips for accurate reporting
                val allUnique15mPoints = mutableMapOf<String, org.osmdroid.util.GeoPoint>()
                for (trip in allTrips) {
                    for (pt in trip.points) {
                        val key = SpatialCacheHelper.toGridKey(pt.latitude, pt.longitude)
                        if (!allUnique15mPoints.containsKey(key)) {
                            allUnique15mPoints[key] = pt
                        }
                    }
                }

                if (allUnique15mPoints.isEmpty()) {
                    _prefetchState.value = PrefetchState.Completed(0, 0)
                    return@launch
                }

                var addedRoutes = 0; var addedBoundaries = 0
                val totalPointsInTrips = allUnique15mPoints.size

                // ── PASS 1: Macro Corridor Coverage (~50m spacing) ───────────────────
                // Guarantees zero blank offline spots along all recorded route corridors rapidly
                val pass1Candidates = mutableListOf<org.osmdroid.util.GeoPoint>()
                for (trip in allTrips) {
                    var lastPt: org.osmdroid.util.GeoPoint? = null
                    for (pt in trip.points) {
                        if (lastPt == null || lastPt.distanceToAsDouble(pt) >= 50.0) {
                            lastPt = pt
                            if (!spatialHelper.hasNearbyCache(pt.latitude, pt.longitude, 40.0)) {
                                pass1Candidates.add(pt)
                            }
                        }
                    }
                }

                val pass1Total = pass1Candidates.size
                var pass1Current = 0
                _prefetchState.value = PrefetchState.Running(1, "Pass 1: Macro Coverage (~50m)", pass1Current, pass1Total, addedRoutes)
                updateNotification("Pass 1: Macro Coverage", pass1Current, pass1Total, false)

                for (pt in pass1Candidates) {
                    while (isPrefetchPaused) {
                        kotlinx.coroutines.delay(500L)
                    }

                    if (!allowOnBattery && !isDeviceCharging()) {
                        _prefetchState.value = PrefetchState.Blocked("Charging disconnected. Connect charger to continue.")
                        dismissNotification()
                        return@launch
                    }

                    // Skip if now cached by an adjacent query
                    if (!spatialHelper.hasNearbyCache(pt.latitude, pt.longitude, 40.0)) {
                                  locManager.resolveMultiLanguageData(pt.latitude, pt.longitude, forceCache = true)
                                  addedRoutes++
                                  kotlinx.coroutines.delay(500L)
                              }

                    pass1Current++
                    _prefetchState.value = PrefetchState.Running(1, "Pass 1: Macro Coverage (~50m)", pass1Current, pass1Total, addedRoutes)
                    updateNotification("Pass 1: Macro Coverage", pass1Current, pass1Total, false)

                    kotlinx.coroutines.delay(1500L)
                }

                // ── PASS 2: Fine Precision Down to ~15m Grid Resolution ──────────────
                // Fills in intermediate gaps and house numbers along the corridors
                val pass2Candidates = allUnique15mPoints.values.filter { pt ->
                    !spatialHelper.isCached(pt.latitude, pt.longitude)
                }

                val pass2Total = pass2Candidates.size
                var pass2Current = 0
                _prefetchState.value = PrefetchState.Running(2, "Pass 2: Fine Precision (~15m)", pass2Current, pass2Total, addedRoutes)
                updateNotification("Pass 2: Fine Precision", pass2Current, pass2Total, false)

                for (pt in pass2Candidates) {
                    while (isPrefetchPaused) {
                        kotlinx.coroutines.delay(500L)
                    }

                    if (!allowOnBattery && !isDeviceCharging()) {
                        _prefetchState.value = PrefetchState.Blocked("Charging disconnected. Connect charger to continue.")
                        dismissNotification()
                        return@launch
                    }

                    if (!spatialHelper.isCached(pt.latitude, pt.longitude)) {
                          locManager.resolveMultiLanguageData(pt.latitude, pt.longitude, forceCache = true)
                          addedRoutes++
                          kotlinx.coroutines.delay(500L)
                      }

                    pass2Current++
                    _prefetchState.value = PrefetchState.Running(2, "Pass 2: Fine Precision (~15m)", pass2Current, pass2Total, addedRoutes)
                    updateNotification("Pass 2: Fine Precision", pass2Current, pass2Total, false)

                    kotlinx.coroutines.delay(1500L)
                }

                // PASS 3: Administrative Boundaries 
                BoundaryHelper.clearMemoryCache()
                val boundaryTargets = getDistinctBoundaryTargets()
                val missingTargets = boundaryTargets.filter { !BoundaryHelper.hasBoundary(context, it.name, it.countryCode) }
                val pass3Total = missingTargets.size
                var pass3Current = 0
                _prefetchState.value = PrefetchState.Running(3, "Pass 3: Boundaries", pass3Current, pass3Total, addedRoutes + addedBoundaries)
                updateNotification("Pass 3: Boundaries", pass3Current, pass3Total, false)

                for (target in missingTargets) {
                    while (isPrefetchPaused) { kotlinx.coroutines.delay(500L) }
                    if (!allowOnBattery && !isDeviceCharging()) {
                        _prefetchState.value = PrefetchState.Blocked("Charging disconnected.")
                        dismissNotification()
                        return@launch
                    }

                    val boundary = BoundaryHelper.getLocalityBoundary(
                        context = context,
                        cityName = target.name,
                        countryCode = target.countryCode,
                        fallbackMunicipality = target.municipality,
                        geoPoint = if (target.lat != null && target.lng != null) org.osmdroid.util.GeoPoint(target.lat, target.lng) else null
                    )
                    if (boundary != null && boundary.isNotEmpty()) {
                        addedBoundaries++
                    }
                    pass3Current++
                    _prefetchState.value = PrefetchState.Running(3, "Pass 3: Boundaries", pass3Current, pass3Total, addedRoutes + addedBoundaries)
                    updateNotification("Pass 3: Boundaries", pass3Current, pass3Total, false)
                    kotlinx.coroutines.delay(300L)
                }

                val finalCached = allUnique15mPoints.values.count { pt -> spatialHelper.isCached(pt.latitude, pt.longitude) }
                _prefetchState.value = PrefetchState.Completed(addedRoutes, addedBoundaries)
                dismissNotification()
            } catch (_: kotlinx.coroutines.CancellationException) {
                dismissNotification()
            } catch (e: Exception) {
                _prefetchState.value = PrefetchState.Error(e.message ?: "Unknown pre-fetch error")
                dismissNotification()
            }
        }
    }

    fun pausePrefetch() {
        isPrefetchPaused = true
        val cur = _prefetchState.value
        if (cur is PrefetchState.Running) {
            _prefetchState.value = PrefetchState.Paused(cur.pass, cur.passName, cur.current, cur.total, cur.added)
            updateNotification(cur.passName, cur.current, cur.total, true)
        }
    }

    fun resumePrefetch() {
        isPrefetchPaused = false
        val cur = _prefetchState.value
        if (cur is PrefetchState.Paused) {
            _prefetchState.value = PrefetchState.Running(cur.pass, cur.passName, cur.current, cur.total, cur.added)
            updateNotification(cur.passName, cur.current, cur.total, false)
        }
    }

    fun cancelPrefetch() {
        isPrefetchPaused = false
        prefetchJob?.cancel()
        prefetchJob = null
        _prefetchState.value = PrefetchState.Idle
        dismissNotification()
    }

    fun fetchMissingBoundaries(isUserInitiated: Boolean = true) {
        if (_prefetchState.value is PrefetchState.Running) return
        
        if (!isUserInitiated) {
            val isRecording = TripManager.getInstance(context).activeTrip.value != null
            val isLiveSharing = LiveSharingManager.getInstance(context).currentSession.value?.isActive == true
            if (isRecording || isLiveSharing) {
                TelemetryLogger.log("CACHE", "Auto boundary prefetch suppressed during active trip recording or live sharing.")
                return
            }
        }

        if (!allowOnBattery && !isDeviceCharging()) {
            _prefetchState.value = PrefetchState.Blocked("Device is not charging.")
            return
        }
        if (!allowMobileData && !isUnmeteredWifi()) {
            _prefetchState.value = PrefetchState.Blocked("Not on Wi-Fi.")
            return
        }

        isPrefetchPaused = false
        prefetchJob?.cancel()

        prefetchJob = managerScope.launch(Dispatchers.IO) {
            try {
                BoundaryHelper.clearMemoryCache()
                val boundaryTargets = getDistinctBoundaryTargets()
                val missingTargets = boundaryTargets.filter { !BoundaryHelper.hasBoundary(context, it.name, it.countryCode) }
                val total = missingTargets.size
                var current = 0
                var added = 0

                _prefetchState.value = PrefetchState.Running(3, "Pass 3: Administrative Boundaries", current, total, added)
                updateNotification("Pass 3: Boundaries", current, total, false)

                for (target in missingTargets) {
                    while (isPrefetchPaused) {
                        kotlinx.coroutines.delay(500L)
                    }

                    if (!allowOnBattery && !isDeviceCharging()) {
                        _prefetchState.value = PrefetchState.Blocked("Charging disconnected.")
                        dismissNotification()
                        return@launch
                    }

                    val boundary = BoundaryHelper.getLocalityBoundary(
                        context = context,
                        cityName = target.name,
                        countryCode = target.countryCode,
                        fallbackMunicipality = target.municipality,
                        geoPoint = if (target.lat != null && target.lng != null) org.osmdroid.util.GeoPoint(target.lat, target.lng) else null
                    )
                    if (boundary != null && boundary.isNotEmpty()) {
                        added++
                    }

                    current++
                    _prefetchState.value = PrefetchState.Running(3, "Pass 3: Administrative Boundaries", current, total, added)
                    updateNotification("Pass 3: Boundaries", current, total, false)
                    kotlinx.coroutines.delay(300L)
                }

                _prefetchState.value = PrefetchState.Completed(0, added)
                dismissNotification()
                calculateCacheDeficit()
            } catch (e: Exception) {
                _prefetchState.value = PrefetchState.Error(e.message ?: "Unknown pre-fetch error")
                dismissNotification()
            }
        }
    }

    fun onTripFinished() {
        managerScope.launch {
            try {
                kotlinx.coroutines.delay(2500L)
                calculateCacheDeficit()
            } catch (_: Exception) {}
        }
    }

    fun calculateCacheDeficit() {
        if (_prefetchState.value !is PrefetchState.Idle && _prefetchState.value !is PrefetchState.Completed) return
        // Immediately transition to Idle so the UI shows the Pre-fetch button and deficit counter
        _prefetchState.value = PrefetchState.Idle
        managerScope.launch {
            try {
                val dbHelper = TripDatabaseHelper(context)
                val allTrips = dbHelper.getAllTrips()
                val spatialHelper = SpatialCacheHelper.getInstance(context)

                // Ensure keys are migrated first
                spatialHelper.migrateLegacyKeys()

                val allUnique15mPoints = mutableMapOf<String, org.osmdroid.util.GeoPoint>()
                for (trip in allTrips) {
                    for (pt in trip.points) {
                        val key = SpatialCacheHelper.toGridKey(pt.latitude, pt.longitude)
                        if (!allUnique15mPoints.containsKey(key)) {
                            allUnique15mPoints[key] = org.osmdroid.util.GeoPoint(pt.latitude, pt.longitude)
                        }
                    }
                }

                // A point is missing if it's NOT explicitly cached.
                val missingRoutes = allUnique15mPoints.values.count { pt -> 
                    !spatialHelper.isCached(pt.latitude, pt.longitude) 
                }
                
                val boundaryTargets = getDistinctBoundaryTargets()
                val missingBoundaries = boundaryTargets.count { !BoundaryHelper.hasBoundary(context, it.name, it.countryCode) }
                val deficit = CacheDeficit(missingRoutes, missingBoundaries)
                _cacheDeficit.value = deficit

                val totalMissing = missingRoutes + missingBoundaries
                if (totalMissing > 0 && _prefetchState.value is PrefetchState.Idle) {
                    val isRecording = TripManager.getInstance(context).activeTrip.value != null
                    val isLiveSharing = LiveSharingManager.getInstance(context).currentSession.value?.isActive == true
                    if (isRecording || isLiveSharing) {
                        return@launch
                    }
                    val canAutoWifi = autoStartOnWifi && isUnmeteredWifi()
                    val canAutoCharger = autoStartOnCharger && isDeviceCharging()
                    if (canAutoWifi || canAutoCharger) {
                        TelemetryLogger.log("CACHE", "Auto-starting prefetch: Wi-Fi=$canAutoWifi, Charger=$canAutoCharger, Missing=$totalMissing")
                        startPrefetch(isUserInitiated = false)
                    } else if (notifyWhenAvailable) {
                        postUpdatesAvailableNotification(deficit)
                    }
                }
            } catch (e: Exception) {
                _cacheDeficit.value = null
            }
        }
    }

    /**
     * Posts a dismissible notification alerting the user that map cache updates are available,
     * equipped with action buttons to Start Download directly or Open the Cache Manager dialog.
     */
    fun postUpdatesAvailableNotification(deficit: CacheDeficit) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                NOTIF_CHANNEL_AVAILABLE,
                "Map Cache Updates",
                android.app.NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifies when offline map cache updates are available."
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
            manager?.createNotificationChannel(channel)
        }

        val startIntent = android.app.PendingIntent.getBroadcast(
            context,
            201,
            Intent(context, CacheNotificationReceiver::class.java).apply {
                action = CacheNotificationReceiver.ACTION_START
            },
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = android.app.PendingIntent.getActivity(
            context,
            202,
            Intent(context, MainActivity::class.java).apply {
                action = ACTION_OPEN_CACHE_MANAGER
                putExtra(EXTRA_OPEN_CACHE_MANAGER, true)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val total = deficit.missingRoutes + deficit.missingBoundaries
        val builder = androidx.core.app.NotificationCompat.Builder(context, NOTIF_CHANNEL_AVAILABLE)
            .setContentTitle("WhereAmI — Map Cache Updates Available")
            .setContentText("$total items need updating (${deficit.missingRoutes} routes, ${deficit.missingBoundaries} boundaries).")
            .setSmallIcon(R.drawable.ic_stat_location)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .addAction(0, "▶ Start Download", startIntent)
            .addAction(0, "Open Cache", openIntent)

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
        manager?.notify(NOTIF_AVAILABLE_ID, builder.build())
    }
}









