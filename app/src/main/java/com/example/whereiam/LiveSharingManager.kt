package com.example.whereiam

import android.content.Context
import android.content.SharedPreferences
import android.os.BatteryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.Locale

enum class LiveShareProvider(val displayName: String) {
    LOCAL("Local Test Server (Port 3003)"),
    SYNOLOGY("Self-Hosted Public Server")
}

data class LivePoint(
    val lat: Double,
    val lng: Double,
    val speedKmh: Float,
    val altitude: Double?,
    val timestamp: Long
)

data class LiveSession(
    val id: String,                    // 10-char slug
    val title: String,
    val createdAt: Long,
    val expiresAt: Long,              // 0 = never / permanent (until manually stopped)
    val isActive: Boolean,
    val isPaused: Boolean = false,    // Overall pause
    val isPersonalPaused: Boolean = false, // Selective pause for personal static link
    val isRandomPaused: Boolean = false,   // Selective pause for random session link
    val serverUrl: String,            // e.g. "https://whereami.yourdomain.com" or "http://127.0.0.1:3003"
    val provider: LiveShareProvider,
    val syncIntervalMinutes: Int,     // 1, 2, 5, 10
    val trailVisible: Boolean = true, // Whether visitors see the full trail or current position only
    val lastSyncTime: Long = 0L,
    val pendingPointsCount: Int = 0
) {
    val isExpired: Boolean
        get() = expiresAt > 0L && System.currentTimeMillis() > expiresAt

    fun getRemainingTimeMs(): Long {
        if (expiresAt <= 0L) return -1L
        val diff = expiresAt - System.currentTimeMillis()
        return if (diff > 0) diff else 0L
    }

    fun getFormattedRemaining(): String {
        val ms = getRemainingTimeMs()
        if (ms < 0L) {
            val elapsed = System.currentTimeMillis() - createdAt
            val hours = elapsed / (1000 * 3600)
            val minutes = (elapsed % (1000 * 3600)) / (1000 * 60)
            return if (hours > 0) "⏱️ ${hours}h ${minutes}m elapsed" else "⏱️ ${minutes}m elapsed"
        }
        if (ms == 0L) return "Expired"
        val hours = ms / (1000 * 3600)
        val minutes = (ms % (1000 * 3600)) / (1000 * 60)
        return if (hours > 0) "${hours}h ${minutes}m left" else "${minutes}m left"
    }

    fun getViewerUrl(useStatic: Boolean = false, staticId: String? = null): String {
        val targetId = if (useStatic && !staticId.isNullOrBlank()) staticId else id
        val cleanBase = serverUrl.trimEnd('/')
        return when (provider) {
            LiveShareProvider.LOCAL ->
                // LOCAL: viewer at http://localhost:3003/live/SESSION_ID
                "http://localhost:3003/live/$targetId"
            LiveShareProvider.SYNOLOGY ->
                // SYNOLOGY: viewer served directly by the user's own server — clean, no GH Pages
                "$cleanBase/live/$targetId"
        }
    }

    fun getApiBaseUrl(): String {
        return when (provider) {
            LiveShareProvider.LOCAL -> "http://127.0.0.1:3003"
            LiveShareProvider.SYNOLOGY -> serverUrl.trimEnd('/')
        }
    }
}

