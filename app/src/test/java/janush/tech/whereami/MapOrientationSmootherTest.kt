package janush.tech.whereami

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
}

