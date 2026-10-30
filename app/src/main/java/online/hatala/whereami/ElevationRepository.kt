package online.hatala.whereami

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.net.URL
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

object ElevationRepository {
    private const val TAG = "ElevationRepository"
    private const val OPEN_METEO_BASE = "https://api.open-meteo.com/v1/elevation"
    private const val OPENTOPO_EUDEM_BASE = "https://api.opentopodata.org/v1/eudem25m"
    private const val OPENTOPO_SRTM_BASE = "https://api.opentopodata.org/v1/srtm30m"
    private const val OPEN_ELEVATION_BASE = "https://api.open-elevation.com/api/v1/lookup"
    private const val BATCH_SIZE = 80
    private const val INTER_BATCH_DELAY_MS = 100L
    private const val MAX_RETRIES = 2

    @Volatile
    private var openMeteoBlockedUntil = 0L

    @Volatile
    private var openElevationBlockedUntil = 0L

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
    ): Double? = withContext(Dispatchers.IO) {
        if (db != null) {
            val cached = db.getCachedElevation(latitude, longitude)
            if (cached != null && cached > 0.0) {
                return@withContext cached
            }
        }
        val list = fetchAltitudes(listOf(GeoPoint(latitude, longitude)), db = db, isSingleLookup = true)
        list.firstOrNull()
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
        isSingleLookup: Boolean = false,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): List<Double?> = withContext(Dispatchers.IO) {
        val result = MutableList<Double?>(points.size) { null }
        if (points.isEmpty()) return@withContext result

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
            return@withContext result
        }

        onProgress(points.size - missingIndices.size, points.size)

        val batches = missingIndices.chunked(BATCH_SIZE)
        var completedSoFar = points.size - missingIndices.size

        for ((batchIdx, indices) in batches.withIndex()) {
            if (isCancelled()) break
            val batchPoints = indices.map { points[it] }

            // 1. Try Open-Meteo Primary (if not quota-blocked)
            var altitudes: List<Double>? = null
            if (System.currentTimeMillis() >= openMeteoBlockedUntil) {
                altitudes = fetchBatchOpenMeteo(batchPoints, isSingleLookup)
            }

            // 2. Try OpenTopoData (EU-DEM 25m / SRTM 30m)
            if (altitudes == null) {
                altitudes = fetchBatchOpenTopoData(batchPoints, isSingleLookup)
            }

            // 3. Fallback to Open-Elevation (if not quota-blocked)
            if (altitudes == null && System.currentTimeMillis() >= openElevationBlockedUntil) {
                altitudes = fetchBatchOpenElevation(batchPoints, isSingleLookup)
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

        result
    }

    /**
     * Open-Meteo elevation query (comma-separated coordinates in GET URL).
     */
    private suspend fun fetchBatchOpenMeteo(points: List<GeoPoint>, isSingleLookup: Boolean = false): List<Double>? {
        val retries = if (isSingleLookup) 1 else MAX_RETRIES
        val connTimeout = if (isSingleLookup) 3_000 else 8_000
        val rTimeout = if (isSingleLookup) 3_000 else 12_000

        repeat(retries) { attempt ->
            var conn: HttpsURLConnection? = null
            try {
                val lats = points.joinToString(",") { String.format(Locale.US, "%.5f", it.latitude) }
                val lngs = points.joinToString(",") { String.format(Locale.US, "%.5f", it.longitude) }
                val urlStr = "$OPEN_METEO_BASE?latitude=$lats&longitude=$lngs"

                conn = (URL(urlStr).openConnection() as HttpsURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/json")
                    connectTimeout = connTimeout
                    readTimeout = rTimeout
                }

                val code = conn.responseCode
                if (code == 429) {
                    // Daily limit exceeded on Open-Meteo -> failover immediately for 30 min
                    openMeteoBlockedUntil = System.currentTimeMillis() + 1_800_000L
                    return null
                }
                if (code >= 500) {
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
            } catch (e: Exception) {
                Log.w(TAG, "Open-Meteo elevation fetch attempt $attempt failed: ${e.message}")
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
     * OpenTopoData query (EU-DEM 25m for Europe, SRTM 30m global fallback).
     * No API key required, highly reliable open-source elevation server.
     */
    private suspend fun fetchBatchOpenTopoData(points: List<GeoPoint>, isSingleLookup: Boolean = false): List<Double>? {
        if (points.isEmpty()) return emptyList()
        val locParam = points.joinToString("|") { String.format(Locale.US, "%.5f,%.5f", it.latitude, it.longitude) }

        // 1. Try EU-DEM 25m (~25m high-resolution European DEM)
        val euResults = queryOpenTopoDataset(OPENTOPO_EUDEM_BASE, locParam, points.size, isSingleLookup)
        if (euResults != null && euResults.all { it > 0.0 }) {
            return euResults
        }

        // 2. Fallback to SRTM 30m (~30m global elevation)
        val srtmResults = queryOpenTopoDataset(OPENTOPO_SRTM_BASE, locParam, points.size, isSingleLookup)
        if (srtmResults != null) {
            if (euResults != null) {
                return euResults.mapIndexed { i, alt -> if (alt > 0.0) alt else srtmResults.getOrElse(i) { 0.0 } }
            }
            return srtmResults
        }

        return euResults
    }

    private suspend fun queryOpenTopoDataset(baseUrl: String, locParam: String, expectedSize: Int, isSingleLookup: Boolean = false): List<Double>? {
        var conn: HttpsURLConnection? = null
        val connTimeout = if (isSingleLookup) 3_000 else 8_000
        val rTimeout = if (isSingleLookup) 3_000 else 12_000
        try {
            val urlStr = "$baseUrl?locations=$locParam"
            conn = (URL(urlStr).openConnection() as HttpsURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                connectTimeout = connTimeout
                readTimeout = rTimeout
            }
            if (conn.responseCode != 200) return null
            val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(jsonStr)
            if (root.optString("status") != "OK" || !root.has("results")) return null
            val arr = root.getJSONArray("results")
            val list = mutableListOf<Double>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val elev = if (obj.isNull("elevation")) 0.0 else obj.getDouble("elevation")
                list.add(elev)
            }
            if (list.size == expectedSize) return list
        } catch (e: Exception) {
            Log.w(TAG, "OpenTopoData query failed ($baseUrl): ${e.message}")
            return null
        } finally {
            conn?.disconnect()
        }
        return null
    }

    /**
     * Open-Elevation fallback query (POST JSON).
     */
    private suspend fun fetchBatchOpenElevation(points: List<GeoPoint>, isSingleLookup: Boolean = false): List<Double>? {
        val retries = if (isSingleLookup) 1 else MAX_RETRIES
        val connTimeout = if (isSingleLookup) 3_000 else 8_000
        val rTimeout = if (isSingleLookup) 3_000 else 12_000

        repeat(retries) { attempt ->
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
                    connectTimeout = connTimeout
                    readTimeout = rTimeout
                }

                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

                val code = conn.responseCode
                if (code == 429 || code == 400) {
                    openElevationBlockedUntil = System.currentTimeMillis() + 1_800_000L
                    return null
                }
                if (code >= 500) {
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
            } catch (e: Exception) {
                Log.w(TAG, "Open-Elevation query attempt $attempt failed: ${e.message}")
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
