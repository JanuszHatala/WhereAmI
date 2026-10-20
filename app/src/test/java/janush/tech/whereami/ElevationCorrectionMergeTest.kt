package janush.tech.whereami

import org.junit.Assert.assertEquals
import org.junit.Test
import org.osmdroid.util.GeoPoint

/** Trip 234: the stop-time DEM pass must never write GPS altitudes over stored DEM ones. */
class ElevationCorrectionMergeTest {

    private val stored = listOf(
        GeoPoint(49.85, 19.27, 300.0), // DEM
        GeoPoint(49.86, 19.27, 345.0), // GPS (ellipsoidal, ~40 m high)
        GeoPoint(49.87, 19.27, 0.0),   // missing
        GeoPoint(49.88, 19.27, 310.0)  // DEM
    )
    private val sources = listOf("DEM", "GPS", "GPS", "DEM")

    @Test
    fun fillMissing_fetchesOnlyNonDemOrMissingPoints() {
        assertEquals(listOf(1, 2), ElevationCorrectionHelper.indicesToFetch(stored, sources, OverrideMode.FILL_MISSING))
        assertEquals(listOf(0, 1, 2, 3), ElevationCorrectionHelper.indicesToFetch(stored, sources, OverrideMode.OVERRIDE_ALL))
    }

    @Test
    fun merge_keepsStoredDemAltitudesAndLabelsFetchedAsDem() {
        val (pts, src) = ElevationCorrectionHelper.mergeFetched(stored, sources, listOf(1, 2), listOf(305.0, null))
        assertEquals(listOf(300.0, 305.0, 0.0, 310.0), pts.map { it.altitude })
        assertEquals(listOf("DEM", "DEM", "GPS", "DEM"), src)
    }
}
