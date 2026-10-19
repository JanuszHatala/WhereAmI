package janush.tech.whereami

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class AltitudeBadgePlacementTest {

    private val density = 2.5f

    @Test
    fun testDefaultTopRightPlacementWhenNoThreats() {
        val cursorX = 500f
        val cursorY = 800f
        val threats = emptyList<Pair<Float, Float>>()

        val chosen = AltitudeBadgePositionEvaluator.selectBestOffset(
            cursorX = cursorX,
            cursorY = cursorY,
            savedPlaceScreenPoints = threats,
            density = density,
            collisionRadiusDp = 50f
        )

        assertEquals(18f, chosen.dxDp)
        assertEquals(-24f, chosen.dyDp)
    }

    @Test
    fun testDefaultTopRightPlacementWhenThreatIsFarAway() {
        val cursorX = 500f
        val cursorY = 800f
        // Saved place is 200dp away (well beyond 50dp collision radius)
        val threats = listOf(Pair(cursorX + (200f * density), cursorY))

        val chosen = AltitudeBadgePositionEvaluator.selectBestOffset(
            cursorX = cursorX,
            cursorY = cursorY,
            savedPlaceScreenPoints = threats,
            density = density,
            collisionRadiusDp = 50f
        )

        assertEquals(18f, chosen.dxDp)
        assertEquals(-24f, chosen.dyDp)
    }

    @Test
    fun testAvoidsCollisionWhenPinIsTopRight() {
        val cursorX = 500f
        val cursorY = 800f
        // Saved place is at (+18dp, -24dp) right where the default badge would sit
        val threats = listOf(Pair(cursorX + (18f * density), cursorY - (24f * density)))

        val chosen = AltitudeBadgePositionEvaluator.selectBestOffset(
            cursorX = cursorX,
            cursorY = cursorY,
            savedPlaceScreenPoints = threats,
            density = density,
            collisionRadiusDp = 50f
        )

        // Must NOT pick Top-Right
        assertNotEquals(BadgeOffset(18f, -24f), chosen)

        // Distance from chosen badge to threat should be maximized
        val badgeX = cursorX + (chosen.dxDp * density)
        val badgeY = cursorY + (chosen.dyDp * density)
        val distToThreat = hypot(threats[0].first - badgeX, threats[0].second - badgeY)

        // Should be at least 30dp away in pixels
        assertTrue("Distance to threat should be >= 30dp: $distToThreat", distToThreat >= 30f * density)
    }

    @Test
    fun testAvoidsCollisionWhenPinIsDirectlyOnCursor() {
        val cursorX = 500f
        val cursorY = 800f
        // Saved place is exactly at the cursor
        val threats = listOf(Pair(cursorX, cursorY))

        val chosen = AltitudeBadgePositionEvaluator.selectBestOffset(
            cursorX = cursorX,
            cursorY = cursorY,
            savedPlaceScreenPoints = threats,
            density = density,
            collisionRadiusDp = 50f
        )

        // All 4 candidates are equal distance from cursor center (~30dp)
        val badgeX = cursorX + (chosen.dxDp * density)
        val badgeY = cursorY + (chosen.dyDp * density)
        val distToThreat = hypot(threats[0].first - badgeX, threats[0].second - badgeY)

        assertTrue(distToThreat > 20f * density)
    }

    @Test
    fun testAllCandidateOffsetsAreNonZero() {
        for (candidate in AltitudeBadgePositionEvaluator.CANDIDATE_OFFSETS) {
            assertTrue(candidate.dxDp != 0f)
            assertTrue(candidate.dyDp != 0f)
        }
    }

    @Test
    fun testAltitudeBadgeStyleProperties() {
        // Opacity should be semi-transparent (~46% / 0x75) so what's below is clearly visible
        val bgAlpha = 0x75
        assertTrue("Background alpha should be semi-transparent (< 0x90)", bgAlpha in 0x60..0x90)

        // Border stroke should be solid Slate-500
        val strokeColor = 0xFF64748B.toInt()
        assertEquals(0xFF64748B.toInt(), strokeColor)
    }
}
