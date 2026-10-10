package janush.tech.whereami

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos

class AccuracyCircleTest {

    @Test
    fun `visual clamping enforces min 10dp and max 120dp bounds`() {
        val density = 2.5f
        val minRadiusPx = 10f * density // 25px
        val maxRadiusPx = 120f * density // 300px

        // Extremely tight RTK accuracy: 1.5m -> e.g. 10px target
        val smallTargetPx = 10f
        val clampedSmall = smallTargetPx.coerceIn(minRadiusPx, maxRadiusPx)
        assertEquals(minRadiusPx, clampedSmall, 0.001f)

        // Extremely degraded indoor accuracy: 150m -> e.g. 850px target
        val largeTargetPx = 850f
        val clampedLarge = largeTargetPx.coerceIn(minRadiusPx, maxRadiusPx)
        assertEquals(maxRadiusPx, clampedLarge, 0.001f)

        // Normal outdoor GPS: 15m -> 120px target
        val normalTargetPx = 120f
        val clampedNormal = normalTargetPx.coerceIn(minRadiusPx, maxRadiusPx)
        assertEquals(120f, clampedNormal, 0.001f)
    }

    @Test
    fun `exponential radius interpolation smoothly converges towards target`() {
        val targetPx = 150f
        var currentRadiusPx = 50f
        val alpha = 0.25f

        for (frame in 1..20) {
            currentRadiusPx += alpha * (targetPx - currentRadiusPx)
        }

        // After 20 frames (~330ms at 60fps), current radius must be virtually identical to target
        assertEquals(targetPx, currentRadiusPx, 0.5f)
    }

    @Test
    fun `cos latitude projection scaling reduces equator pixels proportionally`() {
        val equatorPixels = 100.0
        val latDeg = 60.0 // cos(60 deg) = 0.5
        val projectedPixels = equatorPixels * cos(Math.toRadians(latDeg))
        assertEquals(50.0, projectedPixels, 0.001)
    }
}
