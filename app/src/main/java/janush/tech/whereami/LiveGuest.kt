package janush.tech.whereami

import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.util.Locale

data class LiveGuest(
    val id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val accuracy: Float? = null,
    val lastUpdated: Long = System.currentTimeMillis(),
    val colorIndex: Int = 0,
    val isInactive: Boolean = false,
    val firstSeen: Long = lastUpdated,
    val viewCount: Int = 1
) {
    val geoPoint: GeoPoint
        get() = GeoPoint(lat, lng)

    fun getBearingFrom(hostLat: Double, hostLng: Double): Float {
        val lat1 = Math.toRadians(hostLat)
        val lon1 = Math.toRadians(hostLng)
        val lat2 = Math.toRadians(lat)
        val lon2 = Math.toRadians(lng)
        val dLon = lon2 - lon1
        val y = Math.sin(dLon) * Math.cos(lat2)
        val x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon)
        val brng = Math.toDegrees(Math.atan2(y, x)).toFloat()
        return (brng + 360f) % 360f
    }

    fun getDirectionCompass(hostLat: Double, hostLng: Double): Pair<String, String> {
        val bearing = getBearingFrom(hostLat, hostLng)
        val idx = (Math.round(bearing / 45f) % 8 + 8) % 8
        return when (idx) {
            0 -> "N" to "↑"
            1 -> "NE" to "↗"
            2 -> "E" to "→"
            3 -> "SE" to "↘"
            4 -> "S" to "↓"
            5 -> "SW" to "↙"
            6 -> "W" to "←"
            7 -> "NW" to "↖"
            else -> "N" to "↑"
        }
    }

    companion object {
        val PALETTE_COLORS = intArrayOf(
            0xFF10B981.toInt(), // Emerald
            0xFFF59E0B.toInt(), // Amber
            0xFF8B5CF6.toInt(), // Violet
            0xFF06B6D4.toInt(), // Cyan
            0xFFF43F5E.toInt(), // Rose
            0xFF3B82F6.toInt(), // Blue
            0xFFEC4899.toInt(), // Pink
            0xFF84CC16.toInt()  // Lime
        )

        fun getColor(index: Int): Int {
            val i = (index % PALETTE_COLORS.size + PALETTE_COLORS.size) % PALETTE_COLORS.size
            return PALETTE_COLORS[i]
        }

        fun formatDistance(meters: Float): String {
            return if (meters < 1000f) {
                "${Math.round(meters)} m"
            } else {
                String.format(Locale.US, "%.1f km", meters / 1000f)
            }
        }

        fun formatTimeAgo(timestamp: Long, now: Long = System.currentTimeMillis()): String {
            val diffSec = Math.max(0L, (now - timestamp) / 1000L)
            return when {
                diffSec < 15 -> "just now"
                diffSec < 60 -> "${diffSec}s ago"
                diffSec < 3600 -> "${diffSec / 60}m ago"
                diffSec < 86400 -> "${diffSec / 3600}h ${(diffSec % 3600) / 60}m ago"
                else -> "${diffSec / 86400}d ago"
            }
        }

        fun formatDurationElapsed(fromTimestamp: Long, toTimestamp: Long = System.currentTimeMillis()): String {
            val diffSec = Math.max(0L, (toTimestamp - fromTimestamp) / 1000L)
            return when {
                diffSec < 60 -> "${diffSec}s"
                diffSec < 3600 -> "${diffSec / 60}m"
                diffSec < 86400 -> "${diffSec / 3600}h ${(diffSec % 3600) / 60}m"
                else -> "${diffSec / 86400}d ${(diffSec % 86400) / 3600}h"
            }
        }

        fun fromJson(json: JSONObject): LiveGuest? {
            val id = json.optString("id").takeIf { it.isNotBlank() } ?: return null
            val lat = json.optDouble("lat", Double.NaN)
            val lng = json.optDouble("lng", Double.NaN)
            if (lat.isNaN() || lng.isNaN()) return null

            val name = json.optString("name", "Guest")
            val acc = if (json.has("acc") && !json.isNull("acc")) json.optDouble("acc").toFloat() else null
            val t = json.optLong("t", System.currentTimeMillis())
            val colorIndex = json.optInt("colorIndex", 0)
            val isInactive = json.optBoolean("isInactive", false)
            val firstSeen = json.optLong("firstSeen", t)
            val viewCount = json.optInt("viewCount", 1).coerceAtLeast(1)

            return LiveGuest(
                id = id,
                name = name,
                lat = lat,
                lng = lng,
                accuracy = acc,
                lastUpdated = t,
                colorIndex = colorIndex,
                isInactive = isInactive,
                firstSeen = firstSeen,
                viewCount = viewCount
            )
        }
    }
}
