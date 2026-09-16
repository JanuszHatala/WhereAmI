package janush.tech.whereami

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StationaryDetectorTest {

    @Test
    fun testEmptyCollectionVariance() {
        assertEquals(0f, StationaryDetector.computeVariance(emptyList()), 0.0001f)
    }

    @Test
    fun testIdenticalValuesHaveZeroVariance() {
        val motionlessValues = List(25) { 9.81f }
        val variance = StationaryDetector.computeVariance(motionlessValues)
        assertEquals(0f, variance, 0.00001f)
        assertTrue(variance < StationaryDetector.VARIANCE_THRESHOLD)
    }

    @Test
    fun testRestingOnDeskMicroNoiseIsStationary() {
        val deskNoise = listOf(
            9.80f, 9.81f, 9.82f, 9.81f, 9.80f,
            9.81f, 9.82f, 9.81f, 9.80f, 9.81f,
            9.81f, 9.80f, 9.82f, 9.81f, 9.81f,
            9.80f, 9.81f, 9.82f, 9.81f, 9.80f
        )
        val variance = StationaryDetector.computeVariance(deskNoise)
        assertTrue("Variance $variance should be well below ${StationaryDetector.VARIANCE_THRESHOLD}",
            variance < StationaryDetector.VARIANCE_THRESHOLD)
    }

    @Test
    fun testWalkingOrVehicleVibrationIsKineticMotion() {
        val kineticMagnitudes = listOf(
            9.8f, 11.5f, 8.2f, 12.3f, 7.5f,
            13.1f, 8.0f, 11.8f, 7.2f, 12.9f,
            9.5f, 10.8f, 8.6f, 12.0f, 7.9f
        )
        val variance = StationaryDetector.computeVariance(kineticMagnitudes)
        assertTrue("Variance $variance should be well above ${StationaryDetector.VARIANCE_THRESHOLD}",
            variance > StationaryDetector.VARIANCE_THRESHOLD)
    }
}
