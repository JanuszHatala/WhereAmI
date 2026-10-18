package janush.tech.whereami

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Stabilized coordinate output from [PositionStabilizer].
 */
data class StabilizedFix(
    val lat: Double,
    val lng: Double,
    val accuracy: Float,
    val isAnchored: Boolean
)

/**
 * Hybrid 2D Kinematic Kalman Filter + Stationary Spatial Anchor Gate.
 *
 * Solves mobile GPS multipath noise and stationary coordinate dance:
 * 1. Stationary Spatial Anchor Gate: When stationary (< 0.35 m/s or physical accelerometer quiet),
 *    clamps fixes to an anchor coordinate within the noise envelope (max(8m, accuracy * 0.7)).
 * 2. 2D Continuous Kalman Filter: Smooths latitude and longitude during active motion, eliminating
 *    lateral micro-oscillations without lagging behind true vehicle trajectory.
 */
class PositionStabilizer {

    // ── Stationary Anchor State ──
    private var anchorLat: Double? = null
    private var anchorLng: Double? = null
    private var anchorAccuracy: Float = 10f
    private var consecutiveDisplacements: Int = 0

    // ── 2D Kalman State ──
    private var kalmanLat: Double = 0.0
    private var kalmanLng: Double = 0.0
    private var varianceLat: Double = 1e-8
    private var varianceLng: Double = 1e-8
    private var lastKalmanTimeMs: Long = 0L

    companion object {
        private const val METERS_PER_DEG_LAT = 111320.0
        private const val STATIONARY_SPEED_THRESHOLD_MPS = 0.35f
        private const val BREAKOUT_SPEED_THRESHOLD_MPS = 0.80f
        private const val BASE_ANCHOR_GATE_METERS = 8.0
        private const val BREAKOUT_DISPLACEMENT_COUNT = 3
    }

