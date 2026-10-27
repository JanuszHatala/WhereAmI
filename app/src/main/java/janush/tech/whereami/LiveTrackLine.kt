package janush.tech.whereami

import janush.tech.whereami.tracking.CurvedPolyline
import janush.tech.whereami.tracking.PencilTrail
import org.osmdroid.util.GeoPoint
import kotlin.math.abs

/**
 * Points of the live trip polyline: committed track + pencil trail + cursor tip, drawn as a curve
 * ([CurvedPolyline]) instead of straight chords between the metres-apart recorded vertices.
 *
 * The committed part is densified incrementally: a segment is final once two more vertices follow it, so only
 * the last committed segment and the pencil are re-curved when they change. The segment to the cursor tip
 * stays straight because the tip moves every frame.
 */
class LiveTrackLine {
    /** The polyline points (pass to `Polyline.setPoints`). */
    val points = ArrayList<GeoPoint>()

    private val prefix = ArrayList<GeoPoint>()
    /** Index of the last committed vertex covered by [prefix], -1 when empty. */
    private var prefixEnd = -1
    private var committed: List<GeoPoint> = emptyList()
    private var tailValid = false

    /** New committed track (append or full replacement). */
    fun setCommitted(c: List<GeoPoint>) {
        val old = committed
        val appended = old.isNotEmpty() && c.size >= old.size &&
            same(c[0], old[0]) && same(c[old.size - 1], old[old.size - 1])
        if (!appended) {
            prefix.clear()
            prefixEnd = -1
        }
        committed = c
        val target = c.size - 2
        if (target >= 0) {
            if (prefixEnd < 0) { prefix.add(c[0]); prefixEnd = 0 }
            if (target > prefixEnd) {
                CurvedPolyline.densify(c.size, prefixEnd, target, { c[it].latitude }, { c[it].longitude }) { la, ln ->
                    prefix.add(GeoPoint(la, ln))
                }
                prefixEnd = target
            }
        }
        tailValid = false
    }

    /**
     * The pencil changed (point added or pruned): rebuild the curved committed tail, then append the
     * pencil (curved when >= 2 points) and end at [tip].
     *
     * Catmull-Rom through the pencil prevents squary/zigzag line appearances behind the moving cursor
     * before vertices are committed to track points.
     */
    fun rebuildTail(pencil: PencilTrail, tip: GeoPoint, committedThroughMs: Long = -1L) {
        points.clear()
        points.addAll(prefix)
        val c = committed
        val s = (c.size - 3).coerceAtLeast(0)
        val nc = c.size - s
        if (nc > 0) {
            val fromSeg = if (prefixEnd < 0) {
                points.add(GeoPoint(c[s].latitude, c[s].longitude))
                0
            } else prefixEnd - s
            if (nc - 1 > fromSeg) {
                CurvedPolyline.densify(
                    nc, fromSeg, nc - 1,
                    { c[s + it].latitude }, { c[s + it].longitude }
                ) { la, ln -> points.add(GeoPoint(la, ln)) }
            }
        }
        val blended = if (c.isNotEmpty() && committedThroughMs >= 0L) {
            pencil.blendedFrom(committedThroughMs, c.last().latitude, c.last().longitude, tip.latitude, tip.longitude)
        } else null
        val pSize = pencil.size
        val hasAnchor = c.isNotEmpty()
        val totalTail = (if (hasAnchor) 1 else 0) + pSize + 1
        fun tailLat(idx: Int): Double = when {
            hasAnchor && idx == 0 -> c.last().latitude
            idx == totalTail - 1 -> tip.latitude
            else -> {
                val pIdx = if (hasAnchor) idx - 1 else idx
                blended?.first?.get(pIdx) ?: pencil.latAt(pIdx)
            }
        }
        fun tailLng(idx: Int): Double = when {
            hasAnchor && idx == 0 -> c.last().longitude
            idx == totalTail - 1 -> tip.longitude
            else -> {
                val pIdx = if (hasAnchor) idx - 1 else idx
                blended?.second?.get(pIdx) ?: pencil.lngAt(pIdx)
            }
        }
        if (totalTail >= 3) {
            if (!hasAnchor) {
                points.add(GeoPoint(tailLat(0), tailLng(0)))
            }
            CurvedPolyline.densify(
                totalTail, 0, totalTail - 1,
                { tailLat(it) }, { tailLng(it) }
            ) { la, ln -> points.add(GeoPoint(la, ln)) }
        } else {
            for (i in (if (hasAnchor) 1 else 0) until totalTail) {
                points.add(GeoPoint(tailLat(i), tailLng(i)))
            }
        }
        tailValid = true
    }

    /** False after [setCommitted] or [clear]: [rebuildTail] is needed before [moveTip]. */
    val hasTail: Boolean get() = tailValid && points.isNotEmpty()

    /**
     * Only the cursor moved: updates the tip in place. Returns false (nothing to redraw) when it moved less
     * than about 5 cm, so a parked cursor does not re-copy the whole line every frame.
     */
    fun moveTip(tip: GeoPoint): Boolean {
        if (!hasTail) return false
        val last = points[points.size - 1]
        if (abs(last.latitude - tip.latitude) < TIP_EPS_DEG && abs(last.longitude - tip.longitude) < TIP_EPS_DEG) return false
        points[points.size - 1] = tip
        return true
    }

    fun clear() {
        points.clear(); prefix.clear(); prefixEnd = -1; committed = emptyList(); tailValid = false
    }

    private fun same(a: GeoPoint, b: GeoPoint) = a.latitude == b.latitude && a.longitude == b.longitude

    companion object {
        private const val TIP_EPS_DEG = 0.0000005

        /** A finished track drawn with the same curve as while it was recorded. */
        fun curved(c: List<GeoPoint>): List<GeoPoint> {
            if (c.size < 3) return c
            val out = ArrayList<GeoPoint>(c.size * 2)
            out.add(c[0])
            CurvedPolyline.densify(c.size, 0, c.size - 1, { c[it].latitude }, { c[it].longitude }) { la, ln ->
                out.add(GeoPoint(la, ln))
            }
            return out
        }
    }
}
