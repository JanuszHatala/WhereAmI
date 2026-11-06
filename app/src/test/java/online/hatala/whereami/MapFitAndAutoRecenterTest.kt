package online.hatala.whereami

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.osmdroid.util.GeoPoint

class MapFitAndAutoRecenterTest {

    @Test
    fun `test formatGuestControlBadge formatting`() {
        assertNull(formatGuestControlBadge(0, 0))
        assertEquals("3", formatGuestControlBadge(3, 0))
        assertEquals("2/5", formatGuestControlBadge(2, 3))
        assertEquals("0/2", formatGuestControlBadge(0, 2))
    }

    @Test
    fun `test extractRecentTrackSection handles empty and single points`() {
        val empty = extractRecentTrackSection(emptyList(), null, 1000.0)
        assertTrue(empty.isEmpty())

        val p1 = GeoPoint(50.0, 20.0)
        val single = extractRecentTrackSection(listOf(p1), null, 1000.0)
        assertEquals(1, single.size)
        assertEquals(p1, single[0])
    }

    @Test
    fun `test extractRecentTrackSection slices exact distance from track tail`() {
        // Line along meridian: 1 deg lat ~ 111,132 m.
        // 0.001 deg lat ~ 111.132 m.
        // 10 points separated by 0.001 deg (~111.13m each, ~1000m total).
        val pts = (0..9).map { i ->
            GeoPoint(50.0 + (i * 0.001), 20.0)
        }
        val curPos = GeoPoint(50.010, 20.0) // 10th segment: total 10 segments ~ 1111.32m

        // Target: 500m (should encompass ~4.5 segments from the end)
        val recent500m = extractRecentTrackSection(pts, curPos, 500.0)
        assertTrue(recent500m.size >= 2)
        assertEquals(curPos.latitude, recent500m.last().latitude, 0.00001)

        var totalDist = 0.0
        for (i in 0 until recent500m.size - 1) {
            val dist = SearchHelper.computeDistanceMeters(
                recent500m[i].latitude, recent500m[i].longitude,
                recent500m[i + 1].latitude, recent500m[i + 1].longitude
            )
            totalDist += dist
        }
        assertEquals(500.0, totalDist, 2.0)
    }

    @Test
    fun `test extractRecentTrackSection returns full track when target distance exceeds track length`() {
        val pts = listOf(
            GeoPoint(50.0, 20.0),
            GeoPoint(50.001, 20.0)
        )
        val recent = extractRecentTrackSection(pts, null, 10_000.0)
        assertEquals(2, recent.size)
        assertEquals(pts[0], recent[0])
        assertEquals(pts[1], recent[1])
    }

    @Test
    fun `test isPointInUnobstructedViewport detects point inside and outside`() {
        val center = GeoPoint(50.0, 20.0)
        val zoom = 16.0
        val mapWidth = 1000
        val mapHeight = 2000
        val leftInset = 50
        val rightInset = 150
        val topInset = 300
        val bottomInset = 400

        // Center point is always inside unobstructed viewport center area
        assertTrue(
            isPointInUnobstructedViewport(
                point = center,
                mapCenter = center,
                zoomLevel = zoom,
                mapOrientationDeg = 0f,
                tilesScaleFactor = 1.0f,
                mapWidth = mapWidth,
                mapHeight = mapHeight,
                leftInset = leftInset,
                rightInset = rightInset,
                topInset = topInset,
                bottomInset = bottomInset
            )
        )

        // Point far to the north (several km away at zoom 16 is offscreen)
        val farNorth = GeoPoint(50.05, 20.0)
        assertFalse(
            isPointInUnobstructedViewport(
                point = farNorth,
                mapCenter = center,
                zoomLevel = zoom,
                mapOrientationDeg = 0f,
                tilesScaleFactor = 1.0f,
                mapWidth = mapWidth,
                mapHeight = mapHeight,
                leftInset = leftInset,
                rightInset = rightInset,
                topInset = topInset,
                bottomInset = bottomInset
            )
        )
    }

