package janush.tech.whereami

import org.osmdroid.util.GeoPoint
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

enum class HeatMapTier(
    val tierLevel: Int,
    val colorArgb: Int,
    val strokeWidth: Float,
    val label: String
) {
    TIER_1_BLUE(1, 0xD92563EB.toInt(), 6.0f, "1 visit (Exploratory)"),     // Cobalt Royal Blue (~85% alpha)
    TIER_2_CYAN(2, 0xE606B6D4.toInt(), 7.0f, "2 visits"),                  // Vivid Cyan / Aqua (~90% alpha)
    TIER_3_GREEN(3, 0xF210B981.toInt(), 8.0f, "3-4 visits"),               // Emerald Green (~95% alpha)
    TIER_4_YELLOW(4, 0xFFEAB308.toInt(), 9.0f, "5-7 visits"),              // Golden Yellow (100% alpha)
    TIER_5_ORANGE(5, 0xFFF97316.toInt(), 10.5f, "8-12 visits"),            // Vivid Tangerine Orange (100% alpha)
    TIER_6_RED(6, 0xFFEF4444.toInt(), 12.0f, "13-19 visits"),              // Crimson Fire Red (100% alpha)
    TIER_7_MAGENTA(7, 0xFFD946EF.toInt(), 13.5f, "20+ visits (Hotspot)");   // Neon Magenta (100% alpha)

    companion object {
        fun fromLevel(level: Int): HeatMapTier = when (level.coerceIn(1, 7)) {
            7 -> TIER_7_MAGENTA
            6 -> TIER_6_RED
            5 -> TIER_5_ORANGE
            4 -> TIER_4_YELLOW
            3 -> TIER_3_GREEN
            2 -> TIER_2_CYAN
            else -> TIER_1_BLUE
        }
    }
}

data class HeatMapOptions(
    val consolidateCorridors: Boolean = true,
    val minVisits: Int = 1,
    val epsilonMeters: Double = 10.0
)

