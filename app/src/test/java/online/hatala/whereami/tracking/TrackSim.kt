package online.hatala.whereami.tracking

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * Deterministic kinematic simulator: builds a ground-truth vehicle path from commands and samples
 * noisy GNSS fixes from it. Used by the replay harness to assert smoothness metrics.
 */
class TrackSim(
    seed: Int = 7,
    val lat0: Double = 49.85,
    val lng0: Double = 19.28,
    /** 1-sigma white horizontal noise [m]. */
    private val posNoiseM: Double = 3.0,
    /** 1-sigma slowly varying (multipath-like) noise [m]. */
    private val corrNoiseM: Double = 2.0,
    private val speedNoise: Double = 0.12,
    private val bearingNoiseDeg: Double = 3.0,
    /** Constant error of the reported bearing (fused provider bias), reported accuracy unchanged. */
    private val bearingBiasDeg: Double = 0.0
) {
    private val rnd = Random(seed)
    private val frame = LocalFrame(lat0, lng0)

    data class Truth(val tMs: Long, val e: Double, val n: Double, val headingDeg: Double, val speed: Double)

    val truth = ArrayList<Truth>()
    private var t = 0L
    private var e = 0.0
    private var n = 0.0
    private var h = 0.0
    private var v = 0.0

    /** Default elapsed time offset so tests do not accidentally rely on t=0. */
    val baseElapsedMs = 1_000_000L

    init {
        record()
    }

    private fun record() {
        truth.add(Truth(t, e, n, h, v))
    }

    private fun stepInternal(dtMs: Long, yawRateDegS: Double, targetSpeed: Double) {
        val dt = dtMs / 1000.0
        // Speed ramps at <= 2.5 m/s^2
        val dv = (targetSpeed - v).coerceIn(-2.5 * dt, 2.5 * dt)
        v += dv
        val hr = Math.toRadians(h)
        val dh = yawRateDegS * dt
        val mid = Math.toRadians(h + dh / 2.0)
        e += v * sin(mid) * dt
        n += v * cos(mid) * dt
        h = (h + dh + 360.0) % 360.0
        t += dtMs
        record()
    }

    fun straight(seconds: Double, speed: Double) {
        val steps = (seconds * 100).toInt()
        repeat(steps) { stepInternal(10, 0.0, speed) }
    }

    /** Turn by [deltaDeg] (positive = clockwise/right) over [seconds] at [speed]. */
    fun turn(deltaDeg: Double, seconds: Double, speed: Double) {
        val steps = (seconds * 100).toInt()
        repeat(steps) { stepInternal(10, deltaDeg / seconds, speed) }
    }

    fun stand(seconds: Double) {
        val steps = (seconds * 100).toInt()
        repeat(steps) { stepInternal(10, 0.0, 0.0) }
    }

    fun truthAt(tMs: Long): Truth {
        val idx = (tMs / 10L).toInt().coerceIn(0, truth.size - 1)
        return truth[idx]
    }

    private fun gauss(): Double {
        // Box-Muller
        val u1 = rnd.nextDouble().coerceAtLeast(1e-12)
        val u2 = rnd.nextDouble()
        return kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1)) * cos(2 * Math.PI * u2)
    }

    private var corrE = 0.0
    private var corrN = 0.0

    /**
     * Sample fixes at [hz] Hz between [fromMs, toMs] of sim time. Bearing is reported only when
     * moving (like Android); speed Doppler is always reported.
     */
    fun fixes(hz: Double = 1.0, fromMs: Long = 0L, toMs: Long = t): List<RawFix> {
        val out = ArrayList<RawFix>()
        val period = (1000.0 / hz).toLong()
        var tt = fromMs
        while (tt <= toMs) {
            val tr = truthAt(tt)
            // AR(1) correlated noise with ~6 s time constant
            val a = kotlin.math.exp(-(period / 1000.0) / 6.0)
            corrE = a * corrE + kotlin.math.sqrt(1 - a * a) * corrNoiseM * gauss()
            corrN = a * corrN + kotlin.math.sqrt(1 - a * a) * corrNoiseM * gauss()
            val pe = tr.e + corrE + posNoiseM * gauss()
            val pn = tr.n + corrN + posNoiseM * gauss()
            val moving = tr.speed > 0.5
            out.add(
                RawFix(
                    tElapsedMs = baseElapsedMs + tt,
                    tUtcMs = 1_700_000_000_000L + tt,
                    lat = frame.lat(pn),
                    lng = frame.lng(pe),
                    accuracyM = (hypot(posNoiseM, corrNoiseM) * 1.2).toFloat(),
                    speedMps = (tr.speed + speedNoise * gauss()).coerceAtLeast(0.0).toFloat().let {
                        if (tr.speed < 0.05) (kotlin.math.abs(speedNoise * gauss()) * 0.5).toFloat() else it
                    },
                    speedAccMps = 0.3f,
                    bearingDeg = if (moving) ((tr.headingDeg + bearingBiasDeg + bearingNoiseDeg * gauss() + 360.0) % 360.0).toFloat() else null,
                    bearingAccDeg = if (moving) 6f else null,
                    altitudeM = 300.0
                )
            )
            tt += period
        }
        return out
    }

    fun truthLat(tMs: Long) = frame.lat(truthAt(tMs).n)
    fun truthLng(tMs: Long) = frame.lng(truthAt(tMs).e)

    fun distToTruthM(tMs: Long, lat: Double, lng: Double): Double {
        val tr = truthAt(tMs)
        return hypot(frame.east(lng) - tr.e, frame.north(lat) - tr.n)
    }

    fun metersBetween(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double =
        hypot(frame.east(lng2) - frame.east(lng1), frame.north(lat2) - frame.north(lat1))

    fun frameOf(): LocalFrame = frame
}
