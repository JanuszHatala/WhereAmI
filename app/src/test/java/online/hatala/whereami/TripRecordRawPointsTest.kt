package online.hatala.whereami

import online.hatala.whereami.tracking.TrackPointRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.osmdroid.util.GeoPoint

class TripRecordRawPointsTest {

    @Test
    fun rowsToRawGeoPoints_extractsRawCoordinates_andIgnoresNulls() {
        val rows = listOf(
            TrackPointRow(
                tUtcMs = 1000L,
                lat = 49.8880,
                lng = 19.2220,
                altitudeM = 310.0,
                altSource = "GPS",
                speedMps = 10f,
                hAccM = 4f,
                bearingDeg = 90f,
                rawLat = 49.8885,
                rawLng = 19.2226
            ),
            TrackPointRow(
                tUtcMs = 2000L,
                lat = 49.8890,
                lng = 19.2230,
                altitudeM = null,
                altSource = null,
                speedMps = 12f,
                hAccM = 5f,
                bearingDeg = 90f,
                rawLat = null,
                rawLng = null
            ),
            TrackPointRow(
                tUtcMs = 3000L,
                lat = 49.8900,
                lng = 19.2240,
                altitudeM = 320.0,
                altSource = "DEM",
                speedMps = 14f,
                hAccM = 3f,
                bearingDeg = 95f,
                rawLat = 49.8902,
                rawLng = 19.2243
            )
        )

        val rawGeoPoints = TripDatabaseHelper.rowsToRawGeoPoints(rows)
        assertEquals(2, rawGeoPoints.size)
        assertEquals(49.8885, rawGeoPoints[0].latitude, 1e-6)
        assertEquals(19.2226, rawGeoPoints[0].longitude, 1e-6)
        assertEquals(310.0, rawGeoPoints[0].altitude, 1e-6)

        assertEquals(49.8902, rawGeoPoints[1].latitude, 1e-6)
        assertEquals(19.2243, rawGeoPoints[1].longitude, 1e-6)
        assertEquals(320.0, rawGeoPoints[1].altitude, 1e-6)

        val smoothedGeoPoints = TripDatabaseHelper.rowsToGeoPoints(rows)
        assertEquals(3, smoothedGeoPoints.size)
        assertEquals(49.8880, smoothedGeoPoints[0].latitude, 1e-6)
        assertEquals(19.2220, smoothedGeoPoints[0].longitude, 1e-6)
    }

    @Test
    fun tripRecord_defaultRawPoints_isEmpty() {
        val trip = TripRecord(
            id = 1L,
            startTime = 1000L,
            points = listOf(GeoPoint(49.88, 19.22))
        )
        assertTrue(trip.rawPoints.isEmpty())
        assertEquals(1, trip.points.size)
    }

    @Test
    fun tripRecord_withExplicitRawPoints_preservesBothLists() {
        val smoothed = listOf(GeoPoint(49.880, 19.220), GeoPoint(49.885, 19.225))
        val raw = listOf(GeoPoint(49.8801, 19.2202), GeoPoint(49.8820, 19.2220), GeoPoint(49.8853, 19.2254))

        val trip = TripRecord(
            id = 42L,
            startTime = 5000L,
            points = smoothed,
            rawPoints = raw
        )

        assertEquals(2, trip.points.size)
        assertEquals(3, trip.rawPoints.size)
        assertEquals(49.880, trip.points[0].latitude, 1e-6)
        assertEquals(49.8801, trip.rawPoints[0].latitude, 1e-6)
    }
}
