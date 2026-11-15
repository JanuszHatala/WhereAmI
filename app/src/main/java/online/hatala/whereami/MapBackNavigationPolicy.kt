package online.hatala.whereami

internal const val MAP_CONVENIENT_ZOOM = 17.6
internal const val MAP_CONVENIENT_ZOOM_TOLERANCE = 0.5
internal const val BACK_EXIT_CONFIRM_WINDOW_MS = 2_500L

internal enum class MapBackAction {
    DISMISS_OVERLAY,
    CENTER_MAP,
    RESET_CONVENIENT_ZOOM,
    SHOW_EXIT_TOAST,
    FINISH_APP
}

internal object MapBackNavigationPolicy {
    fun isNearConvenientZoom(zoomLevel: Double): Boolean =
        kotlin.math.abs(zoomLevel - MAP_CONVENIENT_ZOOM) <= MAP_CONVENIENT_ZOOM_TOLERANCE + 1e-9

    fun resolveAction(
        hasOverlaySelection: Boolean,
        hasKnownLocation: Boolean,
        isMapFollowing: Boolean,
        currentZoom: Double,
        lastExitToastElapsedMs: Long,
        nowElapsedMs: Long
    ): MapBackAction {
        if (hasOverlaySelection) return MapBackAction.DISMISS_OVERLAY
        if (hasKnownLocation) {
            if (!isMapFollowing) return MapBackAction.CENTER_MAP
            if (!isNearConvenientZoom(currentZoom)) return MapBackAction.RESET_CONVENIENT_ZOOM
        }
        val withinExitWindow =
            lastExitToastElapsedMs > 0L &&
                (nowElapsedMs - lastExitToastElapsedMs) in 0L..BACK_EXIT_CONFIRM_WINDOW_MS
        return if (withinExitWindow) MapBackAction.FINISH_APP else MapBackAction.SHOW_EXIT_TOAST
    }
}

internal class MapTouchFollowTracker(
    private val touchSlopPx: Float,
    private val edgeSlopPx: Float
) {
    var downX: Float = 0f
        private set
    var downY: Float = 0f
        private set
    var wasFollowingAtDown: Boolean = false
        private set
    var startedInGestureEdge: Boolean = false
        private set
    var isUserDragging: Boolean = false
        private set

    fun onDown(x: Float, y: Float, viewWidth: Int, currentlyFollowing: Boolean) {
        downX = x
        downY = y
        wasFollowingAtDown = currentlyFollowing
        startedInGestureEdge = viewWidth > 0 && (x <= edgeSlopPx || x >= (viewWidth - edgeSlopPx))
        isUserDragging = false
    }

    /** Returns true when this move is an intentional non-edge map pan that should detach follow mode. */
    fun onMove(x: Float, y: Float): Boolean {
        val dx = x - downX
        val dy = y - downY
        val pastSlop = (dx * dx + dy * dy) > (touchSlopPx * touchSlopPx)
        if (pastSlop && !startedInGestureEdge) {
            isUserDragging = true
            return true
        }
        return false
    }

    /** Returns restored follow flag when a system gesture cancels the touch stream after starting in follow mode. */
    fun onCancel(): Boolean? {
        isUserDragging = false
        startedInGestureEdge = false
        return if (wasFollowingAtDown) true else null
    }

    fun onUp() {
        isUserDragging = false
        startedInGestureEdge = false
    }
}
