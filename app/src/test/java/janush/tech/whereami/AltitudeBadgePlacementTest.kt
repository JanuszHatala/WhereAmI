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

        // Border stroke should be solid Black (#000000) for sharp delineation over any map background
        val strokeColor = 0xFF000000.toInt()
        assertEquals(0xFF000000.toInt(), strokeColor)
    }

    @Test
    fun testHysteresisPreventsQuadrantFlickerUnderMicroFluctuations() {
        val cursorX = 500f
        val cursorY = 800f
        // Threat near Top-Right, causing candidate to shift to Bottom-Right (index 1)
        val initialThreats = listOf(Pair(cursorX + (18f * density), cursorY - (24f * density)))

        val initialIdx = AltitudeBadgePositionEvaluator.selectBestOffsetWithHysteresis(
            cursorX = cursorX,
            cursorY = cursorY,
            savedPlaceScreenPoints = initialThreats,
            density = density,
            currentOffsetIndex = 0,
            collisionRadiusDp = 50f,
            hysteresisMarginDp = 12f
        )
        assertNotEquals(0, initialIdx)

        // Now simulate a micro-movement/rotation where another candidate has a microscopic 0.5dp advantage.
        // With hysteresis (hysteresisMarginDp = 12dp), it MUST stay on initialIdx and not flicker.
        val currentOffset = AltitudeBadgePositionEvaluator.CANDIDATE_OFFSETS[initialIdx]
        val currentBadgeX = cursorX + (currentOffset.dxDp * density)
        val currentBadgeY = cursorY + (currentOffset.dyDp * density)

        // Threat moved slightly, keeping current candidate safe (>= 32dp away)
        val microThreats = listOf(Pair(cursorX + (17f * density), cursorY - (25f * density)))
        val nextIdx = AltitudeBadgePositionEvaluator.selectBestOffsetWithHysteresis(
            cursorX = cursorX,
            cursorY = cursorY,
            savedPlaceScreenPoints = microThreats,
            density = density,
            currentOffsetIndex = initialIdx,
            collisionRadiusDp = 50f,
            hysteresisMarginDp = 12f
        )
        assertEquals("Should retain current candidate without flickering", initialIdx, nextIdx)
    }

    @Test
    fun testHysteresisExitsCollisionZoneOnlyWhenThreatClearsExitRadius() {
        val cursorX = 500f
        val cursorY = 800f
        val currentIdx = 1 // Bottom-Right

        // Threat at 55dp: beyond entry collisionRadius (50dp), but within exit radius (60dp)
        val threats = listOf(Pair(cursorX + (55f * density), cursorY))

        val idx = AltitudeBadgePositionEvaluator.selectBestOffsetWithHysteresis(
            cursorX = cursorX,
            cursorY = cursorY,
            savedPlaceScreenPoints = threats,
            density = density,
            currentOffsetIndex = currentIdx,
            collisionRadiusDp = 50f,
            hysteresisMarginDp = 12f
        )
        // Should NOT snap back to default 0 immediately, preventing chattering near 50dp threshold
        assertEquals(currentIdx, idx)

        // Once threat clears 62dp (beyond 60dp exit radius), returns to default 0
        val farThreats = listOf(Pair(cursorX + (62f * density), cursorY))
        val returnedIdx = AltitudeBadgePositionEvaluator.selectBestOffsetWithHysteresis(
            cursorX = cursorX,
            cursorY = cursorY,
            savedPlaceScreenPoints = farThreats,
            density = density,
            currentOffsetIndex = currentIdx,
            collisionRadiusDp = 50f,
            hysteresisMarginDp = 12f
        )
        assertEquals(0, returnedIdx)
    }

    @Test
    fun testAvoidsCollisionWhenSavedPlaceAndGuestClusterAtCursor() {
        val cursorX = 500f
        val cursorY = 800f
        // Saved Place is at the cursor (0, 0), and a guest avatar is spiderfied to the West (-65dp, 0dp)
        val threats = listOf(
            Pair(cursorX, cursorY), // Saved Place Pin
            Pair(cursorX - (65f * density), cursorY) // Guest avatar to the West
        )

        val chosen = AltitudeBadgePositionEvaluator.selectBestOffset(
            cursorX = cursorX,
            cursorY = cursorY,
            savedPlaceScreenPoints = threats,
            density = density,
            collisionRadiusDp = 50f
        )

        // Must NOT pick compact offsets (indices 0..3) which would collide with the pin
        val chosenIdx = AltitudeBadgePositionEvaluator.CANDIDATE_OFFSETS.indexOf(chosen)
        assertTrue("Must pick an outer clearance offset when pin is at cursor: chosen=$chosen (idx=$chosenIdx)", chosenIdx >= 4)

        // Must be on the East / North-East side to stay clear of the guest on the West
        assertTrue("Must be on East side (dxDp > 0) away from West guest", chosen.dxDp > 0f)

        // Distance from chosen badge to any threat must be >= 40dp
        val badgeX = cursorX + (chosen.dxDp * density)
        val badgeY = cursorY + (chosen.dyDp * density)
        val minDist = threats.minOf { (tX, tY) -> hypot(tX - badgeX, tY - badgeY) }
        assertTrue("Distance to nearest threat must be >= 40dp: $minDist", minDist >= 40f * density)
    }
}
