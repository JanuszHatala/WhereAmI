package online.hatala.whereami.tracking

import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import kotlin.math.cos
import kotlin.math.sqrt

/** One coordinate sent to the map matcher. */
data class MatchPoint(val lat: Double, val lng: Double)

/** Outcome of a single matching request, as seen by [RouteMatching.matchTrack]. */
sealed class ChunkResult {
    /** Snapped geometry (lat/lng pairs) for the chunk. */
    data class Matched(val geometry: List<MatchPoint>) : ChunkResult()
    /** The service answered but could not (or should not) snap this chunk; deterministic, not worth retrying. */
    object NoMatch : ChunkResult()
    /** Offline, timeout, 429, 5xx: retry the whole trip later without burning the attempt budget. */
    object Transient : ChunkResult()
}

fun interface RouteMatchApi {
    fun matchChunk(points: List<MatchPoint>): ChunkResult
}

sealed class TripMatchOutcome {
    data class Matched(val geometry: List<MatchPoint>, val matchedChunkFraction: Double) : TripMatchOutcome()
    /** Matching produced nothing trustworthy; keep the raw track only. */
    object Rejected : TripMatchOutcome()
    /** Network trouble or interrupted: try again later. */
    object Retry : TripMatchOutcome()
}

/**
 * Pure logic for post-trip OSRM `/match` road snapping. The recorded (smoothed) track always stays authoritative;
 * the snapped result is an alternate geometry. Snapping is never applied blindly: a chunk is only used when the
 * service is confident AND the snapped line stays within [MAX_SNAP_DEVIATION_M] of nearly all raw points.
 */
object RouteMatching {
    // The public demo server answers 11+ coordinates with HTTP 400 "TooBig" (Oct 2026); with 90 every chunk
    // failed and every trip was rejected. 25 m spacing keeps a 10-point request at ~225 m of road.
    const val MAX_COORDS_PER_REQUEST = 10
    const val MIN_POINT_SPACING_M = 25.0         // dense 1 Hz rows add no information for snapping
    const val MAX_TOTAL_POINTS = 1000            // ~111 requests at the 1.1 s rate limit (~2 min)
    const val SNAP_RADIUS_M = 25
    const val MAX_SNAP_DEVIATION_M = 45.0
    const val MIN_CONFIDENCE = 0.5
    const val MIN_WITHIN_FRACTION = 0.9
    const val MIN_MATCHED_CHUNK_FRACTION = 0.8
    const val MIN_POINTS_TO_MATCH = 4

    private const val EARTH_R = 6_371_000.0

    /** Moving rows only, thinned to [MIN_POINT_SPACING_M] (first/last always kept), capped at [MAX_TOTAL_POINTS]. */
    fun selectPoints(rows: List<TrackPointRow>): List<MatchPoint> {
        val moving = rows.filter { !it.isStationary }
        if (moving.size < 2) return moving.map { MatchPoint(it.lat, it.lng) }
        var spacing = MIN_POINT_SPACING_M
        var out: List<MatchPoint>
        while (true) {
            out = thin(moving, spacing)
            if (out.size <= MAX_TOTAL_POINTS || spacing > 5000.0) break
            spacing *= 1.5
        }
        return out
    }

    private fun thin(rows: List<TrackPointRow>, spacingM: Double): List<MatchPoint> {
        val out = ArrayList<MatchPoint>()
        var last: TrackPointRow? = null
        for (r in rows) {
            val l = last
            if (l == null || distanceM(l.lat, l.lng, r.lat, r.lng) >= spacingM) {
                out.add(MatchPoint(r.lat, r.lng)); last = r
            }
        }
        val tail = rows.last()
        if (out.last().lat != tail.lat || out.last().lng != tail.lng) out.add(MatchPoint(tail.lat, tail.lng))
        return out
    }

    /** Index ranges (inclusive) of at most [MAX_COORDS_PER_REQUEST] points; neighbouring chunks share one point. */
    fun chunks(n: Int, max: Int = MAX_COORDS_PER_REQUEST): List<IntRange> {
        if (n < 2) return emptyList()
        val out = ArrayList<IntRange>()
        var start = 0
        while (start < n - 1) {
            val end = minOf(start + max - 1, n - 1)
            out.add(start..end)
            start = end
        }
        return out
    }

    fun buildUrl(baseUrl: String, pts: List<MatchPoint>): String {
        val coords = pts.joinToString(";") { "${fmt(it.lng)},${fmt(it.lat)}" }
        val radiuses = pts.joinToString(";") { SNAP_RADIUS_M.toString() }
        return "$baseUrl/match/v1/driving/$coords?geometries=geojson&overview=full&gaps=ignore&radiuses=$radiuses"
    }

    private fun fmt(v: Double) = String.format(java.util.Locale.US, "%.6f", v)

