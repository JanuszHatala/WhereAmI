package com.example.whereiam

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Mountain and trekking environment detector.
 * Resolves nearby mountain peaks, passes, elevations, and tourist hiking trails via OSM.
 */
object TrekkingHelper {

    data class TrekkingInfo(
        val peakName: String? = null,
        val elevationMeters: Int? = null,
        val passName: String? = null,
        val trailName: String? = null,
        val trailColor: String? = null
    ) {
        val displayBadge: String?
            get() {
                val parts = mutableListOf<String>()
                if (peakName != null) {
                    val eleStr = if (elevationMeters != null) " (" + elevationMeters + "m)" else ""
                    parts.add("⛰️ " + peakName + eleStr)
                } else if (passName != null) {
                    parts.add("🚩 " + passName)
                }
                if (trailName != null) {
                    parts.add("🥾 " + trailName)
                }
                return if (parts.isNotEmpty()) parts.joinToString(" • ") else null
            }
    }

    private var cachedInfo: TrekkingInfo? = null
    private var cachedLat: Double = 0.0
    private var cachedLng: Double = 0.0
    private var lastQueryTime: Long = 0L

    suspend fun resolveTrekkingInfo(lat: Double, lng: Double): TrekkingInfo? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (cachedInfo != null && (now - lastQueryTime) < 180_000L) {
            val dist = FloatArray(1)
            android.location.Location.distanceBetween(lat, lng, cachedLat, cachedLng, dist)
            if (dist[0] < 200f) return@withContext cachedInfo
        }

        try {
            val urlStr = "https://nominatim.openstreetmap.org/reverse?format=json&lat=" + lat + "&lon=" + lng + "&zoom=16&extratags=1&addressdetails=1"
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "WhereIAmPersonalApp/1.3 (trekking)")
            conn.connectTimeout = 4000
            conn.readTimeout = 4000

            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().readText()
                val root = JSONObject(body)
                val extraTags = root.optJSONObject("extratags")

                val natural = extraTags?.optString("natural")
                val eleStr = extraTags?.optString("ele")
                val ele = eleStr?.toIntOrNull()

                var peak: String? = null
                var pass: String? = null

                if (natural == "peak" || natural == "hill" || natural == "volcano") {
                    peak = root.optString("name").takeIf { it.isNotEmpty() }
                }

                if (extraTags?.optString("mountain_pass") == "yes") {
                    pass = root.optString("name").takeIf { it.isNotEmpty() }
                }

                val info = TrekkingInfo(
                    peakName = peak,
                    elevationMeters = ele,
                    passName = pass,
                    trailName = extraTags?.optString("route").takeIf { it == "hiking" }
                )

                cachedInfo = info
                cachedLat = lat
                cachedLng = lng
                lastQueryTime = now
                return@withContext info
            }
        } catch (_: Exception) {
            // Offline fallback
        }
        return@withContext cachedInfo
    }
}
