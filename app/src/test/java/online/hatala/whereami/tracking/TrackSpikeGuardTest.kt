package online.hatala.whereami.tracking

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
    fun `walking jump of 43m at implied 12 mps is a spike with walking gates`() {
        val (wMinJump, wMaxImplied) = TrackSpikeGuard.gatesForProfile("WALKING")
        assertEquals(15.0, wMinJump, 0.001)
        assertEquals(5.0, wMaxImplied, 0.001)

        val lat2 = homeLat + 43.2 / 111_320.0
        // 43.2m in 3.6s -> implied speed 12.0 m/s (43.2 km/h)
        val isWalkingSpike = TrackSpikeGuard.isSpikeStep(
            homeLat, homeLng, 1_000L,
            lat2, homeLng, 4_600L,
            speedMps = 1.2,
            isStationary = false,
            minJumpM = wMinJump,
            maxImpliedMps = wMaxImplied
        )
        assertTrue("Trip 262 point 83 jump must be rejected for walking", isWalkingSpike)
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

    @Test
    fun `trip 248 style lateral V on a straight corridor is a spike`() {
        // Along north, middle point jogs ~12 m east then returns — classic urban multipath Z
        // with a ≥75° turn at B (shallower jogs are kept as road weave).
        val (aLat, aLng) = at(0.0, 0.0)
        val (bLat, bLng) = at(8.0, 12.0)
        val (cLat, cLng) = at(20.0, 0.5)
        assertTrue(TrackSpikeGuard.isLateralVSpike(aLat, aLng, bLat, bLng, cLat, cLng))
        val xt = TrackSpikeGuard.crossTrackM(aLat, aLng, cLat, cLng, bLat, bLng)
        assertTrue("expected ~12 m cross-track, got $xt", xt in 9.0..14.0)
    }

    @Test
    fun `gentle road bend is not a lateral V spike`() {
        val (aLat, aLng) = at(0.0, 0.0)
        val (bLat, bLng) = at(20.0, 3.0)
        val (cLat, cLng) = at(40.0, 8.0) // gradual ~10° curve
        assertFalse(TrackSpikeGuard.isLateralVSpike(aLat, aLng, bLat, bLng, cLat, cLng))
    }

    @Test
    fun `lateral course outlier rejects sideways jump on stable heading`() {
        val recent = listOf(
            at(0.0, 0.0),
            at(15.0, 0.2),
            at(30.0, -0.1),
            at(45.0, 0.3),
            at(60.0, 0.0)
        )
        val (badLat, badLng) = at(72.0, 12.0) // ~12 m east of the corridor
        assertTrue(
            TrackSpikeGuard.isLateralCourseOutlier(recent, badLat, badLng, speedMps = 10.0)
        )
        val (okLat, okLng) = at(75.0, 0.4) // continues along corridor
        assertFalse(
            TrackSpikeGuard.isLateralCourseOutlier(recent, okLat, okLng, speedMps = 10.0)
        )
    }

    @Test
    fun `intersection turn is not a lateral course outlier`() {
        // Recent steps already turning (course not stable) — left turn into a side street.
        val recent = listOf(
            at(0.0, 0.0),
            at(15.0, 0.0),
            at(28.0, 4.0),
            at(35.0, 14.0),
            at(38.0, 28.0)
        )
        val (nextLat, nextLng) = at(39.0, 45.0)
        assertFalse(
            TrackSpikeGuard.isLateralCourseOutlier(recent, nextLat, nextLng, speedMps = 8.0)
        )
    }

    @Test
    fun `slow crawl over long interval is not falsely rejected as a spike`() {
        // 48 m over 24 s (implied 2.0 m/s) at 2.0 m/s is valid slow traffic / walking movement
        val (lat2, lng2) = at(48.0, 0.0)
        assertFalse(
            TrackSpikeGuard.isSpikeStep(
                homeLat, homeLng, 1_000L,
                lat2, lng2, 25_000L,
                speedMps = 2.0,
                isStationary = false
            )
        )
        // But 48 m in 4 s (implied 12.0 m/s) while reporting 1.5 m/s is a slow teleport spike
        assertTrue(
            TrackSpikeGuard.isSpikeStep(
                homeLat, homeLng, 1_000L,
                lat2, lng2, 5_000L,
                speedMps = 1.5,
                isStationary = false
            )
        )
    }

    @Test
    fun `road curve after straight segment commits all vertices without chord lockup`() {
        // Simulate TripManager.onSmoothedPoints commit pipeline on a straight corridor followed by a 60 deg bend
        val committed = ArrayList<SmoothedPoint>()
        var lastCommittedUtcMs = -1L
        var lastRejected: SmoothedPoint? = null

        fun commit(p: SmoothedPoint) {
            val last = committed.lastOrNull()
            if (last != null) {
                val prevUtc = lastCommittedUtcMs.takeIf { it > 0L } ?: p.tUtcMs
                val twoPoint = TrackSpikeGuard.isSpikeStep(
                    last.lat, last.lng, prevUtc,
                    p.lat, p.lng, p.tUtcMs,
                    p.speedMps, p.isStationary
                )
                val prev2 = if (committed.size >= 2) committed[committed.size - 2] else null
                val threePoint = prev2 != null && TrackSpikeGuard.isReversalSpike(
                    prev2.lat, prev2.lng,
                    last.lat, last.lng, prevUtc,
                    p.lat, p.lng, p.tUtcMs
                )
                if (twoPoint || threePoint) {
                    val prevRej = lastRejected
                    if (prevRej != null && !p.isStationary && p.tUtcMs > prevRej.tUtcMs &&
                        !TrackSpikeGuard.isSpikeStep(
                            prevRej.lat, prevRej.lng, prevRej.tUtcMs,
                            p.lat, p.lng, p.tUtcMs, p.speedMps, p.isStationary
                        )
                    ) {
                        lastRejected = null
                        committed.add(prevRej)
                        committed.add(p)
                        lastCommittedUtcMs = p.tUtcMs
                        return
                    }
                    lastRejected = p
                    return
                }
                lastRejected = null
                if (prev2 != null &&
                    TrackSpikeGuard.isLateralVSpike(
                        prev2.lat, prev2.lng,
                        last.lat, last.lng,
                        p.lat, p.lng
                    )
                ) {
                    committed.removeAt(committed.lastIndex)
                }
            }
            committed.add(p)
            lastCommittedUtcMs = p.tUtcMs
        }

        // 5 straight steps north (25 m every 2 s at 12.5 m/s), then a 60 deg right curve for 6 steps
        val coords = listOf(
            at(0.0, 0.0),
            at(25.0, 0.0),
            at(50.0, 0.0),
            at(75.0, 0.0),
            at(100.0, 0.0),
            at(118.0, 15.0),
            at(130.0, 36.0),
            at(142.0, 58.0),
            at(154.0, 80.0),
            at(166.0, 102.0),
            at(178.0, 124.0)
        )
        coords.forEachIndexed { idx, (la, ln) ->
            val t = (idx + 1) * 2_000L
            commit(
                SmoothedPoint(
                    tElapsedMs = t,
                    tUtcMs = t,
                    lat = la,
                    lng = ln,
                    altitudeM = 350.0,
                    speedMps = 12.5,
                    headingDeg = if (idx < 5) 0.0 else 60.0,
                    accuracyM = 5f,
                    isStationary = false
                )
            )
        }
        assertEquals("Every curve vertex must be committed without chord lockup", coords.size, committed.size)
    }
}
