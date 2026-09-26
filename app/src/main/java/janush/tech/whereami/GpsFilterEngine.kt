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

    /**
     * Evaluates a candidate Location against horizontal accuracy, kinematic plausibility,
     * and consecutive anomaly recovery rules.
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
            ActivityProfile.MTB, ActivityProfile.CYCLING -> 55.0f
            // Reduced from 75m → 55m: at 75m, the uncertainty radius easily spans two parallel
            // residential streets (~50m apart), causing cross-street misidentification (e.g. Klonowa
            // vs Przecznia in Kozy). 55m still admits typical open-sky urban quality.
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

        // Check if implied speed exceeds plausible threshold
        if (impliedSpeedKmh > maxPlausibleSpeedKmh && deltaDistMeters > 30.0) {
            // Check for consecutive anomaly agreement (Stage 3)
            val prevAnomaly = lastAnomalyLocation
            if (prevAnomaly != null && prevAnomaly.distanceTo(candidate) < 50.0f) {
                consecutiveAnomalyCount++
            } else {
                consecutiveAnomalyCount = 1
                lastAnomalyLocation = Location(candidate)
            }

            // If 3 consecutive anomalous fixes agree with each other, accept as legitimate relocation (e.g. boarded car)
            if (consecutiveAnomalyCount >= 3) {
                TelemetryLogger.log(
                    "GPS_FILTER",
                    "Anomaly recovery: 3 consecutive fixes agree at new location (jumped ${deltaDistMeters.toInt()}m). Resetting anchor."
                )
                lastAcceptedLocation = Location(candidate)
                lastAcceptedTimestamp = now
                consecutiveAnomalyCount = 0
                lastAnomalyLocation = null
                return true
            }

            TelemetryLogger.log(
                "GPS_FILTER",
                "Spike rejected: jump of ${deltaDistMeters.toInt()}m in ${deltaTimeSeconds.toInt()}s (implied ${impliedSpeedKmh.toInt()} km/h > ${maxPlausibleSpeedKmh.toInt()} km/h)"
            )
            return false
        }

        // ── Stage 4: Stationary Jitter Dampener ──
        // Dampen position micro-oscillations when stopped without destroying GNSS Doppler hardware speed.
        // If device has micro-displacement (< 4.0m) and speed indicates stationary (< 0.5 m/s or ~1.8 km/h),
        // clamp position to previous fix to prevent map crawling. Doppler speed is preserved for downstream Kalman filter.
        if (deltaDistMeters < 4.0 && candidate.hasSpeed() && candidate.speed < 0.5f) {
            candidate.latitude = prev.latitude
            candidate.longitude = prev.longitude
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
    }
}
