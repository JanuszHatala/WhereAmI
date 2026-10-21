package janush.tech.whereami.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

class TrackSpikeGuardTest {

    private val homeLat = 49.850781
    private val homeLng = 19.285020
    // ~386 m NW of home (trip 240 tip)
    private val spikeLat = 49.853744
    private val spikeLng = 19.282210

    private fun at(n: Double, e: Double) = Pair(
        homeLat + n / 111_320.0,
        homeLng + e / (111_320.0 * cos(Math.toRadians(homeLat)))
    )

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
        // ~45 m in 3 s at 15 m/s — just above MIN_JUMP but implied speed is fine
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
    fun `trip 244 cycling tip gap teleport is a spike`() {
        // Stored seq 250→252→253: 59.6 m snap toward home after 24 s at 7 m/s (expected ~168 m), Δcourse ≈ 172°.
        val aLat = 49.85098; val aLng = 19.28555
        val bLat = 49.85104; val bLng = 19.28570
        val cLat = 49.85082; val cLng = 19.28494
        val d = TrackSpikeGuard.distanceM(bLat, bLng, cLat, cLng)
        assertTrue("expected ~59 m tip, got $d", d in 50.0..70.0)
        assertTrue(
            TrackSpikeGuard.isSpikeStep(
                bLat, bLng, 1_000L,
                cLat, cLng, 25_000L,
                speedMps = 7.0,
                isStationary = false
            )
        )
        assertTrue(
            TrackSpikeGuard.isReversalSpike(
                aLat, aLng,
                bLat, bLng, 1_000L,
                cLat, cLng, 25_000L
            )
        )
    }

    @Test
    fun `trip 244 smaller reverse after gap is a reversal spike`() {
        // seq 256→257→258: ~27.5 m back toward home, Δcourse ≈ 155°, dt 11 s.
        val aLat = 49.85098; val aLng = 19.28500
        val bLat = 49.85104; val bLng = 19.28502
        val cLat = 49.85080; val cLng = 19.28511
        assertTrue(
            TrackSpikeGuard.isReversalSpike(
                aLat, aLng,
                bLat, bLng, 1_000L,
                cLat, cLng, 12_000L
            )
        )
    }

    @Test
    fun `smooth bike turn is not a reversal spike`() {
        val (aLat, aLng) = at(0.0, 0.0)
        val (bLat, bLng) = at(20.0, 0.0)
        val (cLat, cLng) = at(35.0, 15.0) // ~45° bend
        assertFalse(
            TrackSpikeGuard.isReversalSpike(
                aLat, aLng,
                bLat, bLng, 1_000L,
                cLat, cLng, 4_000L
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
