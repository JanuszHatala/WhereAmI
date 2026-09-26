package janush.tech.whereami

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

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
        val dMeters = (later.lng - 19.28) * (111320.0 * kotlin.math.cos(Math.toRadians(49.85)))
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
    fun `new fix blends smoothly without backward jump`() {
        val t0 = 10000L
        interpolator.onNewFix(lat = 49.8500, lng = 19.2800, bearing = 90f, speedMs = 10f, timestamp = t0)
        
        // Extrapolate for 1.0 second (moved east ~10m)
        val t1 = t0 + 1000L
        val extAtT1 = interpolator.interpolate(t1)
        
        // Now new GPS fix arrives at t1, slightly behind the pure extrapolation (e.g. at 8m instead of 10m)
        val gpsLat = 49.8500
        val gpsLng = 19.2800 + (8.0 / (111320.0 * kotlin.math.cos(Math.toRadians(49.85))))
        interpolator.onNewFix(lat = gpsLat, lng = gpsLng, bearing = 90f, speedMs = 10f, timestamp = t1)
        
        // At t1, the blended position must start at extAtT1 (zero jump!)
        val blendStart = interpolator.interpolate(t1)
        assertEquals(extAtT1.lat, blendStart.lat, 0.000001)
        assertEquals(extAtT1.lng, blendStart.lng, 0.000001)
        
        // At t1 + 250ms (end of blend), it smoothly reaches the blended target
        val blendEnd = interpolator.interpolate(t1 + 250L)
        assertTrue(blendEnd.lng >= gpsLng)
    }
}
