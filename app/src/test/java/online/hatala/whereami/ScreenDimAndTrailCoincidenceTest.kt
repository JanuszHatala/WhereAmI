package online.hatala.whereami

import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.osmdroid.util.GeoPoint

class ScreenDimAndTrailCoincidenceTest {

    @Test
    fun testScreenDimResolveWindowBrightness() {
        // Disabled or not dimmed -> BRIGHTNESS_OVERRIDE_NONE (-1.0f)
        assertEquals(
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE,
            ScreenDimController.resolveWindowBrightness(
                shouldKeepScreenOn = true,
                isDimmed = false,
                effectiveDimEnabled = true,
                effectiveLevelPercent = 20
            ),
            0.001f
        )

        // Dimmed with keepScreenOn -> returns perceptual quadratic curve (20% -> 0.04f)
        val dimmedNormal = ScreenDimController.resolveWindowBrightness(
            shouldKeepScreenOn = true,
            isDimmed = true,
            effectiveDimEnabled = true,
            effectiveLevelPercent = 20
        )
        assertEquals(0.04f, dimmedNormal, 0.001f)

        // Dim preview overrides even when keepScreenOn or isDimmed is false (15% -> 0.0225f)
        val preview = ScreenDimController.resolveWindowBrightness(
            shouldKeepScreenOn = false,
            isDimmed = false,
            effectiveDimEnabled = false,
            effectiveLevelPercent = 15,
            isDimPreview = true
        )
        assertEquals(0.0225f, preview, 0.001f)

        // 100% is fully bright (1.0f)
        val preview100 = ScreenDimController.resolveWindowBrightness(
            shouldKeepScreenOn = true,
            isDimmed = true,
            effectiveDimEnabled = true,
            effectiveLevelPercent = 100,
            isDimPreview = true
        )
        assertEquals(1.0f, preview100, 0.001f)
        assertEquals(0.0f, ScreenDimController.resolveScrimAlpha(100), 0.001f)

        // 60% -> 0.36f, no scrim
        val preview60 = ScreenDimController.resolveWindowBrightness(
            shouldKeepScreenOn = true,
            isDimmed = true,
            effectiveDimEnabled = true,
            effectiveLevelPercent = 60,
            isDimPreview = true
        )
        assertEquals(0.36f, preview60, 0.001f)
        assertEquals(0.0f, ScreenDimController.resolveScrimAlpha(60), 0.001f)

        // 5% -> 0.0025f, deep scrim
        val preview5 = ScreenDimController.resolveWindowBrightness(
            shouldKeepScreenOn = true,
            isDimmed = true,
            effectiveDimEnabled = true,
            effectiveLevelPercent = 5,
            isDimPreview = true
        )
        assertEquals(0.0025f, preview5, 0.0001f)
        assertEquals(0.828f, ScreenDimController.resolveScrimAlpha(5), 0.01f)

        // 2% -> 0.0004f, very deep scrim ~0.88f
        val preview2 = ScreenDimController.resolveWindowBrightness(
            shouldKeepScreenOn = true,
            isDimmed = true,
            effectiveDimEnabled = true,
            effectiveLevelPercent = 2,
            isDimPreview = true
        )
        assertEquals(0.0004f, preview2, 0.0001f)
        assertEquals(0.883f, ScreenDimController.resolveScrimAlpha(2), 0.01f)

        // 0% -> 0.0f, max dark scrim 0.92f
        val preview0 = ScreenDimController.resolveWindowBrightness(
            shouldKeepScreenOn = true,
            isDimmed = true,
            effectiveDimEnabled = true,
            effectiveLevelPercent = 0,
            isDimPreview = true
        )
        assertEquals(0.0f, preview0, 0.0001f)
        assertEquals(0.92f, ScreenDimController.resolveScrimAlpha(0), 0.01f)
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
