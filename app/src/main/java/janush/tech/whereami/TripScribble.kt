package janush.tech.whereami

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max

/**
 * Detects auto-started **vehicle** trips that are really indoor / courtyard GNSS multipath nests
 * (field trip 239: 13 min Driving, 0.72 km path, 7 m net, 27 m cluster).
 *
 * Must not discard real short walks / out-and-back loops (field trip 241: Walking 0.19 km, net ~11 m
 * because start≈end, cluster ~39 m — discarded under the first overly broad rule).
 */
object TripScribble {
    /** Max cluster radius (m) for a vehicle indoor nest. */
    const val MAX_CLUSTER_M = 35.0
    /** Max start→end net displacement (m). */
    const val MAX_NET_M = 30.0
    /**
     * Path must be this many times the cluster radius (heavy multipath looping).
     * Trip 239 ≈ 26; trip 241 walk ≈ 4.9 (must stay).
     */
    const val MIN_PATH_OVER_CLUSTER = 12.0
    /** Minimum path length (m) — short real loops stay. */
    const val MIN_PATH_M = 200.0
    /** Minimum duration so a brief accidental start is still handled by [minValid*] trivial discard. */
    const val MIN_DURATION_MS = 90_000L

    data class LatLng(val lat: Double, val lng: Double)

    fun isIndoorScribble(
        isAuto: Boolean,
        durationMs: Long,
        pathDistanceM: Double,
        points: List<LatLng>,
        profile: ActivityProfile
    ): Boolean {
        // Foot profiles: out-and-back near home is normal. Never use nest geometry against them.
        if (!isAuto || !profile.isVehicleProfile || points.size < 8 || durationMs < MIN_DURATION_MS) {
            return false
        }
        val net = distanceM(points.first(), points.last())
        if (net > MAX_NET_M) return false
        val cluster = clusterRadiusM(points)
        if (cluster > MAX_CLUSTER_M) return false
        if (pathDistanceM < MIN_PATH_M) return false
        val pathOverCluster = pathDistanceM / max(cluster, 1.0)
        return pathOverCluster >= MIN_PATH_OVER_CLUSTER
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
