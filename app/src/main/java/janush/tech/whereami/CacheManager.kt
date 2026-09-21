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
            val addedCount: Int,
            val alreadyCachedCount: Int,
            val total: Int
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

    private fun updateNotification(passName: String, current: Int, total: Int, isPaused: Boolean) {
        ensurePrefetchChannel()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager ?: return
        val builder = androidx.core.app.NotificationCompat.Builder(context, NOTIF_CHANNEL_PREFETCH)
            .setContentTitle(if (isPaused) "WhereAmI Pre-fetch (Paused)" else "WhereAmI Offline Pre-fetch")
            .setContentText("$passName: $current of $total")
            .setSmallIcon(R.drawable.ic_stat_location)
            .setProgress(total, current, false)
            .setOngoing(!isPaused)
            .setOnlyAlertOnce(true)
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
                    _prefetchState.value = PrefetchState.Completed(0, 0, 0)
                    return@launch
                }

                var totalAdded = 0
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
                _prefetchState.value = PrefetchState.Running(1, "Pass 1: Macro Coverage (~50m)", pass1Current, pass1Total, totalAdded)
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
                        locManager.resolveMultiLanguageData(pt.latitude, pt.longitude)
                        totalAdded++
                    }

                    pass1Current++
                    _prefetchState.value = PrefetchState.Running(1, "Pass 1: Macro Coverage (~50m)", pass1Current, pass1Total, totalAdded)
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
                _prefetchState.value = PrefetchState.Running(2, "Pass 2: Fine Precision (~15m)", pass2Current, pass2Total, totalAdded)
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
                        locManager.resolveMultiLanguageData(pt.latitude, pt.longitude)
                        totalAdded++
                    }

                    pass2Current++
                    _prefetchState.value = PrefetchState.Running(2, "Pass 2: Fine Precision (~15m)", pass2Current, pass2Total, totalAdded)
                    updateNotification("Pass 2: Fine Precision", pass2Current, pass2Total, false)

                    kotlinx.coroutines.delay(1500L)
                }

                val finalCached = allUnique15mPoints.values.count { pt -> spatialHelper.isCached(pt.latitude, pt.longitude) }
                _prefetchState.value = PrefetchState.Completed(totalAdded, finalCached, totalPointsInTrips)
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
}
