package online.hatala.whereami.tracking

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * Agreement between the provider's course and where its own fixes actually went.
 *
 * The fused provider can report a bearing that is biased by 10-25 deg for tens of seconds while claiming
 * ~5 deg accuracy (field trip 233, 16:39: the filter followed it 33 m off the street). Over several seconds
 * of travel (8 s) the direction of the fix displacement is a robust course measurement (position errors are
 * strongly correlated over that span), so the angle between it and the displacement predicted from the
 * reported bearings and Doppler speeds (trapezoid rule, so turns and braking predict their own chord) is
 * a lower bound for the bearing's real error once the part the fix accuracy can explain is removed. A real
 * bias persists while noise spikes on single fixes, so only the smallest of the last [persistFixes]
 * consecutive values counts. Only near-straight windows are judged: in turns the provider bearing lags and
 * the 1 Hz prediction is distorted, and the observed biases were all on straight stretches.
 */
class CourseConsistency(
    private val windowMs: Long = 8_000L,
    private val minBaselineM: Double = 25.0,
    private val persistFixes: Int = 3,
    private val maxBearingSpreadDeg: Double = 25.0
) {
    private class Sample(val tMs: Long, val lat: Double, val lng: Double, val ve: Double, val vn: Double, val accM: Double)

    private val samples = ArrayDeque<Sample>()
    private val recent = ArrayDeque<Double>()

    /** Persistent disagreement (deg) beyond the fix noise as of the last [add]; null if unknown. */
    var disagreementDeg: Double? = null
        private set

    fun add(tMs: Long, latDeg: Double, lngDeg: Double, bearingDeg: Double?, speedMps: Double?, accuracyM: Double) {
        if (samples.isNotEmpty() && tMs <= samples.last().tMs) clear()
        val v = if (bearingDeg != null && speedMps != null) speedMps else Double.NaN
        val b = Math.toRadians(bearingDeg ?: 0.0)
        samples.addLast(Sample(tMs, latDeg, lngDeg, v * sin(b), v * cos(b), accuracyM))
        while (samples.size > 2 && tMs - samples[1].tMs >= windowMs) samples.removeFirst()
        val now = windowDisagreementDeg()
        if (now == null) recent.clear() else {
            recent.addLast(now)
            if (recent.size > persistFixes) recent.removeFirst()
        }
        disagreementDeg = if (recent.size == persistFixes) recent.min() else null
    }

    fun clear() {
        samples.clear()
        recent.clear()
        disagreementDeg = null
    }

    /**
     * Angle (deg) between the window's fix displacement and the one its bearings predict, beyond what the
     * fixes' accuracy explains (atan(accuracy / baseline)); null if unknown.
     */
    private fun windowDisagreementDeg(): Double? {
        if (samples.size < 3) return null
        val first = samples.first()
        val last = samples.last()
        val k = cos(Math.toRadians(first.lat)).coerceAtLeast(0.01)
        val dE = (last.lng - first.lng) * k * LocalFrame.METERS_PER_DEG_LAT
        val dN = (last.lat - first.lat) * LocalFrame.METERS_PER_DEG_LAT
        val baseline = hypot(dE, dN)
        if (baseline < minBaselineM) return null
        var pE = 0.0
        var pN = 0.0
        for (i in 1 until samples.size) {
            val a = samples[i - 1]
            val b = samples[i]
            if (a.ve.isNaN() || b.ve.isNaN()) return null
            val dt = (b.tMs - a.tMs) / 1000.0
            pE += 0.5 * (a.ve + b.ve) * dt
            pN += 0.5 * (a.vn + b.vn) * dt
        }
        if (hypot(pE, pN) < 1e-6) return null
        val course = Math.toDegrees(atan2(pE, pN))
        if (samples.any { abs(shortestDeltaDeg(course, Math.toDegrees(atan2(it.ve, it.vn)))) > maxBearingSpreadDeg }) return null
        val angle = abs(shortestDeltaDeg(course, Math.toDegrees(atan2(dE, dN))))
        val noise = Math.toDegrees(atan2(0.5 * (first.accM + last.accM), baseline))
        return max(0.0, angle - noise)
    }
}
