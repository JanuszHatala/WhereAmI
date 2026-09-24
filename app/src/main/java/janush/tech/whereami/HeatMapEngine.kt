package janush.tech.whereami

import org.osmdroid.util.GeoPoint
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

enum class HeatMapTier(val tierLevel: Int, val colorArgb: Int, val strokeWidth: Float) {
    TIER_1_COLD(1, 0x8838BDF8.toInt(), 4.5f),      // Sky Blue (translucent ~53% alpha)
    TIER_2_WARM(2, 0xBBFBBF24.toInt(), 6.5f),      // Amber / Gold (~73% alpha)
    TIER_3_HOT(3, 0xDDF97316.toInt(), 8.5f),       // Sunset Orange (~87% alpha)
    TIER_4_PEAK(4, 0xFFEF4444.toInt(), 11.0f);     // Crimson Red (100% alpha)

    companion object {
        fun fromLevel(level: Int): HeatMapTier = when (level) {
            4 -> TIER_4_PEAK
            3 -> TIER_3_HOT
            2 -> TIER_2_WARM
            else -> TIER_1_COLD
        }
    }
}

data class ProcessedHeatMap(
    val tierPolylines: Map<HeatMapTier, List<List<GeoPoint>>>,
    val allSimplifiedPoints: List<GeoPoint>,
    val maxVisits: Int
)

object HeatMapEngine {

    /**
     * Simplifies a polyline using the Ramer-Douglas-Peucker algorithm.
     * Epsilon is in meters (default 10m).
     */
    fun simplifyRdp(points: List<GeoPoint>, epsilonMeters: Double = 10.0): List<GeoPoint> {
        if (points.size <= 2) return points

        var maxDistance = 0.0
        var index = 0
        val start = points.first()
        val end = points.last()

        for (i in 1 until points.size - 1) {
            val dist = perpendicularDistanceMeters(points[i], start, end)
            if (dist > maxDistance) {
                maxDistance = dist
                index = i
            }
        }

        return if (maxDistance > epsilonMeters) {
            val left = simplifyRdp(points.subList(0, index + 1), epsilonMeters)
            val right = simplifyRdp(points.subList(index, points.size), epsilonMeters)
            left.dropLast(1) + right
        } else {
            listOf(start, end)
        }
    }

    private fun perpendicularDistanceMeters(pt: GeoPoint, lineStart: GeoPoint, lineEnd: GeoPoint): Double {
        val latMid = (lineStart.latitude + lineEnd.latitude) / 2.0
        val cosLat = cos(Math.toRadians(latMid))
        val metersPerDegLat = 111139.0
        val metersPerDegLon = 111139.0 * cosLat

        val dx = (lineEnd.longitude - lineStart.longitude) * metersPerDegLon
        val dy = (lineEnd.latitude - lineStart.latitude) * metersPerDegLat
        val lineLen = sqrt(dx * dx + dy * dy)

        if (lineLen < 0.5) {
            val pDx = (pt.longitude - lineStart.longitude) * metersPerDegLon
            val pDy = (pt.latitude - lineStart.latitude) * metersPerDegLat
            return sqrt(pDx * pDx + pDy * pDy)
        }

        val num = abs(
            dy * ((pt.longitude - lineStart.longitude) * metersPerDegLon) -
            dx * ((pt.latitude - lineStart.latitude) * metersPerDegLat)
        )
        return num / lineLen
    }

    /**
     * Computes a 64-bit spatial grid hash cell (~35m resolution).
     */
    fun cellKey(lat: Double, lon: Double): Long {
        val latCell = (lat * 3000.0).toLong()
        val lonCell = (lon * 3000.0).toLong()
        return (latCell shl 32) or (lonCell and 0xFFFFFFFFL)
    }

    /**
     * Determines thermal tier (1 to 4) given the segment visit count and maximum visit count across the dataset.
     */
    fun determineTier(visitCount: Int, maxVisits: Int): HeatMapTier {
        if (visitCount <= 1 || maxVisits <= 1) return HeatMapTier.TIER_1_COLD

        val level = when {
            maxVisits >= 7 -> {
                when {
                    visitCount >= 7 -> 4
                    visitCount >= 4 -> 3
                    visitCount >= 2 -> 2
                    else -> 1
                }
            }
            maxVisits >= 4 -> {
                when {
                    visitCount >= maxVisits -> 4
                    visitCount >= (maxVisits * 0.6).toInt().coerceAtLeast(3) -> 3
                    visitCount >= 2 -> 2
                    else -> 1
                }
            }
            maxVisits == 3 -> {
                when (visitCount) {
                    3 -> 4
                    2 -> 2
                    else -> 1
                }
            }
            maxVisits == 2 -> {
                if (visitCount >= 2) 4 else 1
            }
            else -> 1
        }
        return HeatMapTier.fromLevel(level)
    }

