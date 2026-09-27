package janush.tech.whereami

import android.location.Location

class GpsFilterEngine private constructor() {

    companion object {
        @Volatile
        private var INSTANCE: GpsFilterEngine? = null

        fun getInstance(): GpsFilterEngine {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: GpsFilterEngine().also { INSTANCE = it }
            }
        }
    }

    private var lastAcceptedLocation: Location? = null
    private var lastAcceptedTimestamp: Long = 0L
    private var consecutiveAnomalyCount: Int = 0
    private var lastAnomalyLocation: Location? = null
    private var lastMovementBearing: Float? = null

    /**
     * Evaluates a candidate Location against horizontal accuracy, kinematic plausibility,
     * directional projection (anti-backward-jump), and consecutive anomaly recovery rules.
     *
     * @return true if the location is valid and should be accepted, false if it is a spike/outlier.
     */
    @Synchronized
    fun filterLocation(candidate: Location, profile: ActivityProfile): Boolean {
        val now = candidate.time.takeIf { it > 0 } ?: System.currentTimeMillis()

        // ── Stage 1: Horizontal Accuracy Gate ─────────────────────────────────────
        val maxAccuracy = when (profile) {
            ActivityProfile.WALKING, ActivityProfile.HIKING -> 40.0f
            ActivityProfile.RUNNING -> 45.0f
            ActivityProfile.MTB, ActivityProfile.CYCLING -> 50.0f
            ActivityProfile.CAR -> 55.0f
        }

        if (candidate.hasAccuracy() && candidate.accuracy > maxAccuracy) {
            // Relax threshold slightly if we have had no valid fixes for over 45 seconds (deep canopy / canyon)
            val timeSinceLastValid = now - lastAcceptedTimestamp
            val relaxedMax = maxAccuracy * 2.0f
            if (timeSinceLastValid > 45_000L && candidate.accuracy <= relaxedMax) {
                TelemetryLogger.log("GPS_FILTER", "Accepting degraded fix (acc=${candidate.accuracy.toInt()}m) due to 45s silence")
            } else {
                TelemetryLogger.log("GPS_FILTER", "Rejected: low accuracy ${candidate.accuracy.toInt()}m > ${maxAccuracy.toInt()}m ($profile)")
                return false
            }
        }

        val prev = lastAcceptedLocation
        if (prev == null) {
            // First fix is always accepted as baseline anchor
            lastAcceptedLocation = Location(candidate)
            lastAcceptedTimestamp = now
            consecutiveAnomalyCount = 0
            if (candidate.hasBearing()) lastMovementBearing = candidate.bearing
            return true
        }

        // ── Stage 2: Kinematic Plausibility Check (Speed Jump Rejection) ──────────
        val deltaDistMeters = prev.distanceTo(candidate).toDouble()
        val deltaTimeSeconds = (now - lastAcceptedTimestamp) / 1000.0

        if (deltaTimeSeconds <= 0.0) {
            // Duplicate timestamp or out-of-order packet: ignore
            return false
        }

        val impliedSpeedKmh = (deltaDistMeters / deltaTimeSeconds) * 3.6

        val maxPlausibleSpeedKmh = when (profile) {
            ActivityProfile.WALKING -> 15.0
            ActivityProfile.HIKING -> 25.0
            ActivityProfile.RUNNING -> 35.0
            ActivityProfile.MTB -> 65.0
            ActivityProfile.CYCLING -> 90.0
            ActivityProfile.CAR -> 220.0
        }

        // Reject impossible speed jumps even for smaller distances (e.g. 15m in 0.2s = 270 km/h)
        val isSpeedAnomaly = impliedSpeedKmh > maxPlausibleSpeedKmh && (deltaDistMeters > 10.0 || deltaTimeSeconds < 0.8)

        // ── Stage 3: Directional Projection Gate (Anti-Backward-Jump Filter) ──────
        // In outdoor activities (MTB, Cycling, Running), GPS multipath under foliage frequently causes
        // sudden 10-25m jumps backwards along the trail. If moving forward (speed >= 1.2 m/s), check dot product.
        var isBackwardJump = false
        val activeBearing = lastMovementBearing ?: (if (prev.hasBearing()) prev.bearing else null)
        val isMovingForward = (candidate.hasSpeed() && candidate.speed >= 1.2f) || (prev.hasSpeed() && prev.speed >= 1.2f)

        if (isMovingForward && activeBearing != null && deltaDistMeters >= 8.0) {
            val radBearing = Math.toRadians(activeBearing.toDouble())
            // Heading vector (North is +y, East is +x)
            val vx = kotlin.math.sin(radBearing)
            val vy = kotlin.math.cos(radBearing)

            // Displacement vector from prev to candidate
            val dy = (candidate.latitude - prev.latitude) * 111320.0
            val cosLat = kotlin.math.cos(Math.toRadians(prev.latitude)).coerceAtLeast(0.01)
            val dx = (candidate.longitude - prev.longitude) * 111320.0 * cosLat
            val dispMag = kotlin.math.hypot(dx, dy)

            if (dispMag > 0.1) {
                val cosTheta = (dx * vx + dy * vy) / dispMag
                // cosTheta < -0.3 corresponds to an angle > 107° backwards against heading
                if (cosTheta < -0.35) {
                    isBackwardJump = true
                    TelemetryLogger.log(
                        "GPS_FILTER",
                        "Backward spike detected: jump of ${deltaDistMeters.toInt()}m at angle ${(Math.toDegrees(Math.acos(cosTheta.coerceIn(-1.0, 1.0)))).toInt()}° against heading ${activeBearing.toInt()}°"
                    )
                }
            }
        }

        if (isSpeedAnomaly || isBackwardJump) {
            // Anomaly recovery: check if consecutive anomalies agree (e.g. true U-turn or boarded transport)
            val prevAnomaly = lastAnomalyLocation
            if (prevAnomaly != null && prevAnomaly.distanceTo(candidate) < 40.0f) {
                consecutiveAnomalyCount++
            } else {
                consecutiveAnomalyCount = 1
                lastAnomalyLocation = Location(candidate)
            }

            // If 3 consecutive anomalous fixes agree with each other, accept as legitimate trajectory change
            if (consecutiveAnomalyCount >= 3) {
                TelemetryLogger.log(
                    "GPS_FILTER",
                    "Anomaly recovery: 3 consecutive fixes agree at new location (jumped ${deltaDistMeters.toInt()}m). Resetting anchor."
                )
                lastAcceptedLocation = Location(candidate)
                lastAcceptedTimestamp = now
                consecutiveAnomalyCount = 0
                lastAnomalyLocation = null
                if (candidate.hasBearing()) {
                    lastMovementBearing = candidate.bearing
                } else if (deltaDistMeters >= 4.0) {
                    lastMovementBearing = prev.bearingTo(candidate)
                }
                return true
            }

            TelemetryLogger.log(
                "GPS_FILTER",
                "Spike rejected: dist=${deltaDistMeters.toInt()}m in ${deltaTimeSeconds.toInt()}s (implied ${impliedSpeedKmh.toInt()} km/h, isBackward=$isBackwardJump)"
            )
            return false
        }

        // ── Stage 4: Stationary Jitter Dampener ───────────────────────────────────
        // Dampen position micro-oscillations when stopped without destroying GNSS Doppler hardware speed.
        if (deltaDistMeters < 3.5 && candidate.hasSpeed() && candidate.speed < 0.4f) {
            candidate.latitude = prev.latitude
            candidate.longitude = prev.longitude
        }

        // Update tracking bearing if movement occurred
        if (candidate.hasBearing() && candidate.hasSpeed() && candidate.speed >= 1.0f) {
            lastMovementBearing = candidate.bearing
        } else if (deltaDistMeters >= 4.0) {
            lastMovementBearing = prev.bearingTo(candidate)
        }

        // Valid fix
        consecutiveAnomalyCount = 0
        lastAnomalyLocation = null
        lastAcceptedLocation = Location(candidate)
        lastAcceptedTimestamp = now
        return true
    }

    @Synchronized
    fun reset() {
        lastAcceptedLocation = null
        lastAcceptedTimestamp = 0L
        consecutiveAnomalyCount = 0
        lastAnomalyLocation = null
        lastMovementBearing = null
    }
}