    /**
     * @return snapped geometry when the response is a single confident matching, null otherwise.
     */
    fun parseMatchResponse(body: String): List<MatchPoint>? {
        return try {
            val root = JSONObject(body)
            if (root.optString("code") != "Ok") return null
            val matchings = root.optJSONArray("matchings") ?: return null
            if (matchings.length() != 1) return null
            val m = matchings.getJSONObject(0)
            if (m.optDouble("confidence", 0.0) < MIN_CONFIDENCE) return null
            val coords = m.getJSONObject("geometry").getJSONArray("coordinates")
            val out = ArrayList<MatchPoint>(coords.length())
            for (i in 0 until coords.length()) {
                val c = coords.getJSONArray(i)
                out.add(MatchPoint(c.getDouble(1), c.getDouble(0))) // GeoJSON is [lng, lat]
            }
            out.takeIf { it.size >= 2 }
        } catch (_: Exception) {
            null
        }
    }

    /** True when the snapped line explains the raw points: nothing was "teleported" onto a parallel road. */
    fun isAcceptable(raw: List<MatchPoint>, snapped: List<MatchPoint>): Boolean {
        if (raw.isEmpty() || snapped.size < 2) return false
        val within = raw.count { distanceToPolylineM(it, snapped) <= MAX_SNAP_DEVIATION_M }
        return within.toDouble() / raw.size >= MIN_WITHIN_FRACTION
    }

    /**
     * Match a whole trip chunk by chunk. A deterministic chunk failure falls back to the raw points of that chunk
     * (the alternate geometry stays continuous); a transient failure aborts so the trip is retried later.
     * [shouldContinue] is polled between chunks (e.g. stop when a new trip starts recording).
     */
    fun matchTrack(points: List<MatchPoint>, api: RouteMatchApi, shouldContinue: () -> Boolean = { true }): TripMatchOutcome {
        if (points.size < MIN_POINTS_TO_MATCH) return TripMatchOutcome.Rejected
        val ranges = chunks(points.size)
        val stitched = ArrayList<MatchPoint>()
        var matchedChunks = 0
        for (range in ranges) {
            if (!shouldContinue()) return TripMatchOutcome.Retry
            val raw = points.subList(range.first, range.last + 1)
            val geometry: List<MatchPoint> = when (val r = api.matchChunk(raw)) {
                is ChunkResult.Transient -> return TripMatchOutcome.Retry
                is ChunkResult.NoMatch -> raw
                is ChunkResult.Matched -> if (isAcceptable(raw, r.geometry)) { matchedChunks++; r.geometry } else raw
            }
            appendStitched(stitched, geometry)
        }
        val fraction = matchedChunks.toDouble() / ranges.size
        return if (fraction >= MIN_MATCHED_CHUNK_FRACTION) TripMatchOutcome.Matched(stitched, fraction) else TripMatchOutcome.Rejected
    }

    /** Neighbouring chunks share a boundary coordinate that each snapped slightly differently: drop the near-duplicate. */
    private fun appendStitched(into: MutableList<MatchPoint>, next: List<MatchPoint>) {
        val last = into.lastOrNull()
        val first = next.firstOrNull()
        val skipFirst = last != null && first != null && distanceM(last.lat, last.lng, first.lat, first.lng) < 30.0
        into.addAll(if (skipFirst) next.drop(1) else next)
    }

    fun geometryToJson(points: List<MatchPoint>): String {
        val arr = JSONArray()
        for (p in points) arr.put(JSONArray().put(p.lat).put(p.lng))
        return arr.toString()
    }

    fun geometryFromJson(json: String): List<GeoPoint>? = try {
        val arr = JSONArray(json)
        val out = ArrayList<GeoPoint>(arr.length())
        for (i in 0 until arr.length()) {
            val p = arr.getJSONArray(i)
            out.add(GeoPoint(p.getDouble(0), p.getDouble(1)))
        }
        out.takeIf { it.size >= 2 }
    } catch (_: Exception) {
        null
    }

    fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val x = Math.toRadians(lng2 - lng1) * cos(Math.toRadians((lat1 + lat2) / 2))
        val y = Math.toRadians(lat2 - lat1)
        return sqrt(x * x + y * y) * EARTH_R
    }

    /** Distance from [p] to the polyline in metres (local equirectangular projection, fine at city scale). */
    fun distanceToPolylineM(p: MatchPoint, line: List<MatchPoint>): Double {
        val k = cos(Math.toRadians(p.lat))
        fun ex(lng: Double) = Math.toRadians(lng - p.lng) * k * EARTH_R
        fun ny(lat: Double) = Math.toRadians(lat - p.lat) * EARTH_R
        var best = Double.MAX_VALUE
        for (i in 0 until line.size - 1) {
            val ax = ex(line[i].lng); val ay = ny(line[i].lat)
            val bx = ex(line[i + 1].lng); val by = ny(line[i + 1].lat)
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 < 1e-9) 0.0 else ((-ax) * dx + (-ay) * dy) / len2
            val tc = t.coerceIn(0.0, 1.0)
            val px = ax + tc * dx; val py = ay + tc * dy
            val d = sqrt(px * px + py * py)
            if (d < best) best = d
        }
        return best
    }
}
