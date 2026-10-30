package online.hatala.whereami.tracking

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Ring buffer of gravity-projected gyroscope yaw rates (clockwise positive, rad/s).
 *
 * Projecting the angular velocity onto the gravity axis makes the measurement independent of how
 * the phone is mounted (portrait/landscape, tilted in a cradle). It is only fed while the screen
 * is on and the user is moving (see GyroHeadingSensor); otherwise it stays empty and every
 * consumer falls back to GNSS-only heading.
 */
class GyroYawTracker(private val horizonMs: Long = 6_000L) {

    private val times = LongArray(CAPACITY)
    private val rates = DoubleArray(CAPACITY)
    private var head = 0
    private var count = 0

    @Synchronized
    fun clear() {
        head = 0
        count = 0
    }

    @Synchronized
    fun onSample(tMs: Long, yawRateRadS: Double) {
        if (count > 0) {
            val lastIdx = (head - 1 + CAPACITY) % CAPACITY
            if (tMs <= times[lastIdx]) return
        }
        times[head] = tMs
        rates[head] = yawRateRadS
        head = (head + 1) % CAPACITY
        if (count < CAPACITY) count++
        // Drop samples older than the horizon lazily via capacity; nothing else to do.
    }

    @Synchronized
    fun latestSampleMs(): Long? = if (count == 0) null else times[(head - 1 + CAPACITY) % CAPACITY]

    /**
     * Integral of yaw rate over [t0Ms, t1Ms] in radians (trapezoid), or null if the buffer does not
     * cover at least [minCoverage] of the interval.
     */
    @Synchronized
    fun integrate(t0Ms: Long, t1Ms: Long, minCoverage: Double = 0.7): Double? {
        if (t1Ms <= t0Ms || count < 2) return null
        var sum = 0.0
        var covered = 0L
        var prevT = 0L
        var prevR = 0.0
        var havePrev = false
        for (n in 0 until count) {
            val idx = (head - count + n + CAPACITY) % CAPACITY
            val t = times[idx]
            val r = rates[idx]
            if (havePrev) {
                val a = maxOf(prevT, t0Ms)
                val b = minOf(t, t1Ms)
                if (b > a) {
                    val span = t - prevT
                    val ra = prevR + (r - prevR) * ((a - prevT).toDouble() / span)
                    val rb = prevR + (r - prevR) * ((b - prevT).toDouble() / span)
                    sum += 0.5 * (ra + rb) * (b - a) / 1000.0
                    covered += (b - a)
                }
            }
            prevT = t
            prevR = r
            havePrev = true
        }
        // Hold last rate to the end of the interval (up to 250 ms) to avoid systematic under-coverage.
        val lastT = prevT
        if (lastT < t1Ms) {
            val hold = minOf(t1Ms - maxOf(lastT, t0Ms), 250L)
            if (hold > 0) {
                sum += prevR * hold / 1000.0
                covered += hold
            }
        }
        val total = (t1Ms - t0Ms).toDouble()
        if (covered / total < minCoverage) return null
        return sum * (total / covered).coerceAtMost(1.0 / minCoverage)
    }

    @Synchronized
    fun meanRate(t0Ms: Long, t1Ms: Long): Double? {
        val integral = integrate(t0Ms, t1Ms) ?: return null
        return integral / ((t1Ms - t0Ms) / 1000.0)
    }

    companion object {
        private const val CAPACITY = 512

        /**
         * @param gyro angular velocity [rad/s] in device axes (TYPE_GYROSCOPE)
         * @param gravity vector in device axes that points UP (TYPE_GRAVITY / low-passed accelerometer)
         * @return heading rate, clockwise positive [rad/s], or null if gravity is unusable
         */
        fun headingRateFrom(gyro: FloatArray, gravity: FloatArray): Double? {
            val gx = gravity[0].toDouble()
            val gy = gravity[1].toDouble()
            val gz = gravity[2].toDouble()
            val norm = sqrt(gx * gx + gy * gy + gz * gz)
            if (norm < 4.0) return null // free fall / garbage
            val ccw = (gyro[0] * gx + gyro[1] * gy + gyro[2] * gz) / norm
            val r = -ccw
            return if (abs(r) < 20.0) r else null
        }
    }
}
