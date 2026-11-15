package online.hatala.whereami

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapBackNavigationPolicyTest {

    @Test
    fun `overlay selection takes precedence over recenter, zoom reset, and active exit window`() {
        val action = MapBackNavigationPolicy.resolveAction(
            hasOverlaySelection = true,
            hasKnownLocation = true,
            isMapFollowing = false,
            currentZoom = 11.0,
            lastExitToastElapsedMs = 10_000L,
            nowElapsedMs = 11_000L
        )
        assertEquals(MapBackAction.DISMISS_OVERLAY, action)
    }

    @Test
    fun `full multi-stage journey centers, snaps zoom to convenient level, shows exit toast, and finishes within window`() {
        // Step 1: panned away at wide zoom -> center current position
        assertEquals(
            MapBackAction.CENTER_MAP,
            MapBackNavigationPolicy.resolveAction(
                hasOverlaySelection = false,
                hasKnownLocation = true,
                isMapFollowing = false,
                currentZoom = 11.5,
                lastExitToastElapsedMs = 0L,
                nowElapsedMs = 10_000L
            )
        )

        // Step 2: centered but at startup overview zoom (16.0) -> snap zoom to 17.6
        assertEquals(
            MapBackAction.RESET_CONVENIENT_ZOOM,
            MapBackNavigationPolicy.resolveAction(
                hasOverlaySelection = false,
                hasKnownLocation = true,
                isMapFollowing = true,
                currentZoom = 16.0,
                lastExitToastElapsedMs = 0L,
                nowElapsedMs = 11_000L
            )
        )

        // Step 3: centered at convenient zoom -> show exit confirmation Toast
        assertEquals(
            MapBackAction.SHOW_EXIT_TOAST,
            MapBackNavigationPolicy.resolveAction(
                hasOverlaySelection = false,
                hasKnownLocation = true,
                isMapFollowing = true,
                currentZoom = MAP_CONVENIENT_ZOOM,
                lastExitToastElapsedMs = 0L,
                nowElapsedMs = 12_000L
            )
        )

        // Step 4: confirming Back within 2.5s window -> finish MainActivity
        assertEquals(
            MapBackAction.FINISH_APP,
            MapBackNavigationPolicy.resolveAction(
                hasOverlaySelection = false,
                hasKnownLocation = true,
                isMapFollowing = true,
                currentZoom = MAP_CONVENIENT_ZOOM,
                lastExitToastElapsedMs = 12_000L,
                nowElapsedMs = 13_500L
            )
        )
    }

    @Test
    fun `bidirectional zoom tolerance accepts 17_6 plus-minus 0_5 and resets both zoomed-out and over-zoomed`() {
        assertTrue(MapBackNavigationPolicy.isNearConvenientZoom(17.1))
        assertTrue(MapBackNavigationPolicy.isNearConvenientZoom(17.6))
        assertTrue(MapBackNavigationPolicy.isNearConvenientZoom(18.1))
        assertFalse(MapBackNavigationPolicy.isNearConvenientZoom(17.0))
        assertFalse(MapBackNavigationPolicy.isNearConvenientZoom(18.5))

        assertEquals(
            MapBackAction.RESET_CONVENIENT_ZOOM,
            MapBackNavigationPolicy.resolveAction(
                hasOverlaySelection = false,
                hasKnownLocation = true,
                isMapFollowing = true,
                currentZoom = 18.5,
                lastExitToastElapsedMs = 0L,
                nowElapsedMs = 5_000L
            )
        )

        assertEquals(
            MapBackAction.SHOW_EXIT_TOAST,
            MapBackNavigationPolicy.resolveAction(
                hasOverlaySelection = false,
                hasKnownLocation = true,
                isMapFollowing = true,
                currentZoom = 17.5,
                lastExitToastElapsedMs = 0L,
                nowElapsedMs = 5_000L
            )
        )
    }

    @Test
    fun `expired confirmation window re-shows toast instead of closing`() {
        val action = MapBackNavigationPolicy.resolveAction(
            hasOverlaySelection = false,
            hasKnownLocation = true,
            isMapFollowing = true,
            currentZoom = 17.6,
            lastExitToastElapsedMs = 10_000L,
            nowElapsedMs = 10_000L + BACK_EXIT_CONFIRM_WINDOW_MS + 1L
        )
        assertEquals(MapBackAction.SHOW_EXIT_TOAST, action)
    }

    @Test
    fun `cold start without known location proceeds safely to toast and finish without zoom trap`() {
        assertEquals(
            MapBackAction.SHOW_EXIT_TOAST,
            MapBackNavigationPolicy.resolveAction(
                hasOverlaySelection = false,
                hasKnownLocation = false,
                isMapFollowing = false,
                currentZoom = 2.5,
                lastExitToastElapsedMs = 0L,
                nowElapsedMs = 1_000L
            )
        )
        assertEquals(
            MapBackAction.FINISH_APP,
            MapBackNavigationPolicy.resolveAction(
                hasOverlaySelection = false,
                hasKnownLocation = false,
                isMapFollowing = false,
                currentZoom = 2.5,
                lastExitToastElapsedMs = 1_000L,
                nowElapsedMs = 2_000L
            )
        )
    }
}
