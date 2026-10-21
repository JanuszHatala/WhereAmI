package janush.tech.whereami

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max

/**
 * Detects auto-started "trips" that are really indoor / courtyard GNSS multipath nests
 * (field trip 239: 13 min Driving, 0.72 km path, 7 m net, 27 m cluster).
 */
object TripScribble {
    /** Max cluster radius (m) for a scribble discard. */
    const val MAX_CLUSTER_M = 40.0
    /** Max start→end net displacement (m). */
    const val MAX_NET_M = 40.0
    /** Path length must exceed net by at least this factor (multipath loops). */
    const val MIN_PATH_OVER_NET = 4.0
    /** Minimum duration so a brief real pull-out is not discarded. */
    const val MIN_DURATION_MS = 90_000L

    data class LatLng(val lat: Double, val lng: Double)

    fun isIndoorScribble(
        isAuto: Boolean,
        durationMs: Long,
        pathDistanceM: Double,
        points: List<LatLng>
    ): Boolean {
        if (!isAuto || points.size < 8 || durationMs < MIN_DURATION_MS) return false
        val net = distanceM(points.first(), points.last())
        if (net > MAX_NET_M) return false
        val cluster = clusterRadiusM(points)
        if (cluster > MAX_CLUSTER_M) return false
        // Path much longer than net = looping multipath; tiny path with long dwell is also junk.
        if (pathDistanceM >= max(MIN_PATH_OVER_NET * max(net, 1.0), 80.0)) return true
        return pathDistanceM < 100.0 && cluster < 25.0
    }

    fun clusterRadiusM(points: List<LatLng>): Double {
        if (points.isEmpty()) return 0.0
        val cLat = points.map { it.lat }.average()
        val cLng = points.map { it.lng }.average()
        val c = LatLng(cLat, cLng)
        return points.maxOf { distanceM(c, it) }
    }

    fun distanceM(a: LatLng, b: LatLng): Double {
        val k = cos(Math.toRadians((a.lat + b.lat) / 2.0))
        return hypot((b.lng - a.lng) * k, b.lat - a.lat) * METERS_PER_DEG
    }

    private const val METERS_PER_DEG = 111_320.0
}
