package com.example.whereiam

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class BoundaryPolygon(
    val placeName: String,
    val points: List<GeoPoint>
)

object BoundaryHelper {
    // Memory cache for fetched boundary polygons
    private val boundaryCache = mutableMapOf<String, List<GeoPoint>>()

    suspend fun getLocalityBoundary(
        context: Context,
        cityName: String,
        countryCode: String
    ): List<GeoPoint>? = withContext(Dispatchers.IO) {
        if (cityName.isBlank() || cityName == "Unknown City" || cityName == "--") return@withContext null

        val cacheKey = "${cityName.trim().lowercase()}_${countryCode.trim().lowercase()}"
        boundaryCache[cacheKey]?.let { return@withContext it }

        try {
            val q = URLEncoder.encode("$cityName, $countryCode", "UTF-8")
            val urlStr = "https://nominatim.openstreetmap.org/search?q=$q&polygon_geojson=1&format=json&limit=1"
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "WhereIAmPersonalApp/1.1 (android)")
            conn.connectTimeout = 5000
            conn.readTimeout = 5000

            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().readText()
                val array = JSONArray(body)
                if (array.length() > 0) {
                    val obj = array.getJSONObject(0)
                    val geojson = obj.optJSONObject("geojson") ?: return@withContext null
                    val type = geojson.optString("type")
                    val coords = geojson.optJSONArray("coordinates") ?: return@withContext null

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
                        // Take the primary / largest polygon
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

                    if (result.isNotEmpty()) {
                        val simplified = subsamplePoints(result, 120)
                        boundaryCache[cacheKey] = simplified
                        return@withContext simplified
                    }
                }
            }
        } catch (_: Exception) {}

        return@withContext null
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
