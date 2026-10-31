package online.hatala.whereami.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CurvedPolylineTest {

    @Test
    fun `corner rounding rounds 90-degree bend smoothly`() {
        val lats = doubleArrayOf(50.0, 50.0002, 50.0002)
        val lngs = doubleArrayOf(19.0, 19.0, 19.0003)
        val emitted = mutableListOf<Pair<Double, Double>>()

        CurvedPolyline.densify(
            n = 3, fromSeg = 0, toSeg = 2,
            latAt = { lats[it] }, lngAt = { lngs[it] }
        ) { la, ln -> emitted.add(Pair(la, ln)) }

        // Must emit points cutting the corner smoothly
        assertTrue("Emitted points must include fillet points (was ${emitted.size})", emitted.size > 2)

        // Convex hull invariant: no point should exceed max lat or max lng
        val minLat = lats.minOrNull()!!
        val maxLat = lats.maxOrNull()!!
        val minLng = lngs.minOrNull()!!
        val maxLng = lngs.maxOrNull()!!

        for ((la, ln) in emitted) {
            assertTrue("Lat $la must be within [$minLat, $maxLat]", la in (minLat - 1e-7)..(maxLat + 1e-7))
            assertTrue("Lng $ln must be within [$minLng, $maxLng]", ln in (minLng - 1e-7)..(maxLng + 1e-7))
        }
    }

    @Test
    fun `perfectly straight line emits only segment end vertex without bulges`() {
        val lats = doubleArrayOf(50.0, 50.0002, 50.0004)
        val lngs = doubleArrayOf(19.0, 19.0002, 19.0004)
        val emitted = mutableListOf<Pair<Double, Double>>()

        CurvedPolyline.densify(
            n = 3, fromSeg = 0, toSeg = 2,
            latAt = { lats[it] }, lngAt = { lngs[it] }
        ) { la, ln -> emitted.add(Pair(la, ln)) }

        assertEquals("Straight line segments emit 1 point per segment", 2, emitted.size)
        assertEquals(50.0002, emitted[0].first, 1e-6)
        assertEquals(19.0002, emitted[0].second, 1e-6)
        assertEquals(50.0004, emitted[1].first, 1e-6)
        assertEquals(19.0004, emitted[1].second, 1e-6)
    }

    @Test
    fun `turnDeg correctly classifies straight vs turn`() {
        val straightDeg = CurvedPolyline.turnDeg(0.0, 10.0, 0.0, 10.0)
        assertEquals(0.0, straightDeg, 1e-3)

        val rightDeg = CurvedPolyline.turnDeg(0.0, 10.0, 10.0, 0.0)
        assertEquals(90.0, rightDeg, 1e-3)

        val uTurnDeg = CurvedPolyline.turnDeg(0.0, 10.0, 0.0, -10.0)
        assertEquals(180.0, uTurnDeg, 1e-3)
    }
}