    /**
     * Processes all tracks:
     * 1. Decimates each track using RDP (~10m).
     * 2. Accumulates distinct trip visits per ~35m spatial cell along segments.
     * 3. Classifies each segment into one of 4 thermal tiers.
     * 4. Batches adjacent segments of the same tier into continuous polyline paths.
     */
    fun processTracks(tracks: List<List<GeoPoint>>, epsilonMeters: Double = 10.0): ProcessedHeatMap {
        if (tracks.isEmpty()) {
            return ProcessedHeatMap(emptyMap(), emptyList(), 0)
        }

        // 1. Simplify all tracks
        val simplifiedTracks = tracks.map { track ->
            if (track.size > 2) simplifyRdp(track, epsilonMeters) else track
        }.filter { it.size >= 2 }

        if (simplifiedTracks.isEmpty()) {
            return ProcessedHeatMap(emptyMap(), emptyList(), 0)
        }

        // 2. Spatial frequency mapping with distinct track IDs
        // Cell -> Set of track indices that traversed this cell
        val cellVisits = HashMap<Long, MutableSet<Int>>()

        simplifiedTracks.forEachIndexed { trackIdx, track ->
            for (i in 0 until track.size - 1) {
                val p1 = track[i]
                val p2 = track[i + 1]
                val distMeters = approximateDistanceMeters(p1, p2)
                val stepCount = (distMeters / 25.0).toInt().coerceIn(1, 100)

                for (s in 0..stepCount) {
                    val t = s.toDouble() / stepCount
                    val lat = p1.latitude + t * (p2.latitude - p1.latitude)
                    val lon = p1.longitude + t * (p2.longitude - p1.longitude)
                    val key = cellKey(lat, lon)
                    cellVisits.getOrPut(key) { mutableSetOf() }.add(trackIdx)
                }
            }
        }

        val maxVisits = cellVisits.values.maxOfOrNull { it.size } ?: 1

        // 3. Segment tier assignment and adjacent batching
        val tierPolylines = mutableMapOf<HeatMapTier, MutableList<List<GeoPoint>>>(
            HeatMapTier.TIER_1_COLD to mutableListOf(),
            HeatMapTier.TIER_2_WARM to mutableListOf(),
            HeatMapTier.TIER_3_HOT to mutableListOf(),
            HeatMapTier.TIER_4_PEAK to mutableListOf()
        )

        simplifiedTracks.forEach { track ->
            if (track.size < 2) return@forEach

            var currentTier: HeatMapTier? = null
            var currentPath = mutableListOf<GeoPoint>()

            for (i in 0 until track.size - 1) {
                val p1 = track[i]
                val p2 = track[i + 1]
                val midLat = (p1.latitude + p2.latitude) / 2.0
                val midLon = (p1.longitude + p2.longitude) / 2.0
                val visits = cellVisits[cellKey(midLat, midLon)]?.size ?: 1
                val segmentTier = determineTier(visits, maxVisits)

                if (currentTier == null) {
                    currentTier = segmentTier
                    currentPath.add(p1)
                    currentPath.add(p2)
                } else if (currentTier == segmentTier) {
                    currentPath.add(p2)
                } else {
                    // Flush current path
                    if (currentPath.size >= 2) {
                        tierPolylines[currentTier]?.add(currentPath)
                    }
                    // Start new path overlapping at p1 so there's no visual gap
                    currentTier = segmentTier
                    currentPath = mutableListOf(p1, p2)
                }
            }

            if (currentTier != null && currentPath.size >= 2) {
                tierPolylines[currentTier]?.add(currentPath)
            }
        }

        val allPoints = simplifiedTracks.flatten()
        return ProcessedHeatMap(tierPolylines, allPoints, maxVisits)
    }

    private fun approximateDistanceMeters(p1: GeoPoint, p2: GeoPoint): Double {
        val latMid = (p1.latitude + p2.latitude) / 2.0
        val cosLat = cos(Math.toRadians(latMid))
        val dLat = (p2.latitude - p1.latitude) * 111139.0
        val dLon = (p2.longitude - p1.longitude) * 111139.0 * cosLat
        return sqrt(dLat * dLat + dLon * dLon)
    }
}
