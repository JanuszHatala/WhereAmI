package online.hatala.whereami.tracking

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * Acceleration- and rate-limited angular follower.
 *
 * Replaces the per-frame first-order slew + deadband. The commanded angular *rate* follows
 * `min(maxRate, k * error, sqrt(2 * a * error))` with a bounded angular *acceleration*, so:
 *  - rotation starts and stops smoothly (no stepping),
 *  - it cannot overshoot the target (no rotate-past-and-correct-back),
 *  - extreme targets are tracked at a bounded, comfortable speed.
 */
class RotationSlew(
    /** Proportional gain of the rate command [1/s]; ~ inverse settling time constant. */
    var naturalFreqRadS: Double,
    var maxRateDegS: Double,
    var maxAccelDegS2: Double = 700.0,
    /** Target changes smaller than this are ignored (suppresses micro-hunting at near-constant heading). */
    var hysteresisDeg: Double = 0.4
) {
    var angleDeg: Double = 0.0
        private set
    var rateDegS: Double = 0.0
        private set
    private var heldTarget: Double = 0.0
    var initialized: Boolean = false
        private set

    fun snap(deg: Double) {
        angleDeg = wrapDeg360(deg)
        heldTarget = angleDeg
        rateDegS = 0.0
        initialized = true
    }

    fun step(targetDeg: Double, dtSec: Double): Double {
        if (!initialized) {
            snap(targetDeg)
            return angleDeg
        }
        if (dtSec <= 0.0) return angleDeg
        if (abs(shortestDeltaDeg(heldTarget, targetDeg)) > hysteresisDeg) heldTarget = wrapDeg360(targetDeg)
        val dt = min(dtSec, 0.25)
        val n = max(1, ceil(dt / 0.008).toInt())
        val h = dt / n
        repeat(n) {
            val err = shortestDeltaDeg(angleDeg, heldTarget)
            val mag = abs(err)
            val desired = sign(err) * min(
                maxRateDegS,
                min(naturalFreqRadS * mag, sqrt(2.0 * 0.8 * maxAccelDegS2 * mag))
            )
            val dv = (desired - rateDegS).coerceIn(-maxAccelDegS2 * h, maxAccelDegS2 * h)
            rateDegS += dv
            angleDeg = wrapDeg360(angleDeg + rateDegS * h)
        }
        return angleDeg
    }

    companion object {
        fun forProfile(p: TrackingProfile): RotationSlew = when (p.name) {
            "CAR" -> RotationSlew(naturalFreqRadS = 6.0, maxRateDegS = 160.0, maxAccelDegS2 = 700.0)
            "CYCLING", "MTB" -> RotationSlew(naturalFreqRadS = 5.0, maxRateDegS = 140.0, maxAccelDegS2 = 600.0)
            // On foot: filter heading still wanders a few degrees on a straight sidewalk; 3 deg hysteresis
            // and a lower rate keep course-up from waving to and fro (compass must never command the map).
            else -> RotationSlew(naturalFreqRadS = 3.0, maxRateDegS = 70.0, maxAccelDegS2 = 250.0, hysteresisDeg = 3.0)
        }
    }
}
