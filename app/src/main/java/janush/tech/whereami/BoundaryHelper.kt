package janush.tech.whereami

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class BoundaryPolygon(
    val placeName: String,
    val points: List<GeoPoint>
)

object BoundaryHelper {
    // In-memory cache for fast UI access
    private val memoryCache = ConcurrentHashMap<String, List<GeoPoint>>()

    // Concurrency & Rate Limiting (Nominatim policy: max 1 req/sec)
    private val requestMutex = Mutex()
    @Volatile
    private var lastRequestTime = 0L
    @Volatile
    private var coolDownUntil = 0L

    private const val MIN_REQUEST_INTERVAL_MS = 1500L
    private const val RATE_LIMIT_COOLDOWN_MS = 30_000L

    suspend fun getLocalityBoundary(
        context: Context,
        cityName: String,
        countryCode: String
    ): List<GeoPoint>? = withContext(Dispatchers.IO) {
        if (cityName.isBlank() || cityName == "Unknown City" || cityName == "--") return@withContext null

        val cleanKey = sanitizeKey("${cityName.trim().lowercase(Locale.ROOT)}_${countryCode.trim().lowercase(Locale.ROOT)}")
        
        // Tier 1: In-memory cache
        memoryCache[cleanKey]?.let { return@withContext it }

        // Tier 2: Persistent disk cache
        val diskPoints = loadFromDiskCache(context, cleanKey)
        if (diskPoints != null && diskPoints.isNotEmpty()) {
            memoryCache[cleanKey] = diskPoints
            return@withContext diskPoints
        }

        // Tier 3: Network fetch from OSM Nominatim with rate limiting & 429 resilience
        val now = System.currentTimeMillis()
        if (now < coolDownUntil) {
            TelemetryLogger.log("BOUNDARY", "Skipping network fetch for $cityName: in 429 cooldown for ${(coolDownUntil - now) / 1000}s")
            return@withContext null
        }

        requestMutex.withLock {
            // Re-check cache after acquiring lock
            memoryCache[cleanKey]?.let { return@withContext it }

            val elapsed = System.currentTimeMillis() - lastRequestTime
            if (elapsed < MIN_REQUEST_INTERVAL_MS) {
                kotlinx.coroutines.delay(MIN_REQUEST_INTERVAL_MS - elapsed)
            }
            lastRequestTime = System.currentTimeMillis()

            var fetched = fetchFromNetwork(cityName, countryCode)
            if (fetched == null && (countryCode.equals("PL", ignoreCase = true) || countryCode.isEmpty())) {
                // Polish fallback query
                fetched = fetchFromNetwork(cityName, "Polska")
            }

            if (fetched != null && fetched.isNotEmpty()) {
                val simplified = subsamplePoints(fetched, 120)
                memoryCache[cleanKey] = simplified
                saveToDiskCache(context, cleanKey, simplified)
                return@withContext simplified
            }
        }

        return@withContext null
    }

    private fun fetchFromNetwork(cityName: String, countryPart: String): List<GeoPoint>? {
        return try {
            val q = URLEncoder.encode("$cityName, $countryPart", "UTF-8")
            val urlStr = "https://nominatim.openstreetmap.org/search?q=$q&polygon_geojson=1&format=json&limit=1"
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "WhereIAmPersonalApp/1.1 (android)")
            conn.connectTimeout = 6000
            conn.readTimeout = 6000

            val code = conn.responseCode
            if (code == 429) {
                coolDownUntil = System.currentTimeMillis() + RATE_LIMIT_COOLDOWN_MS
                TelemetryLogger.log("BOUNDARY", "Nominatim HTTP 429 received. Backing off for 30s")
                return null
            }

            if (code == 200) {
                val body = conn.inputStream.bufferedReader().readText()
                val array = JSONArray(body)
                if (array.length() > 0) {
                    val obj = array.getJSONObject(0)
                    val geojson = obj.optJSONObject("geojson") ?: return null
                    val type = geojson.optString("type")
                    val coords = geojson.optJSONArray("coordinates") ?: return null

                    val result = mutableListOf<GeoPoint>()
                    if (type.equals("Polygon", ignoreCase = true)) {
                        val outerRing = coords.getJSONArray(0)
                        for (i in 0 until outerRing.length()) {
                            val coordPair = outerRing.getJSONArray(i)
                            val lon = coordPair.getDouble(0)
                            val lat = coordPair.getDouble(1)
                            result.add(GeoPoint(lat, lon))
                        }
                    } else if (type.equals("MultiPolygon", ignoreCase = true)) {
                        // Take largest outer ring
                        if (coords.length() > 0) {
                            val firstPoly = coords.getJSONArray(0)
                            if (firstPoly.length() > 0) {
                                val outerRing = firstPoly.getJSONArray(0)
                                for (i in 0 until outerRing.length()) {
                                    val coordPair = outerRing.getJSONArray(i)
                                    val lon = coordPair.getDouble(0)
                                    val lat = coordPair.getDouble(1)
                                    result.add(GeoPoint(lat, lon))
                                }
                            }
                        }
                    }
                    if (result.isNotEmpty()) return result
                }
            }
            null
        } catch (e: Exception) {
            TelemetryLogger.log("BOUNDARY", "Network fetch error for $cityName: ${e.message}")
            null
        }
    }

    private fun sanitizeKey(key: String): String {
        return key.replace(Regex("[^a-zA-Z0-9_-]"), "_")
    }

    private fun getCacheDir(context: Context): File {
        val dir = File(context.cacheDir, "boundaries")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun loadFromDiskCache(context: Context, key: String): List<GeoPoint>? {
        return try {
            val file = File(getCacheDir(context), "$key.json")
            if (!file.exists()) return null
            val jsonStr = file.readText()
            val arr = JSONArray(jsonStr)
            val pts = ArrayList<GeoPoint>(arr.length())
            for (i in 0 until arr.length()) {
                val ptArr = arr.getJSONArray(i)
                pts.add(GeoPoint(ptArr.getDouble(0), ptArr.getDouble(1)))
            }
            pts
        } catch (_: Exception) {
            null
        }
    }

    private fun saveToDiskCache(context: Context, key: String, points: List<GeoPoint>) {
        try {
            val file = File(getCacheDir(context), "$key.json")
            val arr = JSONArray()
            for (pt in points) {
                val ptArr = JSONArray()
                ptArr.put(pt.latitude)
                ptArr.put(pt.longitude)
                arr.put(ptArr)
            }
            file.writeText(arr.toString())
        } catch (e: Exception) {
            TelemetryLogger.log("BOUNDARY", "Failed to cache boundary to disk: ${e.message}")
        }
    }

    /**
     * Subsamples a dense list of polygon vertices to prevent canvas path drawing ANRs in osmdroid.
     */
    private fun subsamplePoints(pts: List<GeoPoint>, maxPts: Int): List<GeoPoint> {
        if (pts.size <= maxPts) return pts
        val step = (pts.size - 1).toDouble() / (maxPts - 1).toDouble()
        val res = ArrayList<GeoPoint>(maxPts)
        for (i in 0 until maxPts - 1) {
            val idx = Math.round(i * step).toInt().coerceIn(0, pts.size - 1)
            res.add(pts[idx])
        }
        res.add(pts.last())
        return res
    }
}
