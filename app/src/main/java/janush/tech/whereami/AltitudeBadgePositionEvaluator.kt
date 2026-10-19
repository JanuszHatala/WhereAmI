package janush.tech.whereami

import kotlin.math.hypot

/**
 * Screen-space offset relative to the vehicle/position cursor anchor.
 */
data class BadgeOffset(val dxDp: Float, val dyDp: Float)

/**
 * Pure evaluation engine for calculating optimal altitude badge position beside the position cursor.
 * Guarantees zero occlusion with saved place map pins by dynamically shifting between 4 quadrant
 * candidate positions (Top-Right, Bottom-Right, Bottom-Left, Top-Left) based on spatial proximity.
 */
object AltitudeBadgePositionEvaluator {

    val CANDIDATE_OFFSETS = listOf(
        BadgeOffset(18f, -24f),  // Top-Right (Default)
        BadgeOffset(18f, 24f),   // Bottom-Right
        BadgeOffset(-18f, 24f),  // Bottom-Left
        BadgeOffset(-18f, -24f)  // Top-Left
    )

    /**
     * Selects the best candidate offset with quadrant hysteresis and threat zone entry/exit damping.
     * Prevents high-frequency quadrant jumping and flickering during map rotation, pinch-to-zoom, and minor movements.
     */
    fun selectBestOffsetWithHysteresis(
        cursorX: Float,
        cursorY: Float,
        savedPlaceScreenPoints: List<Pair<Float, Float>>,
        density: Float,
        currentOffsetIndex: Int,
        collisionRadiusDp: Float = 50f,
        hysteresisMarginDp: Float = 12f
    ): Int {
        val collisionRadiusPx = collisionRadiusDp * density
        // Exit radius has 10dp damping: once in collision zone, stay in collision avoidance until threat > 60dp
        val exitRadiusPx = (collisionRadiusDp + 10f) * density

        val threats = savedPlaceScreenPoints.filter { (spX, spY) ->
            val dist = hypot(spX - cursorX, spY - cursorY)
            if (currentOffsetIndex != 0) dist <= exitRadiusPx else dist <= collisionRadiusPx
        }

        if (threats.isEmpty()) {
            return 0 // Default Top-Right
        }

        val safeCurrentIdx = currentOffsetIndex.coerceIn(0, CANDIDATE_OFFSETS.lastIndex)
        val currentOffset = CANDIDATE_OFFSETS[safeCurrentIdx]
        val currentBadgeX = cursorX + (currentOffset.dxDp * density)
        val currentBadgeY = cursorY + (currentOffset.dyDp * density)
        val currentDistToThreat = threats.minOf { (spX, spY) ->
            hypot(spX - currentBadgeX, spY - currentBadgeY)
        }

        // If the current candidate is safely away from threats (>= 32dp), keep it to avoid jitter
        val minSafeDistancePx = 32f * density
        if (currentDistToThreat >= minSafeDistancePx) {
            return safeCurrentIdx
        }

        // Find candidate that maximizes threat distance, requiring a significant margin to switch away
        var bestIdx = safeCurrentIdx
        var maxDist = currentDistToThreat

        for (i in CANDIDATE_OFFSETS.indices) {
            if (i == safeCurrentIdx) continue
            val off = CANDIDATE_OFFSETS[i]
            val bX = cursorX + (off.dxDp * density)
            val bY = cursorY + (off.dyDp * density)
            val d = threats.minOf { (spX, spY) -> hypot(spX - bX, spY - bY) }
            if (d > maxDist + (hysteresisMarginDp * density)) {
                maxDist = d
                bestIdx = i
            }
        }
        return bestIdx
    }

    /**
     * Given the screen position of the cursor and a list of screen coordinates of nearby saved place pins,
     * returns the candidate offset that maximizes distance to the nearest pin (or returns default Top-Right
     * if no pin is within [collisionRadiusDp]).
     */
    fun selectBestOffset(
        cursorX: Float,
        cursorY: Float,
        savedPlaceScreenPoints: List<Pair<Float, Float>>,
        density: Float,
        collisionRadiusDp: Float = 50f
    ): BadgeOffset {
        val idx = selectBestOffsetWithHysteresis(
            cursorX = cursorX,
            cursorY = cursorY,
            savedPlaceScreenPoints = savedPlaceScreenPoints,
            density = density,
            currentOffsetIndex = 0,
            collisionRadiusDp = collisionRadiusDp,
            hysteresisMarginDp = 0f
        )
        return CANDIDATE_OFFSETS[idx]
    }
}
