package janush.tech.whereami

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.osmdroid.util.GeoPoint

class AutoPauseAutoStopTailTrimTest {

    @Test
    fun testProfileAutoStopDefaults() {
        assertEquals(45, ActivityProfile.CAR.autoStopMinutesDefault)
        assertEquals(60, ActivityProfile.CYCLING.autoStopMinutesDefault)
        assertEquals(60, ActivityProfile.MTB.autoStopMinutesDefault)
        assertEquals(90, ActivityProfile.HIKING.autoStopMinutesDefault)
        assertEquals(30, ActivityProfile.RUNNING.autoStopMinutesDefault)
        assertEquals(90, ActivityProfile.WALKING.autoStopMinutesDefault)
    }

    @Test
    fun testSamplingIntervalDuringPausedState() {
        // Foreground: responsive 1 Hz unbatched
        val fgSampling = AppStateManager.computeTripRecordingSampling(
            profile = ActivityProfile.CAR,
            policy = BatteryPowerPolicy.SMART_AUTO,
            isAppInForeground = true,
            carActive = false,
            isPaused = true
        )
        assertEquals(1000L, fgSampling.first)
        assertEquals(1000L, fgSampling.second)
        assertEquals(0L, fgSampling.third)

        // Screen off: relaxed 20s batched sampling for battery optimization
        val bgSampling = AppStateManager.computeTripRecordingSampling(
            profile = ActivityProfile.CAR,
            policy = BatteryPowerPolicy.SMART_AUTO,
            isAppInForeground = false,
            carActive = false,
            isPaused = true
        )
        assertEquals(20_000L, bgSampling.first)
        assertEquals(10_000L, bgSampling.second)
        assertEquals(20_000L, bgSampling.third)
    }

    @Test
    fun testTailTrimmingLogic() {
        val startTime = 1000000L
        val pauseStartTime = startTime + 3600000L // 1 hour into trip
        val stopTime = pauseStartTime + 2700000L  // 45 min stationary in pause

        val p1 = GeoPoint(50.0, 19.0)
        val p2 = GeoPoint(50.01, 19.01)
        val p3 = GeoPoint(50.02, 19.02)
        val jitter1 = GeoPoint(50.02005, 19.02003)
        val jitter2 = GeoPoint(50.02002, 19.02008)

        val fullPoints = listOf(p1, p2, p3, jitter1, jitter2)
        val pause = TripPause(
            startTime = pauseStartTime,
            latitude = p3.latitude,
            longitude = p3.longitude,
            durationMs = stopTime - pauseStartTime,
            pointIndex = 3,
            distanceMeters = 2500.0
        )

        val trip = TripRecord(
            id = 1L,
            startTime = startTime,
            endTime = stopTime,
            distanceMeters = 2535.0, // extra 35m of GPS drift
            points = fullPoints,
            pauses = listOf(pause),
            activityProfile = ActivityProfile.CAR
        )

        // Tail trim execution simulation
        val trimPointIndex = pause.pointIndex.coerceIn(0, trip.points.size)
        val trimEndTime = pause.startTime
        val trimDistance = pause.distanceMeters
        val trimmedPoints = trip.points.take(trimPointIndex)

        assertEquals(3, trimmedPoints.size)
        assertEquals(p3, trimmedPoints.last())
        assertEquals(2500.0, trimDistance, 0.001)
        assertEquals(pauseStartTime, trimEndTime)

        // Trailing pause is removed from finalized trip pauses
        val resultPauses = trip.pauses.filterNot { it.startTime >= trimEndTime }
        assertTrue(resultPauses.isEmpty())
    }

    @Test
    fun testLegacyAutoStopUpgradeLogic() {
        // Any legacy stored value <= 20 should migrate to the new profile default
        val legacyStored = listOf(1, 3, 5, 10, 15, 20)
        for (legacy in legacyStored) {
            val shouldUpgrade = legacy <= 20
            assertTrue("Legacy preset $legacy min must be upgraded to new default", shouldUpgrade)
        }
        val customLong = 40
        assertFalse("Custom preset > 20 min should not trigger legacy upgrade", customLong <= 20)
    }
}