class LiveSharingManager private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: LiveSharingManager? = null

        fun getInstance(context: Context): LiveSharingManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LiveSharingManager(context.applicationContext).also { INSTANCE = it }
            }
        }

        private const val PREFS_NAME = "where_i_am_live_share_prefs"
        private const val KEY_SESSION_ID = "active_session_id"
        private const val KEY_SESSION_TITLE = "active_session_title"
        private const val KEY_SESSION_CREATED = "active_session_created"
        private const val KEY_SESSION_EXPIRES = "active_session_expires"
        private const val KEY_SESSION_ACTIVE = "active_session_is_active"
        private const val KEY_SESSION_PAUSED = "active_session_is_paused"
        private const val KEY_SESSION_PERSONAL_PAUSED = "active_session_personal_paused"
        private const val KEY_SESSION_RANDOM_PAUSED = "active_session_random_paused"
        private const val KEY_SESSION_SERVER = "active_session_server_url"
        private const val KEY_SESSION_PROVIDER = "active_session_provider"
        private const val KEY_SESSION_INTERVAL = "active_session_interval"
        private const val KEY_SESSION_LAST_SYNC = "active_session_last_sync"
        private const val KEY_SESSION_TRAIL_VISIBLE = "active_session_trail_visible"
        private const val KEY_STATIC_LIVE_ID = "personal_static_live_id"
        // Dialog UI preference persistence
        const val KEY_PREF_LINK_MODE_STATIC = "pref_link_mode_static"   // Boolean
        const val KEY_PREF_PROVIDER = "pref_default_provider"           // String (enum name)
        const val KEY_PREF_DURATION = "pref_default_duration_hours"     // Int (0 = permanent)
        const val KEY_PREF_TITLE = "pref_default_title"                 // String

        private val SLUG_CHARS = "23456789abcdefghjkmnpqrstuvwxyz".toCharArray()
        private val random = SecureRandom()

        fun generate10CharSlug(): String {
            val sb = StringBuilder(10)
            for (i in 0 until 10) {
                sb.append(SLUG_CHARS[random.nextInt(SLUG_CHARS.size)])
            }
            return sb.toString()
        }

        fun generateStaticSlug(): String {
            val sb = StringBuilder(6)
            for (i in 0 until 6) {
                sb.append(SLUG_CHARS[random.nextInt(SLUG_CHARS.size)])
            }
            return "jh-${sb}"
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.IO)
    private val dbHelper = TripDatabaseHelper(context)

    private val _currentSession = MutableStateFlow<LiveSession?>(null)
    val currentSession: StateFlow<LiveSession?> = _currentSession.asStateFlow()

    private val _staticLiveId = MutableStateFlow<String>("")
    val staticLiveId: StateFlow<String> = _staticLiveId.asStateFlow()

    private val memoryPointsQueue = mutableListOf<LivePoint>()
    private var lastRecordedLat: Double = 0.0
    private var lastRecordedLng: Double = 0.0
    private var lastRecordedBearing: Float? = null

    init {
        loadSavedSession()
        loadStaticLiveId()
    }

    private fun loadStaticLiveId() {
        val saved = prefs.getString(KEY_STATIC_LIVE_ID, null)
        if (saved.isNullOrBlank()) {
            val generated = generateStaticSlug()
            prefs.edit().putString(KEY_STATIC_LIVE_ID, generated).apply()
            _staticLiveId.value = generated
        } else {
            _staticLiveId.value = saved
        }
    }

    fun regenerateStaticLiveId(): String {
        val newId = generateStaticSlug()
        prefs.edit().putString(KEY_STATIC_LIVE_ID, newId).apply()
        _staticLiveId.value = newId
        val current = _currentSession.value
        if (current != null && current.isActive) {
            scope.launch {
                postSessionMeta(current, newId)
            }
        }
        return newId
    }

    fun getStaticLiveId(): String = _staticLiveId.value

    private fun loadSavedSession() {
        val id = prefs.getString(KEY_SESSION_ID, null) ?: return
        val isActive = prefs.getBoolean(KEY_SESSION_ACTIVE, false)
        val isPaused = prefs.getBoolean(KEY_SESSION_PAUSED, false)
        val expiresAt = prefs.getLong(KEY_SESSION_EXPIRES, 0L)
        if (isActive && expiresAt > 0L && System.currentTimeMillis() > expiresAt) {
            prefs.edit().putBoolean(KEY_SESSION_ACTIVE, false).apply()
            return
        }

        val providerName = prefs.getString(KEY_SESSION_PROVIDER, LiveShareProvider.LOCAL.name) ?: LiveShareProvider.LOCAL.name
        val provider = try { LiveShareProvider.valueOf(providerName) } catch (_: Exception) { LiveShareProvider.LOCAL }

        val defaultUrl = when (provider) {
            LiveShareProvider.LOCAL -> "http://127.0.0.1:3003"
            LiveShareProvider.SYNOLOGY -> "https://whereami.janush.tech"
        }

        val rawSavedUrl = prefs.getString(KEY_SESSION_SERVER, defaultUrl) ?: defaultUrl
        val cleanUrl = if (provider == LiveShareProvider.SYNOLOGY && (rawSavedUrl.contains("127.0.0.1") || rawSavedUrl.contains("192.168.") || rawSavedUrl.contains("localhost"))) {
            "https://live.yourdomain.com"
        } else rawSavedUrl

        val isPersonalPaused = prefs.getBoolean(KEY_SESSION_PERSONAL_PAUSED, false)
        val isRandomPaused = prefs.getBoolean(KEY_SESSION_RANDOM_PAUSED, false)

        val session = LiveSession(
            id = id,
            title = prefs.getString(KEY_SESSION_TITLE, "My Live Hike") ?: "My Live Hike",
            createdAt = prefs.getLong(KEY_SESSION_CREATED, System.currentTimeMillis()),
            expiresAt = expiresAt,
            isActive = isActive,
            isPaused = isPaused,
            isPersonalPaused = isPersonalPaused,
            isRandomPaused = isRandomPaused,
            serverUrl = cleanUrl,
            provider = provider,
            syncIntervalMinutes = prefs.getInt(KEY_SESSION_INTERVAL, 5),
            trailVisible = prefs.getBoolean(KEY_SESSION_TRAIL_VISIBLE, true),
            lastSyncTime = prefs.getLong(KEY_SESSION_LAST_SYNC, 0L),
            pendingPointsCount = memoryPointsQueue.size
        )
        _currentSession.value = session
    }

    fun startSession(
        title: String,
        durationHours: Int, // 0 = never
        serverUrl: String,
        provider: LiveShareProvider = LiveShareProvider.LOCAL,
        syncIntervalMinutes: Int = 5,
        customSlug: String? = null
    ): LiveSession {
        val slug = if (!customSlug.isNullOrBlank()) customSlug else generate10CharSlug()
        val now = System.currentTimeMillis()
        val expiresAt = if (durationHours > 0) now + durationHours * 3600_000L else 0L

        val cleanUrl = serverUrl.trim().trimEnd('/')

        val session = LiveSession(
            id = slug,
            title = if (title.isNotBlank()) title else "My Live Track",
            createdAt = now,
            expiresAt = expiresAt,
            isActive = true,
            isPaused = false,
            isPersonalPaused = false,
            isRandomPaused = false,
            serverUrl = cleanUrl,
            provider = provider,
            syncIntervalMinutes = syncIntervalMinutes,
            trailVisible = true,
            lastSyncTime = 0L,
            pendingPointsCount = 0
        )

        prefs.edit()
            .putString(KEY_SESSION_ID, session.id)
            .putString(KEY_SESSION_TITLE, session.title)
            .putLong(KEY_SESSION_CREATED, session.createdAt)
            .putLong(KEY_SESSION_EXPIRES, session.expiresAt)
            .putBoolean(KEY_SESSION_ACTIVE, true)
            .putBoolean(KEY_SESSION_PAUSED, false)
            .putBoolean(KEY_SESSION_PERSONAL_PAUSED, false)
            .putBoolean(KEY_SESSION_RANDOM_PAUSED, false)
            .putString(KEY_SESSION_SERVER, session.serverUrl)
            .putString(KEY_SESSION_PROVIDER, session.provider.name)
            .putInt(KEY_SESSION_INTERVAL, session.syncIntervalMinutes)
            .putBoolean(KEY_SESSION_TRAIL_VISIBLE, true)
            .putLong(KEY_SESSION_LAST_SYNC, 0L)
            .apply()

        synchronized(memoryPointsQueue) {
            memoryPointsQueue.clear()
        }
        _currentSession.value = session

        scope.launch {
            postSessionMeta(session)
        }

        return session
    }

    fun pauseSession() {
        val s = _currentSession.value ?: return
        val paused = s.copy(isPaused = true)
        prefs.edit().putBoolean(KEY_SESSION_PAUSED, true).apply()
        _currentSession.value = paused

        scope.launch {
            postStatusUpdate(s, "paused")
        }
    }

    fun resumeSession() {
        val s = _currentSession.value ?: return
        val resumed = s.copy(isPaused = false, isPersonalPaused = false, isRandomPaused = false)
        prefs.edit()
            .putBoolean(KEY_SESSION_PAUSED, false)
            .putBoolean(KEY_SESSION_PERSONAL_PAUSED, false)
            .putBoolean(KEY_SESSION_RANDOM_PAUSED, false)
            .apply()
        _currentSession.value = resumed

        scope.launch {
            postStatusUpdate(s, "active", "all")
        }
    }

    fun pausePersonalLink() {
        val s = _currentSession.value ?: return
        val updated = s.copy(isPersonalPaused = true)
        prefs.edit().putBoolean(KEY_SESSION_PERSONAL_PAUSED, true).apply()
        _currentSession.value = updated
        scope.launch {
            postStatusUpdate(s, "paused", "personal")
        }
    }

    fun resumePersonalLink() {
        val s = _currentSession.value ?: return
        val updated = s.copy(isPersonalPaused = false)
        prefs.edit().putBoolean(KEY_SESSION_PERSONAL_PAUSED, false).apply()
        _currentSession.value = updated
        scope.launch {
            postStatusUpdate(s, "active", "personal")
        }
    }

    fun pauseRandomLink() {
        val s = _currentSession.value ?: return
        val updated = s.copy(isRandomPaused = true)
        prefs.edit().putBoolean(KEY_SESSION_RANDOM_PAUSED, true).apply()
        _currentSession.value = updated
        scope.launch {
            postStatusUpdate(s, "paused", "random")
        }
    }

    fun resumeRandomLink() {
        val s = _currentSession.value ?: return
        val updated = s.copy(isRandomPaused = false)
        prefs.edit().putBoolean(KEY_SESSION_RANDOM_PAUSED, false).apply()
        _currentSession.value = updated
        scope.launch {
            postStatusUpdate(s, "active", "random")
        }
    }

    fun renameSession(newTitle: String) {
        val s = _currentSession.value ?: return
        val trimmed = newTitle.trim()
        if (trimmed.isEmpty()) return
        val updated = s.copy(title = trimmed)
        prefs.edit().putString(KEY_SESSION_TITLE, trimmed).apply()
        _currentSession.value = updated
        scope.launch {
            try {
                val urlStr = "${s.getApiBaseUrl()}/api/sessions/${s.id}/rename"
                val conn = URL(urlStr).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.doOutput = true
                val body = JSONObject().apply { put("title", trimmed) }
                OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
                conn.responseCode
            } catch (_: Exception) {}
        }
    }

    fun setSyncInterval(minutes: Int) {
        val s = _currentSession.value ?: return
        if (minutes <= 0) return
        val updated = s.copy(syncIntervalMinutes = minutes)
        prefs.edit().putInt(KEY_SESSION_INTERVAL, minutes).apply()
        _currentSession.value = updated
        scope.launch {
            try {
                val urlStr = "${s.getApiBaseUrl()}/api/sessions/${s.id}/interval"
                val conn = URL(urlStr).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.doOutput = true
                val body = JSONObject().apply { put("syncIntervalMinutes", minutes) }
                OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
                conn.responseCode
            } catch (_: Exception) {}
        }
    }

    fun adjustSession(additionalHours: Double) {
        val s = _currentSession.value ?: return
        val now = System.currentTimeMillis()
        val newExpires = if (s.expiresAt <= 0L) {
            if (additionalHours > 0) now + (additionalHours * 3600_000.0).toLong() else 0L
        } else {
            val baseTime = if (s.expiresAt > now) s.expiresAt else now
            (baseTime + (additionalHours * 3600_000.0).toLong()).coerceAtLeast(now + 60_000L)
        }
        val adjusted = s.copy(expiresAt = newExpires)
        prefs.edit().putLong(KEY_SESSION_EXPIRES, newExpires).apply()
        _currentSession.value = adjusted

        scope.launch {
            try {
                val urlStr = "${s.getApiBaseUrl()}/api/sessions/${s.id}/extend"
                val conn = URL(urlStr).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.doOutput = true
                val body = JSONObject().apply { put("additionalHours", additionalHours) }
                OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
                conn.responseCode
            } catch (_: Exception) {}
        }
    }

    fun extendSession(additionalHours: Int) {
        adjustSession(additionalHours.toDouble())
    }

    fun setTrailVisible(visible: Boolean) {
        val s = _currentSession.value ?: return
        val updated = s.copy(trailVisible = visible)
        prefs.edit().putBoolean(KEY_SESSION_TRAIL_VISIBLE, visible).apply()
        _currentSession.value = updated

        scope.launch {
            try {
                val urlStr = "${s.getApiBaseUrl()}/api/sessions/${s.id}/view-mode"
                val conn = URL(urlStr).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.doOutput = true
                val body = JSONObject().apply { put("trailVisible", visible) }
                OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
                conn.responseCode
            } catch (_: Exception) {}
        }
    }

    fun stopSession() {
        val s = _currentSession.value ?: return
        val stopped = s.copy(isActive = false, isPaused = false)
        prefs.edit().putBoolean(KEY_SESSION_ACTIVE, false).putBoolean(KEY_SESSION_PAUSED, false).apply()
        _currentSession.value = stopped

        scope.launch {
            try {
                val urlStr = "${s.getApiBaseUrl()}/api/sessions/${s.id}/end"
                val conn = URL(urlStr).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.responseCode
            } catch (_: Exception) {}
        }
    }

    fun deleteSession() {
        stopSession()
        prefs.edit().clear().apply()
        synchronized(memoryPointsQueue) {
            memoryPointsQueue.clear()
        }
        _currentSession.value = null
    }

    fun onLocationUpdate(
        lat: Double,
        lng: Double,
        speedKmh: Float,
        altitude: Double?,
        bearing: Float? = null,
        placeName: String?,
        trekkingBadge: String?
    ) {
        val session = _currentSession.value ?: return
        if (!session.isActive || session.isExpired || session.isPaused) {
            if (session.isExpired && session.isActive) {
                stopSession()
            }
            return
        }

        // Distance filter: require at least 15 meters or 15 seconds between breadcrumbs
        val now = System.currentTimeMillis()
        if (lastRecordedLat != 0.0 && lastRecordedLng != 0.0) {
            val dist = FloatArray(1)
            android.location.Location.distanceBetween(lastRecordedLat, lastRecordedLng, lat, lng, dist)
            if (dist[0] < 15f && memoryPointsQueue.isNotEmpty() && (now - memoryPointsQueue.last().timestamp) < 15_000L) {
                return
            }
        }

        lastRecordedLat = lat
        lastRecordedLng = lng
        if (bearing != null) lastRecordedBearing = bearing

        val point = LivePoint(
            lat = lat,
            lng = lng,
            speedKmh = speedKmh,
            altitude = altitude,
            timestamp = now
        )

        synchronized(memoryPointsQueue) {
            memoryPointsQueue.add(point)
        }

        _currentSession.value = session.copy(pendingPointsCount = memoryPointsQueue.size)

        // Check if sync interval threshold is reached
        val intervalMs = session.syncIntervalMinutes * 60_000L
        val shouldSync = intervalMs > 0L && (now - session.lastSyncTime >= intervalMs)

        if (shouldSync) {
            flushPointsToServer(lat, lng, speedKmh, altitude, bearing ?: lastRecordedBearing, placeName, trekkingBadge)
        }
    }

    fun syncNow(
        currentLat: Double? = null,
        currentLng: Double? = null,
        currentSpeed: Float? = null,
        currentAlt: Double? = null,
        currentBearing: Float? = null,
        placeName: String? = null,
        trekkingBadge: String? = null
    ) {
        val lat = currentLat ?: lastRecordedLat
        val lng = currentLng ?: lastRecordedLng
        if (lat == 0.0 && lng == 0.0) return
        flushPointsToServer(lat, lng, currentSpeed ?: 0f, currentAlt, currentBearing ?: lastRecordedBearing, placeName, trekkingBadge)
    }

    private fun flushPointsToServer(
        lat: Double,
        lng: Double,
        speedKmh: Float,
        altitude: Double?,
        bearing: Float?,
        placeName: String?,
        trekkingBadge: String?
    ) {
        val session = _currentSession.value ?: return
        if (!session.isActive) return

        val pointsToPost: List<LivePoint>
        synchronized(memoryPointsQueue) {
            pointsToPost = memoryPointsQueue.toList()
        }

        scope.launch {
            val battery = getBatteryPercentage()
            val ok = postSyncPayload(session, pointsToPost, lat, lng, speedKmh, altitude, bearing, placeName, trekkingBadge, battery)
            if (ok) {
                val now = System.currentTimeMillis()
                val postedTimestamps = pointsToPost.map { it.timestamp }.toSet()
                synchronized(memoryPointsQueue) {
                    memoryPointsQueue.removeAll { it.timestamp in postedTimestamps }
                }
                prefs.edit().putLong(KEY_SESSION_LAST_SYNC, now).apply()
                _currentSession.value = session.copy(lastSyncTime = now, pendingPointsCount = memoryPointsQueue.size)
            }
        }
    }

    private suspend fun postSessionMeta(session: LiveSession, staticIdOverride: String? = null): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val urlStr = "${session.getApiBaseUrl()}/api/sessions/${session.id}"
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.doOutput = true

            val targetStaticId = staticIdOverride ?: _staticLiveId.value
            val body = JSONObject().apply {
                put("id", session.id)
                put("title", session.title)
                put("createdAt", session.createdAt)
                put("expiresAt", session.expiresAt)
                put("trailVisible", session.trailVisible)
                put("syncIntervalMinutes", session.syncIntervalMinutes)
                if (targetStaticId.isNotBlank()) {
                    put("staticId", targetStaticId)
                }
            }

            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
            val code = conn.responseCode
            if (code !in 200..299) {
                TelemetryLogger.log("LIVE_SHARE", "postSessionMeta failed with code $code: $urlStr")
            }
            code in 200..299
        } catch (e: Exception) {
            TelemetryLogger.log("LIVE_SHARE", "postSessionMeta exception: ${e.message}")
            false
        }
    }

    private suspend fun postStatusUpdate(session: LiveSession, status: String, target: String = "all"): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val urlStr = "${session.getApiBaseUrl()}/api/sessions/${session.id}/status"
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.doOutput = true
            val body = JSONObject().apply {
                put("status", status)
                put("target", target)
            }
            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
            conn.responseCode in 200..299
        } catch (e: Exception) {
            TelemetryLogger.log("LIVE_SHARE", "postStatusUpdate exception: ${e.message}")
            false
        }
    }

    private suspend fun postSyncPayload(
        session: LiveSession,
        points: List<LivePoint>,
        lat: Double,
        lng: Double,
        speed: Float,
        altitude: Double?,
        bearing: Float?,
        place: String?,
        trekking: String?,
        battery: Int
    ): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val urlStr = "${session.getApiBaseUrl()}/api/sessions/${session.id}/points"
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.doOutput = true

            val pointsArr = JSONArray()
            points.forEach { p ->
                pointsArr.put(JSONObject().apply {
                    put("lat", p.lat)
                    put("lng", p.lng)
                    put("spd", p.speedKmh)
                    put("alt", p.altitude ?: JSONObject.NULL)
                    put("t", p.timestamp)
                })
            }

            val targetStaticId = _staticLiveId.value
            val root = JSONObject().apply {
                put("points", pointsArr)
                put("isPaused", session.isPaused)
                if (targetStaticId.isNotBlank()) {
                    put("staticId", targetStaticId)
                }
                put("current", JSONObject().apply {
                    put("lat", lat)
                    put("lng", lng)
                    put("spd", speed)
                    put("alt", altitude ?: JSONObject.NULL)
                    put("bearing", bearing ?: JSONObject.NULL)
                    put("place", place ?: "")
                    put("trekking", trekking ?: "")
                    put("battery", battery)
                    put("t", System.currentTimeMillis())
                })
            }

            OutputStreamWriter(conn.outputStream).use { it.write(root.toString()) }
            val code = conn.responseCode
            if (code !in 200..299) {
                TelemetryLogger.log("LIVE_SHARE", "postSyncPayload failed with code $code: $urlStr")
            }
            code in 200..299
        } catch (e: Exception) {
            TelemetryLogger.log("LIVE_SHARE", "postSyncPayload exception: ${e.message}")
            false
        }
    }

    private fun getBatteryPercentage(): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        return bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
    }
}
