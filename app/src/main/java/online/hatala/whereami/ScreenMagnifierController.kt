package online.hatala.whereami

/**
 * Pure state and geometry math for the live whole-screen Magnifier overlay.
 * Scales around the center of the viewport via graphicsLayer(scaleX, scaleY, translationX, translationY).
 */
enum class MagnifierPhase {
    OFF,
    AWAITING_TAP,
    ACTIVE
}

data class MagnifierState(
    val phase: MagnifierPhase = MagnifierPhase.OFF,
    val scale: Float = DEFAULT_SCALE,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f
) {
    val isActive: Boolean get() = phase == MagnifierPhase.ACTIVE
    val isVisible: Boolean get() = phase != MagnifierPhase.OFF

    companion object {
        const val MIN_SCALE = 1.5f
        const val DEFAULT_SCALE = 2.5f
        const val MAX_SCALE = 5.0f

        /**
         * Transitions to ACTIVE centered on the tapped viewport pixel (tapX, tapY).
         * With TransformOrigin.Center, scaling by S maps point (tapX - W/2, tapY - H/2)
         * to S * (tapX - W/2) + offset. To bring the tapped point to the viewport center,
         * offset = -S * (tapX - W/2), then clamped so viewport edges never expose blank space.
         */
        fun activateAtTap(
            tapX: Float,
            tapY: Float,
            viewportWidth: Float,
            viewportHeight: Float,
            initialScale: Float = DEFAULT_SCALE
        ): MagnifierState {
            val scale = initialScale.coerceIn(MIN_SCALE, MAX_SCALE)
            if (viewportWidth <= 0f || viewportHeight <= 0f) {
                return MagnifierState(MagnifierPhase.ACTIVE, scale, 0f, 0f)
            }
            val rawOffsetX = -scale * (tapX - viewportWidth / 2f)
            val rawOffsetY = -scale * (tapY - viewportHeight / 2f)
            val (clampedX, clampedY) = clampOffsets(rawOffsetX, rawOffsetY, scale, viewportWidth, viewportHeight)
            return MagnifierState(
                phase = MagnifierPhase.ACTIVE,
                scale = scale,
                offsetX = clampedX,
                offsetY = clampedY
            )
        }

        fun clampOffsets(
            offsetX: Float,
            offsetY: Float,
            scale: Float,
            viewportWidth: Float,
            viewportHeight: Float
        ): Pair<Float, Float> {
            if (viewportWidth <= 0f || viewportHeight <= 0f || scale <= 1f) return 0f to 0f
            val maxOffsetX = viewportWidth * (scale - 1f) / 2f
            val maxOffsetY = viewportHeight * (scale - 1f) / 2f
            return offsetX.coerceIn(-maxOffsetX, maxOffsetX) to offsetY.coerceIn(-maxOffsetY, maxOffsetY)
        }
    }

    /**
     * Applies 1-finger pan and 2-finger pinch zoom while keeping the viewport filled.
     */
    fun applyTransform(
        panX: Float,
        panY: Float,
        zoomChange: Float,
        viewportWidth: Float,
        viewportHeight: Float
    ): MagnifierState {
        if (phase != MagnifierPhase.ACTIVE) return this
        val nextScale = (scale * zoomChange).coerceIn(MIN_SCALE, MAX_SCALE)
        val scaleRatio = if (scale > 0f) nextScale / scale else 1f
        val rawX = offsetX * scaleRatio + panX
        val rawY = offsetY * scaleRatio + panY
        val (clampedX, clampedY) = clampOffsets(rawX, rawY, nextScale, viewportWidth, viewportHeight)
        return copy(scale = nextScale, offsetX = clampedX, offsetY = clampedY)
    }
}
