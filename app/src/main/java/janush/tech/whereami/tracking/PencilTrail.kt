package janush.tech.whereami.tracking

import kotlin.math.cos
import kotlin.math.hypot

/**
 * The live end of the recorded polyline, drawn exactly where the cursor has been rendered.
 *
 * The recorded track (smoothed, committed by TripManager) trails the cursor by the smoother lag plus the
 * sampler window. Bridging that gap with a re-estimated tail makes the line morph on every fix; instead the
 * gap is filled with the cursor's own rendered path, so the line behaves like a pencil tip under the cursor.
 * Points older than the newest committed vertex are dropped as the recorded track catches up.
 */
class PencilTrail(private val maxPoints: Int = 600) {

    private val t = ArrayList<Long>()
    private val lat = ArrayList<Double>()
    private val lng = ArrayList<Double>()

    val size: Int get() = t.size

    /** Appends a rendered cursor position at render time [tMs] if it moved far enough from the last one. */
    fun add(tMs: Long, latDeg: Double, lngDeg: Double, speedMps: Double): Boolean {
        val n = t.size
        if (n > 0) {
            if (tMs < t[n - 1]) return false
            val k = cos(Math.toRadians(latDeg)).coerceAtLeast(0.01)
            val d = hypot((lngDeg - lng[n - 1]) * k, latDeg - lat[n - 1]) * LocalFrame.METERS_PER_DEG_LAT
            if (d < minStepM(speedMps)) return false
        }
        t.add(tMs); lat.add(latDeg); lng.add(lngDeg)
        if (t.size > maxPoints) {
            t.removeAt(0); lat.removeAt(0); lng.removeAt(0)
        }
        return true
    }

    /** Drops points rendered at or before [tMs] (covered by the committed track). */
    fun pruneThrough(tMs: Long) {
        var drop = 0
        while (drop < t.size && t[drop] <= tMs) drop++
        if (drop == 0) return
        refT = t[drop - 1]; refLat = lat[drop - 1]; refLng = lng[drop - 1]; hasRef = true
        t.subList(0, drop).clear(); lat.subList(0, drop).clear(); lng.subList(0, drop).clear()
    }

    fun clear() {
        t.clear(); lat.clear(); lng.clear()
        hasRef = false
    }

    // Last point dropped by pruneThrough: with the first kept point it brackets the committed time.
    private var refT = 0L
    private var refLat = 0.0
    private var refLng = 0.0
    private var hasRef = false

    /**
     * The pencil re-anchored to the newest committed vertex.
     *
     * The committed vertex is a smoothed (later-fix) estimate that sits a fraction of a metre to a few metres
     * beside where the cursor was rendered at that time. Joining the two directly draws a hook right behind
     * the cursor. Instead the pencil is shifted by that difference at its start, fading to zero over
     * [decayM] metres (never past the tip), so the line leaves the committed vertex smoothly and still ends
     * exactly under the cursor.
     *
     * @return blended (lat, lng) arrays for all points, or null when there is nothing to blend
     */
    fun blendedFrom(cT: Long, cLat: Double, cLng: Double, tipLat: Double, tipLng: Double, decayM: Double = JOIN_DECAY_M): Pair<DoubleArray, DoubleArray>? {
        val n = t.size
        if (n == 0 || !hasRef || cT < refT || cT > t[0]) return null
        val span = (t[0] - refT).toDouble()
        val u = if (span <= 0.0) 1.0 else ((cT - refT) / span).coerceIn(0.0, 1.0)
        val rLat = refLat + (lat[0] - refLat) * u
        val rLng = refLng + (lng[0] - refLng) * u
        val dLat = cLat - rLat
        val dLng = cLng - rLng
        val k = cos(Math.toRadians(cLat)).coerceAtLeast(0.01)
        fun m(aLat: Double, aLng: Double, bLat: Double, bLng: Double) =
            hypot((bLng - aLng) * k, bLat - aLat) * LocalFrame.METERS_PER_DEG_LAT
        val arc = DoubleArray(n)
        for (i in 1 until n) arc[i] = arc[i - 1] + m(lat[i - 1], lng[i - 1], lat[i], lng[i])
        val total = arc[n - 1] + m(lat[n - 1], lng[n - 1], tipLat, tipLng)
        val len = minOf(decayM, total)
        val outLat = DoubleArray(n)
        val outLng = DoubleArray(n)
        for (i in 0 until n) {
            val w = if (len <= 1e-6) 0.0 else (1.0 - arc[i] / len).coerceAtLeast(0.0)
            val f = w * w
            outLat[i] = lat[i] + dLat * f
            outLng[i] = lng[i] + dLng * f
        }
        return outLat to outLng
    }

    inline fun forEach(block: (lat: Double, lng: Double) -> Unit) {
        for (i in 0 until size) block(latAt(i), lngAt(i))
    }

    fun latAt(i: Int): Double = lat[i]
    fun lngAt(i: Int): Double = lng[i]
    fun timeAt(i: Int): Long = t[i]

    companion object {
        /** About 0.1 s of travel, never below half a metre (sub-pixel at street zoom). */
        fun minStepM(speedMps: Double): Double = (0.1 * speedMps).coerceIn(0.5, 4.0)

        /** Distance over which the committed-vertex offset fades out along the pencil. */
        const val JOIN_DECAY_M = 4.0
    }
}
