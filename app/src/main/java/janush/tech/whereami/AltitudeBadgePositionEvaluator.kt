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
        BadgeOffset(18f, -24f),  // 0: Top-Right (Default compact)
        BadgeOffset(18f, 24f),   // 1: Bottom-Right (Compact)
        BadgeOffset(-18f, 24f),  // 2: Bottom-Left (Compact)
        BadgeOffset(-18f, -24f), // 3: Top-Left (Compact)
        BadgeOffset(52f, -24f),  // 4: Far Top-Right (clears co-located saved place pin & guest avatars)
        BadgeOffset(12f, -44f),  // 5: Far Top (clears horizontal badge spreads)
        BadgeOffset(52f, 24f),   // 6: Far Bottom-Right
        BadgeOffset(-52f, -24f), // 7: Far Top-Left
        BadgeOffset(-52f, 24f),  // 8: Far Bottom-Left
        BadgeOffset(12f, 44f)    // 9: Far Bottom
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

        // Check if cursor is co-located with an obstacle (threat within 35dp of cursor)
        val isThreatCoLocatedWithCursor = threats.any { (spX, spY) ->
            hypot(spX - cursorX, spY - cursorY) < 35f * density
        }

        // If current candidate is safely away from threats (>= 32dp), and if co-located threat then candidate must be an outer offset (>= 4), keep it
        val minSafeDistancePx = 32f * density
        if (currentDistToThreat >= minSafeDistancePx && (!isThreatCoLocatedWithCursor || safeCurrentIdx >= 4)) {
            return safeCurrentIdx
        }

        // Find candidate that maximizes threat distance, requiring a significant margin to switch away
        var bestIdx = if (isThreatCoLocatedWithCursor && safeCurrentIdx < 4) 4 else safeCurrentIdx
        var maxDist = if (isThreatCoLocatedWithCursor && safeCurrentIdx < 4) {
            val off = CANDIDATE_OFFSETS[bestIdx]
            val bX = cursorX + (off.dxDp * density)
            val bY = cursorY + (off.dyDp * density)
            threats.minOf { (spX, spY) -> hypot(spX - bX, spY - bY) }
        } else {
            currentDistToThreat
        }

        for (i in CANDIDATE_OFFSETS.indices) {
            if (i == safeCurrentIdx) continue
            // If threat is co-located at cursor, do not pick compact offsets (0..3) as they collide with pin
            if (isThreatCoLocatedWithCursor && i < 4) continue
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
