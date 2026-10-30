package online.hatala.whereami.tracking

import online.hatala.whereami.TripDatabaseHelper
import org.junit.Assert.*
import org.junit.Test

class TrackPointRowTest {
    private fun row(i: Int) = TrackPointRow(1000L * i, 50.0 + i * 1e-4, 19.0, 200.0 + i, "GPS", 12f, 4f, 45f, TrackPointRow.FLAG_STATIONARY)

    @Test
    fun rebind_keepsMetadataWhereCoordinatesMatch_andDropsItWhereTheyDiffer() {
        val existing = (0..2).map { row(it) }
        val lats = doubleArrayOf(existing[0].lat, 51.0, existing[2].lat)
        val lngs = doubleArrayOf(19.0, 19.0, 19.0)
        val out = TrackPointRow.rebind(existing, lats, lngs, listOf(300.0, 310.0, 0.0), listOf("DEM", "DEM", "DEM"))
        assertEquals(1000L * 0, out[0].tUtcMs)
        assertEquals(12f, out[0].speedMps)
        assertEquals("elevation edit applied, source updated", "DEM", out[0].altSource)
        assertEquals(300.0, out[0].altitudeM!!, 1e-9)
        assertNull("moved point loses time", out[1].tUtcMs)
        assertEquals(0, out[1].flags)
        assertNull("zero altitude means unknown", out[2].altitudeM)
        assertNull(out[2].altSource)
        assertEquals(TrackPointRow.FLAG_STATIONARY, out[2].flags)
    }

    @Test
    fun rebind_withMoreCoordinatesThanRows_startsEmptyMetadata() {
        val out = TrackPointRow.rebind(listOf(row(0)), doubleArrayOf(50.0, 50.1), doubleArrayOf(19.0, 19.0), emptyList(), null)
        assertEquals(2, out.size)
        assertNull(out[1].tUtcMs)
    }

    @Test
    fun jsonToRows_legacyJson_mapsCoordinatesAltitudeAndSource() {
        val json = """[{"lat":50.1,"lng":19.2,"alt":210.5,"src":"DEM"},{"lat":50.2,"lng":19.3},{"lat":50.3,"lng":19.4,"alt":0.0}]"""
        val rows = TripDatabaseHelper.jsonToRows(json)
        assertEquals(3, rows.size)
        assertEquals(210.5, rows[0].altitudeM!!, 1e-9)
        assertEquals("DEM", rows[0].altSource)
        assertNull(rows[1].altitudeM)
        assertNull(rows[2].altitudeM)
        assertTrue(rows.all { it.tUtcMs == null && it.speedMps == null })
    }

    @Test
    fun jsonToRows_malformedJson_returnsEmptyInsteadOfThrowing() {
        assertTrue(TripDatabaseHelper.jsonToRows("not json").isEmpty())
    }

    @Test
    fun from_smoothedPoint_preservesRawCoordinates() {
        val sp = SmoothedPoint(
            tElapsedMs = 5000L,
            tUtcMs = 1700000000000L,
            lat = 49.8888,
            lng = 19.2222,
            altitudeM = 300.0,
            speedMps = 15.0,
            headingDeg = 180.0,
            accuracyM = 3.5f,
            isStationary = false,
            rawLat = 49.8890,
            rawLng = 19.2225
        )
        val row = TrackPointRow.from(sp, 300.0, "GPS")
        assertEquals(49.8888, row.lat, 1e-6)
        assertEquals(19.2222, row.lng, 1e-6)
        assertEquals(49.8890, row.rawLat!!, 1e-6)
        assertEquals(19.2225, row.rawLng!!, 1e-6)
    }

    @Test
    fun rebind_matchingCoordinates_preservesRawCoordinates() {
        val existing = listOf(
            TrackPointRow(
                tUtcMs = 1000L,
                lat = 50.0,
                lng = 19.0,
                altitudeM = 200.0,
                altSource = "GPS",
                speedMps = 10f,
                hAccM = 3f,
                bearingDeg = 45f,
                rawLat = 50.0001,
                rawLng = 19.0002
            )
        )
        val remapped = TrackPointRow.rebind(
            existing,
            doubleArrayOf(50.0),
            doubleArrayOf(19.0),
            listOf(205.0),
            listOf("DEM")
        )
        assertEquals(50.0001, remapped[0].rawLat!!, 1e-6)
        assertEquals(19.0002, remapped[0].rawLng!!, 1e-6)
    }
}
