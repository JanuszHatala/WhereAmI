package janush.tech.whereami

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.random.Random

class TripScribbleTest {

    private val lat0 = 49.8508
    private val lng0 = 19.2852
    private fun at(n: Double, e: Double) = TripScribble.LatLng(
        lat0 + n / 111_320.0,
        lng0 + e / (111_320.0 * cos(Math.toRadians(lat0)))
    )

    @Test
    fun `trip 239 style indoor nest is a scribble`() {
        val rnd = Random(42)
        val pts = (0 until 80).map {
            at(rnd.nextDouble() * 40.0 - 20.0, rnd.nextDouble() * 40.0 - 20.0)
        }
        // Path length ~ hundreds of metres of loops; net stays inside the nest
        var path = 0.0
        for (i in 1 until pts.size) path += TripScribble.distanceM(pts[i - 1], pts[i])
        assertTrue(path > 200.0)
        assertTrue(
            TripScribble.isIndoorScribble(
                isAuto = true,
                durationMs = 13 * 60_000L,
                pathDistanceM = 720.0,
                points = pts
            )
        )
    }

    @Test
    fun `real short drive is not a scribble`() {
        val pts = (0 until 40).map { at(it * 8.0, 0.0) } // 320 m north
        assertFalse(
            TripScribble.isIndoorScribble(
                isAuto = true,
                durationMs = 5 * 60_000L,
                pathDistanceM = 320.0,
                points = pts
            )
        )
    }

    @Test
    fun `manual trip is never discarded as scribble`() {
        val pts = (0 until 20).map { at(rnd(it), rnd(it + 1)) }
        assertFalse(
            TripScribble.isIndoorScribble(
                isAuto = false,
                durationMs = 20 * 60_000L,
                pathDistanceM = 800.0,
                points = pts
            )
        )
    }

    private fun rnd(i: Int) = (i % 7) * 3.0
}
