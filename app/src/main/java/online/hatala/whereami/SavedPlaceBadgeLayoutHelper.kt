package online.hatala.whereami

/**
 * Helper to determine whether the saved place badge should collapse to icon-only mode
 * based on available width, text length, and ellipsis headroom.
 */
object SavedPlaceBadgeLayoutHelper {

    /**
     * Determines whether the saved place badge should collapse to icon-only mode.
     *
     * Rules:
     * 1. If [placeName] length > 18 -> icon only.
     * 2. If [availableTextWidthPx] fits the full name -> show text (not icon-only).
     * 3. If full name does not fit, but can fit at least 4 letters + ellipsis ("...") -> show text with ellipsis.
     * 4. If fewer than 4 letters can fit with ellipsis -> switch to icon-only.
     *
     * @param placeName The name of the saved place.
     * @param availableTextWidthPx The horizontal pixel width available specifically for the text label.
     * @param textMeasurer A function measuring text width in pixels.
     * @return `true` if the badge should collapse to an icon-only button; `false` to render text.
     */
    fun shouldShowIconOnly(
        placeName: String,
        availableTextWidthPx: Float,
        textMeasurer: (String) -> Float
    ): Boolean {
        val trimmed = placeName.trim()
        if (trimmed.length > 18) return true
        if (availableTextWidthPx <= 0f) return true

        val fullWidth = textMeasurer(trimmed)
        if (fullWidth <= availableTextWidthPx) return false

        // Full name doesn't fit. If total length is less than 4, it cannot display 4 letters + ellipsis.
        if (trimmed.length < 4) return true

        val min4WithEllipsis = trimmed.take(4) + "..."
        val min4Width = textMeasurer(min4WithEllipsis)
        return availableTextWidthPx < min4Width
    }
}
