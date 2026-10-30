package online.hatala.whereami.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class DisplayRoadSnapTest {
    private val f = LocalFrame(49.82, 19.05)

    @Test
    fun `pulls display toward road when nearest is within gate`() {
        val snap = DisplayRoadSnap()
        // Raw GPS 8 m east of the road centreline
        val rawLat = f.lat(0.0); val rawLng = f.lng(8.0)
        val roadLat = f.lat(0.0); val roadLng = f.lng(0.0)
        snap.observeNearest(rawLat, rawLng, roadLat, roadLng, distanceM = 8.0, dtSec = 1.0)
        snap.observeNearest(rawLat, rawLng, roadLat, roadLng, distanceM = 8.0, dtSec = 1.0)
        snap.observeNearest(rawLat, rawLng, roadLat, roadLng, distanceM = 8.0, dtSec = 1.0)
        val (la, ln) = snap.apply(rawLat, rawLng)
        val east = f.east(ln)
        assertTrue("display should move west toward the road, east=$east", east < 5.0)
        assertEquals(0.0, f.north(la), 0.5)
    }

    @Test
    fun `ignores snaps farther than the gate so parallel roads do not teleport`() {
        val snap = DisplayRoadSnap()
        val rawLat = f.lat(0.0); val rawLng = f.lng(0.0)
        snap.observeNearest(rawLat, rawLng, f.lat(0.0), f.lng(40.0), distanceM = 40.0, dtSec = 1.0)
        val (la, ln) = snap.apply(rawLat, rawLng)
        assertEquals(0.0, hypot(f.east(ln), f.north(la)), 0.2)
    }

    @Test
    fun `offset magnitude is capped`() {
        val snap = DisplayRoadSnap(maxOffsetM = 12.0)
        val rawLat = f.lat(0.0); val rawLng = f.lng(0.0)
        repeat(20) {
            snap.observeNearest(rawLat, rawLng, f.lat(0.0), f.lng(18.0), distanceM = 18.0, dtSec = 0.5)
        }
        assertTrue(snap.offsetM() <= 12.0 + 1e-6)
    }
}
