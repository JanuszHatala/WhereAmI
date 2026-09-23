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

    private val NOTIF_CHANNEL_PREFETCH = "prefetch_channel"
    private val NOTIF_PREFETCH_ID = 3001

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

    private fun updateNotification(passName: String, current: Int, total: Int, isPaused: Boolean) {
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
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
        manager?.cancel(NOTIF_PREFETCH_ID)
    }

    fun startPrefetch() {
        if (_prefetchState.value is PrefetchState.Running) return

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
                        val result = locManager.resolveMultiLanguageData(pt.latitude, pt.longitude)
                        if (result.pl.city == "Unknown City") {
                            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                            val actNw = cm.activeNetwork
                            if (actNw != null) {
                                val caps = cm.getNetworkCapabilities(actNw)
                                if (caps != null && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                                    try {
                                        spatialHelper.put(pt.latitude, pt.longitude, result)
                                    } catch (_: Exception) {}
                                }
                            }
                        }
                        addedRoutes++
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
                        val result = locManager.resolveMultiLanguageData(pt.latitude, pt.longitude)
                        
                        // If we are online but the place is truly unknown, force cache it so we stop looping forever.
                        if (result.pl.city == "Unknown City") {
                            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                            val actNw = cm.activeNetwork
                            if (actNw != null) {
                                val caps = cm.getNetworkCapabilities(actNw)
                                if (caps != null && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                                    try {
                                        spatialHelper.put(pt.latitude, pt.longitude, result)
                                    } catch (_: Exception) {}
                                }
                            }
                        }
                        addedRoutes++
                    }

                    pass2Current++
                    _prefetchState.value = PrefetchState.Running(2, "Pass 2: Fine Precision (~15m)", pass2Current, pass2Total, addedRoutes)
                    updateNotification("Pass 2: Fine Precision", pass2Current, pass2Total, false)

                    kotlinx.coroutines.delay(1500L)
                }

                //  PASS 3: Administrative Boundaries 
                BoundaryHelper.clearMemoryCache()
                val dbCursor = spatialHelper.readableDatabase.rawQuery("SELECT DISTINCT city FROM spatial_cache WHERE city IS NOT NULL AND city != 'Unknown City'", null)
                val cities = mutableListOf<String>()
                while (dbCursor.moveToNext()) {
                    val c = dbCursor.getString(0)
                    if (c.isNotBlank()) cities.add(c)
                }
                dbCursor.close()

                val pass3Total = cities.size
                var pass3Current = 0
                _prefetchState.value = PrefetchState.Running(3, "Pass 3: Boundaries", pass3Current, pass3Total, addedRoutes)
                updateNotification("Pass 3: Boundaries", pass3Current, pass3Total, false)

                for (city in cities) {
                    while (isPrefetchPaused) { kotlinx.coroutines.delay(500L) }
                    if (!allowOnBattery && !isDeviceCharging()) {
                        _prefetchState.value = PrefetchState.Blocked("Charging disconnected.")
                        dismissNotification()
                        return@launch
                    }

                    val cleanCity = city.trim().lowercase(java.util.Locale.ROOT)
                    val cleanKey = "_pl".replace(Regex("[^a-zA-Z0-9_-]"), "_")
                    val file = java.io.File(context.cacheDir, "boundaries/$cleanKey.json")
                    if (!file.exists()) {
                        BoundaryHelper.getLocalityBoundary(context, cityName = city, countryCode = "pl")
                        addedRoutes++
                    }
                    pass3Current++
                    _prefetchState.value = PrefetchState.Running(3, "Pass 3: Boundaries", pass3Current, pass3Total, addedRoutes)
                    updateNotification("Pass 3: Boundaries", pass3Current, pass3Total, false)
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

    fun fetchMissingBoundaries() {
        if (_prefetchState.value is PrefetchState.Running) return
        
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
                val spatialHelper = SpatialCacheHelper.getInstance(context)
                val db = spatialHelper.readableDatabase
                val cursor = db.rawQuery("SELECT DISTINCT city FROM spatial_cache WHERE city IS NOT NULL AND city != 'Unknown City'", null)
                val cities = mutableListOf<String>()
                while (cursor.moveToNext()) {
                    val c = cursor.getString(0)
                    if (c.isNotBlank()) cities.add(c)
                }
                cursor.close()

                val total = cities.size
                var current = 0
                var added = 0

                _prefetchState.value = PrefetchState.Running(3, "Pass 3: Administrative Boundaries", current, total, added)
                updateNotification("Pass 3: Boundaries", current, total, false)

                for (city in cities) {
                    while (isPrefetchPaused) {
                        kotlinx.coroutines.delay(500L)
                    }

                    if (!allowOnBattery && !isDeviceCharging()) {
                        _prefetchState.value = PrefetchState.Blocked("Charging disconnected.")
                        dismissNotification()
                        return@launch
                    }

                    val cleanCity = city.trim().lowercase(java.util.Locale.ROOT)
                    val cleanKey = "${cleanCity}_pl".replace(Regex("[^a-zA-Z0-9_-]"), "_")
                    val file = java.io.File(context.cacheDir, "boundaries/$cleanKey.json")
                    
                    if (!file.exists()) {
                        BoundaryHelper.getLocalityBoundary(context, cityName = city, countryCode = "pl")
                        added++
                    }

                    current++
                    _prefetchState.value = PrefetchState.Running(3, "Pass 3: Administrative Boundaries", current, total, added)
                    updateNotification("Pass 3: Boundaries", current, total, false)
                }

                _prefetchState.value = PrefetchState.Completed(0, added)
                dismissNotification()
            } catch (e: Exception) {
                _prefetchState.value = PrefetchState.Error(e.message ?: "Unknown pre-fetch error")
                dismissNotification()
            }
        }
    }

    fun calculateCacheDeficit() {
        if (_prefetchState.value !is PrefetchState.Idle && _prefetchState.value !is PrefetchState.Completed) return
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
                
                val dbCursor = spatialHelper.readableDatabase.rawQuery("SELECT DISTINCT city FROM spatial_cache WHERE city IS NOT NULL AND city != 'Unknown City'", null)
                var missingBoundaries = 0
                while (dbCursor.moveToNext()) {
                    val city = dbCursor.getString(0)
                    if (city.isNotBlank()) {
                        val cleanCity = city.trim().lowercase(java.util.Locale.ROOT)
                        val cleanKey = "${cleanCity}_pl".replace(Regex("[^a-zA-Z0-9_-]"), "_")
                        val file = java.io.File(context.cacheDir, "boundaries/$cleanKey.json")
                        if (!file.exists()) {
                            missingBoundaries++
                        }
                    }
                }
                dbCursor.close()

                _cacheDeficit.value = CacheDeficit(missingRoutes, missingBoundaries)
            } catch (e: Exception) {
                _cacheDeficit.value = null
            }
        }
    }
}










