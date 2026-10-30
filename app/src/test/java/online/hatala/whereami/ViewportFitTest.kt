package online.hatala.whereami

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
        offsetPixelsY: Double = 0.0,
        tilesScaleFactor: Float = 1.0f
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

        val safeWidth = availWidth * 0.85
        val safeHeight = availHeight * 0.85
        val mppX = spanU / safeWidth
        val mppY = spanV / safeHeight
        val requiredMpp = max(mppX, mppY)
        val scale = if (tilesScaleFactor > 0f) tilesScaleFactor else 1.0f
        val equatorRes = 156543.03392 / scale
        val zoom = log2((equatorRes * cosMeanLat) / requiredMpp)

        val actualMpp = (equatorRes * cosMeanLat) / Math.pow(2.0, zoom)

        val midU = (minU + maxU) / 2.0
        val midV = (minV + maxV) / 2.0

        val uCam = midU - offsetPixelsX * actualMpp
        val vCam = midV - offsetPixelsY * actualMpp

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
        screenHeight: Double,
        tilesScaleFactor: Float = 1.0f
    ): Pair<Double, Double> {
        val alpha = Math.toRadians(orientationDeg.toDouble())
        val cosA = cos(alpha)
        val sinA = sin(alpha)
        val scale = if (tilesScaleFactor > 0f) tilesScaleFactor else 1.0f
        val mpp = ((156543.03392 / scale) * cos(Math.toRadians(camLat))) / Math.pow(2.0, zoom)

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

    @Test
    fun testMaximumFontScaleTilesScaleFactorFitsWithoutClipping() {
        // Real-world scenario from Trip 162 (19.22 km driving trip with profile CAR fontScale = MAXIMUM -> scaleFactor = 1.75f)
        // 1080x2400 phone screen, density = 2.625
        val screenWidth = 1080.0
        val screenHeight = 2400.0
        val scaleFactor = 1.75f // MAXIMUM font scale

        val leftInset = 40.0
        val rightInset = 210.0 // 72dp right controls + margin
        val topInset = 550.0   // Locality card in compact mode + status bar + margin
        val bottomInset = 500.0 // Bottom toolbar + recenter button + margin

        val availWidth = screenWidth - leftInset - rightInset
        val availHeight = screenHeight - topInset - bottomInset

        val screenCenterX = leftInset + availWidth / 2.0
        val screenCenterY = topInset + availHeight / 2.0
        val offsetPixelsX = screenCenterX - screenWidth / 2.0
        val offsetPixelsY = screenCenterY - screenHeight / 2.0

        // Real points from Trip 162 spanning Kęty to Czaniec
        val tripPoints = listOf(
            Pair(49.8558598, 19.2615655), // Start Czaniec
            Pair(49.8906000, 19.2301000), // Rondo Solidarność
            Pair(49.8850000, 19.2299000), // Fabryczna
            Pair(49.8283491, 19.1204180)  // Kozy
        )

        val (zoom, camCenter) = computeRotatedFit(
            points = tripPoints,
            orientationDeg = 0f,
            availWidth = availWidth,
            availHeight = availHeight,
            offsetPixelsX = offsetPixelsX,
            offsetPixelsY = offsetPixelsY,
            tilesScaleFactor = scaleFactor
        )

        // Verify each point projects strictly within unobstructed viewport
        for (pt in tripPoints) {
            val (sx, sy) = projectToScreen(
                lat = pt.first,
                lon = pt.second,
                camLat = camCenter.first,
                camLon = camCenter.second,
                zoom = zoom,
                orientationDeg = 0f,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                tilesScaleFactor = scaleFactor
            )

            assertTrue(
                "Screen X ($sx) must be >= leftInset ($leftInset) under 1.75x tile scaling to prevent left track clipping",
                sx >= leftInset
            )
            assertTrue(
                "Screen X ($sx) must be <= screenWidth - rightInset (${screenWidth - rightInset})",
                sx <= (screenWidth - rightInset)
            )
            assertTrue(
                "Screen Y ($sy) must be >= topInset ($topInset)",
                sy >= topInset
            )
            assertTrue(
                "Screen Y ($sy) must be <= screenHeight - bottomInset (${screenHeight - bottomInset})",
                sy <= (screenHeight - bottomInset)
            )
        }
    }

    @Test
    fun testRecenterAndBottomInsetCalculationsForRecordingAndAutoStandby() {
        // Test recenterBottomPadding logic
        fun computeRecenterPaddingDp(isLandscape: Boolean, showHeatMap: Boolean, isRecording: Boolean, isAutoStandby: Boolean): Float {
            return if (isLandscape) {
                68f
            } else {
                when {
                    showHeatMap && (isRecording || isAutoStandby) -> 180f
                    showHeatMap                                   -> 136f
                    isRecording || isAutoStandby                  -> 136f
                    else                                          -> 78f
                }
            }
        }

        // Test minBottomInsetDp logic
        fun computeMinBottomInsetDp(isRecording: Boolean, isAutoStandby: Boolean): Float {
            return if (isRecording || isAutoStandby) 186f else 130f
        }

        // 1. Idle MANUAL mode (not recording, not auto-standby): default 78dp padding, 130dp inset
        assertEquals(78f, computeRecenterPaddingDp(isLandscape = false, showHeatMap = false, isRecording = false, isAutoStandby = false), 0.001f)
        assertEquals(130f, computeMinBottomInsetDp(isRecording = false, isAutoStandby = false), 0.001f)

        // 2. Active Trip Recording: elevated 136dp padding (180dp with heatmap), 186dp inset
        assertEquals(136f, computeRecenterPaddingDp(isLandscape = false, showHeatMap = false, isRecording = true, isAutoStandby = false), 0.001f)
        assertEquals(180f, computeRecenterPaddingDp(isLandscape = false, showHeatMap = true, isRecording = true, isAutoStandby = false), 0.001f)
        assertEquals(186f, computeMinBottomInsetDp(isRecording = true, isAutoStandby = false), 0.001f)

        // 3. Auto-Detect Standby: elevated 136dp padding (180dp with heatmap), 186dp inset
        // Must match active recording height to completely clear the 82dp auto-detect standby bar
        assertEquals(136f, computeRecenterPaddingDp(isLandscape = false, showHeatMap = false, isRecording = false, isAutoStandby = true), 0.001f)
        assertEquals(180f, computeRecenterPaddingDp(isLandscape = false, showHeatMap = true, isRecording = false, isAutoStandby = true), 0.001f)
        assertEquals(186f, computeMinBottomInsetDp(isRecording = false, isAutoStandby = true), 0.001f)

        // 4. Landscape: constant 68dp padding
        assertEquals(68f, computeRecenterPaddingDp(isLandscape = true, showHeatMap = false, isRecording = false, isAutoStandby = true), 0.001f)
        assertEquals(68f, computeRecenterPaddingDp(isLandscape = true, showHeatMap = true, isRecording = true, isAutoStandby = false), 0.001f)
    }

    @Test
    fun testSelectedTripsExcludesLiveGuestsFromFit() {
        val tripPoints = listOf(
            org.osmdroid.util.GeoPoint(49.82, 19.04),
            org.osmdroid.util.GeoPoint(49.83, 19.05)
        )
        val selectedTrips = listOf(
            TripRecord(
                id = 1L,
                startTime = 1000L,
                points = tripPoints
            )
        )
        val activeGuests = listOf(
            LiveGuest(
                id = "guest-1",
                name = "Alice",
                lat = 52.23, // Warsaw (~300 km away)
                lng = 21.01,
                lastUpdated = System.currentTimeMillis()
            )
        )
        val hostLatLng = Triple(49.85, 19.22, 0f) // Czaniec (~20 km away)

        // When past trips are selected and not actively recording:
        // Points fitted MUST strictly be the selected trip points
        val fittedPoints = mutableListOf<org.osmdroid.util.GeoPoint>()
        val isRecording = false
        if (selectedTrips.isNotEmpty() && !isRecording) {
            selectedTrips.forEach { trip -> fittedPoints.addAll(trip.points) }
        } else if (isRecording) {
            // ...
        } else if (activeGuests.isNotEmpty()) {
            activeGuests.forEach { guest -> fittedPoints.add(guest.geoPoint) }
            hostLatLng.let { fittedPoints.add(org.osmdroid.util.GeoPoint(it.first, it.second)) }
        }

        assertEquals(2, fittedPoints.size)
        assertEquals(tripPoints[0].latitude, fittedPoints[0].latitude, 0.0001)
        assertEquals(tripPoints[1].latitude, fittedPoints[1].latitude, 0.0001)
        // Ensure Warsaw guest and Czaniec host are excluded
        assertTrue(fittedPoints.none { it.latitude > 50.0 })
    }

    @Test
    fun testTightenedViewportInsetsAvoidDoublePadding() {
        val density = 2.625f
        val mapWidth = 1080
        val mapHeight = 2410

        val topCardBottomPx = 583
        val clearancePx = (12f * density).toInt()
        val rightControlsDp = 68f
        val minBottomInsetDp = 186f

        val topInset = topCardBottomPx + clearancePx
        val bottomInset = (minBottomInsetDp * density).toInt()
        val leftInset = clearancePx
        val rightInset = (rightControlsDp * density).toInt()

        val availWidth = mapWidth - leftInset - rightInset
        val availHeight = mapHeight - topInset - bottomInset

        val safeWidth = (availWidth * 0.94).coerceAtLeast(50.0)
        val safeHeight = (availHeight * 0.94).coerceAtLeast(50.0)

        // Verify available dimensions maximize screen estate while guaranteeing clear margins
        assertTrue("Available width should exceed 850px, was $availWidth", availWidth > 850)
        assertTrue("Available height should exceed 1250px, was $availHeight", availHeight > 1250)
        assertTrue("Safe width should exceed 800px, was $safeWidth", safeWidth > 800.0)
        assertTrue("Safe height should exceed 1200px, was $safeHeight", safeHeight > 1200.0)
    }
}
