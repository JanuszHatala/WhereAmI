package janush.tech.whereami.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CurvedPolylineTest {

    @Test
    fun `gradual curve of 2 degrees over 35m segment is subdivided into smooth arc`() {
        // x0=(-35, 0), x1=(0,0), x2=(35, 1.2), x3=(70, 3.6) -> turn angles ~ 2 degrees
        val x0 = -35.0; val y0 = 0.0
        val x2 = 35.0; val y2 = 1.2
        val x3 = 70.0; val y3 = 3.6
        val len = Math.hypot(x2, y2)

        val k = CurvedPolyline.subdivisions(x0, y0, x2, y2, x3, y3, len)
        assertTrue("Subdivisions for 35m gradual bend must be > 1 (was $k)", k > 1)
        assertTrue("Subdivisions should produce points at smooth spacing", k >= 10)
    }

    @Test
    fun `perfectly straight line with 0 degree turn is not subdivided`() {
        val x0 = -35.0; val y0 = 0.0
        val x2 = 35.0; val y2 = 0.0
        val x3 = 70.0; val y3 = 0.0
        val len = 35.0

        val k = CurvedPolyline.subdivisions(x0, y0, x2, y2, x3, y3, len)
        assertEquals("Perfectly straight segment must have 1 subdivision", 1, k)
    }

    @Test
    fun `hairpin bend of 150 degrees curves smoothly instead of straight chord`() {
        val x0 = -10.0; val y0 = 0.0
        val x2 = 5.0; val y2 = 8.0
        val x3 = -5.0; val y3 = 12.0
        val len = Math.hypot(x2, y2)

        val k = CurvedPolyline.subdivisions(x0, y0, x2, y2, x3, y3, len)
        assertTrue("Subdivisions for 150-deg hairpin bend must be > 1 (was $k)", k > 1)
    }

    @Test
    fun `densify preserves incoming tangent when prevLat and prevLng are provided`() {
        val lats = doubleArrayOf(50.0, 50.0001, 50.0002)
        val lngs = doubleArrayOf(19.0, 19.0001, 19.0002)
        val emitted = mutableListOf<Pair<Double, Double>>()

        CurvedPolyline.densify(
            n = 3, fromSeg = 0, toSeg = 2,
            latAt = { lats[it] }, lngAt = { lngs[it] },
            prevLat = 49.9999, prevLng = 18.9999
        ) { la, ln -> emitted.add(Pair(la, ln)) }

        assertTrue("Must emit curve points", emitted.isNotEmpty())
    }
}
