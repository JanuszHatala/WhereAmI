package online.hatala.whereami

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
    fun `trip 239 style indoor CAR nest is a scribble`() {
        val rnd = Random(42)
        // Tight courtyard nest (~20 m span); recorded path length is the multipath sum (720 m).
        val pts = (0 until 100).map {
            at(rnd.nextDouble() * 20.0 - 10.0, rnd.nextDouble() * 20.0 - 10.0)
        }
        assertTrue(TripScribble.clusterRadiusM(pts) <= TripScribble.MAX_CLUSTER_M)
        assertTrue(
            TripScribble.isIndoorScribble(
                isAuto = true,
                durationMs = 13 * 60_000L,
                pathDistanceM = 720.0,
                points = pts,
                profile = ActivityProfile.CAR
            )
        )
    }

    @Test
    fun `trip 241 style short out-and-back WALK is kept`() {
        // Out ~95 m and back: net ~11 m, cluster ~40 m, path ~190 m (field trip 241)
        val out = (0 until 20).map { at(it * 5.0, 0.0) }
        val back = (0 until 20).map { at((19 - it) * 5.0, 2.0) }
        val pts = out + back
        val path = pts.zipWithNext { a, b -> TripScribble.distanceM(a, b) }.sum()
        assertTrue(path in 150.0..250.0)
        assertFalse(
            "real walk must not be discarded as indoor nest",
            TripScribble.isIndoorScribble(
                isAuto = true,
                durationMs = 3 * 60_000L,
                pathDistanceM = 191.0,
                points = pts,
                profile = ActivityProfile.WALKING
            )
        )
    }

    @Test
    fun `same geometry on CAR with low path-over-cluster is kept`() {
        val out = (0 until 20).map { at(it * 5.0, 0.0) }
        val back = (0 until 20).map { at((19 - it) * 5.0, 2.0) }
        val pts = out + back
        assertFalse(
            TripScribble.isIndoorScribble(
                isAuto = true,
                durationMs = 3 * 60_000L,
                pathDistanceM = 191.0,
                points = pts,
                profile = ActivityProfile.CAR
            )
        )
    }

    @Test
    fun `real short drive is not a scribble`() {
        val pts = (0 until 40).map { at(it * 8.0, 0.0) }
        assertFalse(
            TripScribble.isIndoorScribble(
                isAuto = true,
                durationMs = 5 * 60_000L,
                pathDistanceM = 320.0,
                points = pts,
                profile = ActivityProfile.CAR
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
                points = pts,
                profile = ActivityProfile.CAR
            )
        )
    }

    @Test
    fun `hiking and running never use scribble discard`() {
        val rnd = Random(1)
        val pts = (0 until 80).map { at(rnd.nextDouble() * 30 - 15, rnd.nextDouble() * 30 - 15) }
        for (profile in listOf(ActivityProfile.HIKING, ActivityProfile.RUNNING, ActivityProfile.WALKING)) {
            assertFalse(
                TripScribble.isIndoorScribble(
                    isAuto = true,
                    durationMs = 15 * 60_000L,
                    pathDistanceM = 800.0,
                    points = pts,
                    profile = profile
                )
            )
        }
    }

    private fun rnd(i: Int) = (i % 7) * 3.0
}
