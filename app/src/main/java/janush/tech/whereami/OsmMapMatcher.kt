package janush.tech.whereami

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

/**
 * Uses OSRM (Open Source Routing Machine) nearest API to snap GPS coordinates to the 
 * actual road geometry using the vehicle's compass bearing.
 * This completely eliminates "perpendicular cross-street snapping" where point-based
 * geocoding snaps to a side street because it happens to be 2 meters closer to the raw GPS dot.
 */
object OsmMapMatcher {
    private var lastRequestTime = 0L
    private var rateLimitCooldownUntil = 0L

    @Synchronized
    fun getNearestStreet(lat: Double, lng: Double, bearing: Float?): String? {
        val now = System.currentTimeMillis()
        if (now < rateLimitCooldownUntil) return null

        val elapsed = now - lastRequestTime
        if (elapsed < 1100L) {
            // Respect OSRM public API demo server rate limit (~1 req/sec)
            try {
                Thread.sleep(1100L - elapsed)
            } catch (_: InterruptedException) {
                return null
            }
        }
        lastRequestTime = System.currentTimeMillis()

        // If bearing is provided, apply a +/- 35 degree tolerance window
        // This forces the routing engine to reject perpendicular streets (e.g. cross streets)
        val bearingParam = if (bearing != null && bearing >= 0f) {
            "&bearings=${bearing.roundToInt()},35"
        } else {
            ""
        }

        val urlStr = "https://router.project-osrm.org/nearest/v1/driving/$lng,$lat?number=1$bearingParam"

        return try {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "WhereAmIPersonalApp/1.1 (android)")
            conn.connectTimeout = 4000
            conn.readTimeout = 4000

            if (conn.responseCode == 429) {
                rateLimitCooldownUntil = System.currentTimeMillis() + 60_000L
                TelemetryLogger.log("OSRM", "HTTP 429 Too Many Requests; 60s cooldown active")
                return null
            }

            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().readText()
                val root = JSONObject(body)
                
                val waypoints = root.optJSONArray("waypoints")
                if (waypoints != null && waypoints.length() > 0) {
                    val waypoint = waypoints.getJSONObject(0)
                    val name = waypoint.optString("name")
                    // OSRM returns empty string if unnamed
                    return name.takeIf { it.isNotBlank() }
                }
            }
            null
        } catch (e: Exception) {
            TelemetryLogger.log("OSRM", "Error fetching from OSRM: ${e.message}")
            null
        }
    }
}
