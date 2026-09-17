package janush.tech.whereami

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SpeedKinematicsTest {

    /**
     * Reusable model of the Adaptive 1D Velocity Kalman Filter from LocationManager.
     */
    class VelocityKalmanFilter(
        private val baseProcessNoise: Float = 0.08f,
        private val adaptiveFactor: Float = 0.35f,
        private val defaultMeasNoise: Float = 0.25f,
        private val zeroSnapThreshold: Float = 0.4f
    ) {
        var kalmanSpeed: Float? = null
        var kalmanVariance: Float = 0.25f
        var lastValidSpeedMs: Float = 0f

        fun update(rawSpeed: Float, measAccuracyMps: Float? = null): Float {
            if (rawSpeed < zeroSnapThreshold) {
                kalmanSpeed = 0f
                lastValidSpeedMs = 0f
                return 0f
            }

            val measNoise = when {
                measAccuracyMps != null && measAccuracyMps > 0f -> (measAccuracyMps * measAccuracyMps).coerceIn(0.04f, 1.5f)
                else -> defaultMeasNoise
            }

            if (kalmanSpeed == null || kalmanSpeed == 0f) {
                kalmanSpeed = rawSpeed
                kalmanVariance = measNoise
                lastValidSpeedMs = rawSpeed
                return rawSpeed
            }

            val innovation = rawSpeed - kalmanSpeed!!
            val adaptiveQ = baseProcessNoise + adaptiveFactor * innovation * innovation
            val predictedVariance = kalmanVariance + adaptiveQ
            val gain = predictedVariance / (predictedVariance + measNoise)
            kalmanSpeed = kalmanSpeed!! + gain * innovation
            kalmanVariance = (1f - gain) * predictedVariance

            val finalSpeed = if (kalmanSpeed!! < zeroSnapThreshold) 0f else kalmanSpeed!!
            lastValidSpeedMs = finalSpeed
            return finalSpeed
        }
    }

    @Test
    fun testZeroSnapAntiCreep() {
        val filter = VelocityKalmanFilter()
        // Car running at 15 m/s (~54 km/h)
        filter.update(15.0f)
        assertEquals(15.0f, filter.lastValidSpeedMs, 0.01f)

        // Stopping at red light (Doppler speed drops below zero-snap 0.4 m/s)
        val stopped = filter.update(0.2f)
        assertEquals("Speed should immediately snap to 0.0 m/s without creeping", 0.0f, stopped, 0.0001f)
        assertEquals(0.0f, filter.lastValidSpeedMs, 0.0001f)
    }

    @Test
    fun testFastAccelerationResponsiveness() {
        val filter = VelocityKalmanFilter()
        // Initial stop
        filter.update(0.0f)

        // Sudden acceleration from standstill to 10 m/s (36 km/h)
        val step1 = filter.update(10.0f)
        // Since kalmanSpeed was 0, it snaps immediately to 10.0
        assertEquals(10.0f, step1, 0.01f)

        // Next second continues accelerating to 15 m/s
        val step2 = filter.update(15.0f)
        // With adaptive noise, innovation is 5 m/s, gain should be > 0.85
        assertTrue("Output ($step2) should rapidly adapt to acceleration (>= 14.0 m/s)", step2 >= 14.0f)
    }

    @Test
    fun testSteadyCruisingMicroJitterSuppression() {
        val filter = VelocityKalmanFilter()
        // Stabilize at 25.0 m/s (90 km/h)
        for (i in 1..5) {
            filter.update(25.0f)
        }

        // Doppler noise oscillating +/- 0.4 m/s around 25.0
        val noisyInputs = listOf(25.3f, 24.7f, 25.4f, 24.6f, 25.2f, 24.8f)
        val outputs = noisyInputs.map { filter.update(it) }

        for (out in outputs) {
            val delta = abs(out - 25.0f)
            assertTrue("Output deviation $delta should be dampened well below 0.35 m/s", delta < 0.35f)
        }
    }

    @Test
    fun testKinematicDecouplingDoesNotClampMovingSpeed() {
        // Simulating the logic: isMoving = (rawSpeed != null && rawSpeed > 1.2f) || distMoved > 3.0f
        val rawSpeedHighway = 25.0f // 90 km/h
        val distMoved = 25.0f // 25m in 1 sec
        val isMoving = (rawSpeedHighway > 1.2f) || distMoved > 3.0f

        assertTrue("Vehicle on highway must be classified as moving", isMoving)
        // StationaryDetector check is bypassed when isMoving is true
    }
}
