package online.hatala.whereami

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

    /**
     * Bump when on-disk polygon semantics change. v1 = pre-pin; v2 = pin containment;
     * v3 = live name-first (invalidates v1.18.0 reverse-first pollution under city keys).
     */
    private const val CACHE_GEN = "v3"

    fun clearMemoryCache() {
        memoryCache.clear()
    }

    // Concurrency & Rate Limiting (Nominatim policy: max 1 req/sec)
    private val requestMutex = Mutex()
    @Volatile
    private var lastRequestTime = 0L
    @Volatile
    private var coolDownUntil = 0L

    private const val MIN_REQUEST_INTERVAL_MS = 1500L
    private const val RATE_LIMIT_COOLDOWN_MS = 30_000L

    /**
     * Pin borders: reverse-first + must enclose [geoPoint].
     * Live GPS / prefetch borders: name-first for [cityName]; never overwrite a named
     * locality cache with a smaller reverse hit (regression: Kobiernice card + wrong sub-area border).
     */
    suspend fun getLocalityBoundary(
        context: Context,
        cityName: String?,
        countryCode: String = "pl",
        fallbackMunicipality: String? = null,
        geoPoint: GeoPoint? = null,
        forPin: Boolean = false
    ): List<GeoPoint>? = withContext(Dispatchers.IO) {
        val cleanCity = cityName?.trim()?.takeIf {
            it.isNotBlank() && it != "Unknown City" && it != "--" && !it.contains(",") && !it.contains("°")
        }
        val cleanKey = if (cleanCity != null) {
            sanitizeKey("${cleanCity.lowercase(Locale.ROOT)}_${countryCode.trim().lowercase(Locale.ROOT)}_$CACHE_GEN")
        } else if (!fallbackMunicipality.isNullOrBlank()) {
            sanitizeKey("${fallbackMunicipality.trim().lowercase(Locale.ROOT)}_${countryCode.trim().lowercase(Locale.ROOT)}_$CACHE_GEN")
        } else if (geoPoint != null) {
            String.format(Locale.US, "coord_%.3f_%.3f_$CACHE_GEN", geoPoint.latitude, geoPoint.longitude)
        } else {
            return@withContext null
        }

        val pinMode = forPin && geoPoint != null

        fun usableCached(pts: List<GeoPoint>): List<GeoPoint>? {
            if (pts.isEmpty()) return null
            if (pinMode) {
                val pin = geoPoint ?: return null
                if (!isCachePolygonUsableForPin(pts, pin)) {
                    TelemetryLogger.log(
                        "BOUNDARY",
                        "Discarding cached polygon for $cleanKey — does not contain pin " +
                            "(${pin.latitude}, ${pin.longitude})"
                    )
                    return null
                }
            }
            return pts
        }

        // Tier 1: In-memory cache
        memoryCache[cleanKey]?.let { cached ->
            usableCached(cached)?.let { return@withContext it }
            if (pinMode) memoryCache.remove(cleanKey)
        }

        // Tier 2: Persistent disk cache
        val diskPoints = loadFromDiskCache(context, cleanKey)
        if (diskPoints != null) {
            val usable = usableCached(diskPoints)
            if (usable != null) {
                memoryCache[cleanKey] = usable
                return@withContext usable
            }
            if (diskPoints.isEmpty()) {
                // Live: trust negative cache. Pin: fall through to reverse-first.
                if (!pinMode) return@withContext null
            } else if (pinMode) {
                File(getCacheDir(context), "$cleanKey.json").delete()
            }
        }

        // Tier 3: Network fetch from OSM Nominatim with rate limiting & 429 resilience
        val now = System.currentTimeMillis()
        if (now < coolDownUntil) {
            val waitTime = coolDownUntil - now
            TelemetryLogger.log("BOUNDARY", "Waiting ${waitTime / 1000}s for 429 cooldown before fetching $cleanCity...")
            kotlinx.coroutines.delay(waitTime)
        }

        requestMutex.withLock {
            // Re-check cache after acquiring lock
            memoryCache[cleanKey]?.let { cached ->
                usableCached(cached)?.let { return@withContext it }
            }

            // Helper to throttle every individual network query
            suspend fun throttle() {
                val elapsed = System.currentTimeMillis() - lastRequestTime
                if (elapsed < MIN_REQUEST_INTERVAL_MS) {
                    kotlinx.coroutines.delay(MIN_REQUEST_INTERVAL_MS - elapsed)
                }
                lastRequestTime = System.currentTimeMillis()
            }

            var fetched: List<GeoPoint>? = null
            // Pin: reverse must enclose the point. Live: never force containment on the named city.
            val containGate = if (pinMode) geoPoint else null

            // Pin-only: reverse-first so the polygon truly encloses the dropped point.
            if (shouldResolveReverseFirst(forPin = forPin, geoPoint = geoPoint)) {
                for (zoom in listOf(14, 12, 10)) {
                    if (fetched != null) break
                    throttle()
                    fetched = fetchReverseBoundary(
                        geoPoint!!.latitude,
                        geoPoint.longitude,
                        zoom = zoom,
                        mustContain = geoPoint
                    )
                }
            }

            // Name search — live GPS primary path; pin fallback when reverse has no polygon.
            if (fetched == null && cleanCity != null) {
                throttle()
                fetched = fetchFromNetwork(cleanCity, countryCode, mustContain = containGate)
                if (fetched == null && (countryCode.equals("PL", ignoreCase = true) || countryCode.isEmpty())) {
                    throttle()
                    fetched = fetchFromNetwork(cleanCity, "Polska", mustContain = containGate)
                } else if (fetched == null && countryCode.equals("SK", ignoreCase = true)) {
                    throttle()
                    fetched = fetchFromNetwork(cleanCity, "Slovensko", mustContain = containGate)
                }
            }

            // Municipality / gmina fallback
            if (fetched == null && !fallbackMunicipality.isNullOrBlank() && !fallbackMunicipality.equals(cleanCity, ignoreCase = true)) {
                val cleanMun = fallbackMunicipality.replace("Gmina ", "", ignoreCase = true)
                    .replace("gmina ", "", ignoreCase = true)
                    .replace("gm. ", "", ignoreCase = true).trim()
                if (cleanMun.isNotBlank() && !cleanMun.equals(cleanCity, ignoreCase = true)) {
                    val munQuery = if (countryCode.equals("PL", ignoreCase = true) || countryCode.isEmpty()) "gmina $cleanMun" else cleanMun
                    throttle()
                    fetched = fetchFromNetwork(munQuery, countryCode, mustContain = containGate)
                    if (fetched == null && (countryCode.equals("PL", ignoreCase = true) || countryCode.isEmpty())) {
                        throttle()
                        fetched = fetchFromNetwork(cleanMun, "Polska", mustContain = containGate)
                    } else if (fetched == null && countryCode.equals("SK", ignoreCase = true)) {
                        throttle()
                        fetched = fetchFromNetwork(cleanMun, "Slovensko", mustContain = containGate)
                    }
                }
            }

            // Coordinate reverse fallback (live: when name has no polygon; pin: if earlier reverse missed)
            if (fetched == null && geoPoint != null) {
                throttle()
                fetched = fetchReverseBoundary(
                    geoPoint.latitude,
                    geoPoint.longitude,
                    zoom = 12,
                    mustContain = containGate
                )
                if (fetched == null) {
                    throttle()
                    fetched = fetchReverseBoundary(
                        geoPoint.latitude,
                        geoPoint.longitude,
                        zoom = 10,
                        mustContain = containGate
                    )
                }
            }

            if (fetched != null && fetched.isNotEmpty()) {
                val simplified = subsamplePoints(fetched, 120)
                memoryCache[cleanKey] = simplified
                saveToDiskCache(context, cleanKey, simplified)
                return@withContext simplified
            } else if (System.currentTimeMillis() >= coolDownUntil && !pinMode) {
                // Authoritative non-429 response had no polygon. Cache empty array so we don't hammer Nominatim repeatedly.
                // Never negative-cache pin lookups — containment fallbacks must remain available.
                saveToDiskCache(context, cleanKey, emptyList())
            }
        }

        return@withContext null
    }

    /** Pin borders only — reverse-first requires a concrete dropped-pin coordinate. */
    fun shouldResolveReverseFirst(forPin: Boolean, geoPoint: GeoPoint?): Boolean {
        return forPin && geoPoint != null
    }

    /** Live borders keep the named locality polygon even if GPS briefly leaves it. */
    fun isCachePolygonUsableForLive(points: List<GeoPoint>, @Suppress("UNUSED_PARAMETER") gps: GeoPoint): Boolean {
        return points.size >= 3
    }

    fun hasBoundary(context: Context, cityName: String?, countryCode: String = "pl"): Boolean {
        val cleanCity = cityName?.trim()?.takeIf {
            it.isNotBlank() && it != "Unknown City" && it != "--" && !it.contains(",") && !it.contains("°")
        } ?: return true
        val cleanKey = sanitizeKey(
            "${cleanCity.lowercase(Locale.ROOT)}_${countryCode.trim().lowercase(Locale.ROOT)}_$CACHE_GEN"
        )
        val file = File(getCacheDir(context), "$cleanKey.json")
        return file.exists()
    }

    private fun fetchFromNetwork(
        cityName: String,
        countryPart: String,
        mustContain: GeoPoint? = null
    ): List<GeoPoint>? {
        return try {
            val q = URLEncoder.encode("$cityName, $countryPart", "UTF-8")
            val urlStr = "https://nominatim.openstreetmap.org/search?q=$q&polygon_geojson=1&format=json&limit=5&extratags=1"
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "WhereAmIPersonalApp/1.1 (android)")
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
                return selectBestBoundaryPolygon(array, mustContain = mustContain)
            }
            null
        } catch (e: Exception) {
            TelemetryLogger.log("BOUNDARY", "Network fetch error for $cityName: ${e.message}")
            null
        }
    }

    private fun fetchReverseBoundary(
        lat: Double,
        lon: Double,
        zoom: Int,
        mustContain: GeoPoint? = null
    ): List<GeoPoint>? {
        return try {
            val urlStr =
                "https://nominatim.openstreetmap.org/reverse?format=json&lat=$lat&lon=$lon&zoom=$zoom&polygon_geojson=1&extratags=1"
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "WhereAmIPersonalApp/1.1 (android)")
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
                val obj = JSONObject(body)
                val geojson = obj.optJSONObject("geojson") ?: return null
                val poly = parseGeoJsonPolygon(geojson) ?: return null
                if (mustContain != null && !polygonContains(poly, mustContain.latitude, mustContain.longitude)) {
                    return null
                }
                // Reject powiat / voivodeship / country for pin borders when possible
                val adminLevel = obj.optJSONObject("extratags")?.optString("admin_level")?.toIntOrNull()
                val placeRank = obj.optInt("place_rank", -1).takeIf { it > 0 }
                if (mustContain != null && adminTightnessScore(adminLevel, placeRank) < 0) {
                    return null
                }
                return poly
            }
            null
        } catch (e: Exception) {
            TelemetryLogger.log("BOUNDARY", "Reverse boundary error at ($lat, $lon, zoom=$zoom): ${e.message}")
            null
        }
    }

    /**
     * Ray-casting point-in-polygon. [points] outer ring in GeoPoint(lat, lon) order.
     */
    fun polygonContains(points: List<GeoPoint>, lat: Double, lon: Double): Boolean {
        if (points.size < 3) return false
        var inside = false
        var j = points.size - 1
        for (i in points.indices) {
            val yi = points[i].latitude
            val xi = points[i].longitude
            val yj = points[j].latitude
            val xj = points[j].longitude
            val intersect = ((yi > lat) != (yj > lat)) &&
                (lon < (xj - xi) * (lat - yi) / (yj - yi + 1e-15) + xi)
            if (intersect) inside = !inside
            j = i
        }
        return inside
    }

    fun isCachePolygonUsableForPin(points: List<GeoPoint>, pin: GeoPoint): Boolean {
        return points.size >= 3 && polygonContains(points, pin.latitude, pin.longitude)
    }

    /**
     * Higher score = tighter locality. Negative = reject for pin borders (powiat and coarser).
     * OSM: admin_level 10 ≈ village/sołectwo, 7–8 ≈ gmina, 6 ≈ powiat, 4 ≈ województwo.
     */
    fun adminTightnessScore(adminLevel: Int?, placeRank: Int?): Int {
        if (adminLevel != null) {
            if (adminLevel <= 6) return -100 + adminLevel
            return adminLevel * 10 + (placeRank ?: 0)
        }
        if (placeRank != null) {
            // Nominatim place_rank: higher ≈ more specific; ~16 gmina, ~20 village
            if (placeRank < 14) return -50 + placeRank
            return placeRank
        }
        return 0
    }

    fun parseGeoJsonPolygon(geojson: JSONObject): List<GeoPoint>? {
        val type = geojson.optString("type")
        val coords = geojson.optJSONArray("coordinates") ?: return null

        val result = mutableListOf<GeoPoint>()
        if (type.equals("Polygon", ignoreCase = true)) {
            val outerRing = coords.optJSONArray(0) ?: return null
            for (k in 0 until outerRing.length()) {
                val coordPair = outerRing.optJSONArray(k) ?: continue
                val lon = coordPair.getDouble(0)
                val lat = coordPair.getDouble(1)
                result.add(GeoPoint(lat, lon))
            }
        } else if (type.equals("MultiPolygon", ignoreCase = true)) {
            // Take largest outer ring
            if (coords.length() > 0) {
                val firstPoly = coords.optJSONArray(0)
                if (firstPoly != null && firstPoly.length() > 0) {
                    val outerRing = firstPoly.optJSONArray(0)
                    if (outerRing != null) {
                        for (k in 0 until outerRing.length()) {
                            val coordPair = outerRing.optJSONArray(k) ?: continue
                            val lon = coordPair.getDouble(0)
                            val lat = coordPair.getDouble(1)
                            result.add(GeoPoint(lat, lon))
                        }
                    }
                }
            }
        }
        return if (result.isNotEmpty()) result else null
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
        val res = ArrayList<GeoPoint>(maxPts)
        val step = (pts.size - 1).toDouble() / (maxPts - 1).toDouble()
        for (i in 0 until maxPts - 1) {
            val idx = Math.round(i * step).toInt().coerceIn(0, pts.size - 1)
            res.add(pts[idx])
        }
        res.add(pts.last())
        return res
    }

    /**
     * Evaluates all returned items from Nominatim and selects the best administrative boundary polygon,
     * strongly deprioritizing non-administrative features like airfields (aeroway), buildings, and landuse.
     * When [mustContain] is set, candidates that do not enclose the point are discarded, and tighter
     * admin units (higher admin_level / place_rank) win over gmina / county.
     */
    fun selectBestBoundaryPolygon(
        jsonArray: JSONArray,
        mustContain: GeoPoint? = null
    ): List<GeoPoint>? {
        var bestPolygon: List<GeoPoint>? = null
        var bestScore = -1000

        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            val geojson = obj.optJSONObject("geojson") ?: continue
            val poly = parseGeoJsonPolygon(geojson) ?: continue
            if (poly.isEmpty()) continue

            if (mustContain != null &&
                !polygonContains(poly, mustContain.latitude, mustContain.longitude)
            ) {
                continue
            }

            val osmClass = obj.optString("class", "").lowercase()
            val osmType = obj.optString("type", "").lowercase()
            val entityType = obj.optString("osm_type", "").lowercase()
            val adminLevel = obj.optJSONObject("extratags")?.optString("admin_level")?.toIntOrNull()
            val placeRank = obj.optInt("place_rank", -1).takeIf { it > 0 }

            var score = 10
            if (osmClass == "boundary" && osmType == "administrative") {
                score += 100
            } else if (osmClass == "place") {
                score += 50
            } else if (osmClass in listOf("aeroway", "landuse", "building", "leisure", "amenity", "highway", "natural")) {
                score -= 100
            }

            if (entityType == "relation") {
                score += 50
            } else if (entityType == "way") {
                score += 10
            }

            // Prefer relations with comprehensive vertex sets
            if (poly.size >= 80) {
                score += 20
            }

            val tightness = adminTightnessScore(adminLevel, placeRank)
            if (mustContain != null) {
                if (tightness < 0) continue
                score += tightness
                // Prefer smaller geographic extent among containing candidates (village over gmina)
                val latSpan = poly.maxOf { it.latitude } - poly.minOf { it.latitude }
                val lonSpan = poly.maxOf { it.longitude } - poly.minOf { it.longitude }
                score += (1000 - ((latSpan + lonSpan) * 500).toInt()).coerceIn(0, 1000)
            } else if (tightness > 0) {
                score += tightness / 5
            }

            if (score > bestScore) {
                bestScore = score
                bestPolygon = poly
            }
        }
        return bestPolygon
    }
}
