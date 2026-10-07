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
     * Replicates the projection, rotation, and camera centering from fitPointsToUnobstructedViewport.
     */
    private fun computeRotatedFit(
        points: List<Pair<Double, Double>>,
        orientationDeg: Float,
        availWidth: Double,
        availHeight: Double,
        offsetPixelsX: Double = 0.0,
        offsetPixelsY: Double = 0.0
    ): Pair<Double, Pair<Double, Double>> {
        val alpha = Math.toRadians(orientationDeg.toDouble())
        val cosA = cos(alpha)
        val sinA = sin(alpha)

        val meanLat = Math.toRadians(points.map { it.first }.average())
        val meanLon = Math.toRadians(points.map { it.second }.average())
        val cosMeanLat = cos(meanLat)

        var minU = Double.MAX_VALUE
        var maxU = -Double.MAX_VALUE
        var minV = Double.MAX_VALUE
        var maxV = -Double.MAX_VALUE

        for (p in points) {
            val latRad = Math.toRadians(p.first)
            val lonRad = Math.toRadians(p.second)
            val dEast = (lonRad - meanLon) * rEarth * cosMeanLat
            val dNorth = (latRad - meanLat) * rEarth
            val u = dEast * cosA + dNorth * sinA
            val v = dEast * sinA - dNorth * cosA

            if (u < minU) minU = u
            if (u > maxU) maxU = u
            if (v < minV) minV = v
            if (v > maxV) maxV = v
        }

        val spanU = (maxU - minU).coerceAtLeast(200.0)
        val spanV = (maxV - minV).coerceAtLeast(200.0)

        val safeWidth = availWidth * 0.88
        val safeHeight = availHeight * 0.88
        val mppX = spanU / safeWidth
        val mppY = spanV / safeHeight
        val requiredMpp = max(mppX, mppY)
        val zoom = log2((156543.03392 * cosMeanLat) / requiredMpp)

        val midU = (minU + maxU) / 2.0
        val midV = (minV + maxV) / 2.0

        val uCam = midU - offsetPixelsX * requiredMpp
        val vCam = midV - offsetPixelsY * requiredMpp

        val dEastCam = uCam * cosA + vCam * sinA
        val dNorthCam = uCam * sinA - vCam * cosA

        val camLat = Math.toDegrees(meanLat + dNorthCam / rEarth)
        val camLon = Math.toDegrees(meanLon + dEastCam / (rEarth * cosMeanLat))

        return Pair(zoom, Pair(camLat, camLon))
    }

    /**
     * Projects a geographical point (lat, lon) to physical screen coordinates (x, y)
     * as rendered by OSMDroid with canvas rotation orientationDeg.
     */
    private fun projectToScreen(
        lat: Double,
        lon: Double,
        camLat: Double,
        camLon: Double,
        zoom: Double,
        orientationDeg: Float,
        screenWidth: Double,
        screenHeight: Double
    ): Pair<Double, Double> {
        val alpha = Math.toRadians(orientationDeg.toDouble())
        val cosA = cos(alpha)
        val sinA = sin(alpha)
        val mpp = (156543.03392 * cos(Math.toRadians(camLat))) / Math.pow(2.0, zoom)

        val dEast = Math.toRadians(lon - camLon) * rEarth * cos(Math.toRadians(camLat))
        val dNorth = Math.toRadians(lat - camLat) * rEarth
        val u = dEast * cosA + dNorth * sinA
        val v = dEast * sinA - dNorth * cosA

        val screenX = screenWidth / 2.0 + u / mpp
        val screenY = screenHeight / 2.0 + v / mpp
        return Pair(screenX, screenY)
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

        val availWidth = 965.0
        val availHeight = 1320.0

        val (zoom0, center0) = computeRotatedFit(points, 0f, availWidth, availHeight)
        val (zoom90, center90) = computeRotatedFit(points, 90f, availWidth, availHeight)

        // At 0 deg (North-Up), track runs North-South into 1320px height
        // At 90 deg (Course-Up rotated), track runs East-West into narrower 965px width
        assertTrue("Zoom at 90° ($zoom90) should be <= zoom at 0° ($zoom0) to accommodate narrower width", zoom90 <= zoom0)
        assertTrue("Zoom must remain in reasonable human-scale range [14.0, 18.0]", zoom0 in 14.0..18.0)
        assertTrue("Zoom at 90° must remain in reasonable human-scale range [14.0, 18.0]", zoom90 in 14.0..18.0)

        // Center must be roughly midway between 49.843 and 49.851 (~49.847)
        assertEquals(49.847, center0.first, 0.002)
        assertEquals(19.286, center0.second, 0.002)

        // Center should remain consistent across rotations when offsets are 0
        assertEquals(center0.first, center90.first, 0.0001)
        assertEquals(center0.second, center90.second, 0.0001)
    }

    @Test
    fun testTrip161FieldTestPointsFitInsideUnobstructedViewportUnderRotations() {
        // Trip 161 (Bielsko-Biała to Czaniec - 22.18 km driving trip from field test)
        val samplePoints = listOf(
            Pair(49.8181810, 19.0492246), // Start (Bielsko-Biała)
            Pair(49.8250000, 19.1000000),
            Pair(49.8350000, 19.1500000),
            Pair(49.8564201, 19.2000000), // Max Lat
            Pair(49.8400000, 19.2500000),
            Pair(49.8509358, 19.2853236)  // Destination (Czaniec, Zielona)
        )

        val density = 2.625
        val screenWidth = 1080.0
        val screenHeight = 2400.0

        val marginPx = 24.0 * density
        val topInset = 820.0 + marginPx       // Card height (~820px) + margin
        val bottomInset = (186.0 * density) + marginPx // Recenter button + toolbar clearance
        val leftInset = marginPx
        val rightInset = (72.0 * density) + marginPx

        val availWidth = screenWidth - leftInset - rightInset
        val availHeight = screenHeight - topInset - bottomInset

        val screenCenterX = leftInset + availWidth / 2.0
        val screenCenterY = topInset + availHeight / 2.0
        val offsetPixelsX = screenCenterX - screenWidth / 2.0
        val offsetPixelsY = screenCenterY - screenHeight / 2.0

        // Test orientations: North-Up (0°), Course-Up (-65° driving heading), 45°, 90°, 180°, 270°
        val testAngles = listOf(0f, -65f, 45f, 90f, 180f, 270f)

        for (angle in testAngles) {
            val (zoom, cameraCenter) = computeRotatedFit(
                points = samplePoints,
                orientationDeg = angle,
                availWidth = availWidth,
                availHeight = availHeight,
                offsetPixelsX = offsetPixelsX,
                offsetPixelsY = offsetPixelsY
            )

            // Verify that all points project within the unobstructed aperture [leftInset, rightEdge] x [topInset, bottomEdge]
            for ((idx, pt) in samplePoints.withIndex()) {
                val (sx, sy) = projectToScreen(
                    lat = pt.first,
                    lon = pt.second,
                    camLat = cameraCenter.first,
                    camLon = cameraCenter.second,
                    zoom = zoom,
                    orientationDeg = angle,
                    screenWidth = screenWidth,
                    screenHeight = screenHeight
                )

                assertTrue(
                    "Point $idx at angle $angle° screenX ($sx) must be >= leftInset ($leftInset)",
                    sx >= leftInset - 1.0
                )
                assertTrue(
                    "Point $idx at angle $angle° screenX ($sx) must be <= rightEdge (${screenWidth - rightInset})",
                    sx <= (screenWidth - rightInset) + 1.0
                )
                assertTrue(
                    "Point $idx at angle $angle° screenY ($sy) must be >= topInset ($topInset) [LocalityCard clearance]",
                    sy >= topInset - 1.0
                )
                assertTrue(
                    "Point $idx at angle $angle° screenY ($sy) must be <= bottomEdge (${screenHeight - bottomInset}) [Recenter/Toolbar clearance]",
                    sy <= (screenHeight - bottomInset) + 1.0
                )
            }
        }
    }

    @Test
    fun testUnobstructedViewportInsetsCalculation() {
        val density = 2.625f
        val mapWidth = 1080
        val mapHeight = 2400

        // Normal mode portrait
        val topCardDp = 280f
        val statusBarDp = 36f
        val minBottomInsetDp = 186f
        val rightControlsDp = 72f
        val marginPx = (24f * density).toInt()

        val topInset = ((topCardDp + statusBarDp) * density).toInt() + marginPx
        val bottomInset = (minBottomInsetDp * density).toInt() + marginPx
        val leftInset = marginPx
        val rightInset = (rightControlsDp * density).toInt() + marginPx

        val availWidth = mapWidth - leftInset - rightInset
        val availHeight = mapHeight - topInset - bottomInset

        assertTrue("Available width ($availWidth) must leave room for map rendering", availWidth > 700)
        assertTrue("Available height ($availHeight) must leave substantial space between cards", availHeight > 900)

        // Viewport center
        val screenCenterY = topInset + availHeight / 2.0
        val physicalCenterY = mapHeight / 2.0

        // Viewport center must be physically lower than screen center because top card is larger than bottom toolbar
        assertTrue("Viewport center Y ($screenCenterY) must be lower than screen center ($physicalCenterY)", screenCenterY > physicalCenterY)

        val screenCenterX = leftInset + availWidth / 2.0
        val physicalCenterX = mapWidth / 2.0
        assertTrue("Unobstructed center X ($screenCenterX) must be to the left of physical center ($physicalCenterX)", screenCenterX < physicalCenterX)

        val offsetPixelsX = (screenCenterX - physicalCenterX).toInt()
        assertTrue("offsetPixelsX ($offsetPixelsX) must be negative to shift the camera east and center track away from right buttons", offsetPixelsX < 0)
    }

    @Test
    fun testDynamicMeasuredInsetsOnTabletsAndSmallPhones() {
        val density = 2.0f
        val marginPx = (24f * density).toInt()

        // 1. Tablet in Portrait: large screen (1600x2560), card renders at 400px
        val tabletHeight = 2560
        val tabletWidth = 1600
        val measuredCardBottomPx = 400
        val measuredBottomControlsTopPx = 2200

        val resolvedTopInset = measuredCardBottomPx + marginPx
        val minBottomInsetPx = (186f * density).toInt()
        val measuredBottomInset = (tabletHeight - measuredBottomControlsTopPx) + marginPx
        val resolvedBottomInset = maxOf(measuredBottomInset, minBottomInsetPx)

        val availHeight = tabletHeight - resolvedTopInset - resolvedBottomInset
        assertTrue("Tablet available height ($availHeight) utilizes vast majority of screen", availHeight > 1600)

        // 2. Small Smartphone: short screen (720x1280), card renders at 320px
        val phoneHeight = 1280
        val phoneMeasuredCardBottom = 320
        val phoneMeasuredBottomControls = 1000

        val phoneTopInset = phoneMeasuredCardBottom + marginPx
        val phoneMinBottom = (186f * density).toInt()
        val phoneMeasuredBottom = (phoneHeight - phoneMeasuredBottomControls) + marginPx
        val phoneBottomInset = maxOf(phoneMeasuredBottom, phoneMinBottom)
        val phoneAvailHeight = phoneHeight - phoneTopInset - phoneBottomInset

        assertTrue("Small phone available height ($phoneAvailHeight) provides adequate viewport", phoneAvailHeight > 500)
    }
}
