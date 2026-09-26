package janush.tech.whereami

import kotlin.math.cos
import kotlin.math.sin

/**
 * High-performance, battery-friendly dead-reckoning position interpolator.
 * Decouples map marker movement and camera tracking from discrete GPS fix delivery.
 *
 * Between GPS fixes (typically 1.0 - 2.5s apart), interpolates/extrapolates position forward
 * along the vehicle's heading vector at the current speed, producing a fluid movement
 * without backward teleports, stutter, or animation-restart jerks.
 */
class PositionInterpolator {

    data class InterpolatedPoint(
        val lat: Double,
        val lng: Double,
        val bearing: Float?,
        val speedMs: Float,
        val isExtrapolated: Boolean
    )

    private var lastFixLat: Double = 0.0
    private var lastFixLng: Double = 0.0
    private var lastFixBearing: Float? = null
    private var lastFixSpeedMs: Float = 0f
    private var lastFixTimestamp: Long = 0L

    // Smooth transition from previous extrapolated position to new GPS fix
    private var blendStartLat: Double = 0.0
    private var blendStartLng: Double = 0.0
    private var blendTargetLat: Double = 0.0
    private var blendTargetLng: Double = 0.0
    private var blendStartTime: Long = 0L
    private val blendDurationMs: Long = 250L

    @Synchronized
    fun onNewFix(lat: Double, lng: Double, bearing: Float?, speedMs: Float, timestamp: Long) {
        if (lastFixTimestamp == 0L) {
            // First fix anchor
            lastFixLat = lat
            lastFixLng = lng
            lastFixBearing = bearing
            lastFixSpeedMs = speedMs
            lastFixTimestamp = timestamp
            blendTargetLat = lat
            blendTargetLng = lng
            blendStartLat = lat
            blendStartLng = lng
            blendStartTime = timestamp
            return
        }

        // Compute current estimated position at the moment the new fix arrives
        val currentEst = calculateExtrapolatedPosition(timestamp)

        // Smooth blending: blend from current displayed position to the new fix over blendDurationMs
        // This eliminates any jump between extrapolation and the newly measured GPS point!
        blendStartLat = currentEst.first
        blendStartLng = currentEst.second
        blendTargetLat = lat
        blendTargetLng = lng
        blendStartTime = timestamp

        lastFixLat = lat
        lastFixLng = lng
        lastFixBearing = bearing
        lastFixSpeedMs = speedMs
        lastFixTimestamp = timestamp
    }

    private fun calculateExtrapolatedPosition(nowMs: Long): Pair<Double, Double> {
        val dtSec = ((nowMs - lastFixTimestamp) / 1000.0).coerceIn(0.0, 1.2) // Cap extrapolation at 1.2s (anti-runaway)
        if (lastFixSpeedMs < 0.35f || lastFixBearing == null || dtSec <= 0.0) {
            return Pair(lastFixLat, lastFixLng)
        }

        // Distance traveled along bearing since last fix.
        // Between fixes (typically delivered every 1.0s), linear extrapolation maintains velocity.
        // Beyond 1.0s without a fix, decay is applied to prevent vehicle braking/stopping from
        // overshooting and rubber-banding backwards upon delayed fix arrival.
        val decay = if (dtSec > 1.0) {
            (1.0 - (dtSec - 1.0) * 0.5).coerceIn(0.5, 1.0)
        } else {
            1.0
        }
        val distMeters = lastFixSpeedMs * dtSec * decay
        val radBearing = Math.toRadians(lastFixBearing!!.toDouble())
        val dLat = (distMeters * cos(radBearing)) / 111320.0
        val cosLat = cos(Math.toRadians(lastFixLat)).coerceAtLeast(0.01)
        val dLng = (distMeters * sin(radBearing)) / (111320.0 * cosLat)

        return Pair(lastFixLat + dLat, lastFixLng + dLng)
    }

    @Synchronized
    fun interpolate(nowMs: Long): InterpolatedPoint {
        if (lastFixTimestamp == 0L) {
            return InterpolatedPoint(0.0, 0.0, null, 0f, false)
        }

        // If stationary (< 0.35 m/s or ~1.2 km/h), lock strictly to fix coordinates without drifting
        if (lastFixSpeedMs < 0.35f) {
            return InterpolatedPoint(lastFixLat, lastFixLng, lastFixBearing, 0f, false)
        }

        // Blending phase after new fix (first 250ms)
        val blendElapsed = (nowMs - blendStartTime).coerceAtLeast(0L)
        val (lat, lng, isExtrapolated) = if (blendElapsed < blendDurationMs && blendDurationMs > 0L) {
            val progress = (blendElapsed.toFloat() / blendDurationMs).coerceIn(0f, 1f)
            // Ease-out interpolation: smooth deceleration into target
            val t = 1f - (1f - progress) * (1f - progress)
            val blendedLat = blendStartLat + (blendTargetLat - blendStartLat) * t
            val blendedLng = blendStartLng + (blendTargetLng - blendStartLng) * t
            Triple(blendedLat, blendedLng, false)
        } else {
            // Forward dead reckoning beyond the fix
            val pos = calculateExtrapolatedPosition(nowMs)
            Triple(pos.first, pos.second, (nowMs - lastFixTimestamp) > 100L)
        }

        return InterpolatedPoint(
            lat = lat,
            lng = lng,
            bearing = lastFixBearing,
            speedMs = lastFixSpeedMs,
            isExtrapolated = isExtrapolated
        )
    }

    @Synchronized
    fun isStationary(): Boolean {
        return lastFixSpeedMs < 0.35f
    }

    @Synchronized
    fun reset() {
        lastFixLat = 0.0
        lastFixLng = 0.0
        lastFixBearing = null
        lastFixSpeedMs = 0f
        lastFixTimestamp = 0L
        blendStartTime = 0L
    }
}
