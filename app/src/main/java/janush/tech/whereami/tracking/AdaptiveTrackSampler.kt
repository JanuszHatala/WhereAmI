package janush.tech.whereami.tracking

import kotlin.math.cos
import kotlin.math.hypot

/**
 * Decides which smoothed points become recorded track vertices.
 *
 * Online "opening window" line simplification (the streaming form of Douglas-Peucker) with a tolerance
 * that scales with the measured speed, not with the selected activity profile:
 *  - slow movement (walking, hiking) can turn within a metre, so the tolerance is tight and the track dense;
 *  - fast movement cannot turn sharply, so straight stretches are stored sparsely without losing shape.
 * A mislabelled profile (e.g. "Walking" while actually driving) therefore still gets the right density.
 *
 * A vertex is committed when the shape bends (some buffered point deviates from the chord by more than
 * [toleranceM]), when the chord gets longer than [maxSpacingM], or after [MAX_INTERVAL_MS] so stored
 * points keep a usable time resolution. Points closer than [minSpacingM] are never committed.
 */
class AdaptiveTrackSampler {

    private var anchor: SmoothedPoint? = null
    private val window = ArrayList<SmoothedPoint>()

    /** @return the points to commit, in order (0, 1 or 2 points). */
    fun offer(p: SmoothedPoint): List<SmoothedPoint> {
        val a = anchor
        if (a == null) {
            anchor = p
            return listOf(p)
        }
        if (p.isStationary) {
            // Parked: nothing new to draw. Keep the last moving point so the departure joins cleanly,
            // and advance the anchor clock so departure dt does not include stationary dwell time.
            val last = window.lastOrNull()
            window.clear()
            if (last != null && distanceM(a, last) >= minSpacingM(last.speedMps)) {
                anchor = last.copy(tElapsedMs = p.tElapsedMs, tUtcMs = p.tUtcMs)
                return listOf(last)
            }
            anchor = a.copy(tElapsedMs = p.tElapsedMs, tUtcMs = p.tUtcMs)
            return emptyList()
        }

        val chord = distanceM(a, p)
        // Creeping / parking GNSS texture (< ~4 km/h): do not densify until the vehicle has left the nest.
        if (p.speedMps < CRAWL_SPEED_MPS && chord < PARKING_ESCAPE_M) {
            window.add(p)
            return emptyList()
        }
        if (chord < minSpacingM(p.speedMps)) {
            window.add(p)
            return emptyList()
        }

        val tol = toleranceM(p.speedMps)
        val worstPoint = if (window.isNotEmpty()) findMaxDeviationPoint(a, p, window) else null
        if (worstPoint != null && worstPoint.second > tol) {
            // The path bent somewhere inside the window: commit the true apex (vertex of maximum deviation),
            // not window.last() which cuts corners and flattens apexes.
            val vertex = worstPoint.first
            val vertexIdx = window.indexOf(vertex)
            val remainingInWindow = if (vertexIdx >= 0 && vertexIdx < window.size - 1) {
                window.subList(vertexIdx + 1, window.size).toMutableList()
            } else {
                mutableListOf()
            }
            window.clear()
            window.addAll(remainingInWindow)
            anchor = vertex
            val out = arrayListOf(vertex)
            if (distanceM(vertex, p) >= maxSpacingM(p.speedMps)) {
                window.clear()
                anchor = p
                out.add(p)
            } else {
                window.add(p)
            }
            return out
        }

        if (chord >= maxSpacingM(p.speedMps) || p.tElapsedMs - a.tElapsedMs >= MAX_INTERVAL_MS) {
            window.clear()
            anchor = p
            return listOf(p)
        }
        window.add(p)
        return emptyList()
    }

    /** Points accepted into the window but not yet committed (drained at stop so the track ends at the stop). */
    fun drain(): List<SmoothedPoint> {
        val last = window.lastOrNull() ?: return emptyList()
        window.clear()
        anchor = last
        return listOf(last)
    }

    fun reset() {
        anchor = null
        window.clear()
    }

    companion object {
        const val MAX_INTERVAL_MS = 30_000L
        /** Below this (~3.6 km/h), GNSS texture while "moving" is treated as a parking / courtyard nest. */
        const val CRAWL_SPEED_MPS = 1.0
        /** Chord length that ends the crawl hold (leaving the lot / building). */
        const val PARKING_ESCAPE_M = 12.0

        /** Allowed sideways error of the stored polyline vs. the smoothed path. */
        fun toleranceM(speedMps: Double): Double = (0.25 + 0.06 * speedMps).coerceIn(0.25, 2.0)

        /** Below this chord length nothing is stored (sub-metre GNSS texture is not geometry). */
        fun minSpacingM(speedMps: Double): Double = (0.5 * speedMps).coerceIn(0.8, 8.0)

        /** Even on a perfectly straight line store a vertex at least this often (about 2 s of travel). */
        fun maxSpacingM(speedMps: Double): Double = (2.0 * speedMps).coerceIn(2.0, 60.0)

        private const val R = 6_371_000.0

        fun distanceM(a: SmoothedPoint, b: SmoothedPoint): Double {
            val x = Math.toRadians(b.lng - a.lng) * cos(Math.toRadians((a.lat + b.lat) / 2))
            val y = Math.toRadians(b.lat - a.lat)
            return hypot(x, y) * R
        }

        /** Finds point with max perpendicular distance from segment a-b, returning Pair(point, deviation). */
        fun findMaxDeviationPoint(a: SmoothedPoint, b: SmoothedPoint, pts: List<SmoothedPoint>): Pair<SmoothedPoint, Double>? {
            if (pts.isEmpty()) return null
            val k = cos(Math.toRadians(a.lat))
            fun ex(p: SmoothedPoint) = Math.toRadians(p.lng - a.lng) * k * R
            fun ny(p: SmoothedPoint) = Math.toRadians(p.lat - a.lat) * R
            val bx = ex(b); val by = ny(b)
            val len = hypot(bx, by)
            var worstDist = -1.0
            var worstPoint: SmoothedPoint? = null
            for (p in pts) {
                val px = ex(p); val py = ny(p)
                val t = if (len < 1e-6) 0.0 else ((px * bx + py * by) / (len * len)).coerceIn(0.0, 1.0)
                val d = hypot(px - t * bx, py - t * by)
                if (d > worstDist) {
                    worstDist = d
                    worstPoint = p
                }
            }
            return if (worstPoint != null) Pair(worstPoint, worstDist) else null
        }

        /** Max perpendicular distance of [pts] from segment a-b (local planar approximation). */
        fun maxDeviationM(a: SmoothedPoint, b: SmoothedPoint, pts: List<SmoothedPoint>): Double {
            return findMaxDeviationPoint(a, b, pts)?.second ?: 0.0
        }
    }
}
