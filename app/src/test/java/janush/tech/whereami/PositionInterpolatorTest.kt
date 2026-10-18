package janush.tech.whereami

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.math.cos

class PositionInterpolatorTest {

    private lateinit var interpolator: PositionInterpolator

    @Before
    fun setUp() {
        interpolator = PositionInterpolator()
    }

    @Test
    fun `initialization returns empty point`() {
        val point = interpolator.interpolate(1000L)
        assertEquals(0.0, point.lat, 0.0001)
        assertEquals(0.0, point.lng, 0.0001)
        assertNull(point.bearing)
        assertEquals(0f, point.speedMs, 0.0001f)
    }

    @Test
    fun `first fix returns exact coordinates`() {
        val now = 10000L
        interpolator.onNewFix(lat = 49.85, lng = 19.28, bearing = 90f, speedMs = 15f, timestamp = now)
        val point = interpolator.interpolate(now)
        assertEquals(49.85, point.lat, 0.00001)
        assertEquals(19.28, point.lng, 0.00001)
        assertEquals(90f, point.bearing)
        assertEquals(15f, point.speedMs, 0.001f)
    }

    @Test
    fun `stationary fix does not drift over time`() {
        val now = 10000L
        interpolator.onNewFix(lat = 49.85, lng = 19.28, bearing = 90f, speedMs = 0f, timestamp = now)
        
        // 2 seconds later
        val later = interpolator.interpolate(now + 2000L)
        assertEquals(49.85, later.lat, 0.000001)
        assertEquals(19.28, later.lng, 0.000001)
        assertTrue(interpolator.isStationary())
    }

    @Test
    fun `moving east increases longitude forward`() {
        val now = 10000L
        // Moving East (bearing = 90°) at 20 m/s (72 km/h)
        interpolator.onNewFix(lat = 49.85, lng = 19.28, bearing = 90f, speedMs = 20f, timestamp = now)
        
        // 1 second later
        val later = interpolator.interpolate(now + 1000L)
        // Lat should remain virtually identical (moving purely east)
        assertEquals(49.85, later.lat, 0.0001)
        // Lng should increase (moved east by 20m)
        assertTrue("Longitude should increase when heading East", later.lng > 19.28)
        val dMeters = (later.lng - 19.28) * (111320.0 * cos(Math.toRadians(49.85)))
        assertEquals(20.0, dMeters, 2.0)
    }

    @Test
    fun `moving north increases latitude forward`() {
        val now = 10000L
        // Moving North (bearing = 0°) at 15 m/s
        interpolator.onNewFix(lat = 49.85, lng = 19.28, bearing = 0f, speedMs = 15f, timestamp = now)
        
        // 1 second later
        val later = interpolator.interpolate(now + 1000L)
        assertTrue("Latitude should increase when heading North", later.lat > 49.85)
        assertEquals(19.28, later.lng, 0.0001)
        val dMeters = (later.lat - 49.85) * 111320.0
        assertEquals(15.0, dMeters, 1.5)
    }

    @Test
    fun `extrapolation is capped at 1200ms`() {
        val now = 10000L
        interpolator.onNewFix(lat = 49.85, lng = 19.28, bearing = 0f, speedMs = 10f, timestamp = now)
        
        val at1200ms = interpolator.interpolate(now + 1200L)
        val at3000ms = interpolator.interpolate(now + 3000L)
        
        // Position at 3000ms should be equal to position at 1200ms (no runaway extrapolation)
        assertEquals(at1200ms.lat, at3000ms.lat, 0.000001)
        assertEquals(at1200ms.lng, at3000ms.lng, 0.000001)
    }

    @Test
    fun `buffered hermite spline smooth playback between two fixes`() {
        val t0 = 10000L
        val t1 = 11000L
        // Fix 0 at 49.8500, 19.2800 heading East at 10 m/s
        interpolator.onNewFix(lat = 49.8500, lng = 19.2800, bearing = 90f, speedMs = 10f, timestamp = t0)
        // Fix 1 at 49.8500, 19.2800 + 10m heading East at 10 m/s
        val dLng10m = 10.0 / (111320.0 * cos(Math.toRadians(49.8500)))
        interpolator.onNewFix(lat = 49.8500, lng = 19.2800 + dLng10m, bearing = 90f, speedMs = 10f, timestamp = t1)

        val delay = interpolator.bufferDelayMs

        // At clock = t0 + delay, playback is at Fix 0
        val pStart = interpolator.interpolate(t0 + delay)
        assertEquals(49.8500, pStart.lat, 0.00001)
        assertEquals(19.2800, pStart.lng, 0.00001)

        // At clock = (t0 + t1)/2 + delay, playback is at midpoint ~5m
        val pMid = interpolator.interpolate((t0 + t1) / 2 + delay)
        val dMidMeters = (pMid.lng - 19.2800) * 111320.0 * cos(Math.toRadians(49.8500))
        assertEquals(5.0, dMidMeters, 0.5)

        // At clock = t1 + delay, playback reaches Fix 1
        val pEnd = interpolator.interpolate(t1 + delay)
        val dEndMeters = (pEnd.lng - 19.2800) * 111320.0 * cos(Math.toRadians(49.8500))
        assertEquals(10.0, dEndMeters, 0.5)
    }

