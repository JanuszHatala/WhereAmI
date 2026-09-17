package janush.tech.whereami

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    // ── Battery-Safe Route Corridor Pre-fetcher ───────────────────────────────

    sealed class PreloadResult {
        object BlockedByBattery : PreloadResult()
        object BlockedByNetwork : PreloadResult()
        data class Completed(val addedCount: Int, val alreadyCachedCount: Int) : PreloadResult()
        data class Failed(val error: String) : PreloadResult()
    }

    suspend fun prefetchTripCorridors(
        onProgress: (current: Int, total: Int) -> Unit
    ): PreloadResult = withContext(Dispatchers.IO) {
        // Strict battery gating: do not run unless phone is charging OR user explicitly authorized battery usage
        if (!allowOnBattery && !isDeviceCharging()) {
            return@withContext PreloadResult.BlockedByBattery
        }

        // Strict data gating: do not run unless on Wi-Fi OR user explicitly authorized mobile data
        if (!allowMobileData && !isUnmeteredWifi()) {
            return@withContext PreloadResult.BlockedByNetwork
        }

        try {
            val dbHelper = TripDatabaseHelper(context)
            val allTrips = dbHelper.getAllTrips()
            val spatialHelper = SpatialCacheHelper.getInstance(context)
            val locManager = LocationManager.getInstance(context)

            // Extract unique grid points along all recorded trips
            val uniquePoints = mutableMapOf<String, org.osmdroid.util.GeoPoint>()
            for (trip in allTrips) {
                for (pt in trip.points) {
                    val key = SpatialCacheHelper.toGridKey(pt.latitude, pt.longitude)
                    if (!uniquePoints.containsKey(key)) {
                        uniquePoints[key] = pt
                    }
                }
            }

            val totalUnique = uniquePoints.size
            if (totalUnique == 0) {
                return@withContext PreloadResult.Completed(addedCount = 0, alreadyCachedCount = 0)
            }

            val uncachedPoints = uniquePoints.filter { (key, pt) ->
                spatialHelper.get(pt.latitude, pt.longitude) == null
            }.values.toList()

            val alreadyCached = totalUnique - uncachedPoints.size
            var added = 0

            for ((index, pt) in uncachedPoints.withIndex()) {
                // Check if charging was disconnected mid-run
                if (!allowOnBattery && !isDeviceCharging()) {
                    return@withContext PreloadResult.BlockedByBattery
                }

                locManager.resolveMultiLanguageData(pt.latitude, pt.longitude)
                added++
                onProgress(index + 1, uncachedPoints.size)

                // Nominatim polite rate limiting: 1.5s delay between network requests
                kotlinx.coroutines.delay(1500L)
            }

            return@withContext PreloadResult.Completed(addedCount = added, alreadyCachedCount = alreadyCached)
        } catch (e: Exception) {
            return@withContext PreloadResult.Failed(e.message ?: "Unknown pre-fetch error")
        }
    }
}
