package online.hatala.whereami

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.osmdroid.util.GeoPoint

class PointsAltitudeSerializationTest {

    @Test
    fun `points with altitude serialize alt property to json and deserialize back`() {
        val points = listOf(
            GeoPoint(50.0, 19.0, 312.5),
            GeoPoint(50.001, 19.001, 450.0),
            GeoPoint(50.002, 19.002) // no altitude set (0.0)
        )

        val jsonStr = TripDatabaseHelper.pointsToJson(points)
        assertTrue("Expected 312.5 in $jsonStr", jsonStr.contains("\"alt\":312.5") || jsonStr.contains("\"alt\":312.50"))
        assertTrue("Expected 450 in $jsonStr", jsonStr.contains("\"alt\":450") || jsonStr.contains("\"alt\":450.0"))

        val deserialized = TripDatabaseHelper.jsonToPoints(jsonStr)
        assertEquals(3, deserialized.size)
        assertEquals(312.5, deserialized[0].altitude, 0.001)
        assertEquals(450.0, deserialized[1].altitude, 0.001)
        assertEquals(0.0, deserialized[2].altitude, 0.001)
    }

    @Test
    fun `legacy json without alt field parses with default 0 altitude`() {
        val legacyJson = """[{"lat":50.0,"lng":19.0},{"lat":50.001,"lng":19.001}]"""
        val points = TripDatabaseHelper.jsonToPoints(legacyJson)
        assertEquals(2, points.size)
        assertEquals(50.0, points[0].latitude, 0.0001)
        assertEquals(19.0, points[0].longitude, 0.0001)
        assertEquals(0.0, points[0].altitude, 0.0001)
        assertEquals(50.001, points[1].latitude, 0.0001)
        assertEquals(19.001, points[1].longitude, 0.0001)
        assertEquals(0.0, points[1].altitude, 0.0001)
    }
}