    @Synchronized
    fun update(
        lat: Double,
        lng: Double,
        accuracy: Float,
        speedMs: Float,
        timestamp: Long,
        isPhysicalStationary: Boolean = false
    ): StabilizedFix {
        val safeAcc = accuracy.coerceIn(1.0f, 200.0f)

        val isSubLocomotion = speedMs < BREAKOUT_SPEED_THRESHOLD_MPS

        // 1. Cold start initialization
        if (lastKalmanTimeMs == 0L) {
            kalmanLat = lat
            kalmanLng = lng
            val degAcc = safeAcc / METERS_PER_DEG_LAT
            varianceLat = degAcc * degAcc
            varianceLng = degAcc * degAcc
            lastKalmanTimeMs = timestamp

            if (speedMs < STATIONARY_SPEED_THRESHOLD_MPS || (isPhysicalStationary && isSubLocomotion)) {
                anchorLat = lat
                anchorLng = lng
                anchorAccuracy = safeAcc
                return StabilizedFix(lat, lng, safeAcc, isAnchored = true)
            }
            return StabilizedFix(lat, lng, safeAcc, isAnchored = false)
        }

        // 2. Stationary Gate Evaluation
        // Physical accelerometer stillness must NEVER anchor during active locomotion (speed >= 0.8 m/s or 2.9 km/h)
        val candidateStationary = (speedMs < STATIONARY_SPEED_THRESHOLD_MPS) || (isPhysicalStationary && isSubLocomotion)

        if (candidateStationary) {
            val curAnchorLat = anchorLat
            val curAnchorLng = anchorLng

            if (curAnchorLat == null || curAnchorLng == null) {
                // Establish stationary anchor
                anchorLat = lat
                anchorLng = lng
                anchorAccuracy = safeAcc
                consecutiveDisplacements = 0
                syncKalmanTo(lat, lng, safeAcc, timestamp)
                return StabilizedFix(lat, lng, safeAcc, isAnchored = true)
            }

            // Calculate distance between candidate fix and existing anchor
            val distFromAnchorMeters = computeDistanceMeters(curAnchorLat, curAnchorLng, lat, lng)
            val anchorGateRadius = maxOf(BASE_ANCHOR_GATE_METERS, safeAcc * 0.70)

            if (distFromAnchorMeters <= anchorGateRadius) {
                // Fix is within noise gate: clamp strictly to anchor
                consecutiveDisplacements = 0
                // Smoothly update accuracy to reflect sensor certainty
                anchorAccuracy = (anchorAccuracy * 0.8f) + (safeAcc * 0.2f)
                syncKalmanTo(curAnchorLat, curAnchorLng, anchorAccuracy, timestamp)
                return StabilizedFix(curAnchorLat, curAnchorLng, anchorAccuracy, isAnchored = true)
            } else {
                // Candidate outside gate: possible breakout or transient multipath spike
                consecutiveDisplacements++
                val speedBreakout = speedMs >= BREAKOUT_SPEED_THRESHOLD_MPS && !isPhysicalStationary
                if (consecutiveDisplacements >= BREAKOUT_DISPLACEMENT_COUNT || speedBreakout) {
                    // Confirmed locomotion breakout: release anchor
                    releaseAnchor()
                } else {
                    // Suppress single spike, retain anchor
                    return StabilizedFix(curAnchorLat, curAnchorLng, anchorAccuracy, isAnchored = true)
                }
            }
        } else {
            // Speed clearly above stationary threshold: release anchor unless physical accelerometer indicates stillness
            if (!isPhysicalStationary) {
                releaseAnchor()
            } else {
                val curAnchorLat = anchorLat
                val curAnchorLng = anchorLng
                if (curAnchorLat != null && curAnchorLng != null) {
                    val distFromAnchorMeters = computeDistanceMeters(curAnchorLat, curAnchorLng, lat, lng)
                    val anchorGateRadius = maxOf(BASE_ANCHOR_GATE_METERS, safeAcc * 0.70)
                    if (distFromAnchorMeters <= anchorGateRadius) {
                        return StabilizedFix(curAnchorLat, curAnchorLng, anchorAccuracy, isAnchored = true)
                    }
                    consecutiveDisplacements++
                    if (consecutiveDisplacements >= BREAKOUT_DISPLACEMENT_COUNT) {
                        releaseAnchor()
                    } else {
                        return StabilizedFix(curAnchorLat, curAnchorLng, anchorAccuracy, isAnchored = true)
                    }
                } else {
                    releaseAnchor()
                }
            }
        }

        // 3. 2D Kalman Update for Moving State
        val dtSec = ((timestamp - lastKalmanTimeMs).coerceAtLeast(0L) / 1000.0).coerceIn(0.01, 10.0)
        lastKalmanTimeMs = timestamp

        // Process noise: spatial variance growth based on speed
        val metersPerDegLng = METERS_PER_DEG_LAT * cos(Math.toRadians(lat)).coerceAtLeast(0.01)
        val processNoiseMeters = (speedMs * dtSec * 0.5) + 0.1
        val qLat = (processNoiseMeters / METERS_PER_DEG_LAT).let { it * it }
        val qLng = (processNoiseMeters / metersPerDegLng).let { it * it }

        // Predict
        varianceLat += qLat
        varianceLng += qLng

        // Measurement noise
        val rLat = (safeAcc / METERS_PER_DEG_LAT).let { it * it }
        val rLng = (safeAcc / metersPerDegLng).let { it * it }

        // Kalman gain
        val kLat = varianceLat / (varianceLat + rLat)
        val kLng = varianceLng / (varianceLng + rLng)

        // Update state
        kalmanLat += kLat * (lat - kalmanLat)
        kalmanLng += kLng * (lng - kalmanLng)

        // Update covariance
        varianceLat *= (1.0 - kLat)
        varianceLng *= (1.0 - kLng)

        val smoothedAcc = (sqrt(varianceLat) * METERS_PER_DEG_LAT).toFloat().coerceIn(safeAcc * 0.7f, safeAcc * 1.5f)

        return StabilizedFix(kalmanLat, kalmanLng, smoothedAcc, isAnchored = false)
    }

    @Synchronized
    fun releaseAnchor() {
        anchorLat = null
        anchorLng = null
        consecutiveDisplacements = 0
    }

    @Synchronized
    fun reset() {
        releaseAnchor()
        lastKalmanTimeMs = 0L
        kalmanLat = 0.0
        kalmanLng = 0.0
    }

    private fun syncKalmanTo(lat: Double, lng: Double, acc: Float, timestamp: Long) {
        kalmanLat = lat
        kalmanLng = lng
        val degAcc = acc / METERS_PER_DEG_LAT
        varianceLat = degAcc * degAcc
        varianceLng = degAcc * degAcc
        lastKalmanTimeMs = timestamp
    }

    private fun computeDistanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLng / 2) * sin(dLng / 2)
        val c = 2 * kotlin.math.atan2(sqrt(a), sqrt(1 - a))
        return 6371000.0 * c
    }
}
