package online.hatala.whereami

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MapOrientationSmootherTest {

    @Test
    fun `shortestAngularDelta calculates shortest arc across 0-360 boundary clockwise`() {
        // From 350° to 10° is +20° clockwise
        val delta = shortestAngularDelta(350f, 10f)
        assertEquals(20f, delta, 0.01f)
    }

    @Test
    fun `shortestAngularDelta calculates shortest arc across 0-360 boundary counterclockwise`() {
        // From 10° to 350° is -20° counterclockwise
        val delta = shortestAngularDelta(10f, 350f)
        assertEquals(-20f, delta, 0.01f)
    }

    @Test
    fun `shortestAngularDelta handles negative angles and large accumulated wraps`() {
        // -90° (which is 270°) to 90° should be +180° or -180°
        val delta1 = shortestAngularDelta(-90f, 270f)
        assertEquals(0f, delta1, 0.01f)

        // 359° to 1° is +2°
        val delta2 = shortestAngularDelta(359f, 1f)
        assertEquals(2f, delta2, 0.01f)

        // 1° to 359° is -2°
        val delta3 = shortestAngularDelta(1f, 359f)
        assertEquals(-2f, delta3, 0.01f)
    }

    @Test
    fun `shortestAngularDelta never exceeds 180 degrees (impossible to spin 360)`() {
        for (from in 0 until 360 step 15) {
            for (to in 0 until 360 step 15) {
                val delta = shortestAngularDelta(from.toFloat(), to.toFloat())
                assertTrue(
                    "Delta from $from to $to was $delta, which exceeds 180 deg",
                    abs(delta) <= 180.001f
                )
            }
        }
    }

    @Test
    fun `canRotateCourseUp when not recording strictly suppresses stationary and sub-locomotion fixes`() {
        // Physical stationary detector active -> suppressed
        val stationaryRes = canRotateCourseUp(
            hasHeading = true,
            isPhysicallyStationary = true,
            isAnchored = false,
            speedMs = 3.5f,
            isRecording = false,
            isInterpolatorStationary = false
        )
        org.junit.Assert.assertFalse("Must suppress rotation when physically stationary", stationaryRes)

        // Anchored fix -> suppressed
        val anchoredRes = canRotateCourseUp(
            hasHeading = true,
            isPhysicallyStationary = false,
            isAnchored = true,
            speedMs = 3.5f,
            isRecording = false,
            isInterpolatorStationary = false
        )
        org.junit.Assert.assertFalse("Must suppress rotation when fix is anchored", anchoredRes)

        // Speed below 2.78 m/s (10 km/h) -> suppressed when not recording
        val subLocomotionRes = canRotateCourseUp(
            hasHeading = true,
            isPhysicallyStationary = false,
            isAnchored = false,
            speedMs = 2.0f, // 7.2 km/h
            isRecording = false,
            isInterpolatorStationary = false
        )
        org.junit.Assert.assertFalse("Must suppress rotation below 10 km/h when not recording", subLocomotionRes)

        // No heading -> suppressed
        val noHeadingRes = canRotateCourseUp(
            hasHeading = false,
            isPhysicallyStationary = false,
            isAnchored = false,
            speedMs = 5.0f,
            isRecording = false,
            isInterpolatorStationary = false
        )
        org.junit.Assert.assertFalse("Must suppress rotation without valid heading", noHeadingRes)

        // Active vehicular locomotion (>= 10 km/h) and not stationary -> allowed
        val locomotionRes = canRotateCourseUp(
            hasHeading = true,
            isPhysicallyStationary = false,
            isAnchored = false,
            speedMs = 3.0f, // 10.8 km/h
            isRecording = false,
            isInterpolatorStationary = false
        )
        assertTrue("Must allow rotation when driving/cycling >= 10 km/h", locomotionRes)
    }

    @Test
    fun `canRotateCourseUp when recording allows responsive low-speed tracking`() {
        // Speed 0.5 m/s (1.8 km/h) >= 0.42 m/s while recording -> allowed
        val walkingRes = canRotateCourseUp(
            hasHeading = true,
            isPhysicallyStationary = false,
            isAnchored = false,
            speedMs = 0.5f,
            isRecording = true,
            isInterpolatorStationary = false
        )
        assertTrue("Must allow walking speed rotation when actively recording", walkingRes)

        // Low speed but interpolator active -> allowed when recording
        val interpolatorMovingRes = canRotateCourseUp(
            hasHeading = true,
            isPhysicallyStationary = false,
            isAnchored = false,
            speedMs = 0.2f,
            isRecording = true,
            isInterpolatorStationary = false
        )
        assertTrue("Must allow rotation when interpolator is moving during recording", interpolatorMovingRes)

        // But stationary detector still suppresses
        val stationaryRes = canRotateCourseUp(
            hasHeading = true,
            isPhysicallyStationary = true,
            isAnchored = false,
            speedMs = 0.5f,
            isRecording = true,
            isInterpolatorStationary = false
        )
        org.junit.Assert.assertFalse("Stationary detector must still suppress rotation during recording", stationaryRes)
    }
}

