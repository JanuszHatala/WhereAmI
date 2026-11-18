package online.hatala.whereami

import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.osmdroid.util.GeoPoint

class ScreenDimAndTrailCoincidenceTest {

    @Test
    fun testScreenDimResolveWindowBrightness_cappedBySystemBrightness() {
        // Disabled or not dimmed -> BRIGHTNESS_OVERRIDE_NONE (-1.0f)
        assertEquals(
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE,
            ScreenDimController.resolveWindowBrightness(
                shouldKeepScreenOn = true,
                isDimmed = false,
                effectiveDimEnabled = true,
                effectiveLevelPercent = 20,
                systemBrightnessFraction = 0.5f
            ),
            0.001f
        )

        // When system brightness is low (0.06), 20% dim target MUST NOT brighten the display to 0.20
        val dimmedLow = ScreenDimController.resolveWindowBrightness(
            shouldKeepScreenOn = true,
            isDimmed = true,
            effectiveDimEnabled = true,
            effectiveLevelPercent = 20,
            systemBrightnessFraction = 0.06f
        )
        assertEquals(0.06f, dimmedLow, 0.001f)

        // When system brightness is high (0.80), 20% dim target lowers brightness to 0.20
        val dimmedHigh = ScreenDimController.resolveWindowBrightness(
            shouldKeepScreenOn = true,
            isDimmed = true,
            effectiveDimEnabled = true,
            effectiveLevelPercent = 20,
            systemBrightnessFraction = 0.80f
        )
        assertEquals(0.20f, dimmedHigh, 0.001f)
    }

    @Test
    fun testTrailVectorDistinctBy_preservesCoincidentTrailsWithDifferentColors() {
        val wayPts = listOf(GeoPoint(49.85, 19.15), GeoPoint(49.86, 19.16))
        val redTrail = TrailVectorSegment(
            id = 100L,
            name = "Red Trail",
            kind = TrailRouteKind.CYCLING,
            color = TrailColor.RED,
            points = wayPts,
            relationId = 501L
        )
        val greenTrail = TrailVectorSegment(
            id = 100L,
            name = "Green Trail",
            kind = TrailRouteKind.CYCLING,
            color = TrailColor.GREEN,
            points = wayPts,
            relationId = 502L
        )

        val list = listOf(redTrail, greenTrail)
        val distinct = list.distinctBy {
            "${it.id}_${it.relationId}_${it.kind.name}_${it.color.name}_${it.points.firstOrNull()?.latitude}_${it.points.firstOrNull()?.longitude}"
        }

        assertEquals(2, distinct.size)
        assertTrue(distinct.any { it.color == TrailColor.RED })
        assertTrue(distinct.any { it.color == TrailColor.GREEN })
    }

    @Test
    fun testLaneOffsetCalculation_separatesCoincidentTrailsSymmetrically() {
        val strokeWidthPx = 10f
        val offsetRed = VectorTrailOverlay.computeLaneOffsetPx(0, 2, strokeWidthPx)
        val offsetGreen = VectorTrailOverlay.computeLaneOffsetPx(1, 2, strokeWidthPx)

        assertNotEquals(offsetRed, offsetGreen)
        assertEquals(-offsetRed, offsetGreen, 0.001f)
        assertTrue(kotlin.math.abs(offsetGreen - offsetRed) >= strokeWidthPx)
    }
}
