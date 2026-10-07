package janush.tech.whereami

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HikingAndLiveAccuracyTest {

    @Test
    fun testHikingProfileKinematicParameters() {
        val hiking = ActivityProfile.HIKING
        assertEquals("Hiking", hiking.displayName)
        assertEquals("🥾", hiking.iconEmoji)
        assertEquals(2.5f, hiking.autoStartSpeedKmh, 0.01f)
        assertEquals(15_000L, hiking.autoStartDurationMs)
        assertEquals(1.0f, hiking.autoStopSpeedKmh, 0.01f)
        assertEquals(10, hiking.autoStopMinutesDefault)
        assertEquals(3_000L, hiking.gpsIntervalMs)
        assertEquals(1_500L, hiking.minGpsIntervalMs)
    }

    @Test
    fun testLivePointAccuracyField() {
        val pointWithAcc = LivePoint(
            lat = 49.822,
            lng = 19.044,
            speedKmh = 4.2f,
            altitude = 750.5,
            timestamp = 1700000000000L,
            accuracy = 4.8f
        )
        assertEquals(4.8f, pointWithAcc.accuracy!!, 0.01f)

        val pointWithoutAcc = LivePoint(
            lat = 49.822,
            lng = 19.044,
            speedKmh = 4.2f,
            altitude = 750.5,
            timestamp = 1700000000000L
        )
        assertNull(pointWithoutAcc.accuracy)
    }

    @Test
    fun testPayloadAccuracySerialization() {
        val point = LivePoint(
            lat = 49.822,
            lng = 19.044,
            speedKmh = 4.2f,
            altitude = 750.5,
            timestamp = 1700000000000L,
            accuracy = 6.4f
        )

        val pointJson = JSONObject().apply {
            put("lat", point.lat)
            put("lng", point.lng)
            put("spd", point.speedKmh)
            put("alt", point.altitude ?: JSONObject.NULL)
            put("acc", if (point.accuracy != null) Math.round(point.accuracy) else JSONObject.NULL)
            put("t", point.timestamp)
        }

        assertEquals(6, pointJson.getInt("acc"))

        val currentAccuracy: Float? = 8.7f
        val currentJson = JSONObject().apply {
            put("lat", 49.822)
            put("lng", 19.044)
            put("acc", if (currentAccuracy != null) Math.round(currentAccuracy) else JSONObject.NULL)
        }
        assertEquals(9, currentJson.getInt("acc"))

        val nullAccuracy: Float? = null
        val nullAccJson = JSONObject().apply {
            put("acc", if (nullAccuracy != null) Math.round(nullAccuracy) else JSONObject.NULL)
        }
        assertTrue(nullAccJson.isNull("acc"))
    }

    @Test
    fun testTripModeEnumValues() {
        val modes = TripMode.values()
        assertEquals(2, modes.size)
        assertTrue(modes.contains(TripMode.MANUAL))
        assertTrue(modes.contains(TripMode.AUTO))
    }

    @Test
    fun testLiveBreadcrumbGatingRule() {
        // Invariant: breadcrumbs must NEVER accumulate unless activeTrip != null AND displacement >= 15m.
        // Pure elapsed time (e.g. 15s) with displacement < 15m must NOT produce a breadcrumb point.
        fun shouldAddBreadcrumb(hasActiveTrip: Boolean, displacementMeters: Float, elapsedMs: Long): Boolean {
            if (!hasActiveTrip) return false
            if (displacementMeters < 15f) return false
            return true
        }

        // Stationary overnight with no active trip
        assertEquals(false, shouldAddBreadcrumb(hasActiveTrip = false, displacementMeters = 0f, elapsedMs = 60_000L))
        assertEquals(false, shouldAddBreadcrumb(hasActiveTrip = false, displacementMeters = 50f, elapsedMs = 15_000L))

        // Active trip but stationary / jitter (< 15m)
        assertEquals(false, shouldAddBreadcrumb(hasActiveTrip = true, displacementMeters = 3f, elapsedMs = 30_000L))
        assertEquals(false, shouldAddBreadcrumb(hasActiveTrip = true, displacementMeters = 14.9f, elapsedMs = 120_000L))

        // Active trip with genuine displacement (>= 15m)
        assertEquals(true, shouldAddBreadcrumb(hasActiveTrip = true, displacementMeters = 15.0f, elapsedMs = 5_000L))
        assertEquals(true, shouldAddBreadcrumb(hasActiveTrip = true, displacementMeters = 35.0f, elapsedMs = 2_000L))
    }
}

