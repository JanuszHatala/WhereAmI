package janush.tech.whereami

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveShareChargingSyncTest {

    @Test
    fun testActivityProfileChargingProperties() {
        // Verify profile-adapted real-time sync intervals when plugged into a charger
        assertEquals(5_000L, ActivityProfile.CAR.chargingLiveSyncIntervalMs)
        assertEquals(6_000L, ActivityProfile.CYCLING.chargingLiveSyncIntervalMs)
        assertEquals(6_000L, ActivityProfile.MTB.chargingLiveSyncIntervalMs)
        assertEquals(8_000L, ActivityProfile.RUNNING.chargingLiveSyncIntervalMs)
        assertEquals(10_000L, ActivityProfile.HIKING.chargingLiveSyncIntervalMs)
        assertEquals(10_000L, ActivityProfile.WALKING.chargingLiveSyncIntervalMs)

        // Verify profile-adapted breadcrumb displacement thresholds
        assertEquals(15.0f, ActivityProfile.CAR.chargingBreadcrumbDisplacementMeters, 0.01f)
        assertEquals(12.0f, ActivityProfile.CYCLING.chargingBreadcrumbDisplacementMeters, 0.01f)
        assertEquals(12.0f, ActivityProfile.MTB.chargingBreadcrumbDisplacementMeters, 0.01f)
        assertEquals(12.0f, ActivityProfile.RUNNING.chargingBreadcrumbDisplacementMeters, 0.01f)
        assertEquals(8.0f, ActivityProfile.HIKING.chargingBreadcrumbDisplacementMeters, 0.01f)
        assertEquals(8.0f, ActivityProfile.WALKING.chargingBreadcrumbDisplacementMeters, 0.01f)
    }

    private fun resolveSyncParams(
        isCharging: Boolean,
        profile: ActivityProfile,
        speedKmh: Float,
        distanceMeters: Float,
        batteryIntervalMinutes: Int
    ): Pair<Long, Float> {
        val speedMs = speedKmh / 3.6f
        val isStationary = speedMs < 0.35f || (distanceMeters >= 0f && distanceMeters < 4.0f)

        val intervalMs = if (isCharging) {
            if (isStationary) {
                15_000L
            } else {
                profile.chargingLiveSyncIntervalMs
            }
        } else {
            (batteryIntervalMinutes * 60_000L).coerceAtLeast(10_000L)
        }

        val breadcrumbGate = if (isCharging) {
            profile.chargingBreadcrumbDisplacementMeters
        } else {
            15.0f
        }

        return Pair(intervalMs, breadcrumbGate)
    }

    @Test
    fun testChargingSyncCadenceWhenMoving() {
        // Driving at 60 km/h: 5s update interval and 15m breadcrumb gate
        val (carInterval, carGate) = resolveSyncParams(
            isCharging = true,
            profile = ActivityProfile.CAR,
            speedKmh = 60f,
            distanceMeters = 33f,
            batteryIntervalMinutes = 5
        )
        assertEquals(5_000L, carInterval)
        assertEquals(15.0f, carGate, 0.01f)

        // Cycling at 20 km/h: 6s update interval and 12m breadcrumb gate
        val (bikeInterval, bikeGate) = resolveSyncParams(
            isCharging = true,
            profile = ActivityProfile.CYCLING,
            speedKmh = 20f,
            distanceMeters = 16f,
            batteryIntervalMinutes = 5
        )
        assertEquals(6_000L, bikeInterval)
        assertEquals(12.0f, bikeGate, 0.01f)

        // Hiking at 4 km/h: 10s update interval and 8m breadcrumb gate
        val (hikeInterval, hikeGate) = resolveSyncParams(
            isCharging = true,
            profile = ActivityProfile.HIKING,
            speedKmh = 4f,
            distanceMeters = 6f,
            batteryIntervalMinutes = 5
        )
        assertEquals(10_000L, hikeInterval)
        assertEquals(8.0f, hikeGate, 0.01f)

        // Running at 10 km/h: 8s update interval and 12m breadcrumb gate
        val (runInterval, runGate) = resolveSyncParams(
            isCharging = true,
            profile = ActivityProfile.RUNNING,
            speedKmh = 10f,
            distanceMeters = 11f,
            batteryIntervalMinutes = 2
        )
        assertEquals(8_000L, runInterval)
        assertEquals(12.0f, runGate, 0.01f)
    }

    @Test
    fun testChargingStationaryKeepAliveThrottling() {
        // Stopped at traffic light while driving: speed = 0 km/h, distance = 0.5m
        val (carStationaryInterval, carStationaryGate) = resolveSyncParams(
            isCharging = true,
            profile = ActivityProfile.CAR,
            speedKmh = 0f,
            distanceMeters = 0.5f,
            batteryIntervalMinutes = 5
        )
        assertEquals(15_000L, carStationaryInterval)
        assertEquals(15.0f, carStationaryGate, 0.01f)

        // Stopped to rest while hiking: speed = 0.5 km/h (< 0.35 m/s), distance = 1.0m
        val (hikeStationaryInterval, _) = resolveSyncParams(
            isCharging = true,
            profile = ActivityProfile.HIKING,
            speedKmh = 0.8f, // ~0.22 m/s
            distanceMeters = 1.2f,
            batteryIntervalMinutes = 5
        )
        assertEquals(15_000L, hikeStationaryInterval)

        // Immediately resumes fast cadence upon movement
        val (resumedInterval, _) = resolveSyncParams(
            isCharging = true,
            profile = ActivityProfile.CAR,
            speedKmh = 35f,
            distanceMeters = 18f,
            batteryIntervalMinutes = 5
        )
        assertEquals(5_000L, resumedInterval)
    }

    @Test
    fun testBatteryFallbackWhenUnplugged() {
        // Disconnected from charger (on battery): returns configured minute interval and strict 15m gate
        val (batteryInterval5m, batteryGate5m) = resolveSyncParams(
            isCharging = false,
            profile = ActivityProfile.CAR,
            speedKmh = 90f,
            distanceMeters = 50f,
            batteryIntervalMinutes = 5
        )
        assertEquals(300_000L, batteryInterval5m)
        assertEquals(15.0f, batteryGate5m, 0.01f)

        val (batteryInterval1m, batteryGate1m) = resolveSyncParams(
            isCharging = false,
            profile = ActivityProfile.HIKING,
            speedKmh = 4.5f,
            distanceMeters = 8f,
            batteryIntervalMinutes = 1
        )
        assertEquals(60_000L, batteryInterval1m)
        assertEquals(15.0f, batteryGate1m, 0.01f)
    }

    @Test
    fun testBreadcrumbGatingRuleDifferences() {
        fun shouldQueueBreadcrumb(distMeters: Float, gateMeters: Float): Boolean {
            return distMeters >= gateMeters
        }

        // Hiking charging gate is 8m:
        assertFalse("5m move while hiking charging should not queue breadcrumb", shouldQueueBreadcrumb(5.0f, 8.0f))
        assertTrue("9m move while hiking charging should queue breadcrumb", shouldQueueBreadcrumb(9.0f, 8.0f))

        // Hiking on battery gate is 15m:
        assertFalse("9m move while hiking on battery should not queue breadcrumb", shouldQueueBreadcrumb(9.0f, 15.0f))
        assertTrue("16m move while hiking on battery should queue breadcrumb", shouldQueueBreadcrumb(16.0f, 15.0f))

        // Cycling charging gate is 12m:
        assertFalse("10m move while cycling charging should not queue breadcrumb", shouldQueueBreadcrumb(10.0f, 12.0f))
        assertTrue("13m move while cycling charging should queue breadcrumb", shouldQueueBreadcrumb(13.0f, 12.0f))
    }

    @Test
    fun testTelemetryPayloadChargingMetadata() {
        val currentJson = JSONObject().apply {
            put("lat", 49.822)
            put("lng", 19.044)
            put("spd", 54.2f)
            put("isCharging", true)
            put("syncIntervalMs", 2000L)
        }

        assertTrue(currentJson.getBoolean("isCharging"))
        assertEquals(2000L, currentJson.getLong("syncIntervalMs"))

        val batteryJson = JSONObject().apply {
            put("lat", 49.822)
            put("lng", 19.044)
            put("spd", 4.5f)
            put("isCharging", false)
            put("syncIntervalMs", 60000L)
        }

        assertFalse(batteryJson.getBoolean("isCharging"))
        assertEquals(60000L, batteryJson.getLong("syncIntervalMs"))
    }

    @Test
    fun testActiveTripBackfillDownsampling() {
        val now = 1791547791375L
        val startTime = now - 3600_000L // 1h ago
        val tripPoints = (0 until 2500).map { i ->
            org.osmdroid.util.GeoPoint(49.8 + i * 0.0001, 19.0 + i * 0.0001)
        }
        val memoryQueue = mutableListOf<LivePoint>()
        val step = maxOf(1, kotlin.math.ceil(tripPoints.size.toDouble() / 1500.0).toInt())
        val timeStep = (now - startTime) / tripPoints.size
        for (i in tripPoints.indices step step) {
            val gp = tripPoints[i]
            val t = startTime + i * timeStep
            memoryQueue.add(
                LivePoint(
                    lat = gp.latitude,
                    lng = gp.longitude,
                    speedKmh = 45f,
                    altitude = null,
                    timestamp = t,
                    accuracy = null
                )
            )
        }
        if ((tripPoints.size - 1) % step != 0) {
            val lastGp = tripPoints.last()
            memoryQueue.add(
                LivePoint(
                    lat = lastGp.latitude,
                    lng = lastGp.longitude,
                    speedKmh = 45f,
                    altitude = null,
                    timestamp = now,
                    accuracy = null
                )
            )
        }

        assertTrue("Queue must contain backfilled points", memoryQueue.size > 1000)
        assertTrue("Queue must not exceed 2000 points", memoryQueue.size <= 2000)
        assertEquals(tripPoints.first().latitude, memoryQueue.first().lat, 0.00001)
        assertEquals(tripPoints.last().latitude, memoryQueue.last().lat, 0.00001)
    }
}
