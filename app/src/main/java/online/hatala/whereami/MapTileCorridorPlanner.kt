package online.hatala.whereami

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

data class TileCoordKey(
    val layer: MapBaseLayer,
    val zoom: Int,
    val x: Int,
    val y: Int
) {
    val osmdroidSqlKey: Long
        get() = MapTileCorridorPlanner.sqlTileIndex(x, y, zoom)
}

/**
 * Computes deduplicated offline raster map tile coordinates covering recorded trip corridors
 * across zooms 13..15 for Standard OSM plus each trip's configured base map layer.
 */
object MapTileCorridorPlanner {

    val DEFAULT_CORRIDOR_ZOOMS: List<Int> = listOf(13, 14, 15)
    private const val MAX_LAT = 85.05112878
    private const val MAX_SEGMENT_DEG = 0.0025 // ~280m step ensures every z15 tile is hit along chords

    fun sqlTileIndex(x: Int, y: Int, zoom: Int): Long =
        (((zoom.toLong() shl zoom) + x.toLong()) shl zoom) + y.toLong()

    fun latLonToTileXY(latDeg: Double, lonDeg: Double, zoom: Int): Pair<Int, Int> {
        val z = zoom.coerceIn(0, 20)
        val n = 1 shl z
        val clampedLat = latDeg.coerceIn(-MAX_LAT, MAX_LAT)
        val lonNorm = ((lonDeg + 180.0) / 360.0)
        val x = floor(lonNorm * n).toInt().coerceIn(0, n - 1)
        val latRad = Math.toRadians(clampedLat)
        val yDouble = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n
        val y = floor(yDouble).toInt().coerceIn(0, n - 1)
        return x to y
    }

    /**
     * Plans unique [TileCoordKey] entries for a sequence of trip `(points, profileBaseLayer)` pairs.
     * Always includes [MapBaseLayer.STANDARD] plus the trip's configured [profileBaseLayer].
     */
    fun planForTrips(
        tripSpecs: List<Pair<List<Pair<Double, Double>>, MapBaseLayer>>,
        zooms: List<Int> = DEFAULT_CORRIDOR_ZOOMS
    ): List<TileCoordKey> {
        if (tripSpecs.isEmpty() || zooms.isEmpty()) return emptyList()
        val seen = LinkedHashSet<TileCoordKey>(1024)
        for ((points, profileLayer) in tripSpecs) {
            if (points.isEmpty()) continue
            val layers = linkedSetOf(MapBaseLayer.STANDARD, profileLayer)
            for (layer in layers) {
                val minZ = MapTileSourceRegistry.minZoomIntFor(layer)
                val maxZ = MapTileSourceRegistry.maxZoomIntFor(layer)
                val validZooms = zooms.filter { it in minZ..maxZ }
                if (validZooms.isEmpty()) continue
                addCorridorPoints(points, layer, validZooms, seen)
            }
        }
        return seen.sortedWith(compareBy({ it.zoom }, { it.layer.ordinal }, { it.x }, { it.y }))
    }

    private fun addCorridorPoints(
        points: List<Pair<Double, Double>>,
        layer: MapBaseLayer,
        zooms: List<Int>,
        out: MutableSet<TileCoordKey>
    ) {
        var prevLat = Double.NaN
        var prevLon = Double.NaN
        for ((lat, lon) in points) {
            if (lat.isNaN() || lon.isNaN()) continue
            if (!prevLat.isNaN()) {
                val dLat = lat - prevLat
                val cosMid = cos(Math.toRadians((lat + prevLat) * 0.5)).coerceAtLeast(0.1)
                val dLonScaled = (lon - prevLon) * cosMid
                val approxDeg = maxOf(abs(dLat), abs(dLonScaled))
                if (approxDeg > MAX_SEGMENT_DEG) {
                    val steps = ceil(approxDeg / MAX_SEGMENT_DEG).toInt().coerceIn(2, 500)
                    for (s in 1 until steps) {
                        val frac = s.toDouble() / steps.toDouble()
                        val iLat = prevLat + dLat * frac
                        val iLon = prevLon + (lon - prevLon) * frac
                        for (z in zooms) {
                            val (tx, ty) = latLonToTileXY(iLat, iLon, z)
                            out.add(TileCoordKey(layer, z, tx, ty))
                        }
                    }
                }
            }
            for (z in zooms) {
                val (tx, ty) = latLonToTileXY(lat, lon, z)
                out.add(TileCoordKey(layer, z, tx, ty))
            }
            prevLat = lat
            prevLon = lon
        }
    }
}
