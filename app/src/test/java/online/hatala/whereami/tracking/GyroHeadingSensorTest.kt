package online.hatala.whereami.tracking

import android.hardware.Sensor
import android.hardware.SensorEventListener
import org.junit.Assert.*
import org.junit.Test

class GyroHeadingSensorTest {

    private class FakeRegistrar(var supported: Set<Int> = setOf(Sensor.TYPE_GYROSCOPE, Sensor.TYPE_GRAVITY, Sensor.TYPE_ACCELEROMETER)) : SensorRegistrar {
        val registered = mutableListOf<Int>()
        var unregisterCalls = 0
        var gyroPeriodUs = 0
        var gyroLatencyUs = 0
        override fun register(sensorType: Int, samplingPeriodUs: Int, maxReportLatencyUs: Int, listener: SensorEventListener): Boolean {
            if (sensorType !in supported) return false
            registered += sensorType
            if (sensorType == Sensor.TYPE_GYROSCOPE) { gyroPeriodUs = samplingPeriodUs; gyroLatencyUs = maxReportLatencyUs }
            return true
        }
        override fun unregister(listener: SensorEventListener) { unregisterCalls++; registered.clear() }
    }

    @Test
    fun policy_requiresForegroundMovingAndFollowingOrRecording() {
        assertTrue(GyroPolicy.shouldRun(true, true, true))
        assertFalse("screen off", GyroPolicy.shouldRun(false, true, true))
        assertFalse("stationary", GyroPolicy.shouldRun(true, false, true))
        assertFalse("browsing map, not recording", GyroPolicy.shouldRun(true, true, false))
    }

    @Test
    fun movingHysteresis_doesNotFlapAtCrawlSpeed() {
        assertFalse(GyroPolicy.isMovingForGyro(1.5f, wasMoving = false))
        assertTrue(GyroPolicy.isMovingForGyro(2.5f, wasMoving = false))
        assertTrue(GyroPolicy.isMovingForGyro(1.0f, wasMoving = true))
        assertFalse(GyroPolicy.isMovingForGyro(0.5f, wasMoving = true))
    }

    @Test
    fun start_registersGyroAndGravityOnce_andIsIdempotent() {
        val reg = FakeRegistrar()
        val s = GyroHeadingSensor(reg, GyroYawTracker())
        s.start(); s.start()
        assertTrue(s.isRunning)
        assertEquals(1, reg.registered.count { it == Sensor.TYPE_GYROSCOPE })
        assertEquals(1, reg.registered.count { it == Sensor.TYPE_GRAVITY })
        assertEquals(GyroHeadingSensor.GYRO_PERIOD_US, reg.gyroPeriodUs)
        assertEquals(GyroHeadingSensor.GYRO_MAX_LATENCY_US, reg.gyroLatencyUs)
    }

    @Test
    fun stop_unregistersAndIsIdempotent_noLeak() {
        val reg = FakeRegistrar()
        val s = GyroHeadingSensor(reg, GyroYawTracker())
        s.stop()
        assertEquals("stop before start must be a no-op", 0, reg.unregisterCalls)
        s.start(); s.stop(); s.stop()
        assertFalse(s.isRunning)
        assertEquals(1, reg.unregisterCalls)
        assertTrue(reg.registered.isEmpty())
    }

    @Test
    fun restartAfterStop_registersAgain() {
        val reg = FakeRegistrar()
        val s = GyroHeadingSensor(reg, GyroYawTracker())
        s.start(); s.stop(); s.start()
        assertTrue(s.isRunning)
        assertEquals(1, reg.registered.count { it == Sensor.TYPE_GYROSCOPE })
    }

    @Test
    fun missingGravitySensor_fallsBackToAccelerometer() {
        val reg = FakeRegistrar(setOf(Sensor.TYPE_GYROSCOPE, Sensor.TYPE_ACCELEROMETER))
        val s = GyroHeadingSensor(reg, GyroYawTracker())
        s.start()
        assertTrue(s.isRunning)
        assertTrue(Sensor.TYPE_ACCELEROMETER in reg.registered)
    }

    @Test
    fun missingGyro_doesNotRun_andReleasesPartialRegistrations() {
        val reg = FakeRegistrar(setOf(Sensor.TYPE_GRAVITY))
        val s = GyroHeadingSensor(reg, GyroYawTracker())
        s.start()
        assertFalse(s.isRunning)
        assertTrue("gravity listener must not leak", reg.registered.isEmpty())
    }

    @Test
    fun start_clearsStaleSamples() {
        val tracker = GyroYawTracker()
        tracker.onSample(1000, 0.5)
        tracker.onSample(1100, 0.5)
        GyroHeadingSensor(FakeRegistrar(), tracker).start()
        assertNull(tracker.meanRate(1000, 1100))
    }
}
