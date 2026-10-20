package janush.tech.whereami.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class JoinBlendTest {
    private val f = LocalFrame(49.8, 19.0)

    /** Pencil walking north at 1 m/s, one point every 1.0 m / 1 s, pruned through [throughMs]. */
    private fun pencil(throughMs: Long): PencilTrail {
        val p = PencilTrail()
        for (i in 0..24) p.add(i * 1_000L, f.lat(i * 1.0), f.lng(0.0), 1.0)
        p.pruneThrough(throughMs)
        return p
    }

    private fun m(aLat: Double, aLng: Double, bLat: Double, bLng: Double) =
        hypot(f.east(bLng) - f.east(aLng), f.north(bLat) - f.north(aLat))

    @Test
    fun `nothing to blend before the first prune or with an empty pencil`() {
        val p = PencilTrail()
        for (i in 0..5) p.add(i * 500L, f.lat(i * 0.5), f.lng(0.0), 1.0)
        assertNull(p.blendedFrom(1_000, f.lat(1.0), f.lng(0.0), f.lat(3.0), f.lng(0.0)))
        val q = pencil(100_000)
        assertNull("empty after prune", q.blendedFrom(100_000, f.lat(1.0), f.lng(0.0), f.lat(3.0), f.lng(0.0)))
    }

    @Test
    fun `committed vertex offset is applied at the start and fades out before the tip`() {
        val p = pencil(5_000) // committed up to 5 s = 5.0 m north; pencil continues from 6.0 m
        // The smoothed vertex sits 1.2 m east of where the cursor was rendered at 5 s.
        val cLat = f.lat(5.0); val cLng = f.lng(1.2)
        val tipLat = f.lat(12.0); val tipLng = f.lng(0.0)
        val (la, ln) = p.blendedFrom(5_000, cLat, cLng, tipLat, tipLng)!!
        assertEquals(p.size, la.size)
        // First pencil point (1 m after the join): still carries most of the committed lateral offset.
        assertTrue("start offset ${f.east(ln[0])}", f.east(ln[0]) > 0.8)
        // Offset decreases monotonically and is gone 4 m along the pencil.
        var prev = Double.MAX_VALUE
        for (i in 0 until p.size) {
            val e = f.east(ln[i])
            assertTrue(e <= prev + 1e-9)
            prev = e
            val along = f.north(p.latAt(i)) - f.north(p.latAt(0))
            if (along >= 4.0) assertEquals(0.0, e, 1e-6)
        }
        // Points keep their order along the track (no folding back).
        for (i in 1 until p.size) assertTrue(la[i] >= la[i - 1])
        // The line leaves the committed vertex without a jump: joint gap about one pencil step.
        assertTrue(m(cLat, cLng, la[0], ln[0]) < 1.2)
    }

    @Test
    fun `a short pencil is fully faded at the tip so the line still ends under the cursor`() {
        val p = PencilTrail()
        // 1 m steps so every sample clears the walking floor.
        for (i in 0..4) p.add(i * 1_000L, f.lat(i * 1.0), f.lng(0.0), 1.0)
        p.pruneThrough(1_000) // keeps 2.0, 3.0, 4.0 m
        assertTrue(p.size >= 2)
        val tipLat = f.lat(5.0); val tipLng = f.lng(0.0)
        val (_, ln) = p.blendedFrom(1_000, f.lat(1.0), f.lng(2.0), tipLat, tipLng)!!
        assertNotNull(ln)
        // Fade length equals the short pencil, so the tip end has less lateral offset than the join.
        assertTrue(
            "first=${f.east(ln.first())} last=${f.east(ln.last())}",
            f.east(ln.last()) < f.east(ln.first()) - 0.2
        )
    }

    @Test
    fun `join blend removes the hook in the drawn line`() {
        // Committed track along north, but its last vertex is 2 m east of the pencil's own trace.
        val committed = (0..4).map { f.lat(it * 1.0) to f.lng(if (it == 4) 2.0 else 0.0) }
        val p = pencil(4_000)
        val tip = f.lat(12.0) to f.lng(0.0)
        fun jointTurnDeg(cLast: Pair<Double, Double>, p0: Pair<Double, Double>, p1: Pair<Double, Double>): Double {
            val ax = f.east(p0.second) - f.east(cLast.second); val ay = f.north(p0.first) - f.north(cLast.first)
            val bx = f.east(p1.second) - f.east(p0.second); val by = f.north(p1.first) - f.north(p0.first)
            val a = Math.toDegrees(kotlin.math.atan2(ax, ay)); val b = Math.toDegrees(kotlin.math.atan2(bx, by))
            return kotlin.math.abs(shortestDeltaDeg(a, b))
        }
        assertTrue(p.size >= 2)
        val rawTurn = jointTurnDeg(
            committed.last(),
            p.latAt(0) to p.lngAt(0),
            p.latAt(1) to p.lngAt(1)
        )
        val (la, ln) = p.blendedFrom(4_000, committed.last().first, committed.last().second, tip.first, tip.second)!!
        val blendedTurn = jointTurnDeg(
            committed.last(),
            la[0] to ln[0],
            la[1] to ln[1]
        )
        assertTrue("raw $rawTurn blended $blendedTurn", blendedTurn < rawTurn)
        // First blended pencil point sits closer to the committed easting than the raw pencil.
        assertTrue(kotlin.math.abs(f.east(ln[0]) - 2.0) < kotlin.math.abs(f.east(p.lngAt(0)) - 2.0))
    }
}
