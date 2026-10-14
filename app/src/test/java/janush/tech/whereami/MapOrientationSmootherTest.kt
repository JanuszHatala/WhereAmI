package janush.tech.whereami

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MapOrientationSmootherTest {

    private fun stepOrientation(
        current: Float,
        target: Float,
        dtSec: Float,
        maxRateDegPerSec: Float = 90.0f
    ): Float {
        val maxDeltaDeg = maxRateDegPerSec * dtSec
        val diff = ((target - current + 540f) % 360f) - 180f
        val step = diff.coerceIn(-maxDeltaDeg, maxDeltaDeg)
        return (current + step + 360f) % 360f
    }

    @Test
    fun `rotates via shortest arc across 0-360 boundary clockwise`() {
        // From 350° to 10° is +20° clockwise
        val current = 350f
        val target = 10f
        val dtSec = 0.1f // 100ms
        val next = stepOrientation(current, target, dtSec, maxRateDegPerSec = 90f)

        // Max step at 90°/s for 0.1s is 9° -> 350 + 9 = 359°
        assertEquals(359f, next, 0.01f)
    }

    @Test
    fun `rotates via shortest arc across 0-360 boundary counterclockwise`() {
        // From 10° to 350° is -20° counterclockwise
        val current = 10f
        val target = 350f
        val dtSec = 0.1f
        val next = stepOrientation(current, target, dtSec, maxRateDegPerSec = 90f)

        // Max step is -9° -> 10 - 9 = 1°
        assertEquals(1f, next, 0.01f)
    }

    @Test
    fun `velocity clamp enforces max 90 deg per second`() {
        val current = 45f
        val target = 150f // 105° away
        val dtSec = 0.033f // ~33ms frame
        val next = stepOrientation(current, target, dtSec, maxRateDegPerSec = 90f)

        // Target is +105° away, so clockwise step clamped to +90° * dtSec
        val angularDelta = ((next - current + 540f) % 360f) - 180f
        val impliedRate = kotlin.math.abs(angularDelta) / dtSec
        assertTrue("Rotation rate $impliedRate should be <= 90 deg/s", impliedRate <= 90.1f)
        assertEquals(90f * dtSec, angularDelta, 0.001f)
    }

    @Test
    fun `converges smoothly without overshooting target`() {
        var current = 0f
        val target = 45f
        val dtSec = 0.033f

        // Over 1 second (30 frames), max rate 90 deg/s easily reaches 45 deg
        for (i in 0 until 35) {
            current = stepOrientation(current, target, dtSec, maxRateDegPerSec = 90f)
        }
        assertEquals(target, current, 0.01f)
    }
}
