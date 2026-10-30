package online.hatala.whereami

import online.hatala.whereami.tracking.TrackPointRow
import org.junit.Assert.*
import org.junit.Test
import org.osmdroid.util.GeoPoint

class GpxExporterTest {
    private val trip = TripRecord(
        id = 1, title = "A & B", startTime = 1_760_000_000_000L,
        points = listOf(GeoPoint(50.0, 19.0), GeoPoint(50.001, 19.001))
    )

    @Test
    fun rows_produceEleAndTimeAndEscapeTitle() {
        val rows = listOf(
            TrackPointRow(1_760_000_001_000L, 50.0, 19.0, 215.46, "GPS", 10f, 5f, 90f),
            TrackPointRow(1_760_000_002_000L, 50.001, 19.001, null, null, 10f, 5f, 90f)
        )
        val gpx = GpxExporter.buildGpx(trip, rows)
        assertTrue(gpx.contains("<name>A &amp; B</name>"))
        assertTrue(gpx.contains("<ele>215.5</ele>"))
        assertEquals(2, Regex("<trkpt ").findAll(gpx).count())
        assertTrue(gpx.contains("<time>2025-10-09T08:53:21Z</time>"))
        assertEquals("only the first point has an elevation", 1, Regex("<ele>").findAll(gpx).count())
    }

    @Test
    fun legacyRowsWithoutTime_omitTimeInsteadOfInventingIt() {
        val rows = trip.points.map { TrackPointRow(null, it.latitude, it.longitude, null, null, null, null, null) }
        val gpx = GpxExporter.buildGpx(trip, rows)
        val trkPart = gpx.substringAfter("<trkseg>")
        assertFalse(trkPart.contains("<time>"))
    }

    @Test
    fun withoutRows_fallsBackToBareCoordinates() {
        val gpx = GpxExporter.buildGpx(trip, null)
        assertEquals(2, Regex("<trkpt lat=\"[0-9.]+\" lon=\"[0-9.]+\"/>").findAll(gpx).count())
    }
}
