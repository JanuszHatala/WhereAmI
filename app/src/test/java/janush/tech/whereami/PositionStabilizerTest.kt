package janush.tech.whereami

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PositionStabilizerTest {

    private lateinit var stabilizer: PositionStabilizer

    @Before
    fun setUp() {
        stabilizer = PositionStabilizer()
    }

    @Test
    fun `stationary fixes within error gate remain strictly anchored`() {
        val baseLat = 49.850000
        val baseLng = 19.280000
        val t0 = 10000L

        // Initial fix establishing anchor
        val f0 = stabilizer.update(baseLat, baseLng, accuracy = 10f, speedMs = 0.1f, timestamp = t0, isPhysicalStationary = true)
        assertEquals(baseLat, f0.lat, 0.000001)
        assertEquals(baseLng, f0.lng, 0.000001)
        assertTrue(f0.isAnchored)

        // Simulate 10 jittery fixes within 5 meters (e.g. lat jitter ~ 0.00003 deg)
        val jitterDeltas = listOf(
            0.00002 to 0.00001,
            -0.00003 to 0.00002,
            0.00001 to -0.00002,
            -0.00002 to -0.00001,
            0.00003 to 0.00000
        )

        for ((i, delta) in jitterDeltas.withIndex()) {
            val candidateLat = baseLat + delta.first
            val candidateLng = baseLng + delta.second
            val res = stabilizer.update(
                candidateLat,
                candidateLng,
                accuracy = 12f,
                speedMs = 0.2f,
                timestamp = t0 + (i + 1) * 1000L,
                isPhysicalStationary = true
            )
            // Position must be strictly identical to base anchor
            assertEquals("Lat must remain anchored at fix $i", baseLat, res.lat, 0.000001)
            assertEquals("Lng must remain anchored at fix $i", baseLng, res.lng, 0.000001)
            assertTrue(res.isAnchored)
        }
    }

    @Test
    fun `locomotion speed above threshold breaks anchor instantly`() {
        val baseLat = 49.850000
        val baseLng = 19.280000
        val t0 = 10000L

        stabilizer.update(baseLat, baseLng, accuracy = 8f, speedMs = 0f, timestamp = t0, isPhysicalStationary = true)

        // Accelerate to 1.5 m/s (~5.4 km/h) walking
        val movedLat = baseLat + 0.000100
        val movedLng = baseLng + 0.000100
        val res = stabilizer.update(
            movedLat,
            movedLng,
            accuracy = 8f,
            speedMs = 1.5f,
            timestamp = t0 + 2000L,
            isPhysicalStationary = false
        )

        assertFalse("Anchor must be released on active locomotion", res.isAnchored)
        assertNotEquals(baseLat, res.lat, 0.00001)
    }

    @Test
    fun `sustained displacement exceeding gate triggers breakout`() {
        val baseLat = 49.850000
        val baseLng = 19.280000
        val t0 = 10000L

        stabilizer.update(baseLat, baseLng, accuracy = 5f, speedMs = 0.2f, timestamp = t0, isPhysicalStationary = true)

        // Displaced by 18 meters (0.00016 deg lat)
        val displacedLat = baseLat + 0.000160

        // Fix 1 outside gate: suppressed as transient spike
        val res1 = stabilizer.update(displacedLat, baseLng, accuracy = 5f, speedMs = 0.2f, timestamp = t0 + 1000L, isPhysicalStationary = true)
        assertTrue(res1.isAnchored)
        assertEquals(baseLat, res1.lat, 0.000001)

        // Fix 2 outside gate: still held
        val res2 = stabilizer.update(displacedLat, baseLng, accuracy = 5f, speedMs = 0.2f, timestamp = t0 + 2000L, isPhysicalStationary = true)
        assertTrue(res2.isAnchored)

        // Fix 3 outside gate: sustained displacement triggers breakout!
        val res3 = stabilizer.update(displacedLat, baseLng, accuracy = 5f, speedMs = 0.2f, timestamp = t0 + 3000L, isPhysicalStationary = true)
        assertFalse("3rd sustained displacement must trigger breakout", res3.isAnchored)
        assertNotEquals(baseLat, res3.lat, 0.00001)
    }

    @Test
    fun `kalman filter smooths coordinate step response monotonically`() {
        val t0 = 10000L
        stabilizer.update(49.850000, 19.280000, accuracy = 10f, speedMs = 10f, timestamp = t0, isPhysicalStationary = false)

        // Step change forward along latitude
        val targetLat = 49.850200
        val res1 = stabilizer.update(targetLat, 19.280000, accuracy = 10f, speedMs = 10f, timestamp = t0 + 1000L, isPhysicalStationary = false)
        assertTrue("res1 should move towards target", res1.lat > 49.850000 && res1.lat < targetLat)

        val res2 = stabilizer.update(targetLat, 19.280000, accuracy = 10f, speedMs = 10f, timestamp = t0 + 2000L, isPhysicalStationary = false)
        assertTrue("res2 should converge further towards target", res2.lat > res1.lat && res2.lat <= targetLat)
    }

    @Test
    fun `phantom GPS speed spikes during physical stillness do not break anchor`() {
        val baseLat = 49.850000
        val baseLng = 19.280000
        val t0 = 10000L

        // Establish anchor while physically stationary
        stabilizer.update(baseLat, baseLng, accuracy = 8f, speedMs = 0f, timestamp = t0, isPhysicalStationary = true)

        // GPS multipath delivers phantom speed of 1.2 m/s (4.3 km/h) with coordinates inside anchor gate
        val candidateLat = baseLat + 0.000030
        val candidateLng = baseLng + 0.000020
        val res = stabilizer.update(
            candidateLat,
            candidateLng,
            accuracy = 10f,
            speedMs = 1.2f, // Above 0.8 m/s threshold!
            timestamp = t0 + 1000L,
            isPhysicalStationary = true
        )

        assertTrue("Anchor must hold despite speed spike when physical stationary is true", res.isAnchored)
        assertEquals(baseLat, res.lat, 0.000001)
        assertEquals(baseLng, res.lng, 0.000001)
    }

    @Test
    fun `slow walking during trip recording bypasses stationary desk anchor`() {
        val baseLat = 49.850000
        val baseLng = 19.280000
        val t0 = 10000L

        // Initial fix with isTripRecording = true
        val f0 = stabilizer.update(
            baseLat,
            baseLng,
            accuracy = 8f,
            speedMs = 0.5f,
            timestamp = t0,
            isPhysicalStationary = true,
            isTripRecording = true
        )
        assertFalse("Must never anchor during active trip recording", f0.isAnchored)

        // Candidate fix moving 2 meters
        val candidateLat = baseLat + 0.000020
        val f1 = stabilizer.update(
            candidateLat,
            baseLng,
            accuracy = 8f,
            speedMs = 0.8f,
            timestamp = t0 + 1000L,
            isPhysicalStationary = true,
            isTripRecording = true
        )
        assertFalse("Subsequent walking fixes must remain unanchored", f1.isAnchored)
    }
}


