package janush.tech.whereami

import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.net.URL
import javax.net.ssl.HttpsURLConnection

object ElevationRepository {
    private const val BASE_URL = "https://api.open-elevation.com/api/v1/lookup"
    private const val BATCH_SIZE = 100
    private const val INTER_BATCH_DELAY_MS = 150L
    private const val MAX_RETRIES = 3

    /**
     * Fetches DEM altitudes for [points]. Returns a parallel list (null = fetch failed for that batch/point).
     * [onProgress] receives (completedPoints, totalPoints).
     */
    suspend fun fetchAltitudes(
        points: List<GeoPoint>,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): List<Double?> {
        val result = MutableList<Double?>(points.size) { null }
        if (points.isEmpty()) return result

        val batches = points.indices.chunked(BATCH_SIZE)
        for ((batchIdx, indices) in batches.withIndex()) {
            val batchPoints = indices.map { points[it] }
            val altitudes = fetchBatchWithRetry(batchPoints)
            if (altitudes != null) {
                indices.forEachIndexed { i, ptIdx ->
                    result[ptIdx] = altitudes.getOrNull(i)
                }
            }
            val doneCount = ((batchIdx + 1) * BATCH_SIZE).coerceAtMost(points.size)
            onProgress(doneCount, points.size)
            if (batchIdx < batches.lastIndex) {
                delay(INTER_BATCH_DELAY_MS)
            }
        }
        return result
    }

    private suspend fun fetchBatchWithRetry(points: List<GeoPoint>): List<Double>? {
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

                conn = (URL(BASE_URL).openConnection() as HttpsURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json")
                    doOutput = true
                    connectTimeout = 10_000
                    readTimeout = 15_000
                }

                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

                val code = conn.responseCode
                if (code == 429 || code >= 500) {
                    delay(1_000L * (attempt + 1))
                    return@repeat
                }
                if (code != 200) {
                    return null
                }

                val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(jsonStr)
                val resultsArr = root.getJSONArray("results")
                val list = mutableListOf<Double>()
                for (i in 0 until resultsArr.length()) {
                    list.add(resultsArr.getJSONObject(i).getDouble("elevation"))
                }
                return list
            } catch (_: Exception) {
                if (attempt < MAX_RETRIES - 1) {
                    delay(800L * (attempt + 1))
                }
            } finally {
                conn?.disconnect()
            }
        }
        return null
    }
}
