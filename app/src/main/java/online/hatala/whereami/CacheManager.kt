package online.hatala.whereami

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
import org.osmdroid.tileprovider.modules.SqlTileWriter
import org.osmdroid.util.MapTileIndex
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
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
        const val KEY_MAX_CACHE_SIZE_MB = "max_cache_size_mb"
        const val DEFAULT_MAX_CACHE_SIZE_MB = 1024 // 1 GB default
        const val NOTIF_AVAILABLE_ID = 3002
        const val NOTIF_CHANNEL_AVAILABLE = "cache_updates_available"
        const val ACTION_OPEN_CACHE_MANAGER = "online.hatala.whereami.ACTION_OPEN_CACHE_MANAGER"
        const val EXTRA_OPEN_CACHE_MANAGER = "extra_open_cache_manager"

        @Volatile
        private var instance: CacheManager? = null

        internal fun shouldAutoPausePrefetch(isRecording: Boolean, isGeocoderRateLimited: Boolean): Boolean =
            isRecording || isGeocoderRateLimited

        internal fun filterMissingBoundaryTargetsInDir(
            dir: File,
            targets: List<BoundaryTarget>,
            negativeTtlMs: Long = BoundaryHelper.CACHE_MANAGER_NEGATIVE_TTL_MS
        ): List<BoundaryTarget> =
            targets.filter { !BoundaryHelper.hasBoundaryInDir(dir, it.name, it.countryCode, it.municipality, negativeTtlMs) }

        internal fun filterMissingMapTilesInDb(
            cacheDbFile: File,
            tileDir: File?,
            plannedTiles: List<TileCoordKey>
        ): List<TileCoordKey> {
            if (plannedTiles.isEmpty()) return emptyList()
            val foundKeysByProvider = HashMap<String, MutableSet<Long>>()
            if (cacheDbFile.exists() && cacheDbFile.isFile) {
                var db: android.database.sqlite.SQLiteDatabase? = null
                try {
                    db = android.database.sqlite.SQLiteDatabase.openDatabase(
                        cacheDbFile.absolutePath,
                        null,
                        android.database.sqlite.SQLiteDatabase.OPEN_READONLY
                    )
                    val byLayer = plannedTiles.groupBy { it.layer }
                    for ((layer, tiles) in byLayer) {
                        val provider = MapTileSourceRegistry.providerNameFor(layer)
                        val found = foundKeysByProvider.getOrPut(provider) { HashSet() }
                        val sqlKeys = tiles.map { it.osmdroidSqlKey }.distinct()
                        for (chunk in sqlKeys.chunked(450)) {
                            val placeholders = chunk.joinToString(",") { "?" }
                            val args = Array(chunk.size + 1) { idx ->
                                if (idx == 0) provider else chunk[idx - 1].toString()
                            }
                            db.rawQuery(
                                "SELECT DISTINCT key FROM tiles WHERE provider = ? AND key IN ($placeholders)",
                                args
                            ).use { c ->
                                while (c.moveToNext()) {
                                    found.add(c.getLong(0))
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    try { db?.close() } catch (_: Exception) {}
                }
            }
            return plannedTiles.filter { tile ->
                val provider = MapTileSourceRegistry.providerNameFor(tile.layer)
                val inDb = foundKeysByProvider[provider]?.contains(tile.osmdroidSqlKey) == true
                if (inDb) return@filter false
                if (tileDir != null) {
                    val ext = MapTileSourceRegistry.tileSourceFor(tile.layer).imageFilenameEnding()
                    val legacyFile = File(tileDir, "$provider/${tile.zoom}/${tile.x}/${tile.y}$ext.tile")
                    if (legacyFile.exists() && legacyFile.length() > 0L) return@filter false
                }
                true
            }
        }

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

    var maxCacheSizeMb: Int
        get() = prefs.getInt(KEY_MAX_CACHE_SIZE_MB, DEFAULT_MAX_CACHE_SIZE_MB)
        set(value) = prefs.edit().putInt(KEY_MAX_CACHE_SIZE_MB, value).apply()

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

    suspend fun getElevationCacheBytes(): Long = withContext(Dispatchers.IO) {
        TripDatabaseHelper.getInstance(context).getElevationCacheBytes()
    }

    suspend fun getTrailCacheBytes(): Long = withContext(Dispatchers.IO) {
        TrailVectorRepository.getStorageBytes(context)
    }

    suspend fun getTrailCacheFileCount(): Int = withContext(Dispatchers.IO) {
        TrailVectorRepository.getStorageFileCount(context)
    }

    suspend fun clearTrailCache(): Boolean = withContext(Dispatchers.IO) {
        val result = TrailVectorRepository.clearStorage(context)
        calculateCacheDeficit()
        result
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
            val addedBoundaries: Int,
            val addedMapTiles: Int = 0,
            val addedTrails: Int = 0
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

    data class CacheDeficit(
        val missingRoutes: Int,
        val missingBoundaries: Int,
        val missingDemTrips: Int = 0,
        val missingDemPoints: Int = 0,
        val missingMapTiles: Int = 0,
        val missingTrailTiles: Int = 0
    ) {
        val totalMissing: Int
            get() = missingRoutes + missingBoundaries + missingMapTiles + missingTrailTiles
    }

    internal fun shouldAutoPausePrefetch(isRecording: Boolean, isGeocoderRateLimited: Boolean): Boolean =
        Companion.shouldAutoPausePrefetch(isRecording, isGeocoderRateLimited)
    fun resetPrefetchState() {
        if (_prefetchState.value is PrefetchState.Completed || _prefetchState.value is PrefetchState.Error || _prefetchState.value is PrefetchState.Blocked) {
            _prefetchState.value = PrefetchState.Idle
        }
    }

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
        val junkNames = setOf("unknown city", "--")

        // 1. From Trips placesVisited
        try {
            val dbHelper = TripDatabaseHelper.getInstance(context)
            val allTrips = dbHelper.getAllTrips()
            for (trip in allTrips) {
                for (p in trip.placesVisited) {
                    if (p.placeKind != PlaceKind.LOCALITY) continue
                    val name = p.placeName.trim()
                    if (name.isBlank() || junkNames.contains(name.lowercase(Locale.ROOT)) || name.contains(",") || name.contains("°")) {
                        continue
                    }
                    val mun = p.hierarchySubtitle
                        .split("•", ",")
                        .firstOrNull()
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                    val cc = p.countryCode.lowercase(Locale.ROOT)
                    val key = "${name.lowercase(Locale.ROOT)}_${mun.orEmpty().lowercase(Locale.ROOT)}_$cc"
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
                "SELECT city, latitude, longitude, native_json FROM spatial_cache WHERE city IS NOT NULL AND TRIM(city) != '' AND city != 'Unknown City' AND city != '--'",
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
                var cc = ""
                var mun: String? = null
                if (!nativeJson.isNullOrBlank()) {
                    try {
                        val obj = JSONObject(nativeJson)
                        cc = obj.optString("countryCode", "").trim().lowercase(Locale.ROOT)
                        mun = obj.optString("gmina").takeIf { it.isNotBlank() }
                    } catch (_: Exception) {}
                }
                val key = "${name.lowercase(Locale.ROOT)}_${mun.orEmpty().lowercase(Locale.ROOT)}_$cc"
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

        // Immediate UI feedback
        _prefetchState.value = PrefetchState.Running(1, "Starting...", 0, 0, 0)
        updateNotification("Starting prefetch...", 0, 0, false)

        prefetchJob = managerScope.launch {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            val wakeLock = powerManager?.newWakeLock(
                android.os.PowerManager.PARTIAL_WAKE_LOCK,
                "WhereAmI:CachePrefetchWakeLock"
            )?.apply {
                setReferenceCounted(false)
                acquire(45 * 60 * 1000L) // 45 minutes max timeout
            }

            try {
                val dbHelper = TripDatabaseHelper.getInstance(context)
                val allTrips = dbHelper.getAllTrips()
                val spatialHelper = SpatialCacheHelper.getInstance(context)
                val locManager = LocationManager.getInstance(context)

                // 1. Ensure legacy 3-decimal cache keys are migrated so actual trip data is recognized
                spatialHelper.migrateLegacyKeys()

                // Bulk-load cached keys for fast in-memory lookups
                val cachedKeys = spatialHelper.getAllCachedGridKeys().toMutableSet()

                // Calculate total unique points in all trips for accurate reporting
                val allUnique15mPoints = mutableMapOf<String, org.osmdroid.util.GeoPoint>()
                val activeTripId = TripManager.getInstance(context).activeTrip.value?.id
                val completedTrips = allTrips.filter { it.endTime != null && it.endTime > 0 && it.id != activeTripId }
                for (trip in completedTrips) {
                    for (pt in trip.points) {
                        val key = SpatialCacheHelper.toGridKey(pt.latitude, pt.longitude)
                        if (!allUnique15mPoints.containsKey(key)) {
                            allUnique15mPoints[key] = pt
                        }
                    }
                }

                if (allUnique15mPoints.isEmpty()) {
                    _prefetchState.value = PrefetchState.Completed(0, 0)
                    dismissNotification()
                    calculateCacheDeficit()
                    return@launch
                }

                var addedRoutes = 0; var addedBoundaries = 0
                val totalPointsInTrips = allUnique15mPoints.size

                // ── PASS 1: Macro Corridor Coverage (~50m spacing) ───────────────────
                // Guarantees zero blank offline spots along all recorded route corridors rapidly
                val pass1Candidates = mutableListOf<org.osmdroid.util.GeoPoint>()
                for (trip in completedTrips) {
                    var lastPt: org.osmdroid.util.GeoPoint? = null
                    for (pt in trip.points) {
                        if (lastPt == null || lastPt.distanceToAsDouble(pt) >= 50.0) {
                            lastPt = pt
                            val key = SpatialCacheHelper.toGridKey(pt.latitude, pt.longitude)
                            if (key !in cachedKeys && !spatialHelper.hasNearbyCache(pt.latitude, pt.longitude, 30.0)) {
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
                    while (isPrefetchPaused || shouldAutoPausePrefetch(
                            TripManager.getInstance(context).activeTrip.value != null,
                            locManager.isOsmRateLimited() || DataAcquisitionManager.isGeocoderRateLimited()
                        )
                    ) {
                        kotlinx.coroutines.delay(1500L)
                    }

                    if (!allowOnBattery && !isDeviceCharging()) {
                        _prefetchState.value = PrefetchState.Blocked("Charging disconnected. Connect charger to continue.")
                        dismissNotification()
                        return@launch
                    }

                    // Skip if now cached by an adjacent query
                    val key = SpatialCacheHelper.toGridKey(pt.latitude, pt.longitude)
                    if (key !in cachedKeys && !spatialHelper.hasNearbyCache(pt.latitude, pt.longitude, 30.0)) {
                        val resolved = locManager.resolveMultiLanguageData(pt.latitude, pt.longitude, allowNetwork = true, forceCache = true, speedKmh = 30f)
                        if (spatialHelper.get(pt.latitude, pt.longitude) != null) {
                            cachedKeys.add(key)
                            addedRoutes++
                        } else {
                            // If geocoding returned unmapped/empty data, ensure sentinel is saved so candidate does not loop indefinitely as missing
                            spatialHelper.put(pt.latitude, pt.longitude, resolved)
                            cachedKeys.add(key)
                        }
                        kotlinx.coroutines.delay(1100L)
                    }

                    pass1Current++
                    _prefetchState.value = PrefetchState.Running(1, "Pass 1: Macro Coverage (~50m)", pass1Current, pass1Total, addedRoutes)
                    updateNotification("Pass 1: Macro Coverage", pass1Current, pass1Total, false)
                }

                // ── PASS 2: Fine Precision Down to ~15m Grid Resolution ──────────────
                // Fills in intermediate gaps along corridors that lack spatial coverage within 30m
                val pass2Candidates = allUnique15mPoints.values.filter { pt ->
                    val key = SpatialCacheHelper.toGridKey(pt.latitude, pt.longitude)
                    key !in cachedKeys && !spatialHelper.hasNearbyCache(pt.latitude, pt.longitude, 30.0)
                }

                val pass2Total = pass2Candidates.size
                var pass2Current = 0
                _prefetchState.value = PrefetchState.Running(2, "Pass 2: Fine Precision (~15m)", pass2Current, pass2Total, addedRoutes)
                updateNotification("Pass 2: Fine Precision", pass2Current, pass2Total, false)

                for (pt in pass2Candidates) {
                    while (isPrefetchPaused || shouldAutoPausePrefetch(
                            TripManager.getInstance(context).activeTrip.value != null,
                            locManager.isOsmRateLimited() || DataAcquisitionManager.isGeocoderRateLimited()
                        )
                    ) {
                        kotlinx.coroutines.delay(1500L)
                    }

                    if (!allowOnBattery && !isDeviceCharging()) {
                        _prefetchState.value = PrefetchState.Blocked("Charging disconnected. Connect charger to continue.")
                        dismissNotification()
                        return@launch
                    }

                    val key = SpatialCacheHelper.toGridKey(pt.latitude, pt.longitude)
                    if (key !in cachedKeys && !spatialHelper.hasNearbyCache(pt.latitude, pt.longitude, 30.0)) {
                        val resolved = locManager.resolveMultiLanguageData(pt.latitude, pt.longitude, allowNetwork = true, forceCache = true, speedKmh = 30f)
                        if (spatialHelper.get(pt.latitude, pt.longitude) != null) {
                            cachedKeys.add(key)
                            addedRoutes++
                        } else {
                            // If geocoding returned unmapped/empty data, ensure sentinel is saved so candidate does not loop indefinitely as missing
                            spatialHelper.put(pt.latitude, pt.longitude, resolved)
                            cachedKeys.add(key)
                        }
                        kotlinx.coroutines.delay(1100L)
                    }

                    pass2Current++
                    _prefetchState.value = PrefetchState.Running(2, "Pass 2: Fine Precision (~15m)", pass2Current, pass2Total, addedRoutes)
                    updateNotification("Pass 2: Fine Precision", pass2Current, pass2Total, false)
                }

                // PASS 3: Administrative Boundaries
                BoundaryHelper.clearMemoryCache()
                val boundaryTargets = getDistinctBoundaryTargets()
                val missingTargets = filterMissingBoundaryTargets(boundaryTargets)
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

                    if (fetchBoundaryTargetWithRetry(target)) {
                        addedBoundaries++
                    }
                    pass3Current++
                    _prefetchState.value = PrefetchState.Running(3, "Pass 3: Boundaries", pass3Current, pass3Total, addedRoutes + addedBoundaries)
                    updateNotification("Pass 3: Boundaries", pass3Current, pass3Total, false)
                    kotlinx.coroutines.delay(1500L)
                }

                // PASS 4: Offline Map Tiles along saved trip corridors (zooms 13..15)
                val plannedTiles = planSavedTripMapTiles(completedTrips)
                val missingTiles = findMissingMapTiles(plannedTiles)
                val (addedTiles, _) = if (missingTiles.isNotEmpty()) {
                    downloadMissingMapTilesLoop(
                        missingTiles = missingTiles,
                        passNumber = 4,
                        passLabel = "Pass 4: Map Tiles",
                        baseAdded = addedRoutes + addedBoundaries
                    )
                } else {
                    0 to null
                }

                // PASS 5: Outdoor Hiking & Cycling Trails along saved trip corridors
                val plannedTrailTiles = TrailVectorRepository.planSavedTripTrailTileKeys(completedTrips)
                val missingTrailTiles = TrailVectorRepository.filterMissingTrailTileKeys(context, plannedTrailTiles)
                var addedTrails = 0
                val pass5Total = missingTrailTiles.size
                var pass5Current = 0
                if (missingTrailTiles.isNotEmpty()) {
                    _prefetchState.value = PrefetchState.Running(5, "Pass 5: Outdoor Trails", pass5Current, pass5Total, addedRoutes + addedBoundaries + addedTiles)
                    updateNotification("Pass 5: Trails", pass5Current, pass5Total, false)

                    for (tileKey in missingTrailTiles) {
                        while (isPrefetchPaused) {
                            kotlinx.coroutines.delay(500L)
                        }
                        if (!allowOnBattery && !isDeviceCharging()) {
                            _prefetchState.value = PrefetchState.Blocked("Charging disconnected.")
                            dismissNotification()
                            return@launch
                        }
                        val added = TrailVectorRepository.fetchMissingTrailTiles(context, listOf(tileKey))
                        addedTrails += added
                        pass5Current++
                        _prefetchState.value = PrefetchState.Running(5, "Pass 5: Outdoor Trails", pass5Current, pass5Total, addedRoutes + addedBoundaries + addedTiles + addedTrails)
                        updateNotification("Pass 5: Trails", pass5Current, pass5Total, false)
                        kotlinx.coroutines.delay(80L)
                    }
                }

                _prefetchState.value = PrefetchState.Completed(addedRoutes, addedBoundaries, addedTiles, addedTrails)
                dismissNotification()
                calculateCacheDeficit()
            } catch (_: kotlinx.coroutines.CancellationException) {
                dismissNotification()
            } catch (e: Exception) {
                _prefetchState.value = PrefetchState.Error(e.message ?: "Unknown pre-fetch error")
                dismissNotification()
            } finally {
                try {
                    if (wakeLock?.isHeld == true) {
                        wakeLock.release()
                    }
                } catch (_: Exception) {}
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

    fun validateAndCleanCache(onDone: ((Int) -> Unit)? = null) {
        managerScope.launch {
            val spatialHelper = SpatialCacheHelper.getInstance(context)
            val db = spatialHelper.writableDatabase
            var cleaned = 0
            try {
                spatialHelper.sanitizeUniversalInvariants(db)
                // Clean orphan/unknown entries
                val cursor = db.rawQuery("SELECT count(*) FROM spatial_cache WHERE city IS NULL OR city = 'Unknown City' OR city = '--'", null)
                if (cursor.moveToFirst()) {
                    cleaned = cursor.getInt(0)
                }
                cursor.close()
                db.execSQL("DELETE FROM spatial_cache WHERE city IS NULL OR city = 'Unknown City' OR city = '--'")
            } catch (_: Exception) {}
            calculateCacheDeficit()
            withContext(Dispatchers.Main) {
                onDone?.invoke(cleaned)
            }
        }
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

        // Immediate UI feedback
        _prefetchState.value = PrefetchState.Running(3, "Starting...", 0, 0, 0)
        updateNotification("Starting boundaries...", 0, 0, false)

        prefetchJob = managerScope.launch(Dispatchers.IO) {
            try {
                BoundaryHelper.clearMemoryCache()
                val boundaryTargets = getDistinctBoundaryTargets()
                val missingTargets = filterMissingBoundaryTargets(boundaryTargets)
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

                    if (fetchBoundaryTargetWithRetry(target)) {
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
            } catch (_: kotlinx.coroutines.CancellationException) {
                dismissNotification()
            } catch (e: Exception) {
                _prefetchState.value = PrefetchState.Error(e.message ?: "Unknown pre-fetch error")
                dismissNotification()
            }
        }
    }

    fun fetchMissingMapTiles(isUserInitiated: Boolean = true) {
        if (_prefetchState.value is PrefetchState.Running) return

        if (!isUserInitiated) {
            val isRecording = TripManager.getInstance(context).activeTrip.value != null
            val isLiveSharing = LiveSharingManager.getInstance(context).currentSession.value?.isActive == true
            if (isRecording || isLiveSharing) {
                TelemetryLogger.log("CACHE", "Auto map tile prefetch suppressed during active trip recording or live sharing.")
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
        _prefetchState.value = PrefetchState.Running(4, "Pass 4: Map Tiles", 0, 0, 0)
        updateNotification("Map Tiles...", 0, 0, false)

        prefetchJob = managerScope.launch(Dispatchers.IO) {
            try {
                val activeTripId = TripManager.getInstance(context).activeTrip.value?.id
                val completedTrips = TripDatabaseHelper.getInstance(context).getAllTrips()
                    .filter { it.endTime != null && it.endTime > 0 && it.id != activeTripId }
                val planned = planSavedTripMapTiles(completedTrips)
                val missing = findMissingMapTiles(planned)
                val (added, blockReason) = downloadMissingMapTilesLoop(
                    missingTiles = missing,
                    passNumber = 4,
                    passLabel = "Pass 4: Map Tiles",
                    baseAdded = 0
                )
                if (blockReason != null) {
                    _prefetchState.value = PrefetchState.Blocked(blockReason)
                    dismissNotification()
                    calculateCacheDeficit()
                } else {
                    _prefetchState.value = PrefetchState.Completed(0, 0, added)
                    dismissNotification()
                    calculateCacheDeficit()
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                dismissNotification()
            } catch (e: Exception) {
                _prefetchState.value = PrefetchState.Error(e.message ?: "Map tile prefetch failed")
                dismissNotification()
            }
        }
    }

    fun fetchMissingTrails(isUserInitiated: Boolean = true) {
        if (_prefetchState.value is PrefetchState.Running) return

        if (!isUserInitiated) {
            val isRecording = TripManager.getInstance(context).activeTrip.value != null
            val isLiveSharing = LiveSharingManager.getInstance(context).currentSession.value?.isActive == true
            if (isRecording || isLiveSharing) {
                TelemetryLogger.log("CACHE", "Auto trail prefetch suppressed during active trip recording or live sharing.")
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
        _prefetchState.value = PrefetchState.Running(5, "Pass 5: Outdoor Trails", 0, 0, 0)
        updateNotification("Trails...", 0, 0, false)

        prefetchJob = managerScope.launch(Dispatchers.IO) {
            try {
                val activeTripId = TripManager.getInstance(context).activeTrip.value?.id
                val completedTrips = TripDatabaseHelper.getInstance(context).getAllTrips()
                    .filter { it.endTime != null && it.endTime > 0 && it.id != activeTripId }
                val planned = TrailVectorRepository.planSavedTripTrailTileKeys(completedTrips)
                val missing = TrailVectorRepository.filterMissingTrailTileKeys(context, planned)
                val total = missing.size
                var current = 0
                var added = 0

                _prefetchState.value = PrefetchState.Running(5, "Pass 5: Outdoor Trails", current, total, added)
                updateNotification("Pass 5: Trails", current, total, false)

                for (tileKey in missing) {
                    while (isPrefetchPaused) {
                        kotlinx.coroutines.delay(500L)
                    }
                    if (!allowOnBattery && !isDeviceCharging()) {
                        _prefetchState.value = PrefetchState.Blocked("Charging disconnected.")
                        dismissNotification()
                        return@launch
                    }
                    val count = TrailVectorRepository.fetchMissingTrailTiles(context, listOf(tileKey))
                    added += count
                    current++
                    _prefetchState.value = PrefetchState.Running(5, "Pass 5: Outdoor Trails", current, total, added)
                    updateNotification("Pass 5: Trails", current, total, false)
                    kotlinx.coroutines.delay(80L)
                }

                _prefetchState.value = PrefetchState.Completed(0, 0, 0, added)
                dismissNotification()
                calculateCacheDeficit()
            } catch (_: kotlinx.coroutines.CancellationException) {
                dismissNotification()
            } catch (e: Exception) {
                _prefetchState.value = PrefetchState.Error(e.message ?: "Unknown trail prefetch error")
                dismissNotification()
            }
        }
    }

    private fun planSavedTripMapTiles(completedTrips: List<TripRecord>): List<TileCoordKey> {
        if (completedTrips.isEmpty()) return emptyList()
        val layerByProfile = HashMap<ActivityProfile, MapBaseLayer>()
        val specs = completedTrips.map { trip ->
            val layer = layerByProfile.getOrPut(trip.activityProfile) {
                MapProfileConfigHelper.getConfig(context, trip.activityProfile).baseLayer
            }
            val coords = trip.points.map { it.latitude to it.longitude }
            coords to layer
        }
        return MapTileCorridorPlanner.planForTrips(specs)
    }

    private fun findMissingMapTiles(plannedTiles: List<TileCoordKey>): List<TileCoordKey> {
        if (plannedTiles.isEmpty()) return emptyList()
        val tileDir = Configuration.getInstance().osmdroidTileCache
        val cacheDb = if (tileDir != null) File(tileDir, SqlTileWriter.DATABASE_FILENAME) else return emptyList()
        return filterMissingMapTilesInDb(cacheDb, tileDir, plannedTiles)
    }

    private fun getTotalCachedBytesSync(): Long {
        val tileBytes = computeDirSize(Configuration.getInstance().osmdroidTileCache)
        val boundaryBytes = computeDirSize(File(context.cacheDir, "boundaries"))
        val spatialBytes = SpatialCacheHelper.getInstance(context).getStorageBytes()
        val elevationBytes = TripDatabaseHelper.getInstance(context).getElevationCacheBytes()
        val trailBytes = TrailVectorRepository.getStorageBytes(context)
        return tileBytes + boundaryBytes + spatialBytes + elevationBytes + trailBytes
    }

    private suspend fun downloadMissingMapTilesLoop(
        missingTiles: List<TileCoordKey>,
        passNumber: Int,
        passLabel: String,
        baseAdded: Int
    ): Pair<Int, String?> {
        val total = missingTiles.size
        if (total == 0) return 0 to null
        val maxBytes = maxCacheSizeMb.toLong() * 1024L * 1024L
        var currentTotalBytes = getTotalCachedBytesSync()
        if (currentTotalBytes >= maxBytes) {
            return 0 to "Max storage limit (${maxCacheSizeMb} MB) reached."
        }

        val tileWriter = SqlTileWriter()
        val userAgent = Configuration.getInstance().userAgentValue.ifBlank {
            "WhereAmI/2.0 (Android; https://github.com/janush74/WhereAmI)"
        }
        var current = 0
        var added = 0
        var bytesSinceCheck = 0L
        var consecutiveRateLimits = 0

        _prefetchState.value = PrefetchState.Running(passNumber, passLabel, current, total, baseAdded + added)
        updateNotification("Pass $passNumber: Map Tiles", current, total, false)

        for (tile in missingTiles) {
            while (isPrefetchPaused || TripManager.getInstance(context).activeTrip.value != null) {
                kotlinx.coroutines.delay(1000L)
            }
            if (!allowOnBattery && !isDeviceCharging()) {
                return added to "Charging disconnected."
            }
            if (!allowMobileData && !isUnmeteredWifi()) {
                return added to "Wi-Fi disconnected."
            }

            if (bytesSinceCheck >= 512 * 1024L) {
                currentTotalBytes = getTotalCachedBytesSync()
                bytesSinceCheck = 0L
            }
            if (currentTotalBytes + bytesSinceCheck >= maxBytes) {
                return added to "Max storage limit (${maxCacheSizeMb} MB) reached."
            }

            val tileSource = MapTileSourceRegistry.tileSourceFor(tile.layer)
            val mapTileIndex = MapTileIndex.getTileIndex(tile.zoom, tile.x, tile.y)
            val urlStr = MapTileSourceRegistry.tileUrlFor(tile.layer, tile.zoom, tile.x, tile.y)
            var downloaded = false

            for (attempt in 0..1) {
                var conn: HttpURLConnection? = null
                try {
                    conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 10_000
                        readTimeout = 10_000
                        setRequestProperty("User-Agent", userAgent)
                        setRequestProperty("Referer", "https://whereami.hatala.online/")
                    }
                    val code = conn.responseCode
                    if (code in 200..299) {
                        val bytes = conn.inputStream.use { it.readBytes() }
                        if (bytes.isNotEmpty()) {
                            val expiresAt = System.currentTimeMillis() + 30L * 86_400_000L
                            val saved = tileWriter.saveFile(tileSource, mapTileIndex, ByteArrayInputStream(bytes), expiresAt)
                            if (saved) {
                                added++
                                bytesSinceCheck += bytes.size.toLong()
                                downloaded = true
                                consecutiveRateLimits = 0
                            }
                        }
                        break
                    } else if (code == 429 || code == 503) {
                        consecutiveRateLimits++
                        if (attempt == 0) {
                            kotlinx.coroutines.delay(1500L)
                        } else if (consecutiveRateLimits >= 3) {
                            return added to "Tile server rate-limited. Try again later."
                        }
                    } else {
                        consecutiveRateLimits = 0
                        break
                    }
                } catch (_: Exception) {
                    if (attempt == 0) kotlinx.coroutines.delay(400L)
                } finally {
                    try { conn?.disconnect() } catch (_: Exception) {}
                }
            }

            current++
            _prefetchState.value = PrefetchState.Running(passNumber, passLabel, current, total, baseAdded + added)
            updateNotification("Pass $passNumber: Map Tiles", current, total, false)
            if (downloaded) {
                kotlinx.coroutines.delay(180L)
            }
        }

        return added to null
    }

    private fun filterMissingBoundaryTargets(targets: List<BoundaryTarget>): List<BoundaryTarget> =
        filterMissingBoundaryTargetsInDir(BoundaryHelper.getCacheDir(context), targets)

    private suspend fun fetchBoundaryTargetWithRetry(target: BoundaryTarget): Boolean {
        val targetPoint = if (target.lat != null && target.lng != null) {
            org.osmdroid.util.GeoPoint(target.lat, target.lng)
        } else null
        BoundaryHelper.getLocalityBoundary(
            context = context,
            cityName = target.name,
            countryCode = target.countryCode,
            fallbackMunicipality = target.municipality,
            geoPoint = targetPoint
        )
        if (!BoundaryHelper.hasBoundary(context, target.name, target.countryCode, target.municipality, BoundaryHelper.CACHE_MANAGER_NEGATIVE_TTL_MS) &&
            BoundaryHelper.isCoolingDown()
        ) {
            val waitMs = BoundaryHelper.remainingCooldownMs()
            if (waitMs > 0L) {
                kotlinx.coroutines.delay(waitMs + 250L)
            }
            BoundaryHelper.getLocalityBoundary(
                context = context,
                cityName = target.name,
                countryCode = target.countryCode,
                fallbackMunicipality = target.municipality,
                geoPoint = targetPoint
            )
        }
        return BoundaryHelper.hasBoundary(context, target.name, target.countryCode, target.municipality, BoundaryHelper.CACHE_MANAGER_NEGATIVE_TTL_MS)
    }

    // Queue of points that were encountered during driving and determined to be stale (> 60 days) or obsolete (> 1 year without internet)
    private val pendingRefreshPoints = java.util.concurrent.ConcurrentHashMap<String, org.osmdroid.util.GeoPoint>()

    fun enqueuePendingRefresh(lat: Double, lng: Double) {
        val key = SpatialCacheHelper.toGridKey(lat, lng)
        if (!pendingRefreshPoints.containsKey(key)) {
            pendingRefreshPoints[key] = org.osmdroid.util.GeoPoint(lat, lng)
        }
    }

    fun getPendingRefreshCount(): Int = pendingRefreshPoints.size

    fun onTripFinished() {
        managerScope.launch {
            try {
                kotlinx.coroutines.delay(2500L)
                val canAutoWifi = autoStartOnWifi && isUnmeteredWifi()
                val canAutoCharger = autoStartOnCharger && isDeviceCharging()

                // 1. Process pending stale/obsolete refreshes and degraded trip points if power/wifi allows
                val canProceedRefresh = canAutoWifi || canAutoCharger || (allowOnBattery && !LocationManager.getInstance(context).isOsmRateLimited())
                if (canProceedRefresh) {
                    val dbHelper = TripDatabaseHelper.getInstance(context)
                    val lastTrip = dbHelper.getAllTrips().filter { it.endTime != null && it.endTime > 0 }.maxByOrNull { it.endTime ?: 0L }
                    val degradedTripPts = lastTrip?.points?.let { pts ->
                        SpatialCacheHelper.getInstance(context).getIncompleteOrDegradedTripPoints(pts)
                    } ?: emptyList()
                    for (pt in degradedTripPts) {
                        enqueuePendingRefresh(pt.latitude, pt.longitude)
                    }

                    if (pendingRefreshPoints.isNotEmpty()) {
                        val pointsToRefresh = pendingRefreshPoints.values.toList()
                        pendingRefreshPoints.clear()
                        TelemetryLogger.log("CACHE", "Processing ${pointsToRefresh.size} pending corridor refreshes post-trip")
                        val locManager = LocationManager.getInstance(context)
                        for (pt in pointsToRefresh) {
                            if (!allowOnBattery && !isDeviceCharging()) break
                            while (shouldAutoPausePrefetch(
                                    TripManager.getInstance(context).activeTrip.value != null,
                                    locManager.isOsmRateLimited() || DataAcquisitionManager.isGeocoderRateLimited()
                                )
                            ) {
                                kotlinx.coroutines.delay(1500L)
                            }
                            locManager.resolveMultiLanguageData(
                                pt.latitude, pt.longitude, forceCache = true, allowNetwork = true, forceRefresh = true, speedKmh = 30f
                            )
                            kotlinx.coroutines.delay(1100L)
                        }
                    }

                    // 1b. Post-trip outdoor context backfill for offline outdoor trips (sampled every >= 600m)
                    val lastOutdoorTrip = dbHelper.getAllTrips().filter { it.endTime != null && it.endTime > 0 }.maxByOrNull { it.endTime ?: 0L }
                    if (lastOutdoorTrip != null && lastOutdoorTrip.activityProfile.isOutdoorProfile && (canAutoWifi || canAutoCharger)) {
                        var lastOutPt: org.osmdroid.util.GeoPoint? = null
                        var backfilled = 0
                        val spatialHelper = SpatialCacheHelper.getInstance(context)
                        for (pt in lastOutdoorTrip.points) {
                            if (backfilled >= 6) break
                            if (lastOutPt == null || lastOutPt.distanceToAsDouble(pt) >= 600.0) {
                                lastOutPt = pt
                                val existingOut = try { spatialHelper.getOutdoorContext(pt.latitude, pt.longitude) } catch (_: Exception) { null }
                                if (existingOut == null) {
                                    OutdoorContextResolver.resolveOutdoorContext(pt.latitude, pt.longitude, context, allowNetwork = true)
                                    backfilled++
                                    kotlinx.coroutines.delay(15_500L)
                                }
                            }
                        }
                    }
                }

                // 2. Enforce storage limit
                SpatialCacheHelper.getInstance(context).enforceStorageLimit(maxCacheSizeMb)

                // 3. Evaluate deficit and start prefetch or notify
                calculateCacheDeficit()
            } catch (_: Exception) {}
        }
    }

    fun calculateCacheDeficit() {
        if (_prefetchState.value !is PrefetchState.Idle && _prefetchState.value !is PrefetchState.Completed) return
        managerScope.launch {
            try {
                val dbHelper = TripDatabaseHelper.getInstance(context)
                val allTrips = dbHelper.getAllTrips()
                val spatialHelper = SpatialCacheHelper.getInstance(context)

                // Ensure keys are migrated first
                spatialHelper.migrateLegacyKeys()

                val allUnique15mPoints = mutableMapOf<String, org.osmdroid.util.GeoPoint>()
                val activeTripId = TripManager.getInstance(context).activeTrip.value?.id
                val completedTrips = allTrips.filter { it.endTime != null && it.endTime > 0 && it.id != activeTripId }
                for (trip in completedTrips) {
                    for (pt in trip.points) {
                        val key = SpatialCacheHelper.toGridKey(pt.latitude, pt.longitude)
                        if (!allUnique15mPoints.containsKey(key)) {
                            allUnique15mPoints[key] = org.osmdroid.util.GeoPoint(pt.latitude, pt.longitude)
                        }
                    }
                }

                // Bulk-load cached keys in a single SQLite query (~10ms) instead of tens of thousands of individual queries
                val cachedKeys = spatialHelper.getAllCachedGridKeys()
                // True route deficit: a point is missing only if it has NO cached data within 30 meters
                val missingRoutes = allUnique15mPoints.values.count { pt ->
                    val key = SpatialCacheHelper.toGridKey(pt.latitude, pt.longitude)
                    key !in cachedKeys && !spatialHelper.hasNearbyCache(pt.latitude, pt.longitude, 30.0)
                }
                
                val boundaryTargets = getDistinctBoundaryTargets()
                val missingBoundaries = filterMissingBoundaryTargets(boundaryTargets).size
                val missingDem = TripDatabaseHelper.getInstance(context).getMissingDemSummary()
                val plannedMapTiles = planSavedTripMapTiles(completedTrips)
                val missingMapTiles = findMissingMapTiles(plannedMapTiles).size
                val plannedTrailTiles = TrailVectorRepository.planSavedTripTrailTileKeys(completedTrips)
                val missingTrailTiles = TrailVectorRepository.filterMissingTrailTileKeys(context, plannedTrailTiles).size
                val deficit = CacheDeficit(
                    missingRoutes = missingRoutes,
                    missingBoundaries = missingBoundaries,
                    missingDemTrips = missingDem.tripCount,
                    missingDemPoints = missingDem.pointCount,
                    missingMapTiles = missingMapTiles,
                    missingTrailTiles = missingTrailTiles
                )
                _cacheDeficit.value = deficit

                val totalMissing = deficit.totalMissing
                if (totalMissing > 0 && _prefetchState.value is PrefetchState.Idle) {
                    val isRecording = TripManager.getInstance(context).activeTrip.value != null
                    if (isRecording) {
                        return@launch
                    }
                    val isLiveSharing = LiveSharingManager.getInstance(context).currentSession.value?.isActive == true
                    val isStationary = StationaryDetector.getInstance(context).isPhysicallyStationary.value
                    val canAutoWifi = autoStartOnWifi && isUnmeteredWifi()
                    val canAutoCharger = autoStartOnCharger && isDeviceCharging()

                    // If live sharing is active, allow auto-prefetch only when stationary, on Wi-Fi, or charging
                    if (isLiveSharing && !isStationary && !canAutoWifi && !canAutoCharger) {
                        return@launch
                    }
                    if (canAutoWifi || canAutoCharger || (allowOnBattery && isStationary)) {
                        TelemetryLogger.log("CACHE", "Auto-starting prefetch: Wi-Fi=$canAutoWifi, Charger=$canAutoCharger, Stationary=$isStationary, Missing=$totalMissing")
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

        val total = deficit.missingRoutes + deficit.missingBoundaries + deficit.missingMapTiles
        val builder = androidx.core.app.NotificationCompat.Builder(context, NOTIF_CHANNEL_AVAILABLE)
            .setContentTitle("WhereAmI — Map Cache Updates Available")
            .setContentText("$total items need updating (${deficit.missingRoutes} routes, ${deficit.missingBoundaries} boundaries, ${deficit.missingMapTiles} tiles).")
            .setSmallIcon(R.drawable.ic_stat_location)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .addAction(0, "▶ Start Download", startIntent)
            .addAction(0, "Open Cache", openIntent)

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
        manager?.notify(NOTIF_AVAILABLE_ID, builder.build())
    }
}









