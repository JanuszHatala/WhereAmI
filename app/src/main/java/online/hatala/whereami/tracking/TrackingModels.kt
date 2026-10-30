package online.hatala.whereami.tracking

import kotlin.math.cos

/**
 * Tracking Engine v2 shared models. Everything in this package is pure Kotlin (no Android types)
 * so it can be unit tested on the JVM and replayed from recorded traces.
 *
 * Time base: all `tElapsedMs` values are milliseconds of `SystemClock.elapsedRealtime()` (monotonic,
 * continues in doze). `tUtcMs` is wall-clock time of the fix (`Location.time`) and is used only for
 * persistence.
 */

/** Raw GNSS fix as delivered by FusedLocationProvider, normalised to primitives. */
data class RawFix(
    val tElapsedMs: Long,
    val tUtcMs: Long,
    val lat: Double,
    val lng: Double,
    val accuracyM: Float?,
    val speedMps: Float?,
    val speedAccMps: Float?,
    val bearingDeg: Float?,
    val bearingAccDeg: Float?,
    val altitudeM: Double?
)

/**
 * Per-activity kinematic tuning of the engine. All values are physical quantities
 * (no magic per-stage booleans).
 */
data class TrackingProfile(
    val name: String,
    /** Horizontal accuracy above which a fix is rejected (relaxed x2 after long silence). */
    val maxAccuracyM: Float,
    /** Physically plausible top speed (hard gate on implied speed). */
    val maxSpeedMps: Double,
    /** Typical (steady-state std-dev of) longitudinal acceleration [m/s^2]. */
    val sigmaAccel: Double,
    /** Std-dev of unmodelled yaw acceleration [rad/s^2]. */
    val sigmaYawAccel: Double,
    /** Time constant at which an unobserved turn rate decays to zero [s]. */
    val yawRateTauS: Double,
    /** Minimum speed for heading to be considered observable. */
    val minHeadingSpeedMps: Double,
    /** Speed above which the user is considered moving. */
    val movingSpeedMps: Double,
    /** Correlation time of longitudinal acceleration (braking / pulling away lasts this long) [s]. */
    val accelTauS: Double = 2.0
) {
    companion object {
        val CAR = TrackingProfile("CAR", 55f, 61.0, 2.2, 0.35, 4.0, 1.4, 1.5, 2.5)
        val CYCLING = TrackingProfile("CYCLING", 50f, 25.0, 1.4, 0.8, 3.0, 1.0, 1.0, 2.0)
        val MTB = TrackingProfile("MTB", 50f, 18.0, 1.6, 1.0, 2.5, 0.9, 0.8, 1.5)
        val RUNNING = TrackingProfile("RUNNING", 45f, 9.7, 1.2, 1.2, 2.2, 0.8, 0.7, 2.5)
        val HIKING = TrackingProfile("HIKING", 40f, 7.0, 0.8, 1.5, 2.0, 0.6, 0.5, 2.5)
        val WALKING = TrackingProfile("WALKING", 40f, 4.2, 0.8, 1.6, 1.8, 0.5, 0.45, 2.5)
    }
}

/**
 * Filtered kinematic state at [tElapsedMs]. This is what the UI render pipeline extrapolates from,
 * so it carries velocity and turn rate and its own uncertainty.
 */
data class TrackState(
    val tElapsedMs: Long,
    val tUtcMs: Long,
    val lat: Double,
    val lng: Double,
    val altitudeM: Double?,
    val speedMps: Double,
    /** Heading in degrees, clockwise from north, [0, 360). Meaningful only if [headingConfident]. */
    val headingDeg: Double,
    /** Turn rate in degrees/second, clockwise positive. */
    val yawRateDegPerS: Double,
    val posStdM: Double,
    val headingStdDeg: Double,
    val headingConfident: Boolean,
    /** True when zero-velocity updates are active: position and heading are physically frozen. */
    val isStationary: Boolean,
    /** Consecutive rejected fixes preceding this state (degraded signal indicator). */
    val rejectStreak: Int,
    /** True when the filter was (re)initialised at this fix: the UI must snap, not blend. */
    val isReset: Boolean,
    val accuracyM: Float?,
    /** Longitudinal acceleration (m/s², negative = braking); 0 while stationary. */
    val accelMps2: Double = 0.0
)

/** A fixed-lag RTS smoothed point suitable for persistence and polyline drawing. */
data class SmoothedPoint(
    val tElapsedMs: Long,
    val tUtcMs: Long,
    val lat: Double,
    val lng: Double,
    val altitudeM: Double?,
    val speedMps: Double,
    val headingDeg: Double,
    val accuracyM: Float,
    val isStationary: Boolean,
    val rawLat: Double? = null,
    val rawLng: Double? = null
)

enum class FixDecision {
    ACCEPTED,
    /** Filter was re-seeded because several rejected fixes agree with each other (legit jump). */
    ACCEPTED_AFTER_RESET,
    REJECTED_ACCURACY,
    REJECTED_STALE,
    REJECTED_IMPLIED_SPEED,
    REJECTED_INNOVATION
}

data class EngineResult(
    val decision: FixDecision,
    val state: TrackState?,
    /** Smoothed points that left the lag window with this fix, oldest first. */
    val finalized: List<SmoothedPoint>
) {
    val accepted: Boolean get() = decision == FixDecision.ACCEPTED || decision == FixDecision.ACCEPTED_AFTER_RESET
}

/** Local tangent plane (east, north) in metres around a geodetic origin. */
class LocalFrame(val lat0: Double, val lng0: Double) {
    val mPerDegLat: Double = METERS_PER_DEG_LAT
    val mPerDegLng: Double = METERS_PER_DEG_LAT * cos(Math.toRadians(lat0)).coerceAtLeast(0.01)

    fun east(lng: Double): Double = (lng - lng0) * mPerDegLng
    fun north(lat: Double): Double = (lat - lat0) * mPerDegLat
    fun lat(northM: Double): Double = lat0 + northM / mPerDegLat
    fun lng(eastM: Double): Double = lng0 + eastM / mPerDegLng

    companion object {
        const val METERS_PER_DEG_LAT = 111_320.0
    }
}

internal fun wrapRad(a: Double): Double {
    var x = a % (2.0 * Math.PI)
    if (x > Math.PI) x -= 2.0 * Math.PI
    if (x < -Math.PI) x += 2.0 * Math.PI
    return x
}

internal fun wrapDeg360(a: Double): Double {
    val m = a % 360.0
    return if (m < 0.0) m + 360.0 else m
}

/** Shortest signed angular difference to - from in degrees, (-180, 180]. */
fun shortestDeltaDeg(from: Double, to: Double): Double {
    var d = (to - from) % 360.0
    if (d > 180.0) d -= 360.0
    if (d <= -180.0) d += 360.0
    return d
}