data class ProcessedHeatMap(
    val tierPolylines: Map<HeatMapTier, List<List<GeoPoint>>>,
    val allSimplifiedPoints: List<GeoPoint>,
    val maxVisits: Int,
    val totalCorridorKm: Double = 0.0
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

    // Grid resolution: ~16m per cell (111139m / 7000 ~= 15.88m)
    private const val LAT_CELL_FACTOR = 7000.0

    fun cellCoords(lat: Double, lon: Double): Pair<Int, Int> {
        val latCell = (lat * LAT_CELL_FACTOR).toInt()
        val cosLat = cos(Math.toRadians(lat)).coerceAtLeast(0.1)
        val lonCell = (lon * LAT_CELL_FACTOR * cosLat).toInt()
        return Pair(latCell, lonCell)
    }

    /**
     * Computes a 64-bit spatial grid hash cell.
     */
    fun cellKey(lat: Double, lon: Double): Long {
        val (latCell, lonCell) = cellCoords(lat, lon)
        return cellKey(latCell, lonCell)
    }

    fun cellKey(latCell: Int, lonCell: Int): Long {
        return (latCell.toLong() shl 32) or (lonCell.toLong() and 0xFFFFFFFFL)
    }

    /**
     * Queries unique track visits in a spatial corridor buffer around (lat, lon).
     * Using a circular kernel with dLat^2 + dLon^2 <= 5 (~32-35m radius),
     * parallel tracks in adjacent lanes or GPS fixes with lateral drift (~10-25m)
     * are correctly aggregated into the same corridor.
     */
    fun queryCorridorVisits(lat: Double, lon: Double, cellVisits: Map<Long, Set<Int>>): Int {
        val (cLat, cLon) = cellCoords(lat, lon)
        val uniqueTracks = mutableSetOf<Int>()
        for (dLat in -2..2) {
            for (dLon in -2..2) {
                if (dLat * dLat + dLon * dLon <= 5) {
                    val key = cellKey(cLat + dLat, cLon + dLon)
                    cellVisits[key]?.let { uniqueTracks.addAll(it) }
                }
            }
        }
        return uniqueTracks.size.coerceAtLeast(1)
    }

    /**
     * Determines thermal tier (1 to 7) given the segment visit count and maximum visit count across the dataset.
     */
    fun determineTier(visitCount: Int, maxVisits: Int): HeatMapTier {
        if (visitCount <= 1 || maxVisits <= 1) return HeatMapTier.TIER_1_BLUE

        val level = when {
            maxVisits >= 15 -> {
                when {
                    visitCount >= (maxVisits * 0.85).toInt().coerceAtLeast(15) -> 7 // Hotspot
                    visitCount >= (maxVisits * 0.65).toInt().coerceAtLeast(11) -> 6 // Red
                    visitCount >= (maxVisits * 0.45).toInt().coerceAtLeast(8)  -> 5 // Orange
                    visitCount >= (maxVisits * 0.30).toInt().coerceAtLeast(5)  -> 4 // Yellow
                    visitCount >= (maxVisits * 0.18).toInt().coerceAtLeast(3)  -> 3 // Green
                    visitCount >= 2 -> 2 // Cyan
                    else -> 1 // Blue
                }
            }
            maxVisits >= 8 -> {
                when {
                    visitCount >= maxVisits -> 7
                    visitCount >= (maxVisits * 0.75).toInt().coerceAtLeast(6) -> 6
                    visitCount >= (maxVisits * 0.55).toInt().coerceAtLeast(5) -> 5
                    visitCount >= (maxVisits * 0.40).toInt().coerceAtLeast(4) -> 4
                    visitCount >= (maxVisits * 0.25).toInt().coerceAtLeast(3) -> 3
                    visitCount >= 2 -> 2
                    else -> 1
                }
            }
            maxVisits >= 5 -> {
                when {
                    visitCount >= maxVisits -> 6 // Red
                    visitCount >= 4 -> 5 // Orange
                    visitCount >= 3 -> 4 // Yellow
                    visitCount >= 2 -> 2 // Cyan
                    else -> 1 // Blue
                }
            }
            maxVisits == 4 -> {
                when (visitCount) {
                    4 -> 6 // Red
                    3 -> 5 // Orange
                    2 -> 2 // Cyan
                    else -> 1 // Blue
                }
            }
            maxVisits == 3 -> {
                when (visitCount) {
                    3 -> 6 // Red
                    2 -> 4 // Yellow
                    else -> 1 // Blue
                }
            }
            maxVisits == 2 -> {
                if (visitCount >= 2) 6 else 1
            }
            else -> 1
        }
        return HeatMapTier.fromLevel(level)
    }

    /**
     * Backward-compatible overload.
     */
    fun processTracks(tracks: List<List<GeoPoint>>, epsilonMeters: Double = 10.0): ProcessedHeatMap {
        return processTracks(tracks, HeatMapOptions(epsilonMeters = epsilonMeters))
    }

    /**
     * Processes all tracks:
     * 1. Decimates each track using RDP (~10m).
     * 2. Accumulates distinct trip visits per spatial corridor (~32-35m radius) along segments.
     * 3. Consolidates overlapping multi-pass paths into a single clean gradient corridor (when enabled).
     * 4. Filters out segments below minVisits threshold.
     * 5. Classifies segments into 7 thermal tiers and batches adjacent segments.
     */
    fun processTracks(
        tracks: List<List<GeoPoint>>,
        options: HeatMapOptions
    ): ProcessedHeatMap {
        if (tracks.isEmpty()) {
            return ProcessedHeatMap(emptyMap(), emptyList(), 0, 0.0)
        }

        // 1. Simplify all tracks
        val simplifiedTracks = tracks.map { track ->
            if (track.size > 2) simplifyRdp(track, options.epsilonMeters) else track
        }.filter { it.size >= 2 }

        if (simplifiedTracks.isEmpty()) {
            return ProcessedHeatMap(emptyMap(), emptyList(), 0, 0.0)
        }

        // 2. Spatial frequency mapping with distinct track IDs
        val cellVisits = HashMap<Long, MutableSet<Int>>()

        simplifiedTracks.forEachIndexed { trackIdx, track ->
            for (i in 0 until track.size - 1) {
                val p1 = track[i]
                val p2 = track[i + 1]
                val distMeters = approximateDistanceMeters(p1, p2)
                val stepCount = (distMeters / 12.0).toInt().coerceIn(1, 200)

                for (s in 0..stepCount) {
                    val t = s.toDouble() / stepCount
                    val lat = p1.latitude + t * (p2.latitude - p1.latitude)
                    val lon = p1.longitude + t * (p2.longitude - p1.longitude)
                    val key = cellKey(lat, lon)
                    cellVisits.getOrPut(key) { mutableSetOf() }.add(trackIdx)
                }
            }
        }

        // Determine true maximum corridor visit count across all track segments
        var maxVisits = 1
        simplifiedTracks.forEach { track ->
            for (i in 0 until track.size - 1) {
                val midLat = (track[i].latitude + track[i + 1].latitude) / 2.0
                val midLon = (track[i].longitude + track[i + 1].longitude) / 2.0
                val visits = queryCorridorVisits(midLat, midLon, cellVisits)
                if (visits > maxVisits) {
                    maxVisits = visits
                }
            }
        }

        // 3. Segment tier assignment, corridor consolidation, and adjacent batching
        val tierPolylines = mutableMapOf<HeatMapTier, MutableList<List<GeoPoint>>>().apply {
            HeatMapTier.values().forEach { put(it, mutableListOf()) }
        }

        val occupiedCorridorCells = HashSet<Long>()
        var totalCorridorMeters = 0.0

        simplifiedTracks.forEach { track ->
            if (track.size < 2) return@forEach

            var currentTier: HeatMapTier? = null
            var currentPath = mutableListOf<GeoPoint>()

            for (i in 0 until track.size - 1) {
                val p1 = track[i]
                val p2 = track[i + 1]
                val midLat = (p1.latitude + p2.latitude) / 2.0
                val midLon = (p1.longitude + p2.longitude) / 2.0
                val visits = queryCorridorVisits(midLat, midLon, cellVisits)

                // Density filter: skip segments below minimum visits
                if (visits < options.minVisits) {
                    if (currentTier != null && currentPath.size >= 2) {
                        tierPolylines[currentTier]?.add(currentPath)
                        currentPath = mutableListOf()
                        currentTier = null
                    }
                    continue
                }

                // Corridor consolidation: skip redundant duplicate segments along an already drawn corridor
                if (options.consolidateCorridors) {
                    val (cLat, cLon) = cellCoords(midLat, midLon)
                    val midKey = cellKey(cLat, cLon)
                    if (occupiedCorridorCells.contains(midKey)) {
                        if (currentTier != null && currentPath.size >= 2) {
                            tierPolylines[currentTier]?.add(currentPath)
                            currentPath = mutableListOf()
                            currentTier = null
                        }
                        continue
                    }
                }

                // Register corridor cells as occupied to prevent parallel duplicates
                if (options.consolidateCorridors) {
                    val dist = approximateDistanceMeters(p1, p2)
                    totalCorridorMeters += dist
                    val steps = (dist / 14.0).toInt().coerceIn(1, 50)
                    for (s in 0..steps) {
                        val t = s.toDouble() / steps
                        val sLat = p1.latitude + t * (p2.latitude - p1.latitude)
                        val sLon = p1.longitude + t * (p2.longitude - p1.longitude)
                        val (sCLat, sCLon) = cellCoords(sLat, sLon)
                        for (dLat in -1..1) {
                            for (dLon in -1..1) {
                                occupiedCorridorCells.add(cellKey(sCLat + dLat, sCLon + dLon))
                            }
                        }
                    }
                } else {
                    totalCorridorMeters += approximateDistanceMeters(p1, p2)
                }

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
        return ProcessedHeatMap(
            tierPolylines,
            allPoints,
            maxVisits,
            totalCorridorKm = totalCorridorMeters / 1000.0
        )
    }

    private fun approximateDistanceMeters(p1: GeoPoint, p2: GeoPoint): Double {
        val latMid = (p1.latitude + p2.latitude) / 2.0
        val cosLat = cos(Math.toRadians(latMid))
        val dLat = (p2.latitude - p1.latitude) * 111139.0
        val dLon = (p2.longitude - p1.longitude) * 111139.0 * cosLat
        return sqrt(dLat * dLat + dLon * dLon)
    }
}
