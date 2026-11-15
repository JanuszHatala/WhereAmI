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
