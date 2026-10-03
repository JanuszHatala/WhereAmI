package janush.tech.whereami

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.log2
import kotlin.math.max

class ViewportFitTest {

    private val rEarth = 6378137.0

    /**
     * Replicates the projection and rotated bounding box math from fitPointsToUnobstructedViewport.
     */
    private fun computeRotatedFit(
        points: List<Pair<Double, Double>>,
        orientationDeg: Float,
        availWidth: Double,
        availHeight: Double
    ): Pair<Double, Pair<Double, Double>> {
        val thetaRad = Math.toRadians(orientationDeg.toDouble())
        val meanLat = Math.toRadians(points.map { it.first }.average())
        val meanLon = Math.toRadians(points.map { it.second }.average())
        val cosMeanLat = cos(meanLat)

        val cosTheta = cos(thetaRad)
        val sinTheta = sin(thetaRad)

        var minRotX = Double.MAX_VALUE
        var maxRotX = -Double.MAX_VALUE
        var minRotY = Double.MAX_VALUE
        var maxRotY = -Double.MAX_VALUE

        for (p in points) {
            val latRad = Math.toRadians(p.first)
            val lonRad = Math.toRadians(p.second)
            val x = (lonRad - meanLon) * rEarth * cosMeanLat
            val y = (latRad - meanLat) * rEarth
            val rotX = x * cosTheta - y * sinTheta
            val rotY = x * sinTheta + y * cosTheta

            if (rotX < minRotX) minRotX = rotX
            if (rotX > maxRotX) maxRotX = rotX
            if (rotY < minRotY) minRotY = rotY
            if (rotY > maxRotY) maxRotY = rotY
        }

        val spanRotX = (maxRotX - minRotX).coerceAtLeast(250.0)
        val spanRotY = (maxRotY - minRotY).coerceAtLeast(250.0)

        val mppX = spanRotX / availWidth
        val mppY = spanRotY / availHeight
        val requiredMpp = max(mppX, mppY)
        val zoom = log2((156543.03392 * cosMeanLat) / requiredMpp)

        val midRotX = (minRotX + maxRotX) / 2.0
        val midRotY = (minRotY + maxRotY) / 2.0
        val midX = midRotX * cosTheta + midRotY * sinTheta
        val midY = -midRotX * sinTheta + midRotY * cosTheta

        val centerLat = Math.toDegrees(meanLat + midY / rEarth)
        val centerLon = Math.toDegrees(meanLon + midX / (rEarth * cosMeanLat))

        return Pair(zoom, Pair(centerLat, centerLon))
    }

    @Test
    fun testTrip104RotatedBoundingBoxAdjustsZoom() {
        // Real Trip 104 coordinates (Czaniec ride)
        val points = listOf(
            Pair(49.8431058, 19.2870374), // Start
            Pair(49.8465000, 19.2860000), // Mid
            Pair(49.8506504, 19.2851519), // End
            Pair(49.8508616, 19.2852000)  // Destination
        )

        // Portrait unobstructed dimensions on 1080x2400 display
        // Avail width: 1080 - 40 - 75 = 965 px
        // Avail height: 2400 - 750 (top card) - 250 (bottom toolbar) - 80 (margins) = 1320 px
        val availWidth = 965.0
        val availHeight = 1320.0

        val (zoom0, center0) = computeRotatedFit(points, 0f, availWidth, availHeight)
        val (zoom90, center90) = computeRotatedFit(points, 90f, availWidth, availHeight)

        // At 0 deg (North-Up), track runs North-South into 1320px height
        // At 90 deg (Course-Up rotated), track runs East-West into narrower 965px width
        // Therefore zoom at 90 deg must be slightly lower (more zoomed out) to prevent lateral cutoffs
        assertTrue("Zoom at 90° ($zoom90) should be <= zoom at 0° ($zoom0) to accommodate narrower width", zoom90 <= zoom0)
        assertTrue("Zoom must remain in reasonable human-scale range [15.0, 18.0]", zoom0 in 15.0..18.0)
        assertTrue("Zoom at 90° must remain in reasonable human-scale range [15.0, 18.0]", zoom90 in 15.0..18.0)

        // Center must be roughly midway between 49.843 and 49.851 (~49.847)
        assertEquals(49.847, center0.first, 0.002)
        assertEquals(19.286, center0.second, 0.002)

        // Center should remain consistent across rotations
        assertEquals(center0.first, center90.first, 0.0001)
        assertEquals(center0.second, center90.second, 0.0001)
    }

    @Test
    fun testUnobstructedViewportInsetsCalculation() {
        val density = 2.625f
        val mapWidth = 1080
        val mapHeight = 2400

        // Normal mode portrait
        val topCardDp = 260f
        val statusBarDp = 36f
        val bottomToolbarDp = 70f
        val navBarDp = 24f
        val rightControlsDp = 55f
        val marginPx = (32f * density).toInt()

        val topInset = ((topCardDp + statusBarDp) * density).toInt() + marginPx
        val bottomInset = ((bottomToolbarDp + navBarDp) * density).toInt() + marginPx
        val leftInset = marginPx
        val rightInset = (rightControlsDp * density).toInt() + marginPx

        val availWidth = mapWidth - leftInset - rightInset
        val availHeight = mapHeight - topInset - bottomInset

        assertTrue("Available width ($availWidth) must leave room for map rendering", availWidth > 750)
        assertTrue("Available height ($availHeight) must leave substantial space between cards", availHeight > 1000)

        // Viewport center
        val screenCenterY = topInset + availHeight / 2.0
        val physicalCenterY = mapHeight / 2.0

        // Viewport center must be physically lower than screen center because top card is larger than bottom toolbar
        assertTrue("Viewport center Y ($screenCenterY) must be lower than screen center ($physicalCenterY)", screenCenterY > physicalCenterY)
    }
}
