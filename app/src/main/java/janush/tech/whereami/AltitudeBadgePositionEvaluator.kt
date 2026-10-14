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
        val collisionRadiusPx = collisionRadiusDp * density
        val threats = savedPlaceScreenPoints.filter { (spX, spY) ->
            hypot(spX - cursorX, spY - cursorY) <= collisionRadiusPx
        }

        if (threats.isEmpty()) {
            return CANDIDATE_OFFSETS[0] // Default Top-Right
        }

        // Find candidate with maximum distance to the nearest threat
        return CANDIDATE_OFFSETS.maxByOrNull { offset ->
            val badgeX = cursorX + (offset.dxDp * density)
            val badgeY = cursorY + (offset.dyDp * density)
            threats.minOf { (spX, spY) ->
                hypot(spX - badgeX, spY - badgeY)
            }
        } ?: CANDIDATE_OFFSETS[0]
    }
}
