package janush.tech.whereami.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackSpikeGuardTest {

    private val homeLat = 49.850781
    private val homeLng = 19.285020
    // ~386 m NW of home (trip 240 tip)
    private val spikeLat = 49.853744
    private val spikeLng = 19.282210

    @Test
    fun `trip 240 auto-stop tip is a spike`() {
        assertTrue(
            TrackSpikeGuard.isSpikeStep(
                homeLat, homeLng, 1_000L,
                spikeLat, spikeLng, 5_000L,
                speedMps = 0.0,
                isStationary = true
            )
        )
        val d = TrackSpikeGuard.distanceM(homeLat, homeLng, spikeLat, spikeLng)
        assertTrue("expected ~386 m, got $d", d in 350.0..420.0)
    }

    @Test
    fun `normal highway step at speed is not a spike`() {
        // ~45 m in 3 s at 15 m/s
        val lat2 = homeLat + 45.0 / 111_320.0
        assertFalse(
            TrackSpikeGuard.isSpikeStep(
                homeLat, homeLng, 1_000L,
                lat2, homeLng, 4_000L,
                speedMps = 15.0,
                isStationary = false
            )
        )
    }

    @Test
    fun `trimTrailing drops only the bad tip`() {
        data class P(val lat: Double, val lng: Double, val t: Long, val v: Double = 0.0)
        val pts = listOf(
            P(homeLat, homeLng, 1_000L, 1.0),
            P(homeLat + 0.0001, homeLng, 2_000L, 0.5),
            P(spikeLat, spikeLng, 6_000L, 0.0)
        )
        val trimmed = TrackSpikeGuard.trimTrailing(
            pts,
            latOf = { it.lat },
            lngOf = { it.lng },
            utcOf = { it.t },
            speedOf = { it.v },
            stationaryOf = { it.v < 0.5 }
        )
        assertEquals(2, trimmed.size)
        assertEquals(homeLat + 0.0001, trimmed.last().lat, 1e-9)
    }
}