    @Test
    fun `tangent bearing aligns with curve on 90 degree turn`() {
        val t0 = 10000L
        val t1 = 11000L
        // Fix 0 heading East (90°) at 10 m/s
        interpolator.onNewFix(lat = 49.8500, lng = 19.2800, bearing = 90f, speedMs = 10f, timestamp = t0)
        // Fix 1 turning North (0°) after moving East and curving North
        val dLng = 8.0 / (111320.0 * cos(Math.toRadians(49.8500)))
        val dLat = 8.0 / 111320.0
        interpolator.onNewFix(lat = 49.8500 + dLat, lng = 19.2800 + dLng, bearing = 0f, speedMs = 10f, timestamp = t1)

        val delay = interpolator.bufferDelayMs

        // Midpoint of the turn (45° curve)
        val pMid = interpolator.interpolate((t0 + t1) / 2 + delay)
        assertNotNull(pMid.bearing)
        // Bearing should smoothly bend between 90° (East) and 0° (North), roughly 35°–55°
        val b = pMid.bearing!!
        assertTrue("Midpoint bearing $b should be in curve transition (20°..70°)", b in 20f..70f)
    }

    @Test
    fun `continuous motion across fix boundary with zero jump`() {
        val t0 = 10000L
        val t1 = 11000L
        val t2 = 12000L
        val dLng10m = 10.0 / (111320.0 * cos(Math.toRadians(49.8500)))

        interpolator.onNewFix(lat = 49.8500, lng = 19.2800, bearing = 90f, speedMs = 10f, timestamp = t0)
        interpolator.onNewFix(lat = 49.8500, lng = 19.2800 + dLng10m, bearing = 90f, speedMs = 10f, timestamp = t1)
        interpolator.onNewFix(lat = 49.8500, lng = 19.2800 + 2 * dLng10m, bearing = 90f, speedMs = 10f, timestamp = t2)

        val delay = interpolator.bufferDelayMs
        // Point right before boundary
        val beforeBoundary = interpolator.interpolate(t1 + delay - 1L)
        // Point right after boundary
        val afterBoundary = interpolator.interpolate(t1 + delay + 1L)

        // Displacement over 2ms at 10 m/s should be ~0.02m (2cm), never a meter-scale jump
        val dLngMeters = (afterBoundary.lng - beforeBoundary.lng) * 111320.0 * cos(Math.toRadians(49.8500))
        assertEquals(0.02, dLngMeters, 0.05)
    }

    @Test
    fun `noisy doppler bearing at walking speed never causes lateral overshoot or heading loop`() {
        val t0 = 10000L
        val t1 = 11000L
        // Moving North along latitude at 1.0 m/s (~3.6 km/h) walking
        val dLat1m = 1.0 / 111320.0
        // Fix 0 has wild noisy bearing (90° East) despite moving North
        interpolator.onNewFix(lat = 49.8500, lng = 19.2800, bearing = 90f, speedMs = 1.0f, timestamp = t0)
        // Fix 1 has noisy bearing (270° West) despite moving North
        interpolator.onNewFix(lat = 49.8500 + dLat1m, lng = 19.2800, bearing = 270f, speedMs = 1.0f, timestamp = t1)

        val delay = interpolator.bufferDelayMs

        // Sample at 10 steps along playback interval
        for (i in 0..10) {
            val t = t0 + (i * 100L) + delay
            val p = interpolator.interpolate(t)
            // Longitude should stay tightly bound to 19.2800 without bulging East or West
            val latDevMeters = (p.lng - 19.2800) * 111320.0 * cos(Math.toRadians(49.8500))
            assertTrue(
                "Lateral deviation at step $i was $latDevMeters m, should not exceed 0.2m",
                kotlin.math.abs(latDevMeters) <= 0.20
            )
            // Latitude must advance monotonically North
            assertTrue(
                "Latitude should remain in [49.8500, 49.8500 + dLat1m]",
                p.lat in 49.84999..49.85002
            )
        }
    }

    @Test
    fun `raw fix bearing does not overwrite active smoothed heading during playback`() {
        val t0 = 10000L
        val t1 = 11000L
        val dLat1m = 1.0 / 111320.0
        // Moving North at 1.0 m/s with established heading 0° North
        interpolator.onNewFix(lat = 49.8500, lng = 19.2800, bearing = 0f, speedMs = 1.0f, timestamp = t0)
        
        // Next fix moves North, but raw sensor reports noisy 120° bearing
        interpolator.onNewFix(lat = 49.8500 + dLat1m, lng = 19.2800, bearing = 120f, speedMs = 1.0f, timestamp = t1)

        val delay = interpolator.bufferDelayMs
        val p = interpolator.interpolate(t0 + delay + 500L) // mid-playback

        assertNotNull(p.bearing)
        // Bearing must align with physical path North (near 0°), NOT raw noisy 120°
        val diff = (p.bearing!! + 360f) % 360f
        val isNorth = diff <= 15f || diff >= 345f
        assertTrue("Bearing should track physical path North, got $diff°", isNorth)
    }

    @Test
    fun `walking continuous heading smoothly rounds 90 degree corner without twitching`() {
        var t = 10000L
        val dLat1m = 1.0 / 111320.0
        val dLng1m = 1.0 / (111320.0 * cos(Math.toRadians(49.8500)))

        // Step 0-3: Moving North
        for (i in 0..3) {
            interpolator.onNewFix(lat = 49.8500 + i * dLat1m, lng = 19.2800, bearing = 0f, speedMs = 1.0f, timestamp = t)
            t += 1000L
        }

        // Step 4-7: Turning 90° and moving East
        for (i in 1..4) {
            interpolator.onNewFix(lat = 49.8500 + 3 * dLat1m, lng = 19.2800 + i * dLng1m, bearing = null, speedMs = 1.0f, timestamp = t)
            t += 1000L
        }

        val delay = interpolator.bufferDelayMs
        // Playback after corner turn
        val p = interpolator.interpolate(t - 1000L + delay)

        assertNotNull(p.bearing)
        // Heading should smoothly adapt toward East (90° ± 15°)
        val b = p.bearing!!
        assertTrue("Bearing should track East after corner (75°..105°), got $b°", b in 75f..105f)
    }
}

