package janush.tech.whereami

import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.net.URL
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

object ElevationRepository {
    private const val OPEN_METEO_BASE = "https://api.open-meteo.com/v1/elevation"
    private const val OPEN_ELEVATION_BASE = "https://api.open-elevation.com/api/v1/lookup"
    private const val BATCH_SIZE = 80
    private const val INTER_BATCH_DELAY_MS = 100L
    private const val MAX_RETRIES = 2

    /**
     * Synchronous cache-only check. Returns cached elevation if present in SQLite, null otherwise.
     */
    fun getCachedElevationOnly(latitude: Double, longitude: Double, db: TripDatabaseHelper?): Double? {
        return db?.getCachedElevation(latitude, longitude)
    }

    /**
     * Fetches DEM altitude for a single coordinate point.
     * Checks local SQLite cache first. If missed, queries Open-Meteo (falling back to Open-Elevation),
     * and caches the result into SQLite.
     * Returns null if offline or fetch fails.
     */
    suspend fun getElevation(
        latitude: Double,
        longitude: Double,
        db: TripDatabaseHelper? = null
    ): Double? {
        if (db != null) {
            val cached = db.getCachedElevation(latitude, longitude)
            if (cached != null && cached > 0.0) {
                return cached
            }
        }
        val list = fetchAltitudes(listOf(GeoPoint(latitude, longitude)), db = db)
        return list.firstOrNull()
    }

    /**
     * Fetches DEM altitudes for [points]. Checks local SQLite cache first, querying the network
     * only for cache misses. Newly resolved points are written to SQLite cache.
     * Returns a parallel list (null = fetch failed for that point).
     */
    suspend fun fetchAltitudes(
        points: List<GeoPoint>,
        db: TripDatabaseHelper? = null,
        isCancelled: () -> Boolean = { false },
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): List<Double?> {
        val result = MutableList<Double?>(points.size) { null }
        if (points.isEmpty()) return result

        val missingIndices = mutableListOf<Int>()
        for (i in points.indices) {
            val p = points[i]
            val cached = db?.getCachedElevation(p.latitude, p.longitude)
            if (cached != null && cached > 0.0) {
                result[i] = cached
            } else {
                missingIndices.add(i)
            }
        }

        if (missingIndices.isEmpty()) {
            onProgress(points.size, points.size)
            return result
        }

        onProgress(points.size - missingIndices.size, points.size)

        val batches = missingIndices.chunked(BATCH_SIZE)
        var completedSoFar = points.size - missingIndices.size

        for ((batchIdx, indices) in batches.withIndex()) {
            if (isCancelled()) break
            val batchPoints = indices.map { points[it] }

            // 1. Try Open-Meteo Primary
            var altitudes = fetchBatchOpenMeteo(batchPoints)

            // 2. Fallback to Open-Elevation if Open-Meteo failed
            if (altitudes == null) {
                altitudes = fetchBatchOpenElevation(batchPoints)
            }

            if (altitudes != null) {
                val cacheEntries = mutableListOf<Triple<Double, Double, Double>>()
                indices.forEachIndexed { i, ptIdx ->
                    val alt = altitudes.getOrNull(i)
                    if (alt != null && alt > 0.0) {
                        result[ptIdx] = alt
                        val pt = points[ptIdx]
                        cacheEntries.add(Triple(pt.latitude, pt.longitude, alt))
                    }
                }
                if (cacheEntries.isNotEmpty() && db != null) {
                    db.putCachedElevations(cacheEntries)
                }
            }

            completedSoFar += indices.size
            onProgress(completedSoFar.coerceAtMost(points.size), points.size)

            if (batchIdx < batches.lastIndex) {
                delay(INTER_BATCH_DELAY_MS)
            }
        }

        return result
    }

    /**
     * Open-Meteo elevation query (comma-separated coordinates in GET URL).
     */
    private suspend fun fetchBatchOpenMeteo(points: List<GeoPoint>): List<Double>? {
        repeat(MAX_RETRIES) { attempt ->
            var conn: HttpsURLConnection? = null
            try {
                val lats = points.joinToString(",") { String.format(Locale.US, "%.5f", it.latitude) }
                val lngs = points.joinToString(",") { String.format(Locale.US, "%.5f", it.longitude) }
                val urlStr = "$OPEN_METEO_BASE?latitude=$lats&longitude=$lngs"

                conn = (URL(urlStr).openConnection() as HttpsURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/json")
                    connectTimeout = 8_000
                    readTimeout = 12_000
                }

                val code = conn.responseCode
                if (code == 429 || code >= 500) {
                    delay(800L * (attempt + 1))
                    return@repeat
                }
                if (code != 200) {
                    return null
                }

                val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(jsonStr)
                if (!root.has("elevation")) return null
                val resultsArr = root.getJSONArray("elevation")
                val list = mutableListOf<Double>()
                for (i in 0 until resultsArr.length()) {
                    if (resultsArr.isNull(i)) {
                        list.add(0.0)
                    } else {
                        list.add(resultsArr.getDouble(i))
                    }
                }
                if (list.size == points.size) {
                    return list
                }
            } catch (_: Exception) {
                if (attempt < MAX_RETRIES - 1) {
                    delay(600L * (attempt + 1))
                }
            } finally {
                conn?.disconnect()
            }
        }
        return null
    }

    /**
     * Open-Elevation fallback query (POST JSON).
     */
    private suspend fun fetchBatchOpenElevation(points: List<GeoPoint>): List<Double>? {
        repeat(MAX_RETRIES) { attempt ->
            var conn: HttpsURLConnection? = null
            try {
                val body = JSONObject().apply {
                    val arr = JSONArray()
                    points.forEach { p ->
                        arr.put(JSONObject().apply {
                            put("latitude", p.latitude)
                            put("longitude", p.longitude)
                        })
                    }
                    put("locations", arr)
                }.toString()

                conn = (URL(OPEN_ELEVATION_BASE).openConnection() as HttpsURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json")
                    doOutput = true
                    connectTimeout = 8_000
                    readTimeout = 12_000
                }

                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

                val code = conn.responseCode
                if (code == 429 || code >= 500) {
                    delay(800L * (attempt + 1))
                    return@repeat
                }
                if (code != 200) {
                    return null
                }

                val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(jsonStr)
                if (!root.has("results")) return null
                val resultsArr = root.getJSONArray("results")
                val list = mutableListOf<Double>()
                for (i in 0 until resultsArr.length()) {
                    list.add(resultsArr.getJSONObject(i).getDouble("elevation"))
                }
                if (list.size == points.size) {
                    return list
                }
            } catch (_: Exception) {
                if (attempt < MAX_RETRIES - 1) {
                    delay(600L * (attempt + 1))
                }
            } finally {
                conn?.disconnect()
            }
        }
        return null
    }
}