    @Test
    fun `test shouldTriggerAutoRecenter respects all gating conditions`() {
        val now = 100_000L
        val timeout = 60_000L
        val lastInteraction = now - 65_000L // 65s ago (> 60s timeout)
        val outsideSince = now - 65_000L     // off-screen for 65s

        // Base passing case
        assertTrue(
            shouldTriggerAutoRecenter(
                autoRecenterEnabled = true,
                isAppInForeground = true,
                isFollowing = false,
                destinationPoint = null,
                selectedSavedPlace = null,
                isRecording = true,
                selectedTripsEmpty = true,
                showHeatMap = false,
                isUserTouchingMap = false,
                isCursorInsideViewport = false,
                nowElapsedMs = now,
                lastInteractionElapsedMs = lastInteraction,
                outsideViewportSinceElapsedMs = outsideSince,
                timeoutMs = timeout
            )
        )

        // Disabled if autoRecenterEnabled = false
        assertFalse(
            shouldTriggerAutoRecenter(
                autoRecenterEnabled = false,
                isAppInForeground = true,
                isFollowing = false,
                destinationPoint = null,
                selectedSavedPlace = null,
                isRecording = true,
                selectedTripsEmpty = true,
                showHeatMap = false,
                isUserTouchingMap = false,
                isCursorInsideViewport = false,
                nowElapsedMs = now,
                lastInteractionElapsedMs = lastInteraction,
                outsideViewportSinceElapsedMs = outsideSince,
                timeoutMs = timeout
            )
        )

        // Disabled if user is touching map
        assertFalse(
            shouldTriggerAutoRecenter(
                autoRecenterEnabled = true,
                isAppInForeground = true,
                isFollowing = false,
                destinationPoint = null,
                selectedSavedPlace = null,
                isRecording = true,
                selectedTripsEmpty = true,
                showHeatMap = false,
                isUserTouchingMap = true,
                isCursorInsideViewport = false,
                nowElapsedMs = now,
                lastInteractionElapsedMs = lastInteraction,
                outsideViewportSinceElapsedMs = outsideSince,
                timeoutMs = timeout
            )
        )

        // Disabled if cursor is already inside viewport
        assertFalse(
            shouldTriggerAutoRecenter(
                autoRecenterEnabled = true,
                isAppInForeground = true,
                isFollowing = false,
                destinationPoint = null,
                selectedSavedPlace = null,
                isRecording = true,
                selectedTripsEmpty = true,
                showHeatMap = false,
                isUserTouchingMap = false,
                isCursorInsideViewport = true,
                nowElapsedMs = now,
                lastInteractionElapsedMs = lastInteraction,
                outsideViewportSinceElapsedMs = outsideSince,
                timeoutMs = timeout
            )
        )

        // Disabled if inspecting past trips while not recording
        assertFalse(
            shouldTriggerAutoRecenter(
                autoRecenterEnabled = true,
                isAppInForeground = true,
                isFollowing = false,
                destinationPoint = null,
                selectedSavedPlace = null,
                isRecording = false,
                selectedTripsEmpty = false,
                showHeatMap = false,
                isUserTouchingMap = false,
                isCursorInsideViewport = false,
                nowElapsedMs = now,
                lastInteractionElapsedMs = lastInteraction,
                outsideViewportSinceElapsedMs = outsideSince,
                timeoutMs = timeout
            )
        )

        // Disabled if inspecting heat map while not recording
        assertFalse(
            shouldTriggerAutoRecenter(
                autoRecenterEnabled = true,
                isAppInForeground = true,
                isFollowing = false,
                destinationPoint = null,
                selectedSavedPlace = null,
                isRecording = false,
                selectedTripsEmpty = true,
                showHeatMap = true,
                isUserTouchingMap = false,
                isCursorInsideViewport = false,
                nowElapsedMs = now,
                lastInteractionElapsedMs = lastInteraction,
                outsideViewportSinceElapsedMs = outsideSince,
                timeoutMs = timeout
            )
        )

        // Disabled if recent interaction happened < timeout ago
        assertFalse(
            shouldTriggerAutoRecenter(
                autoRecenterEnabled = true,
                isAppInForeground = true,
                isFollowing = false,
                destinationPoint = null,
                selectedSavedPlace = null,
                isRecording = true,
                selectedTripsEmpty = true,
                showHeatMap = false,
                isUserTouchingMap = false,
                isCursorInsideViewport = false,
                nowElapsedMs = now,
                lastInteractionElapsedMs = now - 20_000L, // only 20s ago
                outsideViewportSinceElapsedMs = outsideSince,
                timeoutMs = timeout
            )
        )
    }
}
