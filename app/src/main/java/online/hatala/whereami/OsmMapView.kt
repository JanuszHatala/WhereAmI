package online.hatala.whereami

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.widget.Toast
import org.osmdroid.api.IGeoPoint
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.FolderOverlay
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay
import java.util.Locale
import online.hatala.whereami.tracking.AndroidSensorRegistrar
import online.hatala.whereami.tracking.CompassArrow
import online.hatala.whereami.tracking.CompassHeadingSensor
import online.hatala.whereami.tracking.CompassPolicy
import online.hatala.whereami.tracking.GyroHeadingSensor
import online.hatala.whereami.tracking.GyroPolicy
import online.hatala.whereami.tracking.MotionCourse
import online.hatala.whereami.tracking.PencilTrail
import online.hatala.whereami.tracking.RenderPipeline
import online.hatala.whereami.tracking.CourseUpHeading
import online.hatala.whereami.tracking.RotationSlew
import online.hatala.whereami.tracking.TrackState
import online.hatala.whereami.tracking.TrackingBridge
import online.hatala.whereami.tracking.TrackingRuntime
import online.hatala.whereami.tracking.shortestDeltaDeg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

enum class MapOrientationMode {
    NORTH,     // 0° (North at top)
    EAST,      // 90° (East at top)
    SOUTH,     // 180° (South at top)
    WEST,      // 270° (West at top)
    COURSE_UP  // Auto-rotates with GPS heading
}

enum class MapFontScale(val label: String, val scaleFactor: Float) {
    COMPACT("Compact (90%)", 0.90f),
    NORMAL("Standard (100% - Sharpest)", 1.0f),
    LARGE("Medium (125%)", 1.25f),
    EXTRA_LARGE("Large (150%)", 1.50f),
    MAXIMUM("Extra Large (175%)", 1.75f)
}

enum class MapBaseLayer(val label: String) {
    STANDARD("Standard OSM"),
    FREEMAP_OUTDOOR("Freemap Outdoor (PTTK Szlaki & Hi-DPI)"),
    TOPO("Topographic (OpenTopo)"),
    SATELLITE("Satellite (Esri)")
}

private val FreemapOutdoorSource = object : OnlineTileSourceBase(
    "FreemapOutdoor2x",
    0, 19, 512, ".jpeg",
    arrayOf("https://outdoor.tiles.freemap.sk/"),
    "© Freemap Slovakia, OpenStreetMap contributors"
) {
    override fun getTileURLString(pMapTileIndex: Long): String {
        val zoom = org.osmdroid.util.MapTileIndex.getZoom(pMapTileIndex)
        val x = org.osmdroid.util.MapTileIndex.getX(pMapTileIndex)
        val y = org.osmdroid.util.MapTileIndex.getY(pMapTileIndex)
        return "${getBaseUrl()}$zoom/$x/$y@2x"
    }
}

private val OpenTopoMapSource = XYTileSource(
    "OpenTopoMap",
    1, 17, 256, ".png",
    arrayOf(
        "https://a.tile.opentopomap.org/",
        "https://b.tile.opentopomap.org/",
        "https://c.tile.opentopomap.org/"
    ),
    "© OpenTopoMap (CC-BY-SA)"
)

private val EsriSatelliteSource = object : OnlineTileSourceBase(
    "EsriSatellite",
    0, 19, 256, ".jpg",
    arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"),
    "© Esri, Maxar, Earthstar Geographics"
) {
    override fun getTileURLString(pMapTileIndex: Long): String {
        val zoom = org.osmdroid.util.MapTileIndex.getZoom(pMapTileIndex)
        val x = org.osmdroid.util.MapTileIndex.getX(pMapTileIndex)
        val y = org.osmdroid.util.MapTileIndex.getY(pMapTileIndex)
        return getBaseUrl() + "$zoom/$y/$x"
    }
}

private val WaymarkedTrailsHikingSource = XYTileSource(
    "WaymarkedTrailsHiking",
    1, 18, 256, ".png",
    arrayOf(
        "https://tile.waymarkedtrails.org/hiking/"
    ),
    "© Waymarked Trails (CC-BY-SA)"
)

/**
 * Calculates the map center GeoPoint required so that the given target marker
 * appears at the optical center of the visible map aperture (offset from screen center
 * to account for top locality card and bottom navigation bar overlays).
 */
/**
 * Calculates the map camera center so that [target] appears optically centered
 * in the visible aperture between the top LocalityCard and bottom toolbar.
 *
 * CRUCIAL: To guarantee zero horizontal drift (X strictly centered) and invariant
 * vertical positioning under ANY map orientation (North-Up, Course-Up, manual rotate):
 * 1. Screen UP corresponds to geographic bearing: (360° - mapOrientation) % 360°.
 * 2. The camera center is projected along this bearing by (offsetPixelsY * metersPerPixel).
 * 3. In landscape mode or when offsetPixelsY == 0, camera center is exactly target.
 */
fun getOpticalCenter(
    mapView: MapView?,
    target: IGeoPoint,
    offsetPixelsY: Int,
    offsetPixelsX: Int = 0
): GeoPoint {
    if (mapView == null) return GeoPoint(target.latitude, target.longitude)
    return calculateOpticalCenter(
        lat = target.latitude,
        lon = target.longitude,
        zoom = mapView.zoomLevelDouble,
        mapOrientation = mapView.mapOrientation,
        offsetPixelsY = offsetPixelsY,
        offsetPixelsX = offsetPixelsX,
        tilesScaleFactor = mapView.tilesScaleFactor
    )
}

fun calculateOpticalCenter(
    lat: Double,
    lon: Double,
    zoom: Double,
    mapOrientation: Float,
    offsetPixelsY: Int,
    offsetPixelsX: Int = 0,
    tilesScaleFactor: Float = 1.0f
): GeoPoint {
    if (offsetPixelsX == 0 && offsetPixelsY == 0) {
        return GeoPoint(lat, lon)
    }

    val scale = if (tilesScaleFactor > 0f) tilesScaleFactor else 1.0f
    val metersPerPixel = ((156543.03392 / scale) * kotlin.math.cos(Math.toRadians(lat))) / Math.pow(2.0, zoom)
    val uCam = -offsetPixelsX * metersPerPixel
    val vCam = -offsetPixelsY * metersPerPixel

    val alpha = Math.toRadians(mapOrientation.toDouble())
    val cosA = kotlin.math.cos(alpha)
    val sinA = kotlin.math.sin(alpha)

    val dEastCam = uCam * cosA + vCam * sinA
    val dNorthCam = uCam * sinA - vCam * cosA

    val rEarth = 6378137.0
    val camLat = lat + Math.toDegrees(dNorthCam / rEarth)
    val camLon = lon + Math.toDegrees(dEastCam / (rEarth * kotlin.math.cos(Math.toRadians(lat))))

    return GeoPoint(camLat, camLon)
}

fun formatGuestControlBadge(activeCount: Int, inactiveCount: Int): String? {
    val total = activeCount + inactiveCount
    if (total == 0) return null
    return if (inactiveCount == 0) "$activeCount" else "$activeCount/$total"
}

fun extractRecentTrackSection(
    trackPoints: List<GeoPoint>,
    currentPos: GeoPoint?,
    targetDistanceMeters: Double
): List<GeoPoint> {
    val fullPoints = mutableListOf<GeoPoint>()
    fullPoints.addAll(trackPoints)
    if (currentPos != null) {
        val last = fullPoints.lastOrNull()
        if (last == null || last.latitude != currentPos.latitude || last.longitude != currentPos.longitude) {
            fullPoints.add(currentPos)
        }
    }
    if (fullPoints.size <= 1) return fullPoints
    if (targetDistanceMeters <= 0.0) return listOf(fullPoints.last())

    var accumulated = 0.0
    val tail = mutableListOf<GeoPoint>()
    tail.add(fullPoints.last())

    for (i in fullPoints.size - 1 downTo 1) {
        val pEnd = fullPoints[i]
        val pStart = fullPoints[i - 1]
        val segDist = SearchHelper.computeDistanceMeters(
            pStart.latitude, pStart.longitude,
            pEnd.latitude, pEnd.longitude
        ).toDouble()
        if (accumulated + segDist >= targetDistanceMeters) {
            val remain = targetDistanceMeters - accumulated
            val fraction = (remain / segDist).coerceIn(0.0, 1.0)
            val interpLat = pEnd.latitude - (pEnd.latitude - pStart.latitude) * fraction
            val interpLng = pEnd.longitude - (pEnd.longitude - pStart.longitude) * fraction
            tail.add(GeoPoint(interpLat, interpLng))
            break
        } else {
            accumulated += segDist
            tail.add(pStart)
        }
    }

    return tail.reversed()
}

fun calculateViewportInsets(
    mapWidth: Int,
    mapHeight: Int,
    density: Float,
    isLandscape: Boolean,
    isCompact: Boolean,
    measuredTopInsetPx: Int?,
    measuredBottomInsetPx: Int?,
    isRecording: Boolean,
    isAutoStandby: Boolean
): List<Int> {
    return if (isLandscape) {
        val leftCardPx = (mapWidth * 0.36f).toInt()
        val rightControlsPx = (68f * density).toInt()
        val bottomToolbarPx = (64f * density).toInt()
        val marginPx = (12f * density).toInt()
        listOf(
            leftCardPx + marginPx,
            rightControlsPx,
            marginPx,
            bottomToolbarPx
        )
    } else {
        val topCardDp = if (isCompact) 160f else 240f
        val statusBarDp = 36f
        val rightControlsDp = 68f
        val clearancePx = (12f * density).toInt()

        val minBottomInsetDp = if (isRecording || isAutoStandby) 186f else 128f
        val minBottomInsetPx = (minBottomInsetDp * density).toInt()

        val resolvedTopInset = if (measuredTopInsetPx != null && measuredTopInsetPx > 0) {
            measuredTopInsetPx + clearancePx
        } else {
            ((topCardDp + statusBarDp) * density).toInt() + clearancePx
        }

        val measuredBottomInset = if (measuredBottomInsetPx != null && measuredBottomInsetPx > 0 && mapHeight > measuredBottomInsetPx) {
            (mapHeight - measuredBottomInsetPx) + clearancePx
        } else {
            minBottomInsetPx
        }
        val resolvedBottomInset = maxOf(measuredBottomInset, minBottomInsetPx)

        listOf(
            clearancePx,
            (rightControlsDp * density).toInt(),
            resolvedTopInset,
            resolvedBottomInset
        )
    }
}

fun isPointInUnobstructedViewport(
    point: GeoPoint,
    mapCenter: GeoPoint,
    zoomLevel: Double,
    mapOrientationDeg: Float,
    tilesScaleFactor: Float,
    mapWidth: Int,
    mapHeight: Int,
    leftInset: Int,
    rightInset: Int,
    topInset: Int,
    bottomInset: Int
): Boolean {
    if (mapWidth <= 0 || mapHeight <= 0) return false
    val scale = if (tilesScaleFactor > 0f) tilesScaleFactor else 1.0f
    val metersPerPixel = ((156543.03392 / scale) * kotlin.math.cos(Math.toRadians(mapCenter.latitude))) / Math.pow(2.0, zoomLevel)
    if (metersPerPixel <= 0.0) return false

    val rEarth = 6378137.0
    val dNorth = Math.toRadians(point.latitude - mapCenter.latitude) * rEarth
    val dEast = Math.toRadians(point.longitude - mapCenter.longitude) * rEarth * kotlin.math.cos(Math.toRadians(mapCenter.latitude))

    val alpha = Math.toRadians(mapOrientationDeg.toDouble())
    val cosA = kotlin.math.cos(alpha)
    val sinA = kotlin.math.sin(alpha)

    // Projected to screen axes relative to physical screen center
    val uCam = dEast * cosA - dNorth * sinA
    val vCam = dEast * sinA + dNorth * cosA

    val screenX = (mapWidth / 2.0) + (uCam / metersPerPixel)
    val screenY = (mapHeight / 2.0) - (vCam / metersPerPixel)

    val minX = leftInset.toDouble()
    val maxX = (mapWidth - rightInset).toDouble()
    val minY = topInset.toDouble()
    val maxY = (mapHeight - bottomInset).toDouble()

    return screenX in minX..maxX && screenY in minY..maxY
}

fun shouldTriggerAutoRecenter(
    autoRecenterEnabled: Boolean,
    isAppInForeground: Boolean,
    isFollowing: Boolean,
    destinationPoint: GeoPoint?,
    selectedSavedPlace: SavedPlace?,
    isRecording: Boolean,
    selectedTripsEmpty: Boolean,
    showHeatMap: Boolean,
    isUserTouchingMap: Boolean,
    isCursorInsideViewport: Boolean,
    nowElapsedMs: Long,
    lastInteractionElapsedMs: Long,
    outsideViewportSinceElapsedMs: Long,
    timeoutMs: Long
): Boolean {
    if (!autoRecenterEnabled || !isAppInForeground) return false
    if (isFollowing) return false
    if (destinationPoint != null || selectedSavedPlace != null) return false
    if (!isRecording && (!selectedTripsEmpty || showHeatMap)) return false
    if (isUserTouchingMap) return false
    if (lastInteractionElapsedMs <= 0L || (nowElapsedMs - lastInteractionElapsedMs) < timeoutMs) return false
    return true
}

/**
 * Fits a set of GeoPoints comfortably into the unobstructed viewport area between
 * the top LocalityCard and bottom controls, taking map orientation (rotation) and screen insets into account.
 */
fun fitPointsToUnobstructedViewport(
    map: MapView,
    points: List<GeoPoint>,
    density: Float,
    isLandscape: Boolean,
    isCompact: Boolean,
    measuredTopInsetPx: Int? = null,
    measuredBottomInsetPx: Int? = null,
    isRecording: Boolean = false,
    isAutoStandby: Boolean = false,
    onZoomCalculated: ((Double) -> Unit)? = null
) {
    if (points.isEmpty()) return

    val mapWidth = map.width.takeIf { it > 0 } ?: return
    val mapHeight = map.height.takeIf { it > 0 } ?: return
    val orientationDeg = map.mapOrientation
    val alpha = Math.toRadians(orientationDeg.toDouble())
    val cosA = kotlin.math.cos(alpha)
    val sinA = kotlin.math.sin(alpha)

    // 1. Calculate unobstructed viewport insets (in pixels)
    val (leftInset, rightInset, topInset, bottomInset) = calculateViewportInsets(
        mapWidth = mapWidth,
        mapHeight = mapHeight,
        density = density,
        isLandscape = isLandscape,
        isCompact = isCompact,
        measuredTopInsetPx = measuredTopInsetPx,
        measuredBottomInsetPx = measuredBottomInsetPx,
        isRecording = isRecording,
        isAutoStandby = isAutoStandby
    )

    val availWidth = (mapWidth - leftInset - rightInset).coerceAtLeast(100)
    val availHeight = (mapHeight - topInset - bottomInset).coerceAtLeast(100)

    // Center of unobstructed viewport on screen (in screen pixels)
    val screenCenterX = leftInset + availWidth / 2.0
    val screenCenterY = topInset + availHeight / 2.0

    // Offset of the viewport center relative to physical screen center
    // Positive offsetPixelsX: screenCenterX is to the right of physical center.
    // Negative offsetPixelsX: screenCenterX is to the left of physical center (due to right-side toolbar insets).
    val offsetPixelsX = (screenCenterX - mapWidth / 2.0).toInt()
    val offsetPixelsY = (screenCenterY - mapHeight / 2.0).toInt()

    if (points.size == 1) {
        val target = points.first()
        val cameraCenter = calculateOpticalCenter(
            lat = target.latitude,
            lon = target.longitude,
            zoom = 16.5,
            mapOrientation = orientationDeg,
            offsetPixelsY = offsetPixelsY,
            offsetPixelsX = offsetPixelsX,
            tilesScaleFactor = map.tilesScaleFactor
        )
        map.controller.stopAnimation(false)
        map.controller.setZoom(16.5)
        map.controller.setCenter(cameraCenter)
        onZoomCalculated?.invoke(16.5)
        map.invalidate()
        return
    }

    // 2. Project points into screen-aligned rotated coordinate frame (meters)
    val rEarth = 6378137.0
    val meanLatDeg = points.map { it.latitude }.average()
    val meanLonDeg = points.map { it.longitude }.average()
    val meanLat = Math.toRadians(meanLatDeg)
    val meanLon = Math.toRadians(meanLonDeg)
    val cosMeanLat = kotlin.math.cos(meanLat)

    var minU = Double.MAX_VALUE
    var maxU = -Double.MAX_VALUE
    var minV = Double.MAX_VALUE
    var maxV = -Double.MAX_VALUE

    for (p in points) {
        val latRad = Math.toRadians(p.latitude)
        val lonRad = Math.toRadians(p.longitude)
        val dEast = (lonRad - meanLon) * rEarth * cosMeanLat
        val dNorth = (latRad - meanLat) * rEarth
        // Rotate into screen-aligned coordinates:
        // u is aligned with screen X (right positive)
        // v is aligned with screen Y (down positive)
        val u = dEast * cosA + dNorth * sinA
        val v = dEast * sinA - dNorth * cosA

        if (u < minU) minU = u
        if (u > maxU) maxU = u
        if (v < minV) minV = v
        if (v > maxV) maxV = v
    }

    // Minimum span of 60m to avoid over-zooming on stationary points
    val spanU = (maxU - minU).coerceAtLeast(60.0)
    val spanV = (maxV - minV).coerceAtLeast(60.0)

    // 3. Compute zoom fitting both rotated width and height into unobstructed window with 6% safety margin
    val scaleFactor = map.tilesScaleFactor.takeIf { it > 0f } ?: 1.0f
    val equatorRes = 156543.03392 / scaleFactor
    val safeWidth = (availWidth * 0.94).coerceAtLeast(50.0)
    val safeHeight = (availHeight * 0.94).coerceAtLeast(50.0)
    val metersPerPixelX = spanU / safeWidth
    val metersPerPixelY = spanV / safeHeight
    val requiredMpp = kotlin.math.max(metersPerPixelX, metersPerPixelY)

    val calculatedZoom = (kotlin.math.log2((equatorRes * cosMeanLat) / requiredMpp))
        .coerceIn(map.minZoomLevel, 18.5)

    val actualMpp = (equatorRes * cosMeanLat) / Math.pow(2.0, calculatedZoom)

    // 4. Center of rotated bounding box in (u, v) coordinates
    val midU = (minU + maxU) / 2.0
    val midV = (minV + maxV) / 2.0

    // Shift camera in (u, v) coordinates so midU, midV lands at (screenCenterX, screenCenterY)
    val uCam = midU - offsetPixelsX * actualMpp
    val vCam = midV - offsetPixelsY * actualMpp

    // Un-rotate camera displacement back to East/North tangent coordinates
    val dEastCam = uCam * cosA + vCam * sinA
    val dNorthCam = uCam * sinA - vCam * cosA

    val camLat = Math.toDegrees(meanLat + dNorthCam / rEarth)
    val camLon = Math.toDegrees(meanLon + dEastCam / (rEarth * cosMeanLat))
    val cameraCenter = GeoPoint(camLat, camLon)

    map.controller.stopAnimation(false)
    map.controller.setZoom(calculatedZoom)
    map.controller.setCenter(cameraCenter)
    onZoomCalculated?.invoke(calculatedZoom)
    map.invalidate()
}

/**
 * Places overlays (such as trip polylines) strictly above background and hiking tile overlays,
 * but below interactive pins and position tracking markers.
 */
private fun addOverlayAboveTiles(map: MapView, overlay: org.osmdroid.views.overlay.Overlay) {
    if (map.overlays.contains(overlay)) {
        map.overlays.remove(overlay)
    }
    val markerIdx = map.overlays.indexOfFirst { it is Marker }
    if (markerIdx >= 0) {
        map.overlays.add(markerIdx, overlay)
    } else {
        map.overlays.add(overlay)
    }
}

/**
 * Ensures the altitude badge overlay is positioned at the very top of all visual map layers
 * (above polylines, accuracy circles, markers, pins, and badges).
 */
private fun bringAltitudeOverlayToFront(map: MapView, altitudeOverlay: AltitudeCursorBadgeOverlay) {
    if (map.overlays.lastOrNull() === altitudeOverlay) return
    map.overlays.remove(altitudeOverlay)
    map.overlays.add(altitudeOverlay)
}

/**
 * Inserts a guest marker below the host's own position tracking marker and altitude overlay,
 * ensuring the host marker and altitude badge are always stably positioned on top without z-fighting.
 */
private fun insertGuestMarkerBelowHostOverlays(
    map: MapView,
    gm: Marker,
    marker: Marker?,
    altitudeOverlay: AltitudeCursorBadgeOverlay
) {
    if (map.overlays.contains(gm)) {
        map.overlays.remove(gm)
    }
    val mIdx = marker?.let { map.overlays.indexOf(it) } ?: -1
    val altIdx = map.overlays.indexOf(altitudeOverlay)
    val targetIdx = when {
        mIdx >= 0 -> mIdx
        altIdx >= 0 -> altIdx
        else -> -1
    }
    if (targetIdx >= 0) {
        map.overlays.add(targetIdx, gm)
    } else {
        map.overlays.add(gm)
    }
}

/**
 * Composable OSM map panel with:
 * - Position dot / heading arrow.
 * - Red live trip polyline drawing (#EF4444).
 * - Fit-to-track action.
 * - Zoom in / Zoom out, Recenter, Compass / Course-Up auto-rotation.
 * - Search destination pin.
 * - Profile-aware look-ahead map centering.
 */
@Composable
fun OsmMapView(
    latLng: Triple<Double, Double, Float?>? = null, // (lat, lng, bearing-or-null)
    locationFix: LocationFix? = null,
    altitudeM: Double? = null,
    isAltitudeFallback: Boolean = false,
    altitudeSourceMode: AltitudeSourceMode = AltitudeSourceMode.DEM,
    onAltitudeSourceModeChange: ((AltitudeSourceMode) -> Unit)? = null,
    trackPoints: List<GeoPoint> = emptyList(),
    rawTrackPoints: List<GeoPoint> = emptyList(),
    selectedTrips: List<TripRecord> = emptyList(),
    savedPlaces: List<SavedPlace> = emptyList(),
    boundaryPoints: List<GeoPoint>? = null,
    pinnedBoundaryPoints: List<GeoPoint>? = null,
    showBorders: Boolean = false,
    onToggleBorders: (() -> Unit)? = null,
    heatMapTracks: List<List<GeoPoint>> = emptyList(),
    showHeatMap: Boolean = false,
    onToggleHeatMap: (() -> Unit)? = null,
    onOpenHeatMapSettings: (() -> Unit)? = null,
    heatMapFilterActive: Boolean = false,
    heatMapTitle: String = "Heat Map",
    heatMapConsolidate: Boolean = true,
    heatMapMinVisits: Int = 1,
    fitTrackTrigger: Long = 0L,
    fitHeatMapTrigger: Long = 0L,
    fitPlacesTrigger: Long = 0L,
    destinationPoint: GeoPoint? = null,
    destinationAltitude: Double? = null,
    destinationAltitudeLoading: Boolean = false,
    selectedSavedPlace: SavedPlace? = null,
    onDestinationMarkerClick: (() -> Unit)? = null,
    onSavedPlaceClick: ((SavedPlace) -> Unit)? = null,
    onClearDestination: (() -> Unit)? = null,
    onMapClick: ((GeoPoint) -> Unit)? = null,
    activeGuests: List<LiveGuest> = emptyList(),
    showGuestDistanceBadge: Boolean = true,
    onGuestClick: ((LiveGuest) -> Unit)? = null,
    isLiveSharingActive: Boolean = false,
    onToggleShowGuestDistanceBadge: ((Boolean) -> Unit)? = null,
    onOpenLiveVisitorsDetails: (() -> Unit)? = null,
    activityProfile: ActivityProfile = ActivityProfile.CAR,
    isCompact: Boolean = true,
    opticalOffsetY: Int? = null,
    opticalOffsetX: Int? = null,
    measuredTopInsetPx: Int? = null,
    measuredBottomInsetPx: Int? = null,
    isRecording: Boolean = false,
    isAutoStandby: Boolean = false,
    recenterTrigger: Long = 0L,
    onFollowingChanged: ((Boolean) -> Unit)? = null,
    pauses: List<TripPause> = emptyList(),
    orientationMode: MapOrientationMode = MapOrientationMode.NORTH,
    onOrientationModeChange: ((MapOrientationMode) -> Unit)? = null,
    onInstantShare: (() -> Unit)? = null,
    onClearSelectedTrips: (() -> Unit)? = null,
    onOpenCacheManager: (() -> Unit)? = null,
    showMapSettingsExternal: Boolean = false,
    onDismissMapSettings: (() -> Unit)? = null,
    outdoorAreaBoundaryPoints: List<GeoPoint>? = null,
    outdoorAreaKind: OutdoorAreaKind? = null,
    outdoorAreaName: String? = null,
    outdoorAreaCenter: GeoPoint? = null,
    fitOutdoorAreaTrigger: Long = 0L,
    searchRoutePolylines: List<List<GeoPoint>>? = null,
    onTrailSelected: ((TrailVectorSegment) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentOnMapClick by rememberUpdatedState(onMapClick)
    val currentOnOrientationChange by rememberUpdatedState(onOrientationModeChange)
    val currentOnGuestClick by rememberUpdatedState(onGuestClick)

    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val density = androidx.compose.ui.platform.LocalDensity.current.density

    // Deduplicate all active session guests with valid locations for status counters
    val dedupedSessionGuests = remember(activeGuests) {
        activeGuests.groupBy { it.name.trim().lowercase(Locale.ROOT) }
            .values
            .mapNotNull { group -> group.maxByOrNull { it.lastUpdated } }
    }
    val sessionActiveGuestCount = remember(dedupedSessionGuests) {
        dedupedSessionGuests.count { !it.isInactive }
    }
    val sessionInactiveGuestCount = remember(dedupedSessionGuests) {
        dedupedSessionGuests.count { it.isInactive }
    }

    var autoRecenterEnabled by remember {
        mutableStateOf(MapProfileConfigHelper.getAutoRecenterEnabled(context))
    }
    var autoRecenterTimeoutSec by remember {
        mutableStateOf(MapProfileConfigHelper.getAutoRecenterTimeoutSec(context))
    }
    var isUserTouchingMap by remember { mutableStateOf(false) }
    var lastMapInteractionElapsedMs by remember {
        mutableStateOf(android.os.SystemClock.elapsedRealtime())
    }
    var cursorOutsideViewportSinceMs by remember { mutableStateOf(0L) }

    // Viewport-aware optical offsets (supporting both vertical and horizontal camera centering)
    val effectiveOpticalOffsetX = remember(opticalOffsetX, isLandscape) {
        if (!isLandscape) 0
        else opticalOffsetX ?: 0
    }
    val effectiveOpticalOffsetY = remember(opticalOffsetY, isLandscape, isCompact, density) {
        opticalOffsetY ?: run {
            if (isLandscape) 0
            else {
                val topObstructionDp = if (isCompact) 150f else 280f
                val bottomObstructionDp = 200f
                val offsetDp = ((topObstructionDp - bottomObstructionDp) / 2f) - 24f
                (offsetDp * density).toInt()
            }
        }
    }

    remember(context) {
        Configuration.getInstance().apply {
            userAgentValue = "WhereAmI/2.0 (Android; https://github.com/janush74/WhereAmI)"
            tileFileSystemThreads = 8.toShort()
            tileDownloadThreads = 8.toShort()
            tileFileSystemMaxQueueSize = 160.toShort()
            tileDownloadMaxQueueSize = 160.toShort()
            // 600 MB persistent offline tile disk cache
            tileFileSystemCacheMaxBytes = 600L * 1024 * 1024
            tileFileSystemCacheTrimBytes = 500L * 1024 * 1024
            cacheMapTileCount = 350.toShort()
        }
        true
    }

    val minZoom = 3.0
    val maxZoom = 19.85
    var currentZoom by remember { mutableStateOf(16.0) }
    var currentMapOrientation by remember { mutableStateOf(0f) }
    var viewportScrollBucket by remember { mutableStateOf(0) }

    var mapView by remember { mutableStateOf<MapView?>(null) }
    var marker by remember { mutableStateOf<Marker?>(null) }
    var destMarker by remember { mutableStateOf<Marker?>(null) }
    var showFilteredPolyline by remember {
        mutableStateOf(MapProfileConfigHelper.getShowFilteredPolyline(context))
    }
    var showRawPolyline by remember {
        mutableStateOf(MapProfileConfigHelper.getShowRawPolyline(context))
    }
    var polyline by remember { mutableStateOf<Polyline?>(null) }
    var rawPolyline by remember { mutableStateOf<Polyline?>(null) }
    val liveLine = remember { LiveTrackLine() }
    var boundaryPolygon by remember { mutableStateOf<org.osmdroid.views.overlay.Polygon?>(null) }
    var pinnedBoundaryPolygon by remember { mutableStateOf<org.osmdroid.views.overlay.Polygon?>(null) }
    var selectedTripPolylines by remember { mutableStateOf<List<Polyline>>(emptyList()) }
    var selectedTripRawPolylines by remember { mutableStateOf<List<Polyline>>(emptyList()) }
    var heatMapPolylines by remember { mutableStateOf<List<Polyline>>(emptyList()) }
    val guestMarkersMap = remember { mutableMapOf<String, Marker>() }
    val guestMarkerBadgeKeys = remember { mutableMapOf<String, String>() }
    var isFollowing by remember { mutableStateOf(true) }
    val headingMarkerIcon = remember(context) { makeMarkerIcon(context, true) }
    val dotMarkerIcon = remember(context) { makeMarkerIcon(context, false) }
    val accuracyOverlay = remember(context) { AccuracyCircleOverlay(context) }
    val prefs = remember(context) {
        context.getSharedPreferences("where_am_i_map_prefs", Context.MODE_PRIVATE)
    }

    val initialProfileConfig = remember(activityProfile) {
        MapProfileConfigHelper.getConfig(context, activityProfile)
    }

    var baseLayer by remember(activityProfile) {
        mutableStateOf(initialProfileConfig.baseLayer)
    }

    var showHikingOverlay by remember(activityProfile) {
        mutableStateOf(initialProfileConfig.showHikingOverlay)
    }

    var showOutdoorAreasOverlay by remember(activityProfile) {
        mutableStateOf(initialProfileConfig.showOutdoorAreasOverlay)
    }

    var trailThickness by remember(activityProfile) {
        mutableStateOf(initialProfileConfig.trailThickness)
    }

    var fontScale by remember(activityProfile) {
        mutableStateOf(initialProfileConfig.fontScale)
    }

    var trackColorHex by remember(activityProfile) {
        mutableStateOf(initialProfileConfig.trackColorHex)
    }

    var vectorTrailOverlayRef by remember { mutableStateOf<VectorTrailOverlay?>(null) }
    var outdoorBoundaryPolygon by remember { mutableStateOf<org.osmdroid.views.overlay.Polygon?>(null) }
    var searchRouteOverlays by remember { mutableStateOf<List<Polyline>>(emptyList()) }

    var prePinZoom by remember { mutableStateOf<Double?>(null) }
    var prePinCenter by remember { mutableStateOf<GeoPoint?>(null) }

    val altitudeOverlay = remember(context) { AltitudeCursorBadgeOverlay(context) }
    val guestLeaderLinesOverlay = remember(context) { GuestLeaderLinesOverlay(context) }
    var showAltitudeOnMap by remember { mutableStateOf(MapProfileConfigHelper.getShowAltitudeOnMap(context)) }
    var showInactiveGuests by remember { mutableStateOf(MapProfileConfigHelper.getShowInactiveGuests(context)) }
    val visibleGuests = remember(activeGuests, showInactiveGuests) {
        val filtered = if (showInactiveGuests) activeGuests else activeGuests.filter { !it.isInactive }
        // Deduplicate by guest name, retaining only the single latest position per unique person
        filtered.groupBy { it.name.trim().lowercase(Locale.ROOT) }
            .values
            .mapNotNull { group -> group.maxByOrNull { it.lastUpdated } }
    }
    var currentSpiderfyPositions by remember { mutableStateOf<Map<String, GeoPoint>>(emptyMap()) }
    var lastAppliedHeadingState by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(
        showAltitudeOnMap,
        mapView,
        destinationPoint,
        destinationAltitude,
        destinationAltitudeLoading,
        savedPlaces,
        altitudeM,
        isAltitudeFallback,
        altitudeSourceMode,
        fontScale,
        locationFix?.speedMs
    ) {
        val map = mapView ?: return@LaunchedEffect
        bringAltitudeOverlayToFront(map, altitudeOverlay)
        val currentSpeed = (locationFix?.speedMs ?: 0f) * 3.6f
        altitudeOverlay.isEnabled = showAltitudeOnMap
        altitudeOverlay.updateData(
            // Position is owned by the frame loop (rendered pose); a raw-fix position here would make the
            // badge jump between the two on every altitude/speed change.
            geoPoint = marker?.position
                ?: locationFix?.let { GeoPoint(it.lat, it.lng) } ?: latLng?.let { GeoPoint(it.first, it.second) },
            altitude = altitudeM,
            isGpsFallback = isAltitudeFallback,
            fontScaleFactor = fontScale.scaleFactor,
            currentSpeedKmh = currentSpeed,
            isGpsMode = (altitudeSourceMode == AltitudeSourceMode.GPS),
            savedPlaces = savedPlaces,
            destinationPoint = destinationPoint,
            guestPoints = visibleGuests.map { currentSpiderfyPositions[it.id] ?: it.geoPoint }
        )
        map.invalidate()
    }

    var showSettingsDialog by remember { mutableStateOf(false) }
    LaunchedEffect(showMapSettingsExternal) {
        if (showMapSettingsExternal) {
            showSettingsDialog = true
        }
    }
    var hikingOverlayRef by remember { mutableStateOf<TilesOverlay?>(null) }
    var hikingProviderRef by remember { mutableStateOf<MapTileProviderBasic?>(null) }
    val currentFix = remember(locationFix, latLng) {
        locationFix ?: latLng?.let {
            LocationFix(it.first, it.second, it.third, 0f, System.currentTimeMillis())
        }
    }
    // Tracking Engine v2 render side: predicts at frame time from the filtered kinematic state,
    // blends corrections critically damped and never extrapolates along a lagged raw bearing.
    val renderPipeline = remember { RenderPipeline(TrackingBridge.profileFor(activityProfile), TrackingRuntime.gyro) }
    val courseSlew = remember(activityProfile) { RotationSlew.forProfile(TrackingBridge.profileFor(activityProfile)) }
    LaunchedEffect(activityProfile) { renderPipeline.profile = TrackingBridge.profileFor(activityProfile) }
    // The frame loop is a long-lived coroutine: it must read the *current* fix, not the one captured at launch.
    val latestFix by rememberUpdatedState(currentFix)
    val latestRecording by rememberUpdatedState(isRecording)
    val latestRawTrackPoints by rememberUpdatedState(rawTrackPoints)
    val latestShowFilteredPolyline by rememberUpdatedState(showFilteredPolyline)
    val latestShowRawPolyline by rememberUpdatedState(showRawPolyline)
    val latestDestinationAltitude by rememberUpdatedState(destinationAltitude)
    val latestDestinationAltitudeLoading by rememberUpdatedState(destinationAltitudeLoading)
    val latestDestinationPoint by rememberUpdatedState(destinationPoint)
    val latestAltitudeM by rememberUpdatedState(altitudeM)
    val latestIsAltitudeFallback by rememberUpdatedState(isAltitudeFallback)
    val latestAltitudeSourceMode by rememberUpdatedState(altitudeSourceMode)
    val latestSavedPlaces by rememberUpdatedState(savedPlaces)
    val latestShowAltitudeOnMap by rememberUpdatedState(showAltitudeOnMap)
    val latestFontScale by rememberUpdatedState(fontScale)
    val latestVisibleGuests by rememberUpdatedState(visibleGuests)
    val latestSpiderfyPositions by rememberUpdatedState(currentSpiderfyPositions)
    val tripManagerRef = remember(context) { TripManager.getInstance(context) }
    val pencilTrail = remember { PencilTrail() }
    val motionCourse = remember { MotionCourse() }
    var committedThroughMs by remember { mutableStateOf(-1L) }
    var lastFedStateMs by remember { mutableStateOf(Long.MIN_VALUE) }
    val effectiveLatLng = remember(currentFix) {
        currentFix?.let { Triple(it.lat, it.lng, it.bearing) }
    }
    var lastFrozenBearing by remember { mutableStateOf(currentFix?.bearing) }


    // Dynamic Base Layer Switch
    LaunchedEffect(baseLayer, mapView) {
        val map = mapView ?: return@LaunchedEffect
        val tileSource = when (baseLayer) {
            MapBaseLayer.STANDARD -> TileSourceFactory.MAPNIK
            MapBaseLayer.FREEMAP_OUTDOOR -> FreemapOutdoorSource
            MapBaseLayer.TOPO -> OpenTopoMapSource
            MapBaseLayer.SATELLITE -> EsriSatelliteSource
        }
        val layerMaxZoom = if (baseLayer == MapBaseLayer.TOPO) 17.85 else maxZoom
        map.maxZoomLevel = layerMaxZoom
        if (map.zoomLevelDouble > layerMaxZoom) {
            map.controller.setZoom(layerMaxZoom)
            currentZoom = layerMaxZoom
        }
        map.setTileSource(tileSource)
        MapProfileConfigHelper.setBaseLayer(context, activityProfile, baseLayer)
        map.invalidate()
    }

    // Sync Profile-Specific Map Settings when ActivityProfile switches
    LaunchedEffect(activityProfile) {
        val config = MapProfileConfigHelper.getConfig(context, activityProfile)
        baseLayer = config.baseLayer
        showHikingOverlay = config.showHikingOverlay
        showOutdoorAreasOverlay = config.showOutdoorAreasOverlay
        trailThickness = config.trailThickness
        fontScale = config.fontScale
        trackColorHex = config.trackColorHex
        if (orientationMode != config.orientationMode) {
            onOrientationModeChange?.invoke(config.orientationMode)
        }
    }

    LaunchedEffect(trackColorHex, mapView) {
        val map = mapView ?: return@LaunchedEffect
        val trackColor = try { Color.parseColor(trackColorHex) } catch (_: Exception) { Color.parseColor("#EF4444") }
        polyline?.outlinePaint?.color = trackColor
        map.invalidate()
    }

    // Dynamic Label / Font Scale
    LaunchedEffect(fontScale, mapView) {
        val map = mapView ?: return@LaunchedEffect
        map.tilesScaleFactor = fontScale.scaleFactor
        MapProfileConfigHelper.setFontScale(context, activityProfile, fontScale)
        map.invalidate()
    }

    // Dynamic Hiking / Tourist Trail Overlay
    LaunchedEffect(showHikingOverlay, trailThickness, mapView) {
        val map = mapView ?: return@LaunchedEffect
        MapProfileConfigHelper.setHikingOverlay(context, activityProfile, showHikingOverlay)
        MapProfileConfigHelper.setTrailThickness(context, activityProfile, trailThickness)
        if (showHikingOverlay) {
            if (hikingOverlayRef == null) {
                val provider = MapTileProviderBasic(context, WaymarkedTrailsHikingSource)
                val overlay = TilesOverlay(provider, context).apply {
                    loadingBackgroundColor = Color.TRANSPARENT
                    loadingLineColor = Color.TRANSPARENT
                }
                hikingProviderRef = provider
                hikingOverlayRef = overlay
            }
            hikingOverlayRef?.let {
                if (!map.overlays.contains(it)) {
                    val insertIdx = if (map.overlays.size > 1) 1 else 0
                    map.overlays.add(insertIdx, it)
                }
            }

            if (vectorTrailOverlayRef == null) {
                val vOverlay = VectorTrailOverlay(
                    context = context,
                    scope = scope,
                    trailThickness = trailThickness,
                    onTrailSelected = onTrailSelected
                )
                vectorTrailOverlayRef = vOverlay
            }
            vectorTrailOverlayRef?.let {
                it.trailThickness = trailThickness
                it.onTrailSelected = onTrailSelected
                if (!map.overlays.contains(it)) {
                    map.overlays.add(it)
                }
            }
        } else {
            hikingOverlayRef?.let { map.overlays.remove(it) }
            vectorTrailOverlayRef?.let { map.overlays.remove(it) }
        }
        map.invalidate()
    }

    // Direct orientation for fixed modes; COURSE_UP is smoothly slewed at 35 FPS in the continuous frame loop
    LaunchedEffect(orientationMode) {
        val map = mapView ?: return@LaunchedEffect
        when (orientationMode) {
            MapOrientationMode.NORTH -> map.mapOrientation = 0f
            MapOrientationMode.EAST -> map.mapOrientation = 270f
            MapOrientationMode.SOUTH -> map.mapOrientation = 180f
            MapOrientationMode.WEST -> map.mapOrientation = 90f
            MapOrientationMode.COURSE_UP -> { /* smoothly handled at 35 FPS in frame loop */ }
        }
        currentMapOrientation = map.mapOrientation
        map.invalidate()
    }

    // Filtered states reach the render pipeline from TrackingRuntime.states in the frame loop (every state,
    // in order); a fix without one (cached startup position) is fed here so the cursor can snap to it.
    LaunchedEffect(currentFix) {
        val fix = currentFix ?: return@LaunchedEffect
        if (fix.track == null) renderPipeline.onState(syntheticTrackState(fix))
        if (fix.bearing != null && lastFrozenBearing == null) {
            lastFrozenBearing = fix.bearing
        }
    }

    val isAppInForeground by remember { AppStateManager.getInstance(context).isAppInForegroundFlow }.collectAsState()

    // Gyro yaw rate: foreground-only, only while moving and following/recording (see GyroPolicy).
    val gyroSensor = remember { GyroHeadingSensor(AndroidSensorRegistrar(context.applicationContext)) }
    var movingForGyro by remember { mutableStateOf(false) }
    LaunchedEffect(currentFix?.speedMs) {
        movingForGyro = GyroPolicy.isMovingForGyro(currentFix?.speedMs ?: 0f, movingForGyro)
    }
    val gyroWanted = GyroPolicy.shouldRun(isAppInForeground, movingForGyro, isFollowing || isRecording)
    LaunchedEffect(gyroWanted) {
        if (gyroWanted) gyroSensor.start() else gyroSensor.stop()
    }
    DisposableEffect(gyroSensor) { onDispose { gyroSensor.stop() } }

    // Compass arrow: foreground, on foot, moving and following/recording only (see CompassPolicy); the setting
    // is on by default. It drives the arrow only, never position, line or recording.
    var compassArrowEnabled by remember { mutableStateOf(MapProfileConfigHelper.getCompassArrow(context)) }
    val compassSensor = remember { CompassHeadingSensor(AndroidSensorRegistrar(context.applicationContext)) }
    val compassArrow = remember { CompassArrow() }
    val onFootProfile = TrackingBridge.profileFor(activityProfile).maxSpeedMps <= RenderPipeline.ON_FOOT_MAX_SPEED_MPS
    val onFootNow = rememberUpdatedState(onFootProfile)
    var movingForCompass by remember { mutableStateOf(false) }
    LaunchedEffect(currentFix?.speedMs) {
        movingForCompass = CompassPolicy.isMovingForCompass(currentFix?.speedMs ?: 0f, movingForCompass)
    }
    val compassWanted = CompassPolicy.shouldRun(
        compassArrowEnabled, onFootProfile, isAppInForeground, movingForCompass, isFollowing || isRecording
    )
    LaunchedEffect(compassWanted) {
        if (compassWanted) compassSensor.start() else {
            compassSensor.stop()
            compassArrow.reset()
        }
    }
    // Magnetic declination (true north): changes by degrees per hundreds of km, so a coarse key suffices.
    val declinationKey = currentFix?.let { (it.lat * 5).toInt() to (it.lng * 5).toInt() }
    LaunchedEffect(declinationKey) {
        val f = currentFix ?: return@LaunchedEffect
        compassSensor.declinationDeg = android.hardware.GeomagneticField(
            f.lat.toFloat(), f.lng.toFloat(), 0f, System.currentTimeMillis()
        ).declination.toDouble()
    }
    DisposableEffect(compassSensor) { onDispose { compassSensor.stop() } }

    // Auto-Recenter: automatically re-engage tracking if the map was panned away and left untouched
    LaunchedEffect(
        autoRecenterEnabled,
        autoRecenterTimeoutSec,
        isFollowing,
        destinationPoint,
        selectedSavedPlace,
        isRecording,
        selectedTrips.size,
        showHeatMap,
        isAppInForeground
    ) {
        if (!autoRecenterEnabled || !isAppInForeground) {
            cursorOutsideViewportSinceMs = 0L
            return@LaunchedEffect
        }
        while (isActive) {
            kotlinx.coroutines.delay(1_000L)
            val map = mapView ?: continue
            val nowMs = android.os.SystemClock.elapsedRealtime()

            val effectivePoint = marker?.position
                ?: locationFix?.let { GeoPoint(it.lat, it.lng) }
                ?: latLng?.let { GeoPoint(it.first, it.second) }

            if (effectivePoint == null) {
                cursorOutsideViewportSinceMs = 0L
                continue
            }

            val insets = calculateViewportInsets(
                mapWidth = map.width,
                mapHeight = map.height,
                density = density,
                isLandscape = isLandscape,
                isCompact = isCompact,
                measuredTopInsetPx = measuredTopInsetPx,
                measuredBottomInsetPx = measuredBottomInsetPx,
                isRecording = isRecording,
                isAutoStandby = isAutoStandby
            )

            val isInside = isPointInUnobstructedViewport(
                point = effectivePoint,
                mapCenter = map.mapCenter as GeoPoint,
                zoomLevel = map.zoomLevelDouble,
                mapOrientationDeg = map.mapOrientation,
                tilesScaleFactor = map.tilesScaleFactor,
                mapWidth = map.width,
                mapHeight = map.height,
                leftInset = insets[0],
                rightInset = insets[1],
                topInset = insets[2],
                bottomInset = insets[3]
            )

            if (isInside || isUserTouchingMap || isFollowing) {
                cursorOutsideViewportSinceMs = 0L
            } else if (cursorOutsideViewportSinceMs == 0L) {
                cursorOutsideViewportSinceMs = nowMs
            }

            val shouldRecenter = shouldTriggerAutoRecenter(
                autoRecenterEnabled = autoRecenterEnabled,
                isAppInForeground = isAppInForeground,
                isFollowing = isFollowing,
                destinationPoint = destinationPoint,
                selectedSavedPlace = selectedSavedPlace,
                isRecording = isRecording,
                selectedTripsEmpty = selectedTrips.isEmpty(),
                showHeatMap = showHeatMap,
                isUserTouchingMap = isUserTouchingMap,
                isCursorInsideViewport = isInside,
                nowElapsedMs = nowMs,
                lastInteractionElapsedMs = lastMapInteractionElapsedMs,
                outsideViewportSinceElapsedMs = cursorOutsideViewportSinceMs,
                timeoutMs = autoRecenterTimeoutSec * 1_000L
            )

            if (shouldRecenter) {
                isFollowing = true
                val centerGp = getOpticalCenter(map, effectivePoint, effectiveOpticalOffsetY, effectiveOpticalOffsetX)
                map.controller.setCenter(centerGp)
                map.invalidate()
                cursorOutsideViewportSinceMs = 0L
            }
        }
    }

    // Continuous Frame Loop: render pipeline sampled on every display VSYNC while moving, so the marker,
    // camera, rotation and polyline tip share one clock (SystemClock.elapsedRealtime, same base as fixes).
    // Strictly suspended when screen is off / app is in background to prevent background CPU wakeups.
    LaunchedEffect(
        isFollowing,
        destinationPoint,
        selectedSavedPlace,
        orientationMode,
        isRecording,
        isAppInForeground,
        showAltitudeOnMap,
        isAltitudeFallback,
        fontScale
    ) {
        if (!isAppInForeground) return@LaunchedEffect
        var lastRenderedLat = 0.0
        var lastFreshHeadingMs = Long.MIN_VALUE / 2
        var lastRenderedLng = 0.0
        var lastRenderedAngle = -999f
        var lastRenderedAltitude = -999.0
        var lastRenderedAltitudeEnabled: Boolean? = null
        var lastRenderedIsFallback: Boolean? = null
        var lastRenderedFontScaleFactor: Float = fontScale.scaleFactor
        var lastFrameMs = 0L
        var lastActiveMs = 0L

        while (isActive) {
            val map = mapView
            val fix = latestFix
            var settled = true
            if (map != null && fix != null) {
                val nowMs = android.os.SystemClock.elapsedRealtime()
                val dtSec = if (lastFrameMs > 0L) ((nowMs - lastFrameMs).coerceIn(1L, 250L) / 1000.0) else 0.016
                val framesMissed = lastFrameMs == 0L || nowMs - lastFrameMs > 1_000L
                lastFrameMs = nowMs
                renderPipeline.movingOnSpeedMps = if (latestRecording) 0.5 else 0.8
                val knownStateMs = renderPipeline.latestState?.tElapsedMs
                val newStates = TrackingRuntime.states.since(lastFedStateMs)
                for (s in newStates) {
                    renderPipeline.onState(s)
                    lastFedStateMs = s.tElapsedMs
                }
                // Several states at once (a delivered batch): pencil points drawn after the previously known
                // state were dead reckoning; they are redrawn from the real states below.
                val redrawFromMs = if (newStates.size >= PencilTrail.BATCH_STATES) knownStateMs else null
                if (framesMissed || redrawFromMs != null || newStates.size >= PencilTrail.BATCH_STATES) {
                    // Align pose with pathBetween after screen-off / batch catch-up so pose does not jump back to pre-batch offset.
                    renderPipeline.clearCorrectionOffsets()
                }
                val pose = renderPipeline.frame(nowMs)

                if (pose != null && pose.lat != 0.0 && pose.lng != 0.0) {
                    val gp = GeoPoint(pose.lat, pose.lng)
                    // Arrow direction: at slow speed the direction of the curve the cursor is drawing; otherwise
                    // the filter's heading while it observes one; otherwise, when visibly moving, the direction
                    // of the cursor's own rendered motion (never a stale held value).
                    if (pose.headingFresh) lastFreshHeadingMs = nowMs
                    val motionDeg = motionCourse.update(nowMs, pose.lat, pose.lng)
                    val cursorMoving = pose.isMoving && !pose.isStationary
                    // Arrow: path tangent at slow foot pace; vehicles prefer motion course when the filter
                    // heading is freshly wrong (sideways arrow while driving through a bend).
                    val courseBearing = when {
                        cursorMoving && onFootNow.value && pose.pathHeadingDeg != null -> pose.pathHeadingDeg
                        cursorMoving && !onFootNow.value -> {
                            val motion = MotionCourse.forVehicle(
                                motionDeg,
                                pose.headingDeg.takeIf { pose.headingConfident },
                                nowMs - lastFreshHeadingMs
                            )
                            if (motion != null && pose.headingFresh &&
                                kotlin.math.abs(shortestDeltaDeg(pose.headingDeg, motion)) > 35.0
                            ) motion
                            else if (pose.headingFresh) pose.headingDeg
                            else motion
                        }
                        pose.headingFresh -> pose.headingDeg
                        cursorMoving -> motionDeg
                        else -> null
                    }
                    // Compass drives the arrow only (never course-up): walking compass steps ~3x the filter and
                    // made the map wave on straight sidewalks.
                    val compassBearing = if (compassSensor.isRunning) {
                        compassArrow.update(
                            nowMs,
                            TrackingRuntime.compass.headingAt(pose.renderTimeMs),
                            courseBearing,
                            cursorMoving
                        )
                    } else null
                    val arrowBearing = compassBearing ?: courseBearing
                    val mapBearing = CourseUpHeading.resolve(
                        isMoving = cursorMoving,
                        isAnchored = pose.isStationary,
                        headingConfident = pose.headingConfident,
                        headingDeg = pose.headingDeg,
                        motionCourseDeg = motionDeg
                    )
                    if (arrowBearing != null) {
                        lastFrozenBearing = arrowBearing.toFloat()
                    } else if (lastFrozenBearing == null && fix.bearing != null) {
                        lastFrozenBearing = fix.bearing
                    }
                    val effectiveBearing = lastFrozenBearing
                    val isMoving = cursorMoving
                    val hasHeading = isMoving && (effectiveBearing != null)
                    settled = pose.isStationary && !pose.isMoving
                    if (!settled) lastActiveMs = nowMs

                    if (!map.overlays.contains(accuracyOverlay)) {
                        val mIdx = marker?.let { map.overlays.indexOf(it) } ?: -1
                        if (mIdx >= 0) {
                            map.overlays.add(mIdx, accuracyOverlay)
                        } else {
                            map.overlays.add(accuracyOverlay)
                        }
                    }
                    val effectiveAccuracy = pose.accuracyM ?: fix.accuracy
                    accuracyOverlay.updatePosition(pose.lat, pose.lng, effectiveAccuracy)

                    val m = marker ?: Marker(map).also {
                        it.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        it.isFlat = false
                        it.infoWindow = null
                        it.setOnMarkerClickListener { _, _ -> true }
                        map.overlays.add(it)
                        marker = it
                    }
                    m.infoWindow = null
                    m.setOnMarkerClickListener { _, _ -> true }

                    bringAltitudeOverlayToFront(map, altitudeOverlay)
                    val frameSpeed = fix.speedMs * 3.6f
                    altitudeOverlay.isEnabled = latestShowAltitudeOnMap
                    altitudeOverlay.updateData(
                        geoPoint = gp,
                        altitude = latestAltitudeM,
                        isGpsFallback = latestIsAltitudeFallback,
                        fontScaleFactor = latestFontScale.scaleFactor,
                        currentSpeedKmh = frameSpeed,
                        isGpsMode = (latestAltitudeSourceMode == AltitudeSourceMode.GPS),
                        savedPlaces = latestSavedPlaces,
                        destinationPoint = latestDestinationPoint,
                        guestPoints = latestVisibleGuests.map { latestSpiderfyPositions[it.id] ?: it.geoPoint }
                    )

                    // Course-up: filter / motion course only (see CourseUpHeading). Never compass or path tangent.
                    if (orientationMode == MapOrientationMode.COURSE_UP && isFollowing) {
                        val isPhysicallyStationary = try {
                            StationaryDetector.getInstance(context).isPhysicallyStationary.value
                        } catch (_: Exception) { false }
                        val canRotate = canRotateCourseUp(
                            hasHeading = mapBearing != null,
                            isPhysicallyStationary = isPhysicallyStationary,
                            isAnchored = fix.isAnchored,
                            speedMs = fix.speedMs,
                            isRecording = latestRecording,
                            isInterpolatorStationary = pose.isStationary
                        )
                        if (canRotate && mapBearing != null) {
                            // Re-sync if the map was rotated externally (gesture, mode switch)
                            val mapCourse = (-map.mapOrientation).toDouble()
                            if (!courseSlew.initialized ||
                                kotlin.math.abs(shortestDeltaDeg(courseSlew.angleDeg, mapCourse)) > 2.0
                            ) {
                                courseSlew.snap(mapCourse)
                            }
                            val course = courseSlew.step(mapBearing, dtSec)
                            map.mapOrientation = normalizeAngle(-course.toFloat())
                            currentMapOrientation = map.mapOrientation
                        }
                    }

                    // Live polyline = committed smoothed points (TripManager) + pencil trail (the cursor's own
                    // rendered path since the newest committed vertex) + cursor tip. The line therefore always
                    // ends under the cursor and never re-shapes ahead of it.
                    if (latestRecording) {
                        val isPausedNow = tripManagerRef.isPaused.value

                        // 1. Filtered trip polyline
                        if (latestShowFilteredPolyline) {
                            val trackColor = try { Color.parseColor(trackColorHex) } catch (_: Exception) { Color.parseColor("#EF4444") }
                            val line = polyline ?: Polyline(map).also {
                                it.outlinePaint.color = trackColor
                                it.outlinePaint.strokeWidth = 12f
                                it.outlinePaint.strokeCap = Paint.Cap.ROUND
                                it.outlinePaint.strokeJoin = Paint.Join.ROUND
                                it.infoWindow = null
                                it.setOnClickListener { _, _, _ -> true }
                                addOverlayAboveTiles(map, it)
                                polyline = it
                            }
                            if (isPausedNow) {
                                if (pencilTrail.size > 0 || liveLine.hasTail) {
                                    pencilTrail.clear()
                                    liveLine.anchorAtCommitted()
                                    line.setPoints(liveLine.points)
                                }
                            } else {
                                val before = pencilTrail.size
                                committedThroughMs = maxOf(committedThroughMs, tripManagerRef.lastCommittedElapsedMs)
                                pencilTrail.pruneThrough(committedThroughMs)
                                // Large catch-up (app background → foreground, or fused batch): drop extrapolated pencil
                                // entirely and rebuild from committed → now so we do not draw hieroglyph chords/spikes.
                                val catchUp = framesMissed || redrawFromMs != null || newStates.size >= PencilTrail.BATCH_STATES
                                if (catchUp) {
                                    pencilTrail.dropAfter(committedThroughMs)
                                } else {
                                    redrawFromMs?.let { pencilTrail.dropAfter(it) }
                                }
                                val vehiclePencil = !onFootNow.value
                                val pencilEndMs = if (pencilTrail.size > 0) pencilTrail.timeAt(pencilTrail.size - 1) else committedThroughMs
                                val refillFromMs = if (catchUp && committedThroughMs > 0L) committedThroughMs else pencilEndMs
                                val backfilled = catchUp && !pose.isStationary && refillFromMs > 0L &&
                                    renderPipeline.pathBetween(refillFromMs, pose.renderTimeMs)
                                        .count {
                                            pencilTrail.add(it.tMs, it.lat, it.lng, it.speedMps, vehicle = vehiclePencil)
                                        } > 0
                                val added = !pose.isStationary && (!pose.isPredicted || pose.speedMps >= 1.5) &&
                                    pencilTrail.add(
                                        pose.renderTimeMs,
                                        pose.lat,
                                        pose.lng,
                                        pose.speedMps,
                                        vehicle = vehiclePencil
                                    )
                                if (pose.isStationary && pencilTrail.size > 0 && committedThroughMs >= pose.renderTimeMs - 1_500L) {
                                    pencilTrail.clear()
                                }
                                if (added || backfilled || pencilTrail.size != before || !liveLine.hasTail) {
                                    liveLine.rebuildTail(pencilTrail, gp, committedThroughMs, pose.speedMps)
                                    line.setPoints(liveLine.points)
                                } else if (liveLine.moveTip(gp)) {
                                    line.setPoints(liveLine.points)
                                }
                            }
                        } else {
                            if (polyline != null) {
                                map.overlays.remove(polyline)
                                polyline = null
                            }
                        }

                        // 2. Raw GNSS polyline (thin cyan line connecting raw antenna fixes, drawn above filtered line)
                        if (latestShowRawPolyline) {
                            val rawLine = rawPolyline ?: Polyline(map).also {
                                it.outlinePaint.color = Color.parseColor("#06B6D4")
                                it.outlinePaint.strokeWidth = 4f
                                it.outlinePaint.strokeCap = Paint.Cap.ROUND
                                it.outlinePaint.strokeJoin = Paint.Join.ROUND
                                it.infoWindow = null
                                it.setOnClickListener { _, _, _ -> true }
                                addOverlayAboveTiles(map, it)
                                rawPolyline = it
                            }
                            val rawPts = latestRawTrackPoints
                            if (rawPts.isNotEmpty()) {
                                rawLine.setPoints(rawPts)
                            }
                        } else {
                            if (rawPolyline != null) {
                                map.overlays.remove(rawPolyline)
                                rawPolyline = null
                            }
                        }
                    }

                    if (lastAppliedHeadingState != hasHeading) {
                        m.icon = if (hasHeading) headingMarkerIcon else dotMarkerIcon
                        lastAppliedHeadingState = hasHeading
                    }

                    // In OSMDroid, Marker rotation on canvas is (-map.mapOrientation - m.rotation) when isFlat is false.
                    // Since the canvas itself is pre-rotated by +map.mapOrientation, the net marker angle on SCREEN is -m.rotation.
                    // The vehicle's heading vector on screen (relative to top of screen) is:
                    //   roadScreenAngle = (effectiveBearing + map.mapOrientation + 360f) % 360f.
                    val roadScreenAngle = if (hasHeading && effectiveBearing != null) {
                        (effectiveBearing + map.mapOrientation + 360f) % 360f
                    } else {
                        0f
                    }

                    val dLat = kotlin.math.abs(gp.latitude - lastRenderedLat)
                    val dLng = kotlin.math.abs(gp.longitude - lastRenderedLng)
                    val dAngle = kotlin.math.abs(roadScreenAngle - lastRenderedAngle)
                    val currentAltVal = latestAltitudeM ?: 0.0
                    val dAlt = kotlin.math.abs(currentAltVal - lastRenderedAltitude)
                    val altEnabledChanged = lastRenderedAltitudeEnabled != latestShowAltitudeOnMap
                    val fallbackChanged = lastRenderedIsFallback != latestIsAltitudeFallback
                    val fontScaleChanged = lastRenderedFontScaleFactor != latestFontScale.scaleFactor

                    // Only update marker position and invalidate if visually meaningful displacement occurred or settings changed
                    if (dLat > 0.0000005 || dLng > 0.0000005 || dAngle > 0.2f || dAlt > 0.5 ||
                        altEnabledChanged || fallbackChanged || fontScaleChanged) {
                        m.position = gp
                        m.rotation = -roadScreenAngle
                        m.title = null
                        lastRenderedLat = gp.latitude
                        lastRenderedLng = gp.longitude
                        lastRenderedAngle = roadScreenAngle
                        lastRenderedAltitude = currentAltVal
                        lastRenderedAltitudeEnabled = latestShowAltitudeOnMap
                        lastRenderedIsFallback = latestIsAltitudeFallback
                        lastRenderedFontScaleFactor = latestFontScale.scaleFactor

                        if (isFollowing && destinationPoint == null && selectedSavedPlace == null) {
                            val centerGp = getOpticalCenter(map, gp, effectiveOpticalOffsetY, effectiveOpticalOffsetX)
                            map.controller.setCenter(centerGp)
                        }
                        map.invalidate()
                    }
                }
            }

            // Thermal / Battery optimization: full display rate only while moving (plus a short settling
            // window so corrections and rotation finish); 350 ms cadence when parked.
            val activeRecently = android.os.SystemClock.elapsedRealtime() - lastActiveMs < 1_500L
            if (settled && !activeRecently) {
                kotlinx.coroutines.delay(350L)
            } else {
                withFrameNanos { /* next VSYNC */ }
            }
        }
    }

    // Destination Pin (from Search)
    LaunchedEffect(destinationPoint) {
        val map = mapView ?: return@LaunchedEffect
        if (destinationPoint == null) {
            destMarker?.let { map.overlays.remove(it) }
            destMarker = null
            map.invalidate()
            return@LaunchedEffect
        }

        isFollowing = false

        val dm = destMarker ?: Marker(map).also {
            it.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            it.infoWindow = null
            map.overlays.add(it)
            destMarker = it
        }
        dm.position = destinationPoint
        dm.icon = makeDestIcon(context)
        dm.infoWindow = null
        dm.setOnMarkerClickListener { _, _ ->
            onDestinationMarkerClick?.invoke()
            true // Consumed! No blank popup bubble
        }

        val centerGp = getOpticalCenter(map, destinationPoint, effectiveOpticalOffsetY)
        map.controller.animateTo(centerGp)
        map.invalidate()
    }

    // Center camera on Selected Saved Place
    LaunchedEffect(selectedSavedPlace) {
        val map = mapView ?: return@LaunchedEffect
        val sp = selectedSavedPlace ?: return@LaunchedEffect
        isFollowing = false
        val centerGp = getOpticalCenter(map, sp.geoPoint, effectiveOpticalOffsetY, effectiveOpticalOffsetX)
        map.controller.animateTo(centerGp)
    }

    // Saved Places Custom Pins
    var savedPlaceMarkers by remember { mutableStateOf<List<Marker>>(emptyList()) }
    LaunchedEffect(savedPlaces) {
        val map = mapView ?: return@LaunchedEffect
        // Remove existing custom place markers
        savedPlaceMarkers.forEach { map.overlays.remove(it) }

        val newMarkers = mutableListOf<Marker>()
        savedPlaces.forEach { place ->
            val spm = Marker(map).apply {
                position = place.geoPoint
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = makeSavedPlaceIcon(context, place)
                title = "${place.category.iconEmoji} ${place.name}"
                snippet = listOfNotNull(
                    place.street.takeIf { it.isNotBlank() },
                    place.locality.takeIf { it.isNotBlank() }
                ).joinToString(", ")
                infoWindow = null
                setOnMarkerClickListener { _, _ ->
                    val centerGp = getOpticalCenter(map, place.geoPoint, effectiveOpticalOffsetY, effectiveOpticalOffsetX)
                    map.controller.animateTo(centerGp)
                    onSavedPlaceClick?.invoke(place)
                    true // Consumed! No blank OSM popup
                }
            }
            map.overlays.add(spm)
            newMarkers.add(spm)
        }
        savedPlaceMarkers = newMarkers
        map.invalidate()
    }

    // Update live trip polyline (Filtered & Raw)
    LaunchedEffect(trackPoints, rawTrackPoints, isRecording, showFilteredPolyline, showRawPolyline) {
        val map = mapView ?: return@LaunchedEffect
        if (!isRecording) {
            liveLine.clear()
            pencilTrail.clear()
            committedThroughMs = -1L
            if (trackPoints.isEmpty() || (!showFilteredPolyline && !showRawPolyline)) {
                polyline?.let { map.overlays.remove(it) }
                polyline = null
                rawPolyline?.let { map.overlays.remove(it) }
                rawPolyline = null
                map.invalidate()
                return@LaunchedEffect
            }

            if (showFilteredPolyline && trackPoints.isNotEmpty()) {
                val trackColor = try { Color.parseColor(trackColorHex) } catch (_: Exception) { Color.parseColor("#EF4444") }
                val line = polyline ?: Polyline(map).also {
                    it.outlinePaint.color = trackColor
                    it.outlinePaint.strokeWidth = 12f
                    it.outlinePaint.strokeCap = Paint.Cap.ROUND
                    it.outlinePaint.strokeJoin = Paint.Join.ROUND
                    it.infoWindow = null
                    it.setOnClickListener { _, _, _ -> true }
                    addOverlayAboveTiles(map, it)
                    polyline = it
                }

                addOverlayAboveTiles(map, line)
                line.setPoints(LiveTrackLine.curved(trackPoints))
            } else {
                polyline?.let { map.overlays.remove(it) }
                polyline = null
            }

            if (showRawPolyline && rawTrackPoints.isNotEmpty()) {
                val rawLine = rawPolyline ?: Polyline(map).also {
                    it.outlinePaint.color = Color.parseColor("#06B6D4")
                    it.outlinePaint.strokeWidth = 4f
                    it.outlinePaint.strokeCap = Paint.Cap.ROUND
                    it.outlinePaint.strokeJoin = Paint.Join.ROUND
                    it.infoWindow = null
                    it.setOnClickListener { _, _, _ -> true }
                    addOverlayAboveTiles(map, it)
                    rawPolyline = it
                }

                addOverlayAboveTiles(map, rawLine)
                rawLine.setPoints(rawTrackPoints)
            } else {
                rawPolyline?.let { map.overlays.remove(it) }
                rawPolyline = null
            }
            map.invalidate()
        } else {
            // When actively recording: committed points come from TripManager (smoothed track). Rebuild the
            // committed prefix whenever that list changes (resume, background gap, restore); the per-frame
            // loop appends the dynamic tail. This is the single source of the live polyline.
            if (!showFilteredPolyline) {
                polyline?.let { map.overlays.remove(it) }
                polyline = null
            } else {
                val committedGeoPoints = trackPoints.map { p -> GeoPoint(p.latitude, p.longitude) }
                liveLine.setCommitted(committedGeoPoints)
                committedThroughMs = tripManagerRef.lastCommittedElapsedMs
                if (trackPoints.isEmpty()) pencilTrail.clear()
                pencilTrail.pruneThrough(committedThroughMs)
                val currentPt = marker?.position
                    ?: locationFix?.let { GeoPoint(it.lat, it.lng) }
                    ?: latLng?.let { GeoPoint(it.first, it.second) }
                val tip = currentPt ?: trackPoints.lastOrNull()
                if (tip != null) {
                    val curSpeedMps = (latestFix?.speedMs ?: 0f).toDouble()
                    liveLine.rebuildTail(pencilTrail, tip, committedThroughMs, speedMps = curSpeedMps)
                    polyline?.setPoints(liveLine.points)
                }
            }

            if (!showRawPolyline) {
                rawPolyline?.let { map.overlays.remove(it) }
                rawPolyline = null
            } else if (rawTrackPoints.isNotEmpty()) {
                val rawLine = rawPolyline ?: Polyline(map).also {
                    it.outlinePaint.color = Color.parseColor("#06B6D4")
                    it.outlinePaint.strokeWidth = 4f
                    it.outlinePaint.strokeCap = Paint.Cap.ROUND
                    it.outlinePaint.strokeJoin = Paint.Join.ROUND
                    it.infoWindow = null
                    it.setOnClickListener { _, _, _ -> true }
                    addOverlayAboveTiles(map, it)
                    rawPolyline = it
                }
                rawLine.setPoints(rawTrackPoints)
            }
            map.invalidate()
        }
    }

    // Render Rest Stops / Pauses along polyline
    var pauseMarkers by remember { mutableStateOf<List<Marker>>(emptyList()) }
    LaunchedEffect(pauses) {
        val map = mapView ?: return@LaunchedEffect
        pauseMarkers.forEach { map.overlays.remove(it) }
        pauseMarkers = emptyList()

        if (pauses.isEmpty()) {
            map.invalidate()
            return@LaunchedEffect
        }

        val newPauseMarkers = mutableListOf<Marker>()
        pauses.filter { it.durationMs >= 30_000L }.forEach { p ->
            val pm = Marker(map).apply {
                position = GeoPoint(p.latitude, p.longitude)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                val durText = when {
                    p.durationMs < 60_000L -> "${p.durationMs / 1000}s"
                    p.durationMs < 3600_000L -> "${p.durationMs / 60000}m"
                    else -> "${p.durationMs / 3600000}h ${(p.durationMs % 3600000) / 60000}m"
                }
                icon = makePauseIcon(context, durText)
                infoWindow = null
                setOnMarkerClickListener { _, _ -> true }
            }
            map.overlays.add(pm)
            newPauseMarkers.add(pm)
        }
        pauseMarkers = newPauseMarkers
        map.invalidate()
    }

    // Render Guest Markers (Follow back guest positions)
    val zoomSpiderfyBucket = kotlin.math.round(currentZoom * 8.0).toInt()
    val orientationSpiderfyBucket = kotlin.math.round(currentMapOrientation / 5.0).toInt()
    LaunchedEffect(
        visibleGuests,
        showGuestDistanceBadge,
        latLng,
        zoomSpiderfyBucket,
        orientationSpiderfyBucket,
        viewportScrollBucket,
        fontScale,
        savedPlaces.size
    ) {
        val map = mapView ?: return@LaunchedEffect

        if (visibleGuests.isEmpty()) {
            if (guestMarkersMap.isNotEmpty()) {
                guestMarkersMap.values.forEach { map.overlays.remove(it) }
                guestMarkersMap.clear()
                guestMarkerBadgeKeys.clear()
                guestLeaderLinesOverlay.updateLines(emptyList())
                map.overlays.remove(guestLeaderLinesOverlay)
                map.invalidate()
            }
            return@LaunchedEffect
        }

        val visibleIds = visibleGuests.map { it.id }.toSet()
        val removedIds = guestMarkersMap.keys.filter { it !in visibleIds }
        var mapChanged = false

        if (removedIds.isNotEmpty()) {
            removedIds.forEach { id ->
                guestMarkersMap.remove(id)?.let { map.overlays.remove(it) }
                guestMarkerBadgeKeys.remove(id)
            }
            mapChanged = true
        }

        val hostLoc = latLng?.let { Triple(it.first, it.second, it.third) }
        val hostGp = marker?.position ?: latLng?.let { GeoPoint(it.first, it.second) }
        val obstacleGps = savedPlaces.map { it.geoPoint }
        val effectiveZoom = if (currentZoom > 0.0) currentZoom else map.zoomLevelDouble
        val vpCenter = GeoPoint(map.mapCenter.latitude, map.mapCenter.longitude)
        val vpWidthDp = (map.width / density).toDouble().takeIf { it > 100.0 } ?: 360.0
        val vpHeightDp = (map.height / density).toDouble().takeIf { it > 100.0 } ?: 760.0

        val spiderfyPositions = computeSpiderfyOffsets(
            guests = visibleGuests,
            hostPos = hostGp,
            obstaclePoints = obstacleGps,
            zoomLevel = effectiveZoom,
            density = density,
            mapOrientationDeg = map.mapOrientation,
            fontScaleFactor = fontScale.scaleFactor,
            showGuestDistanceBadge = showGuestDistanceBadge,
            viewportCenter = vpCenter,
            viewportWidthDp = vpWidthDp,
            viewportHeightDp = vpHeightDp
        )
        currentSpiderfyPositions = spiderfyPositions

        val spiderfyLines = visibleGuests.mapNotNull { guest ->
            val disp = spiderfyPositions[guest.id]
            if (disp != null && (disp.latitude != guest.lat || disp.longitude != guest.lng)) {
                GuestSpiderfyLine(
                    realPos = guest.geoPoint,
                    displayPos = disp,
                    colorArgb = LiveGuest.getColor(guest.colorIndex),
                    isInactive = guest.isInactive
                )
            } else null
        }
        guestLeaderLinesOverlay.updateLines(spiderfyLines)
        if (spiderfyLines.isNotEmpty()) {
            if (!map.overlays.contains(guestLeaderLinesOverlay)) {
                val firstGuestIdx = guestMarkersMap.values.firstOrNull()?.let { map.overlays.indexOf(it) } ?: -1
                if (firstGuestIdx >= 0) {
                    map.overlays.add(firstGuestIdx, guestLeaderLinesOverlay)
                } else {
                    val altIdx = map.overlays.indexOf(altitudeOverlay)
                    val insertIdx = if (altIdx >= 0) altIdx else map.overlays.size
                    map.overlays.add(insertIdx, guestLeaderLinesOverlay)
                }
                mapChanged = true
            }
        } else {
            if (map.overlays.contains(guestLeaderLinesOverlay)) {
                map.overlays.remove(guestLeaderLinesOverlay)
                mapChanged = true
            }
        }

        visibleGuests.forEach { guest ->
            val distMeters = if (hostLoc != null) {
                val results = FloatArray(1)
                android.location.Location.distanceBetween(hostLoc.first, hostLoc.second, guest.lat, guest.lng, results)
                results[0]
            } else null

            val badgeText = GuestMarkerHelper.formatBadgeText(guest, distMeters, showGuestDistanceBadge)
            val fontScaleFactor = latestFontScale.scaleFactor
            val badgeKey = "${guest.name}|${guest.colorIndex}|${guest.isInactive}|$badgeText|$fontScaleFactor"
            val displayPos = spiderfyPositions[guest.id] ?: guest.geoPoint

            val existingMarker = guestMarkersMap[guest.id]
            val gm = if (existingMarker != null) {
                if (existingMarker.position.latitude != displayPos.latitude || existingMarker.position.longitude != displayPos.longitude) {
                    existingMarker.position = displayPos
                    mapChanged = true
                }
                if (guestMarkerBadgeKeys[guest.id] != badgeKey) {
                    val iconResult = makeGuestMarkerIcon(context, guest, distMeters, showGuestDistanceBadge, fontScaleFactor)
                    existingMarker.icon = iconResult.drawable
                    existingMarker.setAnchor(iconResult.anchorX, iconResult.anchorY)
                    guestMarkerBadgeKeys[guest.id] = badgeKey
                    mapChanged = true
                }
                if (!map.overlays.contains(existingMarker)) {
                    insertGuestMarkerBelowHostOverlays(map, existingMarker, marker, altitudeOverlay)
                    mapChanged = true
                }
                existingMarker
            } else {
                val iconResult = makeGuestMarkerIcon(context, guest, distMeters, showGuestDistanceBadge, fontScaleFactor)
                val newMarker = Marker(map).apply {
                    position = displayPos
                    setAnchor(iconResult.anchorX, iconResult.anchorY)
                    icon = iconResult.drawable
                }
                guestMarkersMap[guest.id] = newMarker
                guestMarkerBadgeKeys[guest.id] = badgeKey
                insertGuestMarkerBelowHostOverlays(map, newMarker, marker, altitudeOverlay)
                mapChanged = true
                newMarker
            }

            gm.title = guest.name
            val distStr = distMeters?.let { " • ${LiveGuest.formatDistance(it)}" } ?: ""
            val statusStr = if (guest.isInactive) " (inactive)" else ""
            gm.snippet = "Guest$statusStr$distStr"
            gm.infoWindow = null
            gm.setOnMarkerClickListener { _, _ ->
                if (currentOnGuestClick != null) {
                    currentOnGuestClick?.invoke(guest)
                } else {
                    val distInfo = distMeters?.let { "\nDistance to host: ${LiveGuest.formatDistance(it)}" } ?: ""
                    val statusInfo = if (guest.isInactive) " (inactive > 5 min)" else " (active)"
                    android.widget.Toast.makeText(
                        context,
                        "👤 ${guest.name}$statusInfo$distInfo",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
                true
            }
        }

        if (mapChanged) {
            map.invalidate()
        }
    }

    // Render Boundary Polygon (Phase 2)
    LaunchedEffect(boundaryPoints) {
        val map = mapView ?: return@LaunchedEffect
        boundaryPolygon?.let { map.overlays.remove(it) }
        boundaryPolygon = null

        if (boundaryPoints != null && boundaryPoints.size >= 3) {
            val poly = org.osmdroid.views.overlay.Polygon(map).apply {
                outlinePaint.color = Color.parseColor("#38BDF8") // Sky blue border
                outlinePaint.strokeWidth = 5f
                fillPaint.color = Color.parseColor("#2238BDF8")  // Semi-transparent blue fill
                points = boundaryPoints
                infoWindow = null
                setOnClickListener { _, _, _ -> true }
            }
            map.overlays.add(0, poly)
            boundaryPolygon = poly
        }
        map.invalidate()
    }

    // Render Pinned Locality Boundary Polygon (Red-ish dashed styling, distinct from sky-blue GPS boundary)
    LaunchedEffect(pinnedBoundaryPoints) {
        val map = mapView ?: return@LaunchedEffect
        pinnedBoundaryPolygon?.let { map.overlays.remove(it) }
        pinnedBoundaryPolygon = null

        if (pinnedBoundaryPoints != null && pinnedBoundaryPoints.size >= 3) {
            if (prePinZoom == null) {
                prePinZoom = map.zoomLevelDouble
                prePinCenter = GeoPoint(map.mapCenter.latitude, map.mapCenter.longitude)
            }
            val poly = org.osmdroid.views.overlay.Polygon(map).apply {
                outlinePaint.color = Color.parseColor("#EF4444") // Coral/Crimson Red border
                outlinePaint.strokeWidth = 6f
                outlinePaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(20f, 12f), 0f)
                fillPaint.color = Color.parseColor("#20EF4444")  // Subtle red tint
                points = pinnedBoundaryPoints
                infoWindow = null
                setOnClickListener { _, _, _ -> true }
            }
            map.overlays.add(0, poly)
            pinnedBoundaryPolygon = poly
        }
        map.invalidate()
    }

    // Render Outdoor Area Boundary Polygon (Mountain Ranges in Amber #F59E0B, Parks/Reserves in Emerald #10B981)
    LaunchedEffect(outdoorAreaBoundaryPoints, outdoorAreaKind, showOutdoorAreasOverlay) {
        val map = mapView ?: return@LaunchedEffect
        outdoorBoundaryPolygon?.let { map.overlays.remove(it) }
        outdoorBoundaryPolygon = null

        if (showOutdoorAreasOverlay && outdoorAreaBoundaryPoints != null && outdoorAreaBoundaryPoints.size >= 3) {
            val isRange = outdoorAreaKind == OutdoorAreaKind.MOUNTAIN_RANGE
            val strokeCol = if (isRange) Color.parseColor("#F59E0B") else Color.parseColor("#10B981")
            val fillCol = if (isRange) Color.parseColor("#14F59E0B") else Color.parseColor("#1810B981")
            val dashArray = if (isRange) floatArrayOf(24f, 12f) else floatArrayOf(18f, 10f)

            val poly = org.osmdroid.views.overlay.Polygon(map).apply {
                outlinePaint.color = strokeCol
                outlinePaint.strokeWidth = if (isRange) 6f else 5.5f
                outlinePaint.pathEffect = android.graphics.DashPathEffect(dashArray, 0f)
                fillPaint.color = fillCol
                points = outdoorAreaBoundaryPoints
                infoWindow = null
                setOnClickListener { _, _, _ -> true }
            }
            map.overlays.add(0, poly)
            outdoorBoundaryPolygon = poly
        }
        map.invalidate()
    }

    // Render Outdoor Area On-Map Badge Marker (sticking to the map at area center, styled with border color)
    var outdoorAreaMarker by remember { mutableStateOf<Marker?>(null) }
    LaunchedEffect(outdoorAreaBoundaryPoints, outdoorAreaName, outdoorAreaKind, outdoorAreaCenter, showOutdoorAreasOverlay) {
        val map = mapView ?: return@LaunchedEffect
        outdoorAreaMarker?.let { map.overlays.remove(it) }
        outdoorAreaMarker = null

        if (showOutdoorAreasOverlay && !outdoorAreaName.isNullOrBlank() &&
            (outdoorAreaBoundaryPoints != null || outdoorAreaCenter != null)
        ) {
            val center = outdoorAreaCenter ?: outdoorAreaBoundaryPoints?.let { pts ->
                if (pts.isNotEmpty()) {
                    val bb = BoundingBox.fromGeoPoints(pts)
                    GeoPoint(bb.centerLatitude, bb.centerLongitude)
                } else null
            }
            if (center != null) {
                val om = Marker(map).apply {
                    position = center
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    icon = makeOutdoorAreaBadgeIcon(context, outdoorAreaName, outdoorAreaKind)
                    infoWindow = null
                    setOnMarkerClickListener { _, _ ->
                        outdoorAreaBoundaryPoints?.let { pts ->
                            if (pts.isNotEmpty()) {
                                isFollowing = false
                                fitPointsToUnobstructedViewport(
                                    map = map,
                                    points = pts,
                                    density = density,
                                    isLandscape = isLandscape,
                                    isCompact = isCompact,
                                    measuredTopInsetPx = measuredTopInsetPx,
                                    measuredBottomInsetPx = measuredBottomInsetPx,
                                    isRecording = isRecording,
                                    isAutoStandby = isAutoStandby,
                                    onZoomCalculated = { currentZoom = it }
                                )
                            }
                        }
                        true
                    }
                }
                map.overlays.add(om)
                outdoorAreaMarker = om
            }
        }
        map.invalidate()
    }

    // Fit-to-outdoor-area trigger
    LaunchedEffect(fitOutdoorAreaTrigger) {
        if (fitOutdoorAreaTrigger > 0L) {
            val map = mapView ?: return@LaunchedEffect
            val pts = outdoorAreaBoundaryPoints
            if (!pts.isNullOrEmpty()) {
                isFollowing = false
                fitPointsToUnobstructedViewport(
                    map = map,
                    points = pts,
                    density = density,
                    isLandscape = isLandscape,
                    isCompact = isCompact,
                    measuredTopInsetPx = measuredTopInsetPx,
                    measuredBottomInsetPx = measuredBottomInsetPx,
                    isRecording = isRecording,
                    isAutoStandby = isAutoStandby,
                    onZoomCalculated = { currentZoom = it }
                )
            } else if (outdoorAreaCenter != null) {
                isFollowing = false
                val centerGp = getOpticalCenter(map, outdoorAreaCenter, effectiveOpticalOffsetY, effectiveOpticalOffsetX)
                map.controller.setZoom(12.0)
                map.controller.animateTo(centerGp)
            }
        }
    }

    // Render Search Route Polylines (Full trail routes or rivers)
    LaunchedEffect(searchRoutePolylines) {
        val map = mapView ?: return@LaunchedEffect
        searchRouteOverlays.forEach { map.overlays.remove(it) }
        searchRouteOverlays = emptyList()

        if (!searchRoutePolylines.isNullOrEmpty()) {
            val newPolys = mutableListOf<Polyline>()
            val density = context.resources.displayMetrics.density
            for (linePoints in searchRoutePolylines) {
                if (linePoints.size < 2) continue
                val poly = Polyline(map).apply {
                    outlinePaint.color = Color.parseColor("#EF4444")
                    outlinePaint.strokeWidth = 6.0f * density
                    outlinePaint.strokeCap = Paint.Cap.ROUND
                    outlinePaint.strokeJoin = Paint.Join.ROUND
                    setPoints(linePoints)
                }
                map.overlays.add(poly)
                newPolys.add(poly)
            }
            searchRouteOverlays = newPolys
        }
        map.invalidate()
    }

    // Render Selected Past Trips with Distinct Colors (Filtered & Raw)
    // Invariant: When an active trip is actively recording or trackPoints is present,
    // NEVER render past trips on the map.
    LaunchedEffect(selectedTrips, isRecording, trackPoints.size, showFilteredPolyline, showRawPolyline) {
        val map = mapView ?: return@LaunchedEffect
        // Remove existing past trip polylines
        selectedTripPolylines.forEach { map.overlays.remove(it) }
        selectedTripPolylines = emptyList()
        selectedTripRawPolylines.forEach { map.overlays.remove(it) }
        selectedTripRawPolylines = emptyList()

        if (isRecording || trackPoints.isNotEmpty() || selectedTrips.isEmpty() || (!showFilteredPolyline && !showRawPolyline)) {
            map.invalidate()
            return@LaunchedEffect
        }

        val colors = listOf(
            "#EF4444", // Vivid Red
            "#3B82F6", // Blue
            "#10B981", // Emerald Green
            "#F59E0B", // Amber
            "#8B5CF6", // Purple
            "#EC4899", // Pink
            "#14B8A6", // Teal
            "#F97316"  // Orange
        )

        val newLines = mutableListOf<Polyline>()
        val newRawLines = mutableListOf<Polyline>()
        selectedTrips.forEachIndexed { index, trip ->
            if (showFilteredPolyline && trip.points.size >= 2) {
                val colorHex = if (selectedTrips.size == 1) {
                    MapProfileConfigHelper.getConfig(context, trip.activityProfile).trackColorHex
                } else {
                    colors[index % colors.size]
                }
                val curvedPoints = LiveTrackLine.curved(trip.points)
                val line = Polyline(map).apply {
                    outlinePaint.color = Color.parseColor(colorHex)
                    outlinePaint.strokeWidth = 8f
                    outlinePaint.strokeCap = Paint.Cap.ROUND
                    outlinePaint.strokeJoin = Paint.Join.ROUND
                    infoWindow = null
                    setOnClickListener { _, _, _ -> true }
                    setPoints(curvedPoints)
                }
                addOverlayAboveTiles(map, line)
                newLines.add(line)
            }

            if (showRawPolyline && trip.rawPoints.size >= 2) {
                val rawLine = Polyline(map).apply {
                    outlinePaint.color = Color.parseColor("#06B6D4")
                    outlinePaint.strokeWidth = 4f
                    outlinePaint.strokeCap = Paint.Cap.ROUND
                    outlinePaint.strokeJoin = Paint.Join.ROUND
                    infoWindow = null
                    setOnClickListener { _, _, _ -> true }
                    setPoints(trip.rawPoints)
                }
                addOverlayAboveTiles(map, rawLine)
                newRawLines.add(rawLine)
            }
        }
        selectedTripPolylines = newLines
        selectedTripRawPolylines = newRawLines
        map.invalidate()
    }

    // Render Road / Path Heat Map Layer (Density of all recorded paths, frequency-based coloring)
    var allHeatMapPoints by remember { mutableStateOf<List<GeoPoint>>(emptyList()) }
    var heatMapFolderOverlay by remember { mutableStateOf<FolderOverlay?>(null) }

    LaunchedEffect(heatMapTracks, showHeatMap, heatMapConsolidate, heatMapMinVisits) {
        val map = mapView ?: return@LaunchedEffect
        heatMapFolderOverlay?.let { map.overlays.remove(it) }
        heatMapFolderOverlay = null
        heatMapPolylines.forEach { map.overlays.remove(it) }
        heatMapPolylines = emptyList()

        if (!showHeatMap || heatMapTracks.isEmpty()) {
            allHeatMapPoints = emptyList()
            map.invalidate()
            return@LaunchedEffect
        }

        val processed = withContext(Dispatchers.Default) {
            HeatMapEngine.processTracks(
                heatMapTracks,
                HeatMapOptions(
                    consolidateCorridors = heatMapConsolidate,
                    minVisits = heatMapMinVisits,
                    epsilonMeters = 10.0
                )
            )
        }

        allHeatMapPoints = processed.allSimplifiedPoints

        val folder = FolderOverlay().apply { name = "HeatMapLayer" }
        // Draw from lowest to highest thermal tier so peak thermal lines render on top
        listOf(
            HeatMapTier.TIER_1_BLUE,
            HeatMapTier.TIER_2_CYAN,
            HeatMapTier.TIER_3_GREEN,
            HeatMapTier.TIER_4_YELLOW,
            HeatMapTier.TIER_5_ORANGE,
            HeatMapTier.TIER_6_RED,
            HeatMapTier.TIER_7_MAGENTA
        ).forEach { tier ->
            val paths = processed.tierPolylines[tier] ?: emptyList()
            paths.forEach { path ->
                if (path.size >= 2) {
                    val poly = Polyline(map).apply {
                        outlinePaint.color = tier.colorArgb
                        outlinePaint.strokeWidth = tier.strokeWidth
                        outlinePaint.strokeCap = Paint.Cap.ROUND
                        outlinePaint.strokeJoin = Paint.Join.ROUND
                        infoWindow = null
                        setOnClickListener { _, _, _ -> true }
                        setPoints(path)
                    }
                    folder.add(poly)
                }
            }
        }
        map.overlays.add(0, folder)
        heatMapFolderOverlay = folder
        map.invalidate()
    }

    // Collect all visible points (selected past trips OR active recording track OR live sharing group)
    // Invariant 1: When past trips are selected, ONLY fit the selected trip points. Never inflate the bounding box with live guests or host location in other cities.
    // Invariant 2: When recording, ONLY fit active track points. Do not include past trips or live guests.
    // Invariant 3: When neither is active, fit active live sharing guests + host position.
    val allShownPoints = remember(trackPoints, selectedTrips, isRecording, visibleGuests, latLng) {
        val pts = mutableListOf<GeoPoint>()
        if (selectedTrips.isNotEmpty() && !isRecording) {
            // Selected past trips take absolute priority for map fitting
            selectedTrips.forEach { trip ->
                pts.addAll(trip.points)
            }
        } else if (isRecording || trackPoints.isNotEmpty()) {
            // Active trip recording: include recorded track AND live cursor position
            pts.addAll(trackPoints)
            if (latLng != null) {
                pts.add(GeoPoint(latLng.first, latLng.second))
            }
        } else if (visibleGuests.isNotEmpty()) {
            // Live sharing group (only when no trip is recording or selected)
            visibleGuests.forEach { guest ->
                pts.add(guest.geoPoint)
            }
            if (latLng != null) {
                pts.add(GeoPoint(latLng.first, latLng.second))
            }
        }
        pts
    }

    // Fit-to-track trigger (fits all shown trips: active and/or selected)
    LaunchedEffect(fitTrackTrigger) {
        if (fitTrackTrigger > 0L) {
            val map = mapView ?: return@LaunchedEffect
            val pointsToFit = if (allShownPoints.isNotEmpty()) {
                allShownPoints
            } else if (latLng != null) {
                listOf(GeoPoint(latLng.first, latLng.second))
            } else emptyList()
            if (pointsToFit.isNotEmpty()) {
                isFollowing = false
                fitPointsToUnobstructedViewport(
                    map = map,
                    points = pointsToFit,
                    density = density,
                    isLandscape = isLandscape,
                    isCompact = isCompact,
                    measuredTopInsetPx = measuredTopInsetPx,
                    measuredBottomInsetPx = measuredBottomInsetPx,
                    isRecording = isRecording,
                    isAutoStandby = isAutoStandby,
                    onZoomCalculated = { currentZoom = it }
                )
            }
        }
    }

    // Fit-to-saved-places trigger
    LaunchedEffect(fitPlacesTrigger) {
        if (fitPlacesTrigger > 0L && savedPlaces.isNotEmpty()) {
            val map = mapView ?: return@LaunchedEffect
            isFollowing = false
            fitPointsToUnobstructedViewport(
                map = map,
                points = savedPlaces.map { it.geoPoint },
                density = density,
                isLandscape = isLandscape,
                isCompact = isCompact,
                measuredTopInsetPx = measuredTopInsetPx,
                measuredBottomInsetPx = measuredBottomInsetPx,
                isRecording = isRecording,
                isAutoStandby = isAutoStandby,
                onZoomCalculated = { currentZoom = it }
            )
        }
    }

    // Fit-to-heat-map trigger
    LaunchedEffect(fitHeatMapTrigger) {
        if (fitHeatMapTrigger > 0L) {
            val map = mapView ?: return@LaunchedEffect
            isFollowing = false
            val pts = if (allHeatMapPoints.isNotEmpty()) allHeatMapPoints else heatMapTracks.flatten()
            if (pts.isNotEmpty()) {
                try {
                    val rawBox = BoundingBox.fromGeoPoints(pts)
                    val minSpan = 0.008
                    val latSpan = rawBox.latitudeSpan.coerceAtLeast(minSpan)
                    val lonSpan = rawBox.longitudeSpan.coerceAtLeast(minSpan)
                    val centerLat = rawBox.centerLatitude
                    val centerLon = rawBox.centerLongitude
                    val paddedBox = BoundingBox(
                        centerLat + latSpan * 0.55,
                        centerLon + lonSpan * 0.55,
                        centerLat - latSpan * 0.55,
                        centerLon - lonSpan * 0.55
                    )
                    map.zoomToBoundingBox(paddedBox, true, 120)
                } catch (e: Exception) {}
            }
        }
    }

    LaunchedEffect(isFollowing) {
        onFollowingChanged?.invoke(isFollowing)
    }

    LaunchedEffect(recenterTrigger) {
        if (recenterTrigger > 0L) {
            isFollowing = true
            effectiveLatLng?.let { pos ->
                val gp = GeoPoint(pos.first, pos.second)
                val centerGp = getOpticalCenter(mapView, gp, effectiveOpticalOffsetY)
                mapView?.controller?.setCenter(centerGp)
            }
            mapView?.invalidate()
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                MapView(ctx).apply {
                    val initialTileSource = when (baseLayer) {
                        MapBaseLayer.STANDARD -> TileSourceFactory.MAPNIK
                        MapBaseLayer.FREEMAP_OUTDOOR -> FreemapOutdoorSource
                        MapBaseLayer.TOPO -> OpenTopoMapSource
                        MapBaseLayer.SATELLITE -> EsriSatelliteSource
                    }
                    setTileSource(initialTileSource)
                    tilesScaleFactor = fontScale.scaleFactor
                    setMultiTouchControls(true)
                    val rotationGestureOverlay = RotationGestureOverlay(this).apply {
                        isEnabled = true
                    }
                    overlays.add(rotationGestureOverlay)
                    minZoomLevel = minZoom
                    maxZoomLevel = maxZoom
                    controller.setZoom(16.0)
                    currentZoom = 16.0

                    // Initialize camera center immediately so map never opens on blank ocean (0,0)
                    val initialLat = locationFix?.lat ?: latLng?.first ?: run {
                        val locPrefs = ctx.getSharedPreferences("online.hatala.whereami.loc_prefs", Context.MODE_PRIVATE)
                        val savedLat = locPrefs.getFloat("cached_lat", Float.NaN)
                        if (!savedLat.isNaN()) savedLat.toDouble() else 52.0693
                    }
                    val initialLon = locationFix?.lng ?: latLng?.second ?: run {
                        val locPrefs = ctx.getSharedPreferences("online.hatala.whereami.loc_prefs", Context.MODE_PRIVATE)
                        val savedLon = locPrefs.getFloat("cached_lon", Float.NaN)
                        if (!savedLon.isNaN()) savedLon.toDouble() else 19.4803
                    }
                    val initialOpticalCenter = calculateOpticalCenter(
                        lat = initialLat,
                        lon = initialLon,
                        zoom = 16.0,
                        mapOrientation = 0f,
                        offsetPixelsY = effectiveOpticalOffsetY,
                        offsetPixelsX = effectiveOpticalOffsetX
                    )
                    controller.setCenter(initialOpticalCenter)
                    // Disable blur-inducing tile upscaling; render crisp 1:1 pixel native tiles
                    isTilesScaledToDpi = false
                    zoomController.setVisibility(
                        org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER
                    )

                    // Map gesture listener:
                    // Long-press drops a pin at that point & reverse geocodes.
                    // Single-tap clears active pin/saved place card if visible, without dropping a new pin or showing any empty hint/bubble!
                    val eventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
                        override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                            if (destinationPoint != null || selectedSavedPlace != null || outdoorAreaBoundaryPoints != null) {
                                onClearDestination?.invoke()
                            }
                            // Always consume single tap and close any lingering info windows to guarantee no empty hint appears
                            org.osmdroid.views.overlay.infowindow.InfoWindow.closeAllInfoWindowsOn(this@apply)
                            return true
                        }
                        override fun longPressHelper(p: GeoPoint): Boolean {
                            currentOnMapClick?.invoke(p)
                            return true
                        }
                    })
                    overlays.add(0, eventsOverlay)

                    var isUserDragging = false
                    setOnTouchListener { _, event ->
                        lastMapInteractionElapsedMs = android.os.SystemClock.elapsedRealtime()
                        when (event.actionMasked) {
                            android.view.MotionEvent.ACTION_DOWN -> {
                                isUserDragging = true
                                isUserTouchingMap = true
                            }
                            android.view.MotionEvent.ACTION_MOVE -> {
                                isUserDragging = true
                                isUserTouchingMap = true
                                isFollowing = false
                            }
                            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                                isUserDragging = false
                                isUserTouchingMap = false
                            }
                        }
                        false
                    }

                    var lastScrollSpiderfyLat = 0.0
                    var lastScrollSpiderfyLng = 0.0
                    addMapListener(object : org.osmdroid.events.MapListener {
                        override fun onScroll(event: org.osmdroid.events.ScrollEvent?): Boolean {
                            if (isUserDragging) {
                                isFollowing = false
                            }
                            if (kotlin.math.abs(mapOrientation - currentMapOrientation) >= 2f) {
                                currentMapOrientation = mapOrientation
                            }
                            if (kotlin.math.abs(zoomLevelDouble - currentZoom) >= 0.05) {
                                currentZoom = zoomLevelDouble
                            }
                            if (latestVisibleGuests.isNotEmpty()) {
                                val c = mapCenter
                                val dLat = kotlin.math.abs(c.latitude - lastScrollSpiderfyLat)
                                val dLng = kotlin.math.abs(c.longitude - lastScrollSpiderfyLng)
                                val spanLat = boundingBox.latitudeSpan.coerceAtLeast(1e-5)
                                val spanLng = boundingBox.longitudeSpanWithDateLine.coerceAtLeast(1e-5)
                                if (dLat > spanLat * 0.06 || dLng > spanLng * 0.06) {
                                    lastScrollSpiderfyLat = c.latitude
                                    lastScrollSpiderfyLng = c.longitude
                                    viewportScrollBucket++
                                }
                            }
                            return false
                        }
                        override fun onZoom(event: org.osmdroid.events.ZoomEvent?): Boolean {
                            currentZoom = event?.zoomLevel ?: zoomLevelDouble
                            currentMapOrientation = mapOrientation
                            viewportScrollBucket++
                            postDelayed({
                                if (kotlin.math.abs(zoomLevelDouble - currentZoom) >= 0.02) {
                                    currentZoom = zoomLevelDouble
                                }
                                viewportScrollBucket++
                            }, 350L)
                            return false
                        }
                    })
                    mapView = this
                }
            },
            update = { }
        )

        MapFloatingControlsColumn(
            mapView = mapView,
            minZoom = minZoom,
            maxZoom = maxZoom,
            currentZoom = currentZoom,
            onZoomChange = { currentZoom = it },
            isFollowing = isFollowing,
            onFollowingChange = { isFollowing = it },
            destinationPoint = destinationPoint,
            selectedSavedPlace = selectedSavedPlace,
            onClearDestination = onClearDestination,
            hikingProviderRef = hikingProviderRef,
            orientationMode = orientationMode,
            effectiveLatLng = effectiveLatLng,
            lastFrozenBearing = lastFrozenBearing,
            effectiveOpticalOffsetY = effectiveOpticalOffsetY,
            effectiveOpticalOffsetX = effectiveOpticalOffsetX,
            onInstantShare = onInstantShare,
            onOpenCacheManager = onOpenCacheManager,
            allShownPoints = allShownPoints,
            selectedTrips = selectedTrips,
            onClearSelectedTrips = onClearSelectedTrips,
            pinnedBoundaryPoints = pinnedBoundaryPoints,
            prePinZoom = prePinZoom,
            prePinCenter = prePinCenter,
            onRestorePrePin = {
                prePinZoom = null
                prePinCenter = null
            },
            onViewportScrollIncrement = { viewportScrollBucket++ },
            density = density,
            isLandscape = isLandscape,
            isCompact = isCompact,
            measuredTopInsetPx = measuredTopInsetPx,
            measuredBottomInsetPx = measuredBottomInsetPx,
            isRecording = isRecording,
            isAutoStandby = isAutoStandby,
            isLiveSharingActive = isLiveSharingActive,
            activeGuestCount = sessionActiveGuestCount,
            inactiveGuestCount = sessionInactiveGuestCount,
            visibleGuests = visibleGuests,
            showInactiveGuests = showInactiveGuests,
            onToggleShowInactiveGuests = {
                showInactiveGuests = it
                MapProfileConfigHelper.setShowInactiveGuests(context, it)
            },
            showVisitorLabels = showGuestDistanceBadge,
            onToggleShowVisitorLabels = { enabled ->
                onToggleShowGuestDistanceBadge?.invoke(enabled)
            },
            onOpenLiveVisitorsDetails = onOpenLiveVisitorsDetails,
            activityProfile = activityProfile,
            trackPoints = trackPoints,
            onUserInteracted = {
                lastMapInteractionElapsedMs = android.os.SystemClock.elapsedRealtime()
            },
            context = context
        )

        MapRecenterPill(
            isFollowing = isFollowing,
            destinationPoint = destinationPoint,
            selectedSavedPlace = selectedSavedPlace,
            isLandscape = isLandscape,
            showHeatMap = showHeatMap,
            isRecording = isRecording,
            isAutoStandby = isAutoStandby,
            onClick = {
                isFollowing = true
                effectiveLatLng?.let { pos ->
                    val gp = GeoPoint(pos.first, pos.second)
                    val centerGp = getOpticalCenter(mapView, gp, effectiveOpticalOffsetY)
                    mapView?.controller?.setCenter(centerGp)
                }
                mapView?.invalidate()
            }
        )

        MapHeatMapFloatingToolbar(
            showHeatMap = showHeatMap,
            isLandscape = isLandscape,
            isRecording = isRecording,
            heatMapFilterActive = heatMapFilterActive,
            onOpenHeatMapSettings = onOpenHeatMapSettings,
            allHeatMapPoints = allHeatMapPoints,
            heatMapTracks = heatMapTracks,
            mapView = mapView,
            pinnedBoundaryPoints = pinnedBoundaryPoints,
            prePinZoom = prePinZoom,
            prePinCenter = prePinCenter,
            onRestorePrePin = {
                prePinZoom = null
                prePinCenter = null
            },
            onToggleHeatMap = onToggleHeatMap,
            onFollowingChange = { isFollowing = it }
        )

        // Floating Left-Side Thermal Color Legend (in portrait only; in landscape it is docked beneath LocalityCard)
        if (showHeatMap && !isLandscape) {
            HeatMapLegend(
                onOpenHeatMapSettings = onOpenHeatMapSettings,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 12.dp)
            )
        }
    }

    if (showSettingsDialog) {
        MapSettingsDialogHost(
            activityProfile = activityProfile,
            baseLayer = baseLayer,
            onBaseLayerChange = { baseLayer = it },
            showHikingOverlay = showHikingOverlay,
            onHikingOverlayToggle = { showHikingOverlay = it },
            showOutdoorAreasOverlay = showOutdoorAreasOverlay,
            onOutdoorAreasOverlayToggle = { enabled ->
                showOutdoorAreasOverlay = enabled
                MapProfileConfigHelper.setOutdoorAreasOverlay(context, activityProfile, enabled)
            },
            trailThickness = trailThickness,
            onTrailThicknessChange = { thickness ->
                trailThickness = thickness
                MapProfileConfigHelper.setTrailThickness(context, activityProfile, thickness)
            },
            showBorders = showBorders,
            onToggleBorders = onToggleBorders,
            showHeatMap = showHeatMap,
            onToggleHeatMap = onToggleHeatMap,
            compassArrowEnabled = compassArrowEnabled,
            onCompassArrowToggle = {
                compassArrowEnabled = it
                MapProfileConfigHelper.setCompassArrow(context, it)
            },
            showAltitudeOnMap = showAltitudeOnMap,
            onShowAltitudeToggle = {
                showAltitudeOnMap = it
                altitudeOverlay.isEnabled = it
                MapProfileConfigHelper.setShowAltitudeOnMap(context, it)
                mapView?.invalidate()
                mapView?.postInvalidate()
            },
            showFilteredPolyline = showFilteredPolyline,
            onShowFilteredPolylineToggle = {
                showFilteredPolyline = it
                MapProfileConfigHelper.setShowFilteredPolyline(context, it)
                mapView?.invalidate()
            },
            showRawPolyline = showRawPolyline,
            onShowRawPolylineToggle = {
                showRawPolyline = it
                MapProfileConfigHelper.setShowRawPolyline(context, it)
                mapView?.invalidate()
            },
            altitudeSourceMode = altitudeSourceMode,
            onAltitudeSourceModeChange = onAltitudeSourceModeChange,
            fontScale = fontScale,
            onFontScaleChange = { fontScale = it },
            orientationMode = orientationMode,
            onOrientationModeChange = { nextMode ->
                currentOnOrientationChange?.invoke(nextMode)
                when (nextMode) {
                    MapOrientationMode.NORTH -> mapView?.mapOrientation = 0f
                    MapOrientationMode.EAST -> mapView?.mapOrientation = 270f
                    MapOrientationMode.SOUTH -> mapView?.mapOrientation = 180f
                    MapOrientationMode.WEST -> mapView?.mapOrientation = 90f
                    MapOrientationMode.COURSE_UP -> {
                        val b = effectiveLatLng?.third ?: lastFrozenBearing
                        b?.let { mapView?.mapOrientation = -it }
                    }
                }
                mapView?.invalidate()
            },
            effectiveLatLng = effectiveLatLng,
            lastFrozenBearing = lastFrozenBearing,
            mapView = mapView,
            hikingProviderRef = hikingProviderRef,
            trackColorHex = trackColorHex,
            onTrackColorChange = { newColor ->
                MapProfileConfigHelper.setTrackColor(context, activityProfile, newColor)
                trackColorHex = newColor
            },
            onOpenCacheManager = {
                showSettingsDialog = false
                onOpenCacheManager?.invoke()
            },
            showInactiveGuests = showInactiveGuests,
            onShowInactiveGuestsToggle = {
                showInactiveGuests = it
                MapProfileConfigHelper.setShowInactiveGuests(context, it)
            },
            showVisitorLabels = showGuestDistanceBadge,
            onShowVisitorLabelsToggle = { enabled ->
                onToggleShowGuestDistanceBadge?.invoke(enabled)
            },
            autoRecenterEnabled = autoRecenterEnabled,
            onAutoRecenterToggle = { enabled ->
                autoRecenterEnabled = enabled
                MapProfileConfigHelper.setAutoRecenterEnabled(context, enabled)
            },
            autoRecenterTimeoutSec = autoRecenterTimeoutSec,
            onAutoRecenterTimeoutSecChange = { sec ->
                autoRecenterTimeoutSec = sec
                MapProfileConfigHelper.setAutoRecenterTimeoutSec(context, sec)
            },
            onDismiss = {
                showSettingsDialog = false
                mapView?.invalidate()
                onDismissMapSettings?.invoke()
            },
            altitudeOverlay = altitudeOverlay,
            context = context
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            hikingProviderRef?.clearTileCache()
            mapView?.onDetach()
        }
    }
}


private data class FitTargetOption(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val points: List<GeoPoint>
)

@Composable
private fun BoxScope.MapFloatingControlsColumn(
    mapView: MapView?,
    minZoom: Double,
    maxZoom: Double,
    currentZoom: Double,
    onZoomChange: (Double) -> Unit,
    isFollowing: Boolean,
    onFollowingChange: (Boolean) -> Unit,
    destinationPoint: GeoPoint?,
    selectedSavedPlace: SavedPlace?,
    onClearDestination: (() -> Unit)?,
    hikingProviderRef: org.osmdroid.tileprovider.MapTileProviderBasic?,
    orientationMode: MapOrientationMode,
    effectiveLatLng: Triple<Double, Double, Float?>?,
    lastFrozenBearing: Float?,
    effectiveOpticalOffsetY: Int,
    effectiveOpticalOffsetX: Int,
    onInstantShare: (() -> Unit)?,
    onOpenCacheManager: (() -> Unit)?,
    allShownPoints: List<GeoPoint>,
    selectedTrips: List<TripRecord>,
    onClearSelectedTrips: (() -> Unit)?,
    pinnedBoundaryPoints: List<GeoPoint>?,
    prePinZoom: Double?,
    prePinCenter: GeoPoint?,
    onRestorePrePin: () -> Unit,
    onViewportScrollIncrement: () -> Unit,
    density: Float,
    isLandscape: Boolean,
    isCompact: Boolean,
    measuredTopInsetPx: Int?,
    measuredBottomInsetPx: Int?,
    isRecording: Boolean,
    isAutoStandby: Boolean,
    isLiveSharingActive: Boolean,
    activeGuestCount: Int,
    inactiveGuestCount: Int,
    visibleGuests: List<LiveGuest>,
    showInactiveGuests: Boolean,
    onToggleShowInactiveGuests: (Boolean) -> Unit,
    showVisitorLabels: Boolean,
    onToggleShowVisitorLabels: (Boolean) -> Unit,
    onOpenLiveVisitorsDetails: (() -> Unit)?,
    activityProfile: ActivityProfile,
    trackPoints: List<GeoPoint>,
    onUserInteracted: () -> Unit,
    context: Context
) {
        var showGuestMenu by remember { mutableStateOf(false) }
        var showFitMenu by remember { mutableStateOf(false) }

        // Floating Zoom Controls & Map Tools:
        // Unified stacked column for BOTH Portrait and Landscape, centered vertically on the screen (Alignment.CenterEnd).
        // Each control is an exact 42dp Box with 4dp separation, preventing touch-target overlap and guaranteeing a clean visual gap.
        // Automatically centers vertically based on the number of currently active controls.
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Zoom In [+] Button
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(ComposeColor(0xCC1E293B))
                    .clickable {
                        onUserInteracted()
                        val map = mapView ?: return@clickable
                        val targetZoom = (map.zoomLevelDouble + 1.0).coerceAtMost(maxZoom)
                        map.controller.zoomIn()
                        onZoomChange(targetZoom)
                        map.postDelayed({
                            onZoomChange(map.zoomLevelDouble)
                            onViewportScrollIncrement()
                        }, 360L)
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Zoom In",
                    tint = ComposeColor.White,
                    modifier = Modifier.size(22.dp)
                )
            }

            // Zoom Out [-] Button
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(ComposeColor(0xCC1E293B))
                    .clickable {
                        onUserInteracted()
                        val map = mapView ?: return@clickable
                        val targetZoom = (map.zoomLevelDouble - 1.0).coerceAtLeast(minZoom)
                        map.controller.zoomOut()
                        onZoomChange(targetZoom)
                        map.postDelayed({
                            onZoomChange(map.zoomLevelDouble)
                            onViewportScrollIncrement()
                        }, 360L)
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Remove,
                    contentDescription = "Zoom Out",
                    tint = ComposeColor.White,
                    modifier = Modifier.size(22.dp)
                )
            }

            // Unified MyLocation & Refresh Button
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(if (isFollowing && destinationPoint == null && selectedSavedPlace == null) ComposeColor(0xEE0284C7) else ComposeColor(0xCC1E293B))
                    .clickable {
                        onUserInteracted()
                        onFollowingChange(true)
                        onClearDestination?.invoke()
                        mapView?.tileProvider?.clearTileCache()
                        hikingProviderRef?.clearTileCache()

                        val targetMapOrientation = when (orientationMode) {
                            MapOrientationMode.COURSE_UP -> {
                                val b = effectiveLatLng?.third ?: lastFrozenBearing
                                if (b != null) -b else 0f
                            }
                            MapOrientationMode.NORTH -> 0f
                            MapOrientationMode.EAST -> 270f
                            MapOrientationMode.SOUTH -> 180f
                            MapOrientationMode.WEST -> 90f
                        }
                        mapView?.mapOrientation = targetMapOrientation

                        effectiveLatLng?.let { pos ->
                            val gp = GeoPoint(pos.first, pos.second)
                            val centerGp = getOpticalCenter(mapView, gp, effectiveOpticalOffsetY, effectiveOpticalOffsetX)
                            mapView?.controller?.animateTo(centerGp, null, 400L)
                        }
                        mapView?.invalidate()
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.MyLocation,
                    contentDescription = "Recenter & Refresh",
                    tint = ComposeColor.White,
                    modifier = Modifier.size(22.dp)
                )
            }

            // Instant Share Current Position
            if (onInstantShare != null && effectiveLatLng != null) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(ComposeColor(0xCC1E293B))
                        .clickable {
                            onUserInteracted()
                            onInstantShare.invoke()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Share Current Position",
                        tint = ComposeColor(0xFF38BDF8),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            // Guests Control Button & Context Menu (Visible when Live Sharing is ON)
            if (isLiveSharingActive) {
                val badgeText = formatGuestControlBadge(activeGuestCount, inactiveGuestCount)
                val badgeColor = when {
                    activeGuestCount > 0 && inactiveGuestCount == 0 -> ComposeColor(0xFF10B981)
                    activeGuestCount > 0                            -> ComposeColor(0xFF0284C7)
                    else                                            -> ComposeColor(0xFF64748B)
                }

                Box(contentAlignment = Alignment.TopEnd) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(ComposeColor(0xCC1E293B))
                            .clickable {
                                onUserInteracted()
                                showGuestMenu = !showGuestMenu
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Group,
                            contentDescription = "Live Guests Menu",
                            tint = if (activeGuestCount > 0) ComposeColor(0xFF38BDF8) else ComposeColor(0xFF94A3B8),
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    if (badgeText != null) {
                        Box(
                            modifier = Modifier
                                .offset(x = 4.dp, y = (-4).dp)
                                .sizeIn(minWidth = 18.dp, minHeight = 16.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(badgeColor)
                                .border(1.2.dp, ComposeColor(0xFF0F172A), RoundedCornerShape(8.dp))
                                .padding(horizontal = 3.5.dp, vertical = 0.5.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = badgeText,
                                color = ComposeColor.White,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                style = androidx.compose.ui.text.TextStyle(
                                    platformStyle = androidx.compose.ui.text.PlatformTextStyle(
                                        includeFontPadding = false
                                    )
                                )
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = showGuestMenu,
                        onDismissRequest = { showGuestMenu = false },
                        modifier = Modifier
                            .background(ComposeColor(0xF00F172A))
                            .border(1.dp, ComposeColor(0xFF334155), RoundedCornerShape(8.dp))
                            .widthIn(min = 230.dp, max = 280.dp)
                    ) {
                        // Header
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "Visitors: $activeGuestCount active • $inactiveGuestCount inactive",
                                    color = ComposeColor(0xFF94A3B8),
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            },
                            onClick = { },
                            enabled = false
                        )

                        HorizontalDivider(color = ComposeColor(0xFF334155))

                        // Show Guests on Map (Fit)
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "Show Guests on Map (Fit)",
                                    color = if (visibleGuests.isNotEmpty()) ComposeColor.White else ComposeColor(0xFF64748B),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.CropFree,
                                    contentDescription = null,
                                    tint = if (visibleGuests.isNotEmpty()) ComposeColor(0xFF38BDF8) else ComposeColor(0xFF64748B),
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            onClick = {
                                onUserInteracted()
                                showGuestMenu = false
                                val map = mapView ?: return@DropdownMenuItem
                                if (visibleGuests.isNotEmpty()) {
                                    onFollowingChange(false)
                                    fitPointsToUnobstructedViewport(
                                        map = map,
                                        points = visibleGuests.map { it.geoPoint },
                                        density = density,
                                        isLandscape = isLandscape,
                                        isCompact = isCompact,
                                        measuredTopInsetPx = measuredTopInsetPx,
                                        measuredBottomInsetPx = measuredBottomInsetPx,
                                        isRecording = isRecording,
                                        isAutoStandby = isAutoStandby,
                                        onZoomCalculated = { onZoomChange(it) }
                                    )
                                }
                            },
                            enabled = visibleGuests.isNotEmpty()
                        )

                        // Visitor Details
                        if (onOpenLiveVisitorsDetails != null) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "Visitor Details",
                                        color = ComposeColor.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Info,
                                        contentDescription = null,
                                        tint = ComposeColor(0xFF38BDF8),
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                onClick = {
                                    onUserInteracted()
                                    showGuestMenu = false
                                    onOpenLiveVisitorsDetails()
                                }
                            )
                        }

                        HorizontalDivider(color = ComposeColor(0xFF334155))

                        // Show Inactive Visitors Toggle
                        DropdownMenuItem(
                            text = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Show Inactive Visitors",
                                        color = ComposeColor.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Normal,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Switch(
                                        checked = showInactiveGuests,
                                        onCheckedChange = {
                                            onUserInteracted()
                                            onToggleShowInactiveGuests(it)
                                        },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = ComposeColor.White,
                                            checkedTrackColor = ComposeColor(0xFF38BDF8),
                                            uncheckedThumbColor = ComposeColor(0xFF94A3B8),
                                            uncheckedTrackColor = ComposeColor(0xFF334155)
                                        )
                                    )
                                }
                            },
                            onClick = {
                                onUserInteracted()
                                onToggleShowInactiveGuests(!showInactiveGuests)
                            }
                        )

                        // Show Visitor Labels Toggle
                        DropdownMenuItem(
                            text = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Show Visitor Labels",
                                        color = ComposeColor.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Normal,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Switch(
                                        checked = showVisitorLabels,
                                        onCheckedChange = {
                                            onUserInteracted()
                                            onToggleShowVisitorLabels(it)
                                        },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = ComposeColor.White,
                                            checkedTrackColor = ComposeColor(0xFF38BDF8),
                                            uncheckedThumbColor = ComposeColor(0xFF94A3B8),
                                            uncheckedTrackColor = ComposeColor(0xFF334155)
                                        )
                                    )
                                }
                            },
                            onClick = {
                                onUserInteracted()
                                onToggleShowVisitorLabels(!showVisitorLabels)
                            }
                        )
                    }
                }
            }

            // Cache Deficit Alert & Progress Button
            val cacheManager = remember { CacheManager.getInstance(context) }
            val cacheDeficit by cacheManager.cacheDeficit.collectAsState()
            val prefetchState by cacheManager.prefetchState.collectAsState()
            LaunchedEffect(Unit) {
                if (cacheDeficit == null) {
                    cacheManager.calculateCacheDeficit()
                }
            }
            val totalMissingCache = (cacheDeficit?.missingRoutes ?: 0) + (cacheDeficit?.missingBoundaries ?: 0)

            val isPrefetchRunning = prefetchState is CacheManager.PrefetchState.Running
            val isPrefetchPaused = prefetchState is CacheManager.PrefetchState.Paused
            // Only show green Completed tick when zero missing items remain; if new/remaining missing items exist, show the amber warning badge!
            val isPrefetchCompleted = prefetchState is CacheManager.PrefetchState.Completed && totalMissingCache == 0

            val showCacheControl = (totalMissingCache > 0 || isPrefetchRunning || isPrefetchPaused || isPrefetchCompleted) && onOpenCacheManager != null

            if (showCacheControl) {
                val runningProgress: Float? = when (val s = prefetchState) {
                    is CacheManager.PrefetchState.Running -> if (s.total > 0) (s.current.toFloat() / s.total.toFloat()).coerceIn(0f, 1f) else null
                    is CacheManager.PrefetchState.Paused -> if (s.total > 0) (s.current.toFloat() / s.total.toFloat()).coerceIn(0f, 1f) else null
                    else -> null
                }
                val percentInt: Int? = when (val s = prefetchState) {
                    is CacheManager.PrefetchState.Running -> if (s.total > 0) ((s.current.toFloat() / s.total.toFloat()) * 100).toInt() else null
                    is CacheManager.PrefetchState.Paused -> if (s.total > 0) ((s.current.toFloat() / s.total.toFloat()) * 100).toInt() else null
                    else -> null
                }

                Box(contentAlignment = Alignment.TopEnd) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(ComposeColor(0xCC1E293B))
                            .clickable {
                                if (isPrefetchCompleted) {
                                    cacheManager.calculateCacheDeficit()
                                    val remaining = (cacheDeficit?.missingRoutes ?: 0) + (cacheDeficit?.missingBoundaries ?: 0)
                                    if (remaining > 0) {
                                        cacheManager.resetPrefetchState()
                                        onOpenCacheManager.invoke()
                                    } else {
                                        android.widget.Toast.makeText(
                                            context,
                                            "Offline data is up to date",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                        cacheManager.resetPrefetchState()
                                    }
                                } else {
                                    onOpenCacheManager.invoke()
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isPrefetchRunning || isPrefetchPaused) {
                            if (runningProgress != null) {
                                CircularProgressIndicator(
                                    progress = { runningProgress },
                                    modifier = Modifier.size(42.dp),
                                    color = ComposeColor(0xFF38BDF8),
                                    trackColor = ComposeColor(0x3338BDF8),
                                    strokeWidth = 3.dp
                                )
                            } else {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(42.dp),
                                    color = ComposeColor(0xFF38BDF8),
                                    trackColor = ComposeColor(0x3338BDF8),
                                    strokeWidth = 3.dp
                                )
                            }
                        }

                        val iconTint = when {
                            isPrefetchCompleted -> ComposeColor(0xFF10B981)
                            isPrefetchRunning || isPrefetchPaused -> ComposeColor(0xFF38BDF8)
                            else -> ComposeColor(0xFFF59E0B)
                        }

                        Icon(
                            imageVector = Icons.Default.Storage,
                            contentDescription = when {
                                isPrefetchCompleted -> "Offline Cache Complete"
                                isPrefetchRunning -> "Fetching Offline Cache..."
                                else -> "Missing Offline Cache Items ($totalMissingCache)"
                            },
                            tint = iconTint,
                            modifier = Modifier.size(21.dp)
                        )
                    }

                    // Badge top-right
                    when {
                        isPrefetchCompleted -> {
                            Box(
                                modifier = Modifier
                                    .offset(x = 4.dp, y = (-4).dp)
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .background(ComposeColor(0xFF10B981))
                                    .border(1.5.dp, ComposeColor(0xFF0F172A), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Completed",
                                    tint = ComposeColor.White,
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                        }
                        isPrefetchRunning || isPrefetchPaused -> {
                            Box(
                                modifier = Modifier
                                    .offset(x = 6.dp, y = (-5).dp)
                                    .sizeIn(minWidth = 18.dp, minHeight = 16.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(ComposeColor(0xFF0284C7))
                                    .border(1.2.dp, ComposeColor(0xFF0F172A), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 3.dp, vertical = 0.5.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (percentInt != null) "$percentInt%" else "${(prefetchState as? CacheManager.PrefetchState.Running)?.pass ?: 1}/3",
                                    color = ComposeColor.White,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    style = androidx.compose.ui.text.TextStyle(
                                        platformStyle = androidx.compose.ui.text.PlatformTextStyle(
                                            includeFontPadding = false
                                        )
                                    )
                                )
                            }
                        }
                        else -> {
                            Box(
                                modifier = Modifier
                                    .offset(x = 4.dp, y = (-4).dp)
                                    .sizeIn(minWidth = 18.dp, minHeight = 18.dp)
                                    .clip(CircleShape)
                                    .background(ComposeColor(0xFFF59E0B))
                                    .border(1.5.dp, ComposeColor(0xFF0F172A), CircleShape)
                                    .padding(horizontal = 4.dp, vertical = 1.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "!",
                                    color = ComposeColor.Black,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Black,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    style = androidx.compose.ui.text.TextStyle(
                                        platformStyle = androidx.compose.ui.text.PlatformTextStyle(
                                            includeFontPadding = false
                                        )
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // Fit Options Resolution
            val fitOptions = remember(
                selectedTrips,
                isRecording,
                trackPoints,
                visibleGuests,
                effectiveLatLng,
                activityProfile
            ) {
                val list = mutableListOf<FitTargetOption>()
                if (selectedTrips.isNotEmpty() && !isRecording) {
                    val pts = selectedTrips.flatMap { it.points }
                    if (pts.isNotEmpty()) {
                        list.add(FitTargetOption("Selected Trips", Icons.Default.CropFree, pts))
                    }
                } else {
                    val currentHostGp = effectiveLatLng?.let { GeoPoint(it.first, it.second) }
                    val tripPts = mutableListOf<GeoPoint>().apply {
                        addAll(trackPoints)
                        if (currentHostGp != null) add(currentHostGp)
                    }

                    if (isRecording || trackPoints.isNotEmpty()) {
                        list.add(FitTargetOption("Current Trip", Icons.Default.Route, tripPts))
                        val recentPts = extractRecentTrackSection(
                            trackPoints = trackPoints,
                            currentPos = currentHostGp,
                            targetDistanceMeters = activityProfile.recentSectionMeters
                        )
                        if (recentPts.isNotEmpty()) {
                            list.add(
                                FitTargetOption(
                                    "Recent Section (${activityProfile.recentSectionLabel})",
                                    Icons.Default.Timeline,
                                    recentPts
                                )
                            )
                        }
                    }

                    if ((isRecording || trackPoints.isNotEmpty()) && visibleGuests.isNotEmpty()) {
                        val combined = mutableListOf<GeoPoint>().apply {
                            addAll(tripPts)
                            addAll(visibleGuests.map { it.geoPoint })
                        }
                        list.add(FitTargetOption("Trip & Guests", Icons.Default.Groups, combined))
                    } else if (visibleGuests.isNotEmpty() && currentHostGp != null) {
                        val combined = mutableListOf<GeoPoint>().apply {
                            add(currentHostGp)
                            addAll(visibleGuests.map { it.geoPoint })
                        }
                        list.add(FitTargetOption("My Position & Guests", Icons.Default.Groups, combined))
                    }

                    if (visibleGuests.isNotEmpty()) {
                        list.add(FitTargetOption("Guests", Icons.Default.Group, visibleGuests.map { it.geoPoint }))
                    }
                }
                list
            }

            // Fit Shown Trips (Single-click immediate or multi-option popup menu)
            if (fitOptions.isNotEmpty()) {
                Box(contentAlignment = Alignment.TopEnd) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(ComposeColor(0xCC1E293B))
                            .clickable {
                                onUserInteracted()
                                val map = mapView ?: return@clickable
                                if (fitOptions.size == 1) {
                                    val opt = fitOptions.first()
                                    onFollowingChange(false)
                                    fitPointsToUnobstructedViewport(
                                        map = map,
                                        points = opt.points,
                                        density = density,
                                        isLandscape = isLandscape,
                                        isCompact = isCompact,
                                        measuredTopInsetPx = measuredTopInsetPx,
                                        measuredBottomInsetPx = measuredBottomInsetPx,
                                        isRecording = isRecording,
                                        isAutoStandby = isAutoStandby,
                                        onZoomCalculated = { onZoomChange(it) }
                                    )
                                } else {
                                    showFitMenu = !showFitMenu
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CropFree,
                            contentDescription = "Fit to Screen",
                            tint = ComposeColor(0xFF38BDF8),
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    if (fitOptions.size > 1) {
                        DropdownMenu(
                            expanded = showFitMenu,
                            onDismissRequest = { showFitMenu = false },
                            modifier = Modifier
                                .background(ComposeColor(0xF00F172A))
                                .border(1.dp, ComposeColor(0xFF334155), RoundedCornerShape(8.dp))
                                .widthIn(min = 210.dp, max = 280.dp)
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "Fit Viewport",
                                        color = ComposeColor(0xFF94A3B8),
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                },
                                onClick = { },
                                enabled = false
                            )

                            HorizontalDivider(color = ComposeColor(0xFF334155))

                            fitOptions.forEach { option ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = option.label,
                                            color = ComposeColor.White,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = option.icon,
                                            contentDescription = null,
                                            tint = ComposeColor(0xFF38BDF8),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    },
                                    onClick = {
                                        onUserInteracted()
                                        showFitMenu = false
                                        val map = mapView ?: return@DropdownMenuItem
                                        onFollowingChange(false)
                                        fitPointsToUnobstructedViewport(
                                            map = map,
                                            points = option.points,
                                            density = density,
                                            isLandscape = isLandscape,
                                            isCompact = isCompact,
                                            measuredTopInsetPx = measuredTopInsetPx,
                                            measuredBottomInsetPx = measuredBottomInsetPx,
                                            isRecording = isRecording,
                                            isAutoStandby = isAutoStandby,
                                            onZoomCalculated = { onZoomChange(it) }
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // Hide / Unselect Shown Historical Trips
            if (!isRecording && selectedTrips.isNotEmpty() && onClearSelectedTrips != null) {
                Box(contentAlignment = Alignment.TopEnd) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(ComposeColor(0xCC7F1D1D))
                            .clickable { onClearSelectedTrips.invoke() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Hide Shown Trips",
                            tint = ComposeColor(0xFFFCA5A5),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .offset(x = 4.dp, y = (-4).dp)
                            .sizeIn(minWidth = 18.dp, minHeight = 18.dp)
                            .clip(CircleShape)
                            .background(ComposeColor(0xFFEF4444))
                            .border(1.5.dp, ComposeColor(0xFF0F172A), CircleShape)
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${selectedTrips.size}",
                            color = ComposeColor.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            style = androidx.compose.ui.text.TextStyle(
                                platformStyle = androidx.compose.ui.text.PlatformTextStyle(
                                    includeFontPadding = false
                                )
                            )
                        )
                    }
                }
            }

            // Restore Zoom after inspecting dropped-pin or saved-place boundary
            if (pinnedBoundaryPoints != null && prePinZoom != null) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(ComposeColor(0xEE1E293B))
                        .clickable {
                            val map = mapView ?: return@clickable
                            val zoom = prePinZoom ?: return@clickable
                            val center = prePinCenter ?: return@clickable
                            map.controller.stopAnimation(false)
                            map.controller.setZoom(zoom)
                            map.controller.setCenter(center)
                            onRestorePrePin()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Restore Zoom",
                        tint = ComposeColor(0xFF38BDF8),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
}

@Composable
private fun BoxScope.MapRecenterPill(
    isFollowing: Boolean,
    destinationPoint: GeoPoint?,
    selectedSavedPlace: SavedPlace?,
    isLandscape: Boolean,
    showHeatMap: Boolean,
    isRecording: Boolean,
    isAutoStandby: Boolean,
    onClick: () -> Unit
) {
    if (!isFollowing && destinationPoint == null && selectedSavedPlace == null) {
        val recenterBottomPadding = if (isLandscape) {
            68.dp
        } else {
            when {
                showHeatMap && (isRecording || isAutoStandby) -> 180.dp
                showHeatMap                                   -> 136.dp
                isRecording || isAutoStandby                  -> 136.dp
                else                                          -> 78.dp
            }
        }
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(20.dp),
            color = ComposeColor(0xEE0284C7),
            shadowElevation = 6.dp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = recenterBottomPadding)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.MyLocation,
                    contentDescription = null,
                    tint = ComposeColor.White,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Recenter",
                    color = ComposeColor.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun BoxScope.MapHeatMapFloatingToolbar(
    showHeatMap: Boolean,
    isLandscape: Boolean,
    isRecording: Boolean,
    heatMapFilterActive: Boolean,
    onOpenHeatMapSettings: (() -> Unit)?,
    allHeatMapPoints: List<GeoPoint>,
    heatMapTracks: List<List<GeoPoint>>,
    mapView: MapView?,
    pinnedBoundaryPoints: List<GeoPoint>?,
    prePinZoom: Double?,
    prePinCenter: GeoPoint?,
    onRestorePrePin: () -> Unit,
    onToggleHeatMap: (() -> Unit)?,
    onFollowingChange: (Boolean) -> Unit
) {
        // Floating Heat Map Controls — only in portrait (in landscape, it is merged into the bottom app toolbar)
        if (showHeatMap && !isLandscape) {
            val heatMapBottomPadding = if (isRecording) 134.dp else 84.dp
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = ComposeColor(0xF00F172A),
                border = androidx.compose.foundation.BorderStroke(1.dp, ComposeColor(0xFFF97316)),
                shadowElevation = 8.dp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = heatMapBottomPadding)
                    .padding(horizontal = 8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Heat Map label
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Whatshot,
                            contentDescription = null,
                            tint = ComposeColor(0xFFF97316),
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = if (heatMapFilterActive) "Heat Map*" else "Heat Map",
                            color = ComposeColor.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }

                    // 0. Filter / Settings
                    if (onOpenHeatMapSettings != null) {
                        Surface(
                            onClick = { onOpenHeatMapSettings() },
                            shape = RoundedCornerShape(12.dp),
                            color = if (heatMapFilterActive) ComposeColor(0xFFEA580C) else ComposeColor(0xFF1E293B),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Box(
                                modifier = Modifier.fillMaxHeight().padding(horizontal = 7.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Tune,
                                        contentDescription = "Heat Map Filter",
                                        tint = if (heatMapFilterActive) ComposeColor.White else ComposeColor(0xFFF97316),
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        text = "Filter",
                                        color = if (heatMapFilterActive) ComposeColor.White else ComposeColor(0xFFF97316),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        softWrap = false,
                                        style = androidx.compose.ui.text.TextStyle(
                                            platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                                            lineHeight = 11.sp
                                        )
                                    )
                                }
                            }
                        }
                    }

                    // 1. Fit All
                    Surface(
                        onClick = {
                            val map = mapView ?: return@Surface
                            onFollowingChange(false)
                            val pts = if (allHeatMapPoints.isNotEmpty()) allHeatMapPoints else heatMapTracks.flatten()
                            if (pts.isNotEmpty()) {
                                try {
                                    val rawBox = BoundingBox.fromGeoPoints(pts)
                                    val minSpan = 0.008
                                    val latSpan = rawBox.latitudeSpan.coerceAtLeast(minSpan)
                                    val lonSpan = rawBox.longitudeSpan.coerceAtLeast(minSpan)
                                    val centerLat = rawBox.centerLatitude
                                    val centerLon = rawBox.centerLongitude
                                    val paddedBox = BoundingBox(
                                        centerLat + latSpan * 0.55,
                                        centerLon + lonSpan * 0.55,
                                        centerLat - latSpan * 0.55,
                                        centerLon - lonSpan * 0.55
                                    )
                                    map.zoomToBoundingBox(paddedBox, true, 120)
                                } catch (e: Exception) {}
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        color = ComposeColor(0xFF1E293B),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Box(
                            modifier = Modifier.fillMaxHeight().padding(horizontal = 7.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CropFree,
                                    contentDescription = "Fit All to Map",
                                    tint = ComposeColor(0xFF38BDF8),
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = "Fit All",
                                    color = ComposeColor(0xFF38BDF8),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    softWrap = false,
                                    style = androidx.compose.ui.text.TextStyle(
                                        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                                        lineHeight = 11.sp
                                    )
                                )
                            }
                        }
                    }

                    // 2. Close Heat Map
                    Surface(
                        onClick = { onToggleHeatMap?.invoke() },
                        shape = RoundedCornerShape(12.dp),
                        color = ComposeColor(0xFF7F1D1D),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Box(
                            modifier = Modifier.fillMaxHeight().padding(horizontal = 7.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close Heat Map",
                                    tint = ComposeColor(0xFFFCA5A5),
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = "Close",
                                    color = ComposeColor(0xFFFCA5A5),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    softWrap = false,
                                    style = androidx.compose.ui.text.TextStyle(
                                        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                                        lineHeight = 11.sp
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // Restore Zoom after inspecting dropped-pin or saved-place boundary
            if (pinnedBoundaryPoints != null && prePinZoom != null) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(ComposeColor(0xEE1E293B))
                        .clickable {
                            val map = mapView ?: return@clickable
                            val zoom = prePinZoom ?: return@clickable
                            val center = prePinCenter ?: return@clickable
                            map.controller.stopAnimation(false)
                            map.controller.setZoom(zoom)
                            map.controller.setCenter(center)
                            onRestorePrePin()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Restore Zoom",
                        tint = ComposeColor(0xFF38BDF8),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
}

@Composable
private fun MapSettingsDialogHost(
    activityProfile: ActivityProfile,
    baseLayer: MapBaseLayer,
    onBaseLayerChange: (MapBaseLayer) -> Unit,
    showHikingOverlay: Boolean,
    onHikingOverlayToggle: (Boolean) -> Unit,
    showOutdoorAreasOverlay: Boolean = false,
    onOutdoorAreasOverlayToggle: (Boolean) -> Unit = {},
    trailThickness: TrailThickness = TrailThickness.NORMAL,
    onTrailThicknessChange: (TrailThickness) -> Unit = {},
    showBorders: Boolean,
    onToggleBorders: (() -> Unit)?,
    showHeatMap: Boolean,
    onToggleHeatMap: (() -> Unit)?,
    showInactiveGuests: Boolean,
    onShowInactiveGuestsToggle: (Boolean) -> Unit,
    showVisitorLabels: Boolean,
    onShowVisitorLabelsToggle: (Boolean) -> Unit,
    autoRecenterEnabled: Boolean,
    onAutoRecenterToggle: (Boolean) -> Unit,
    autoRecenterTimeoutSec: Int,
    onAutoRecenterTimeoutSecChange: (Int) -> Unit,
    compassArrowEnabled: Boolean,
    onCompassArrowToggle: (Boolean) -> Unit,
    showAltitudeOnMap: Boolean,
    onShowAltitudeToggle: (Boolean) -> Unit,
    showFilteredPolyline: Boolean,
    onShowFilteredPolylineToggle: (Boolean) -> Unit,
    showRawPolyline: Boolean,
    onShowRawPolylineToggle: (Boolean) -> Unit,
    altitudeSourceMode: AltitudeSourceMode,
    onAltitudeSourceModeChange: ((AltitudeSourceMode) -> Unit)?,
    fontScale: MapFontScale,
    onFontScaleChange: (MapFontScale) -> Unit,
    orientationMode: MapOrientationMode,
    onOrientationModeChange: ((MapOrientationMode) -> Unit)?,
    effectiveLatLng: Triple<Double, Double, Float?>?,
    lastFrozenBearing: Float?,
    mapView: MapView?,
    hikingProviderRef: org.osmdroid.tileprovider.MapTileProviderBasic?,
    trackColorHex: String,
    onTrackColorChange: (String) -> Unit,
    onOpenCacheManager: (() -> Unit)?,
    onDismiss: () -> Unit,
    altitudeOverlay: AltitudeCursorBadgeOverlay,
    context: Context
) {
    MapSettingsDialog(
        activityProfile = activityProfile,
        currentBaseLayer = baseLayer,
        onBaseLayerChange = onBaseLayerChange,
        hikingOverlayEnabled = showHikingOverlay,
        onHikingOverlayToggle = onHikingOverlayToggle,
        showOutdoorAreasOverlay = showOutdoorAreasOverlay,
        onOutdoorAreasOverlayToggle = onOutdoorAreasOverlayToggle,
        trailThickness = trailThickness,
        onTrailThicknessChange = onTrailThicknessChange,
        showBorders = showBorders,
        onToggleBorders = { onToggleBorders?.invoke() },
        showHeatMap = showHeatMap,
        onToggleHeatMap = { onToggleHeatMap?.invoke() },
        showInactiveGuests = showInactiveGuests,
        onShowInactiveGuestsToggle = onShowInactiveGuestsToggle,
        showVisitorLabels = showVisitorLabels,
        onShowVisitorLabelsToggle = onShowVisitorLabelsToggle,
        autoRecenterEnabled = autoRecenterEnabled,
        onAutoRecenterToggle = onAutoRecenterToggle,
        autoRecenterTimeoutSec = autoRecenterTimeoutSec,
        onAutoRecenterTimeoutSecChange = onAutoRecenterTimeoutSecChange,
        compassArrowEnabled = compassArrowEnabled,
        onCompassArrowToggle = onCompassArrowToggle,
        showAltitudeEnabled = showAltitudeOnMap,
        onShowAltitudeToggle = onShowAltitudeToggle,
        showFilteredPolylineEnabled = showFilteredPolyline,
        onShowFilteredPolylineToggle = onShowFilteredPolylineToggle,
        showRawPolylineEnabled = showRawPolyline,
        onShowRawPolylineToggle = onShowRawPolylineToggle,
        currentAltitudeSourceMode = altitudeSourceMode,
        onAltitudeSourceModeChange = { onAltitudeSourceModeChange?.invoke(it) },
        currentFontScale = fontScale,
        onFontScaleChange = onFontScaleChange,
        currentOrientationMode = orientationMode,
        onOrientationModeChange = { onOrientationModeChange?.invoke(it) },
        currentLatLng = effectiveLatLng,
        onClearCache = {
            mapView?.tileProvider?.clearTileCache()
            hikingProviderRef?.clearTileCache()
            mapView?.invalidate()
            android.widget.Toast.makeText(context, "Map tile cache cleared", android.widget.Toast.LENGTH_SHORT).show()
        },
        onOpenCacheManager = { onOpenCacheManager?.invoke() },
        onResetProfileDefaults = {
            MapProfileConfigHelper.resetProfileDefaults(context, activityProfile)
            val cfg = MapProfileConfigHelper.getConfig(context, activityProfile)
            onBaseLayerChange(cfg.baseLayer)
            onHikingOverlayToggle(cfg.showHikingOverlay)
            onOutdoorAreasOverlayToggle(cfg.showOutdoorAreasOverlay)
            onTrailThicknessChange(cfg.trailThickness)
            onFontScaleChange(cfg.fontScale)
            onTrackColorChange(cfg.trackColorHex)
            onOrientationModeChange?.invoke(cfg.orientationMode)
            android.widget.Toast.makeText(context, "Reset defaults for ${activityProfile.displayName}", android.widget.Toast.LENGTH_SHORT).show()
        },
        onResetAllProfileDefaults = {
            MapProfileConfigHelper.resetAllProfileDefaults(context)
            val cfg = MapProfileConfigHelper.getConfig(context, activityProfile)
            onBaseLayerChange(cfg.baseLayer)
            onHikingOverlayToggle(cfg.showHikingOverlay)
            onOutdoorAreasOverlayToggle(cfg.showOutdoorAreasOverlay)
            onTrailThicknessChange(cfg.trailThickness)
            onFontScaleChange(cfg.fontScale)
            onTrackColorChange(cfg.trackColorHex)
            onOrientationModeChange?.invoke(cfg.orientationMode)
            android.widget.Toast.makeText(context, "Reset all profile defaults", android.widget.Toast.LENGTH_SHORT).show()
        },
        currentTrackColorHex = trackColorHex,
        onTrackColorChange = onTrackColorChange,
        onDismiss = onDismiss
    )
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MapSettingsDialog(
    activityProfile: ActivityProfile,
    currentBaseLayer: MapBaseLayer,
    onBaseLayerChange: (MapBaseLayer) -> Unit,
    hikingOverlayEnabled: Boolean,
    onHikingOverlayToggle: (Boolean) -> Unit,
    showOutdoorAreasOverlay: Boolean = false,
    onOutdoorAreasOverlayToggle: (Boolean) -> Unit = {},
    trailThickness: TrailThickness = TrailThickness.NORMAL,
    onTrailThicknessChange: (TrailThickness) -> Unit = {},
    showBorders: Boolean = false,
    onToggleBorders: () -> Unit = {},
    showHeatMap: Boolean = false,
    onToggleHeatMap: () -> Unit = {},
    showInactiveGuests: Boolean = true,
    onShowInactiveGuestsToggle: (Boolean) -> Unit = {},
    showVisitorLabels: Boolean = true,
    onShowVisitorLabelsToggle: (Boolean) -> Unit = {},
    autoRecenterEnabled: Boolean = true,
    onAutoRecenterToggle: (Boolean) -> Unit = {},
    autoRecenterTimeoutSec: Int = 60,
    onAutoRecenterTimeoutSecChange: (Int) -> Unit = {},
    compassArrowEnabled: Boolean = true,
    onCompassArrowToggle: (Boolean) -> Unit = {},
    showAltitudeEnabled: Boolean = true,
    onShowAltitudeToggle: (Boolean) -> Unit = {},
    showFilteredPolylineEnabled: Boolean = true,
    onShowFilteredPolylineToggle: (Boolean) -> Unit = {},
    showRawPolylineEnabled: Boolean = false,
    onShowRawPolylineToggle: (Boolean) -> Unit = {},
    currentAltitudeSourceMode: AltitudeSourceMode = AltitudeSourceMode.DEM,
    onAltitudeSourceModeChange: (AltitudeSourceMode) -> Unit = {},
    currentTrackColorHex: String = "#EF4444",
    onTrackColorChange: (String) -> Unit = {},
    currentFontScale: MapFontScale,
    onFontScaleChange: (MapFontScale) -> Unit,
    currentOrientationMode: MapOrientationMode,
    onOrientationModeChange: (MapOrientationMode) -> Unit,
    currentLatLng: Triple<Double, Double, Float?>? = null,
    onClearCache: () -> Unit,
    onOpenCacheManager: () -> Unit,
    onResetProfileDefaults: () -> Unit,
    onResetAllProfileDefaults: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val cacheDownloadState by MapCacheHelper.downloadState.collectAsState()
    val scrollState = rememberScrollState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(cacheDownloadState) {
        if (cacheDownloadState !is CacheDownloadState.Idle) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ComposeColor(0xFF0F172A),
        tonalElevation = 8.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Layers,
                        contentDescription = null,
                        tint = ComposeColor(0xFF38BDF8),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Map Settings & Layers",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = ComposeColor.White
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(ComposeColor(0xFF1E293B))
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close",
                        tint = ComposeColor.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Map Orientation Mode (Course-Up AUTO vs Fixed North)
            Text(
                text = "Map Orientation",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = ComposeColor(0xFF94A3B8)
            )
            // Row 1: AUTO + North-Up
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    MapOrientationMode.COURSE_UP to "AUTO\nCourse-Up",
                    MapOrientationMode.NORTH to "North-Up\n(0°)"
                ).forEach { (mode, label) ->
                    val isSelected = currentOrientationMode == mode
                    Button(
                        onClick = { onOrientationModeChange(mode) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSelected) ComposeColor(0xFF0284C7) else ComposeColor(0xFF1E293B)
                        ),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = label,
                            fontSize = 11.sp,
                            color = ComposeColor.White,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            style = androidx.compose.ui.text.TextStyle(
                                platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                                lineHeight = 13.sp
                            )
                        )
                    }
                }
            }
            // Row 2: East + South + West
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    MapOrientationMode.EAST to "East-Up\n(90°)",
                    MapOrientationMode.SOUTH to "South-Up\n(180°)",
                    MapOrientationMode.WEST to "West-Up\n(270°)"
                ).forEach { (mode, label) ->
                    val isSelected = currentOrientationMode == mode
                    Button(
                        onClick = { onOrientationModeChange(mode) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSelected) ComposeColor(0xFF0284C7) else ComposeColor(0xFF1E293B)
                        ),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = label,
                            fontSize = 11.sp,
                            color = ComposeColor.White,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            style = androidx.compose.ui.text.TextStyle(
                                platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                                lineHeight = 13.sp
                            )
                        )
                    }
                }
            }

            HorizontalDivider(color = ComposeColor(0xFF334155))

            // Base Map Selection
            Text(
                text = "Base Map",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = ComposeColor(0xFF94A3B8)
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                MapBaseLayer.values().forEach { layer ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onBaseLayerChange(layer) }
                            .padding(vertical = 2.dp, horizontal = 4.dp)
                    ) {
                        RadioButton(
                            selected = (layer == currentBaseLayer),
                            onClick = { onBaseLayerChange(layer) },
                            modifier = Modifier.size(24.dp),
                            colors = RadioButtonDefaults.colors(
                                selectedColor = ComposeColor(0xFF38BDF8),
                                unselectedColor = ComposeColor(0xFF64748B)
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = layer.label,
                                fontSize = 13.5.sp,
                                color = if (layer == currentBaseLayer) ComposeColor.White else ComposeColor(0xFFCBD5E1),
                                fontWeight = if (layer == currentBaseLayer) FontWeight.Bold else FontWeight.Normal,
                                style = androidx.compose.ui.text.TextStyle(
                                    platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                                    lineHeight = 15.sp
                                )
                            )
                            if (layer == MapBaseLayer.FREEMAP_OUTDOOR) {
                                Text(
                                    text = stringResource(R.string.freemap_outdoor_description),
                                    fontSize = 11.sp,
                                    color = ComposeColor(0xFF10B981),
                                    style = androidx.compose.ui.text.TextStyle(
                                        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                                        lineHeight = 13.sp
                                    )
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = ComposeColor(0xFF334155))

            // Hiking / Tourist Trails Overlay
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onHikingOverlayToggle(!hikingOverlayEnabled) }
                    .padding(vertical = 4.dp, horizontal = 6.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Hiking Trails & Peaks",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = ComposeColor.White,
                        style = androidx.compose.ui.text.TextStyle(
                            platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                            lineHeight = 15.sp
                        )
                    )
                    Text(
                        text = "Marked color-coded trails & summits (Waymarked Trails)",
                        fontSize = 11.sp,
                        color = ComposeColor(0xFF94A3B8),
                        modifier = Modifier.padding(top = 2.dp),
                        style = androidx.compose.ui.text.TextStyle(
                            platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                            lineHeight = 14.sp
                        )
                    )
                }
                Switch(
                    checked = hikingOverlayEnabled,
                    onCheckedChange = { onHikingOverlayToggle(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = ComposeColor.White,
                        checkedTrackColor = ComposeColor(0xFF10B981),
                        uncheckedThumbColor = ComposeColor(0xFF94A3B8),
                        uncheckedTrackColor = ComposeColor(0xFF334155)
                    )
                )
            }

            // Trail Line Thickness (Standard, Thick, Bold) directly under Hiking Trails & Peaks
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Trail Line Thickness",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (hikingOverlayEnabled) ComposeColor(0xFF94A3B8) else ComposeColor(0xFF64748B)
                    )
                    if (!hikingOverlayEnabled) {
                        Text(
                            text = "(Hiking Trails OFF)",
                            fontSize = 10.sp,
                            color = ComposeColor(0xFFFBBF24)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TrailThickness.values().forEach { thickness ->
                        val isSelected = trailThickness == thickness
                        Button(
                            onClick = { onTrailThicknessChange(thickness) },
                            enabled = hikingOverlayEnabled,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isSelected) ComposeColor(0xFF0284C7) else ComposeColor(0xFF1E293B),
                                disabledContainerColor = if (isSelected) ComposeColor(0xFF0284C7).copy(alpha = 0.4f) else ComposeColor(0xFF1E293B).copy(alpha = 0.4f)
                            ),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = when (thickness) {
                                    TrailThickness.NORMAL -> "Standard"
                                    TrailThickness.THICK -> "Thick"
                                    TrailThickness.EXTRA_THICK -> "Bold"
                                },
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = ComposeColor.White,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = ComposeColor(0xFF334155))

            // Outdoor & Protected Areas (Mountain Ranges, National & Landscape Parks, Reserves)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onOutdoorAreasOverlayToggle(!showOutdoorAreasOverlay) }
                    .padding(vertical = 4.dp, horizontal = 6.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Outdoor & Protected Areas",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = ComposeColor.White,
                        style = androidx.compose.ui.text.TextStyle(
                            platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                            lineHeight = 15.sp
                        )
                    )
                    Text(
                        text = "Mountain ranges (Beskidy, Tatry), national/landscape parks & nature reserves",
                        fontSize = 11.sp,
                        color = ComposeColor(0xFF94A3B8),
                        modifier = Modifier.padding(top = 2.dp),
                        style = androidx.compose.ui.text.TextStyle(
                            platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                            lineHeight = 14.sp
                        )
                    )
                }
                Switch(
                    checked = showOutdoorAreasOverlay,
                    onCheckedChange = { onOutdoorAreasOverlayToggle(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = ComposeColor.White,
                        checkedTrackColor = ComposeColor(0xFF10B981),
                        uncheckedThumbColor = ComposeColor(0xFF94A3B8),
                        uncheckedTrackColor = ComposeColor(0xFF334155)
                    )
                )
            }

            HorizontalDivider(color = ComposeColor(0xFF334155))

            // City / Town / Village Borders
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onToggleBorders() }
                    .padding(vertical = 4.dp, horizontal = 6.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Show City/Town Borders",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = ComposeColor.White,
                        style = androidx.compose.ui.text.TextStyle(
                            platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                            lineHeight = 15.sp
                        )
                    )
                    Text(
                        text = "Highlights territorial boundaries of the current locality on the map",
                        fontSize = 11.sp,
                        color = ComposeColor(0xFF94A3B8),
                        modifier = Modifier.padding(top = 2.dp),
                        style = androidx.compose.ui.text.TextStyle(
                            platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                            lineHeight = 14.sp
                        )
                    )
                }
                Switch(
                    checked = showBorders,
                    onCheckedChange = { onToggleBorders() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = ComposeColor.White,
                        checkedTrackColor = ComposeColor(0xFF0284C7),
                        uncheckedThumbColor = ComposeColor(0xFF94A3B8),
                        uncheckedTrackColor = ComposeColor(0xFF334155)
                    )
                )
            }

            HorizontalDivider(color = ComposeColor(0xFF334155))

            // Road & Path Heat Map
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onToggleHeatMap() }
                    .padding(vertical = 4.dp, horizontal = 6.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Road & Path Heat Map",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = ComposeColor.White,
                        style = androidx.compose.ui.text.TextStyle(
                            platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                            lineHeight = 15.sp
                        )
                    )
                    Text(
                        text = "Visualizes all previously traveled routes as glowing heat corridors",
                        fontSize = 11.sp,
                        color = ComposeColor(0xFF94A3B8),
                        modifier = Modifier.padding(top = 2.dp),
                        style = androidx.compose.ui.text.TextStyle(
                            platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                            lineHeight = 14.sp
                        )
                    )
                }
                Switch(
                    checked = showHeatMap,
                    onCheckedChange = { onToggleHeatMap() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = ComposeColor.White,
                        checkedTrackColor = ComposeColor(0xFFF97316),
                        uncheckedThumbColor = ComposeColor(0xFF94A3B8),
                        uncheckedTrackColor = ComposeColor(0xFF334155)
                    )
                )
            }

            HorizontalDivider(color = ComposeColor(0xFF334155))

            // Compass arrow (on foot)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onCompassArrowToggle(!compassArrowEnabled) }
                    .padding(vertical = 4.dp, horizontal = 6.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Compass Arrow",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = ComposeColor.White,
                        style = androidx.compose.ui.text.TextStyle(
                            platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                            lineHeight = 15.sp
                        )
                    )
                    Text(
                        text = "Walking, hiking, running: arrow from the phone compass, checked against your track",
                        fontSize = 11.sp,
                        color = ComposeColor(0xFF94A3B8),
                        modifier = Modifier.padding(top = 2.dp),
                        style = androidx.compose.ui.text.TextStyle(
                            platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                            lineHeight = 14.sp
                        )
                    )
                }
                Switch(
                    checked = compassArrowEnabled,
                    onCheckedChange = { onCompassArrowToggle(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = ComposeColor.White,
                        checkedTrackColor = ComposeColor(0xFF10B981),
                        uncheckedThumbColor = ComposeColor(0xFF94A3B8),
                        uncheckedTrackColor = ComposeColor(0xFF334155)
                    )
                )
            }

            HorizontalDivider(color = ComposeColor(0xFF334155))

            // Altitude on Map (Beside Cursor)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onShowAltitudeToggle(!showAltitudeEnabled) }
                    .padding(vertical = 4.dp, horizontal = 6.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Altitude on Map",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = ComposeColor.White,
                        style = androidx.compose.ui.text.TextStyle(
                            platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                            lineHeight = 15.sp
                        )
                    )
                    Text(
                        text = "Show elevation badge beside vehicle cursor",
                        fontSize = 11.sp,
                        color = ComposeColor(0xFF94A3B8),
                        modifier = Modifier.padding(top = 2.dp),
                        style = androidx.compose.ui.text.TextStyle(
                            platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                            lineHeight = 14.sp
                        )
                    )
                }
                Switch(
                    checked = showAltitudeEnabled,
                    onCheckedChange = { onShowAltitudeToggle(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = ComposeColor.White,
                        checkedTrackColor = ComposeColor(0xFF10B981),
                        uncheckedThumbColor = ComposeColor(0xFF94A3B8),
                        uncheckedTrackColor = ComposeColor(0xFF334155)
                    )
                )
            }

            if (showAltitudeEnabled) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp)
                ) {
                    Text(
                        text = "Altitude Source",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ComposeColor(0xFF94A3B8)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            AltitudeSourceMode.DEM to "DEM (Elevation Model)",
                            AltitudeSourceMode.GPS to "GPS Direct"
                        ).forEach { (mode, label) ->
                            val isSelected = currentAltitudeSourceMode == mode
                            Button(
                                onClick = { onAltitudeSourceModeChange(mode) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isSelected) ComposeColor(0xFF0284C7) else ComposeColor(0xFF1E293B)
                                ),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    label,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = ComposeColor.White,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                    Text(
                        text = if (currentAltitudeSourceMode == AltitudeSourceMode.DEM)
                            "Topographic elevation model (Open-Meteo) with SQLite caching & instant GPS fallback (dashed border while resolving)"
                        else
                            "Raw direct GPS satellite altitude (dashed border)",
                        fontSize = 10.5.sp,
                        color = ComposeColor(0xFF64748B),
                        modifier = Modifier.padding(top = 4.dp),
                        style = androidx.compose.ui.text.TextStyle(
                            platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                            lineHeight = 13.sp
                        )
                    )
                }
            }

            HorizontalDivider(color = ComposeColor(0xFF334155))

            // Live Sharing Visitors
            Text(
                text = "Live Visitors",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = ComposeColor(0xFF94A3B8)
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onShowInactiveGuestsToggle(!showInactiveGuests) }
                    .padding(vertical = 4.dp, horizontal = 6.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Show Inactive Visitors",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = ComposeColor.White,
                        style = androidx.compose.ui.text.TextStyle(
                            platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                            lineHeight = 15.sp
                        )
                    )
                    Text(
                        text = "Display visitor avatars on the map even after >5 minutes without telemetry updates",
                        fontSize = 11.sp,
                        color = ComposeColor(0xFF94A3B8),
                        modifier = Modifier.padding(top = 2.dp),
                        style = androidx.compose.ui.text.TextStyle(
                            platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                            lineHeight = 14.sp
                        )
                    )
                }
                Switch(
                    checked = showInactiveGuests,
                    onCheckedChange = { onShowInactiveGuestsToggle(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = ComposeColor.White,
                        checkedTrackColor = ComposeColor(0xFF38BDF8),
                        uncheckedThumbColor = ComposeColor(0xFF94A3B8),
                        uncheckedTrackColor = ComposeColor(0xFF334155)
                    )
                )
            }

            // Show Visitor Labels
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onShowVisitorLabelsToggle(!showVisitorLabels) }
                    .padding(vertical = 4.dp, horizontal = 6.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Show Visitor Labels",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = ComposeColor.White,
                        style = androidx.compose.ui.text.TextStyle(
                            platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                            lineHeight = 15.sp
                        )
                    )
                    Text(
                        text = "Attach name, distance, and time pill badge below visitor avatars",
                        fontSize = 11.sp,
                        color = ComposeColor(0xFF94A3B8),
                        modifier = Modifier.padding(top = 2.dp),
                        style = androidx.compose.ui.text.TextStyle(
                            platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                            lineHeight = 14.sp
                        )
                    )
                }
                Switch(
                    checked = showVisitorLabels,
                    onCheckedChange = { onShowVisitorLabelsToggle(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = ComposeColor.White,
                        checkedTrackColor = ComposeColor(0xFF38BDF8),
                        uncheckedThumbColor = ComposeColor(0xFF94A3B8),
                        uncheckedTrackColor = ComposeColor(0xFF334155)
                    )
                )
            }

            HorizontalDivider(color = ComposeColor(0xFF334155))

            // Auto-Recenter Section
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onAutoRecenterToggle(!autoRecenterEnabled) }
                    .padding(vertical = 4.dp, horizontal = 6.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Auto-Recenter",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = ComposeColor.White,
                        style = androidx.compose.ui.text.TextStyle(
                            platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                            lineHeight = 15.sp
                        )
                    )
                    Text(
                        text = "Automatically recenter map when your position is off-screen and map is untouched",
                        fontSize = 11.sp,
                        color = ComposeColor(0xFF94A3B8),
                        modifier = Modifier.padding(top = 2.dp),
                        style = androidx.compose.ui.text.TextStyle(
                            platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                            lineHeight = 14.sp
                        )
                    )
                }
                Switch(
                    checked = autoRecenterEnabled,
                    onCheckedChange = { onAutoRecenterToggle(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = ComposeColor.White,
                        checkedTrackColor = ComposeColor(0xFF10B981),
                        uncheckedThumbColor = ComposeColor(0xFF94A3B8),
                        uncheckedTrackColor = ComposeColor(0xFF334155)
                    )
                )
            }

            if (autoRecenterEnabled) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp)
                ) {
                    Text(
                        text = "Inactivity Timeout",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ComposeColor(0xFF94A3B8)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            30 to "30s",
                            60 to "60s (Default)",
                            120 to "2 min"
                        ).forEach { (seconds, label) ->
                            val isSelected = autoRecenterTimeoutSec == seconds
                            Button(
                                onClick = { onAutoRecenterTimeoutSecChange(seconds) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isSelected) ComposeColor(0xFF0284C7) else ComposeColor(0xFF1E293B)
                                ),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    label,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = ComposeColor.White,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = ComposeColor(0xFF334155))

            // Map Font & Label Scaling
            Text(
                text = "Map Labels Size",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = ComposeColor(0xFF94A3B8)
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                MapFontScale.values().forEach { scale ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onFontScaleChange(scale) }
                            .padding(vertical = 2.dp, horizontal = 4.dp)
                    ) {
                        RadioButton(
                            selected = (scale == currentFontScale),
                            onClick = { onFontScaleChange(scale) },
                            modifier = Modifier.size(24.dp),
                            colors = RadioButtonDefaults.colors(
                                selectedColor = ComposeColor(0xFF38BDF8),
                                unselectedColor = ComposeColor(0xFF64748B)
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = scale.label,
                            fontSize = 13.5.sp,
                            color = if (scale == currentFontScale) ComposeColor.White else ComposeColor(0xFFCBD5E1),
                            style = androidx.compose.ui.text.TextStyle(
                                platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                                lineHeight = 15.sp
                            )
                        )
                    }
                }
            }

            // Raster Tile Limitation Notice (Item FT2-15 & FT2-13)
            Surface(
                color = ComposeColor(0xFF1E293B),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = ComposeColor(0xFF94A3B8),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Map tiles are pre-rendered bitmap images. Scaling above Standard stretches raster pixels. For razor-sharp labels and official PTTK trail colors, select 'Freemap Outdoor (PTTK Szlaki & Hi-DPI)'. In COURSE_UP mode, bitmap labels rotate with the map; use North-Up for upright labels.",
                        fontSize = 11.sp,
                        color = ComposeColor(0xFF94A3B8),
                        lineHeight = 15.sp
                    )
                }
            }

            HorizontalDivider(color = ComposeColor(0xFF334155))

            // Trip Polylines (Filtered & Raw)
            Text(
                text = "Trip Polylines",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = ComposeColor(0xFF94A3B8)
            )

            // 1. Show Filtered Trip Line
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onShowFilteredPolylineToggle(!showFilteredPolylineEnabled) }
                    .padding(vertical = 4.dp, horizontal = 6.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Show Filtered Trip Line",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = ComposeColor.White,
                        style = androidx.compose.ui.text.TextStyle(
                            platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                            lineHeight = 15.sp
                        )
                    )
                    Text(
                        text = "Smooth, kinematic-filtered route line (standard)",
                        fontSize = 11.sp,
                        color = ComposeColor(0xFF94A3B8),
                        modifier = Modifier.padding(top = 2.dp),
                        style = androidx.compose.ui.text.TextStyle(
                            platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                            lineHeight = 14.sp
                        )
                    )
                }
                Switch(
                    checked = showFilteredPolylineEnabled,
                    onCheckedChange = { onShowFilteredPolylineToggle(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = ComposeColor.White,
                        checkedTrackColor = ComposeColor(0xFF10B981),
                        uncheckedThumbColor = ComposeColor(0xFF94A3B8),
                        uncheckedTrackColor = ComposeColor(0xFF334155)
                    )
                )
            }

            // 2. Show Raw GNSS Line
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onShowRawPolylineToggle(!showRawPolylineEnabled) }
                    .padding(vertical = 4.dp, horizontal = 6.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Show Raw GNSS Line",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = ComposeColor.White,
                        style = androidx.compose.ui.text.TextStyle(
                            platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                            lineHeight = 15.sp
                        )
                    )
                    Text(
                        text = "Thin cyan line connecting every raw GPS antenna fix",
                        fontSize = 11.sp,
                        color = ComposeColor(0xFF94A3B8),
                        modifier = Modifier.padding(top = 2.dp),
                        style = androidx.compose.ui.text.TextStyle(
                            platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                            lineHeight = 14.sp
                        )
                    )
                }
                Switch(
                    checked = showRawPolylineEnabled,
                    onCheckedChange = { onShowRawPolylineToggle(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = ComposeColor.White,
                        checkedTrackColor = ComposeColor(0xFF06B6D4),
                        uncheckedThumbColor = ComposeColor(0xFF94A3B8),
                        uncheckedTrackColor = ComposeColor(0xFF334155)
                    )
                )
            }

            if (!showFilteredPolylineEnabled && !showRawPolylineEnabled) {
                Text(
                    text = "Disabling both lines pauses map line rendering to save battery. Trip recording continues in full fidelity.",
                    fontSize = 11.sp,
                    color = ComposeColor(0xFF10B981),
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    style = androidx.compose.ui.text.TextStyle(
                        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                        lineHeight = 14.sp
                    )
                )
            }

            HorizontalDivider(color = ComposeColor(0xFF334155))

            // Recording Track Color (per activity profile)
            Text(
                text = "Track Color (${activityProfile.displayName})",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = ComposeColor(0xFF94A3B8)
            )
            val availableColors = listOf(
                "#EF4444" to "Red",
                "#F59E0B" to "Amber",
                "#10B981" to "Emerald",
                "#38BDF8" to "Sky Blue",
                "#8B5CF6" to "Purple",
                "#EC4899" to "Pink",
                "#06B6D4" to "Cyan",
                "#F97316" to "Orange"
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                availableColors.forEach { (colorHex, _) ->
                    val isSelected = currentTrackColorHex.equals(colorHex, ignoreCase = true)
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(ComposeColor(android.graphics.Color.parseColor(colorHex)))
                            .border(
                                width = if (isSelected) 3.dp else 1.dp,
                                color = if (isSelected) ComposeColor.White else ComposeColor(0x55FFFFFF),
                                shape = CircleShape
                            )
                            .clickable { onTrackColorChange(colorHex) },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Selected",
                                tint = ComposeColor.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = ComposeColor(0xFF334155))

            // Profile Map Defaults & Reset Section
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Profile Defaults (${activityProfile.displayName})",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ComposeColor(0xFF94A3B8)
                )
                Text(
                    text = "Saved Automatically",
                    fontSize = 11.sp,
                    color = ComposeColor(0xFF10B981),
                    fontWeight = FontWeight.Medium
                )
            }

            Text(
                text = "Map type, orientation, trail overlays, and label size are remembered separately for ${activityProfile.displayName}.",
                fontSize = 11.sp,
                color = ComposeColor(0xFF64748B),
                lineHeight = 15.sp
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onResetProfileDefaults,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ComposeColor(0xFF334155),
                        contentColor = ComposeColor.White
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "Reset ${activityProfile.displayName}",
                        fontSize = 11.sp,
                        color = ComposeColor.White,
                        maxLines = 1,
                        softWrap = false
                    )
                }

                Button(
                    onClick = onResetAllProfileDefaults,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ComposeColor(0xFF1E293B),
                        contentColor = ComposeColor.White
                    ),
                    border = BorderStroke(1.dp, ComposeColor(0xFF475569)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "Reset All Profiles",
                        fontSize = 11.sp,
                        color = ComposeColor.White,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            HorizontalDivider(color = ComposeColor(0xFF334155))

            // Offline Map Cache & Pre-download Section (Consolidated into Map Settings)
            Text(
                text = "Offline Map Cache",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = ComposeColor(0xFF94A3B8)
            )

            val tileDir = remember { Configuration.getInstance().osmdroidTileCache }
            var tileCacheSizeMb by remember {
                mutableStateOf(
                    try {
                        var size = 0L
                        tileDir.walkTopDown().forEach { if (it.isFile) size += it.length() }
                        size / (1024 * 1024)
                    } catch (_: Exception) { 0L }
                )
            }

            LaunchedEffect(cacheDownloadState) {
                if (cacheDownloadState is CacheDownloadState.Completed) {
                    try {
                        var size = 0L
                        tileDir.walkTopDown().forEach { if (it.isFile) size += it.length() }
                        tileCacheSizeMb = size / (1024 * 1024)
                    } catch (_: Exception) {}
                }
            }

            Text(
                text = "Disk cache: ${tileCacheSizeMb} MB / 500 MB maximum limit",
                fontSize = 12.sp,
                color = ComposeColor(0xFF94A3B8)
            )

            Button(
                onClick = {
                    onDismiss()
                    onOpenCacheManager()
                },
                colors = ButtonDefaults.buttonColors(containerColor = ComposeColor(0xFF0284C7)),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.Storage, contentDescription = null, modifier = Modifier.size(16.dp), tint = ComposeColor.White)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Manage Storage, Boundaries & Pre-fetch", fontSize = 13.sp, color = ComposeColor.White, fontWeight = FontWeight.SemiBold)
            }

            when (val state = cacheDownloadState) {
                is CacheDownloadState.Downloading -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(ComposeColor(0xFF1E293B))
                            .padding(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Downloading tiles: ${state.current} / ${state.total}",
                                fontSize = 12.sp,
                                color = ComposeColor(0xFF38BDF8)
                            )
                            Text(
                                text = "${state.percent}%",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = ComposeColor(0xFF38BDF8)
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { state.percent / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = ComposeColor(0xFF38BDF8),
                            trackColor = ComposeColor(0xFF0F172A)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(
                                onClick = { MapCacheHelper.cancelDownload() },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("Cancel", fontSize = 11.sp, color = ComposeColor(0xFFF87171))
                            }
                        }
                    }
                }
                is CacheDownloadState.Completed -> {
                    Text(
                        text = "✅ Cached ${state.totalTiles} tiles (+${String.format(java.util.Locale.getDefault(), "%.1f", state.addedMb)} MB)",
                        fontSize = 12.sp,
                        color = ComposeColor(0xFF10B981)
                    )
                }
                is CacheDownloadState.AlreadyCached -> {
                    Text(
                        text = "✅ Map region already fully cached (${state.totalTiles} tiles on disk)",
                        fontSize = 12.sp,
                        color = ComposeColor(0xFF10B981)
                    )
                }
                is CacheDownloadState.Failed -> {
                    Text(
                        text = "❌ Cache download failed: ${state.error}",
                        fontSize = 12.sp,
                        color = ComposeColor(0xFFF87171)
                    )
                }
                else -> {}
            }

            Spacer(modifier = Modifier.height(36.dp))
        }
    }
}

private fun makeDestIcon(context: Context): android.graphics.drawable.BitmapDrawable {
    val size = (context.resources.displayMetrics.density * 36).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val cx = size / 2f
    val cy = size / 2.6f
    val r = size / 3.2f

    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EF4444")
        style = Paint.Style.FILL
    }
    canvas.drawCircle(cx, cy, r, p)

    val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    canvas.drawCircle(cx, cy, r * 0.4f, dot)

    val path = Path().apply {
        moveTo(cx - r * 0.8f, cy)
        lineTo(cx + r * 0.8f, cy)
        lineTo(cx, size.toFloat() - 2f)
        close()
    }
    canvas.drawPath(path, p)

    return android.graphics.drawable.BitmapDrawable(context.resources, bmp)
}

private fun makeMarkerIcon(context: Context, hasBearing: Boolean): android.graphics.drawable.BitmapDrawable {
    val density = context.resources.displayMetrics.density
    if (!hasBearing) {
        // Small, crisp Google Maps-style stationary position dot (16dp diameter, 2dp white border)
        val size = (18f * density).toInt().coerceAtLeast(18)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = size / 2f
        val cy = size / 2f
        val dotRadius = 6.0f * density
        val whiteBorderWidth = 2.0f * density

        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            setShadowLayer(2f * density, 0f, 1f * density, 0x40000000)
        }
        canvas.drawCircle(cx, cy, dotRadius + whiteBorderWidth, ringPaint)

        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1A73E8") // Google Maps signature blue
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy, dotRadius, dotPaint)

        return android.graphics.drawable.BitmapDrawable(context.resources, bmp)
    } else {
        // Directional navigation arrow (28dp)
        val size = (28f * density).toInt().coerceAtLeast(28)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = size / 2f
        val cy = size / 2f
        val radius = size / 2f - (3f * density)

        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            setShadowLayer(2f * density, 0f, 1f * density, 0x40000000)
        }
        canvas.drawCircle(cx, cy, radius + (2f * density), ringPaint)

        val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1A73E8") // Google Maps signature blue
            style = Paint.Style.FILL
        }
        val path = Path().apply {
            moveTo(cx, cy - radius)
            lineTo(cx - radius * 0.55f, cy + radius * 0.65f)
            lineTo(cx, cy + radius * 0.25f)
            lineTo(cx + radius * 0.55f, cy + radius * 0.65f)
            close()
        }
        canvas.drawPath(path, arrowPaint)

        return android.graphics.drawable.BitmapDrawable(context.resources, bmp)
    }
}

private fun makeSavedPlaceIcon(context: Context, place: SavedPlace): android.graphics.drawable.BitmapDrawable {
    val size = (context.resources.displayMetrics.density * 34).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val cx = size / 2f
    val cy = size / 2.6f
    val r = size / 3.2f

    val hex = place.getEffectiveColorHex()
    val pinColor = try {
        Color.parseColor(hex)
    } catch (_: Exception) {
        Color.parseColor("#10B981")
    }

    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = pinColor
        style = Paint.Style.FILL
    }
    canvas.drawCircle(cx, cy, r, p)

    val path = Path().apply {
        moveTo(cx - r * 0.8f, cy)
        lineTo(cx + r * 0.8f, cy)
        lineTo(cx, size.toFloat() - 2f)
        close()
    }
    canvas.drawPath(path, p)

    // Inner White Circle
    val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    canvas.drawCircle(cx, cy, r * 0.55f, inner)

    // Text Emoji in center
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = r * 0.75f
        textAlign = Paint.Align.CENTER
    }
    val yPos = cy - (textPaint.descent() + textPaint.ascent()) / 2
    canvas.drawText(place.category.iconEmoji, cx, yPos, textPaint)

    return android.graphics.drawable.BitmapDrawable(context.resources, bmp)
}

internal fun makePauseIcon(context: Context, durText: String): android.graphics.drawable.BitmapDrawable {
    val density = context.resources.displayMetrics.density
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 10f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    val fullText = "⏸ $durText"
    val textBounds = Rect()
    textPaint.getTextBounds(fullText, 0, fullText.length, textBounds)

    val padH = (8f * density).toInt()
    val width = textBounds.width() + padH * 2
    val height = (20f * density).toInt()

    val bmp = Bitmap.createBitmap(maxOf(width, (36f * density).toInt()), height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)

    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F59E0B") // Amber
        style = Paint.Style.FILL
    }
    val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }

    val rect = RectF(1f * density, 1f * density, bmp.width - 1f * density, height - 1f * density)
    val cornerRadius = height / 2f
    canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint)
    canvas.drawRoundRect(rect, cornerRadius, cornerRadius, strokePaint)

    val textY = (height / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f)
    canvas.drawText(fullText, bmp.width / 2f, textY, textPaint)

    return android.graphics.drawable.BitmapDrawable(context.resources, bmp)
}

internal fun makeOutdoorAreaBadgeIcon(
    context: Context,
    name: String,
    kind: OutdoorAreaKind?
): android.graphics.drawable.BitmapDrawable {
    val density = context.resources.displayMetrics.density
    val isRange = kind == OutdoorAreaKind.MOUNTAIN_RANGE
    val accentHex = if (isRange) "#F59E0B" else "#10B981"
    val accentColor = Color.parseColor(accentHex)
    val iconEmoji = if (isRange) "⛰️" else if (kind == OutdoorAreaKind.NATIONAL_PARK) "🏞️" else "🌲"

    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.LEFT
    }
    val fullText = "$iconEmoji $name"
    val textBounds = Rect()
    textPaint.getTextBounds(fullText, 0, fullText.length, textBounds)

    val padH = (10f * density).toInt()
    val width = textBounds.width() + padH * 2
    val height = (26f * density).toInt()

    val bmp = Bitmap.createBitmap(maxOf(width, (40f * density).toInt()), height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)

    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E60F172A") // 90% slate-900
        style = Paint.Style.FILL
    }
    val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accentColor
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }

    val rect = RectF(1.5f * density, 1.5f * density, bmp.width - 1.5f * density, height - 1.5f * density)
    val cornerRadius = 6f * density
    canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint)
    canvas.drawRoundRect(rect, cornerRadius, cornerRadius, strokePaint)

    val textY = (height / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f)
    canvas.drawText(fullText, padH.toFloat(), textY, textPaint)

    return android.graphics.drawable.BitmapDrawable(context.resources, bmp)
}

/**
 * Green pin with a ▶ play-arrow shape — used as the trip START marker in Trip Details map.
 */
internal fun makeStartMarkerIcon(context: Context): android.graphics.drawable.BitmapDrawable {
    val density = context.resources.displayMetrics.density
    val size = (26f * density).toInt().coerceAtLeast(1)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)

    // Filled green circle
    val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#10B981") // Emerald 500
        style = Paint.Style.FILL
    }
    val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    val cx = size / 2f
    val cy = size / 2f
    val radius = (size / 2f) - (1.5f * density)
    canvas.drawCircle(cx, cy, radius, circlePaint)
    canvas.drawCircle(cx, cy, radius, strokePaint)

    // ▶ play triangle
    val triPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    val triSize = radius * 0.55f
    val path = android.graphics.Path().apply {
        moveTo(cx - triSize * 0.45f, cy - triSize * 0.7f)
        lineTo(cx + triSize * 0.8f, cy)
        lineTo(cx - triSize * 0.45f, cy + triSize * 0.7f)
        close()
    }
    canvas.drawPath(path, triPaint)

    return android.graphics.drawable.BitmapDrawable(context.resources, bmp)
}

/**
 * Red pin with a ■ stop-square shape — used as the trip END marker in Trip Details map.
 */
internal fun makeEndMarkerIcon(context: Context): android.graphics.drawable.BitmapDrawable {
    val density = context.resources.displayMetrics.density
    val size = (26f * density).toInt().coerceAtLeast(1)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)

    // Filled red circle
    val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EF4444") // Red 500
        style = Paint.Style.FILL
    }
    val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    val cx = size / 2f
    val cy = size / 2f
    val radius = (size / 2f) - (1.5f * density)
    canvas.drawCircle(cx, cy, radius, circlePaint)
    canvas.drawCircle(cx, cy, radius, strokePaint)

    // ■ stop square
    val sqPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    val sqHalf = radius * 0.4f
    canvas.drawRect(cx - sqHalf, cy - sqHalf, cx + sqHalf, cy + sqHalf, sqPaint)

    return android.graphics.drawable.BitmapDrawable(context.resources, bmp)
}

internal data class GuestMarkerIconResult(
    val drawable: android.graphics.drawable.BitmapDrawable,
    val anchorX: Float,
    val anchorY: Float
)

internal fun makeGuestMarkerIcon(
    context: Context,
    guest: LiveGuest,
    distanceMeters: Float?,
    showDistance: Boolean,
    fontScale: Float = 1.0f
): GuestMarkerIconResult {
    val density = context.resources.displayMetrics.density
    val avatarDiameter = (32f * density).toInt()

    val hasCustomName = GuestMarkerHelper.hasCustomName(guest.name)
    val badgeText = GuestMarkerHelper.formatBadgeText(guest, distanceMeters, showDistance)

    val badgeHeight = if (badgeText.isNotEmpty()) (18f * density * fontScale).toInt() else 0
    val badgeSpacing = (3f * density).toInt()

    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 10f * density * fontScale
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    val textWidth = if (badgeText.isNotEmpty()) textPaint.measureText(badgeText) else 0f
    val badgeWidth = if (badgeText.isNotEmpty()) (textWidth + 14f * density * fontScale).toInt() else 0

    val totalWidth = maxOf(avatarDiameter, badgeWidth) + (8f * density).toInt()
    val totalHeight = avatarDiameter + (if (badgeHeight > 0) badgeHeight + badgeSpacing else 0) + (4f * density).toInt()

    val bmp = Bitmap.createBitmap(totalWidth, totalHeight, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)

    val cx = totalWidth / 2f
    val cy = avatarDiameter / 2f + (2f * density)
    val radius = avatarDiameter / 2f - (2f * density)

    val baseColor = LiveGuest.getColor(guest.colorIndex)
    val alpha = if (guest.isInactive) 130 else 255

    // Outer shadow
    val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb((alpha * 0.35f).toInt(), 0, 0, 0)
        style = Paint.Style.FILL
    }
    canvas.drawCircle(cx, cy + 2f * density, radius + 1f * density, shadowPaint)

    // Dark slate background (#0F172A)
    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(alpha, 15, 23, 42)
        style = Paint.Style.FILL
    }
    canvas.drawCircle(cx, cy, radius, bgPaint)

    // Vibrant colored rim (or muted if inactive)
    val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (guest.isInactive) {
            val r = Color.red(baseColor)
            val g = Color.green(baseColor)
            val b = Color.blue(baseColor)
            Color.argb(alpha, (r + 100) / 2, (g + 100) / 2, (b + 100) / 2)
        } else {
            baseColor
        }
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }
    canvas.drawCircle(cx, cy, radius - 1.5f * density, rimPaint)

    if (hasCustomName) {
        // Initial letter inside circle for named visitors
        val initial = GuestMarkerHelper.formatInitial(guest.name)
        val initialPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (guest.isInactive) Color.argb(alpha, 180, 190, 205) else Color.WHITE
            textSize = radius * 1.15f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val textY = cy - ((initialPaint.descent() + initialPaint.ascent()) / 2f)
        canvas.drawText(initial, cx, textY, initialPaint)
    } else {
        // Person Silhouette 👤 for unnamed visitors
        val personPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (guest.isInactive) Color.argb(alpha, 180, 190, 205) else Color.WHITE
            style = Paint.Style.FILL
        }
        val headRadius = radius * 0.30f
        val headCenterY = cy - radius * 0.22f
        canvas.drawCircle(cx, headCenterY, headRadius, personPaint)

        val bodyRect = RectF(
            cx - radius * 0.58f,
            headCenterY + headRadius + 1.5f * density,
            cx + radius * 0.58f,
            cy + radius - 2f * density
        )
        canvas.save()
        val clipPath = android.graphics.Path().apply {
            addCircle(cx, cy, radius - 2f * density, android.graphics.Path.Direction.CW)
        }
        canvas.clipPath(clipPath)
        canvas.drawRoundRect(bodyRect, 4f * density, 4f * density, personPaint)
        canvas.restore()
    }

    // Attached Distance & Name Badge
    if (badgeHeight > 0 && badgeText.isNotEmpty()) {
        val badgeLeft = cx - badgeWidth / 2f
        val badgeTop = cy + radius + badgeSpacing
        val badgeRight = cx + badgeWidth / 2f
        val badgeBottom = badgeTop + badgeHeight
        val badgeRect = RectF(badgeLeft, badgeTop, badgeRight, badgeBottom)

        val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(235, 15, 23, 42) // Full contrast slate #0F172A
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(badgeRect, 6f * density, 6f * density, badgeBgPaint)

        val badgeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (guest.isInactive) Color.argb(200, 100, 116, 139) else baseColor
            style = Paint.Style.STROKE
            strokeWidth = 1f * density
        }
        canvas.drawRoundRect(badgeRect, 6f * density, 6f * density, badgeBorderPaint)

        val textY = badgeTop + (badgeHeight / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f)
        textPaint.color = Color.WHITE // Inactive guest label remains 100% white and readable
        textPaint.alpha = 255
        canvas.drawText(badgeText, cx, textY, textPaint)
    }

    val anchorX = cx / totalWidth.toFloat()
    val anchorY = cy / totalHeight.toFloat()

    return GuestMarkerIconResult(
        drawable = android.graphics.drawable.BitmapDrawable(context.resources, bmp),
        anchorX = anchorX,
        anchorY = anchorY
    )
}

private data class ScreenBox(val x: Double, val y: Double, val halfW: Double, val halfH: Double) {
    fun overlaps(other: ScreenBox, padding: Double = 6.0): Boolean {
        val dx = kotlin.math.abs(x - other.x)
        val dy = kotlin.math.abs(y - other.y)
        return dx < (halfW + other.halfW + padding) && dy < (halfH + other.halfH + padding)
    }

    fun overlapArea(other: ScreenBox, padding: Double = 6.0): Double {
        val ox = (halfW + other.halfW + padding) - kotlin.math.abs(x - other.x)
        val oy = (halfH + other.halfH + padding) - kotlin.math.abs(y - other.y)
        return if (ox > 0.0 && oy > 0.0) ox * oy else 0.0
    }
}

fun computeSpiderfyOffsets(
    guests: List<LiveGuest>,
    hostPos: GeoPoint? = null,
    obstaclePoints: List<GeoPoint> = emptyList(),
    zoomLevel: Double = 17.0,
    density: Float = 2.5f,
    mapOrientationDeg: Float = 0f,
    fontScaleFactor: Float = 1.0f,
    showGuestDistanceBadge: Boolean = true,
    viewportCenter: GeoPoint? = null,
    viewportWidthDp: Double = 360.0,
    viewportHeightDp: Double = 760.0
): Map<String, GeoPoint> {
    if (guests.isEmpty()) return emptyMap()

    val refLat = viewportCenter?.latitude ?: hostPos?.latitude ?: guests.firstOrNull()?.lat ?: 49.85
    val refLng = viewportCenter?.longitude ?: hostPos?.longitude ?: guests.firstOrNull()?.lng ?: 19.28
    val cosLat = kotlin.math.cos(Math.toRadians(refLat)).coerceAtLeast(0.01)
    val scale = fontScaleFactor.toDouble().coerceIn(0.85, 2.0)
    // osmdroid renders 256px tiles scaled by tilesScaleFactor (= fontScaleFactor).
    // 1 dp on screen equals (density) screen pixels, while 1 meter equals tilesScaleFactor / metersPerBasePixel pixels.
    val metersPerBasePixel = (156543.03392 * cosLat / Math.pow(2.0, zoomLevel.coerceIn(4.0, 21.0)))
    val dpPerMeter = scale / (metersPerBasePixel * density.toDouble().coerceAtLeast(1.0))
    val mLat = 111132.0
    val mLng = 111132.0 * cosLat

    val rad = Math.toRadians(mapOrientationDeg.toDouble())
    val cosA = kotlin.math.cos(rad)
    val sinA = kotlin.math.sin(rad)

    fun toScreen(latDeg: Double, lngDeg: Double): Pair<Double, Double> {
        val xNu = (lngDeg - refLng) * mLng * dpPerMeter
        val yNu = -(latDeg - refLat) * mLat * dpPerMeter
        val sx = cosA * xNu - sinA * yNu
        val sy = sinA * xNu + cosA * yNu
        return Pair(sx, sy)
    }

    fun fromScreenOffset(sx: Double, sy: Double): Pair<Double, Double> {
        val xNu = cosA * sx + sinA * sy
        val yNu = -sinA * sx + cosA * sy
        val dXMeters = xNu / dpPerMeter
        val dYMeters = -yNu / dpPerMeter
        val dLat = dYMeters / mLat
        val dLng = dXMeters / mLng
        return Pair(dLat, dLng)
    }

    val staticPinBoxes = mutableListOf<ScreenBox>()
    val staticBadgeBoxes = mutableListOf<ScreenBox>()
    if (hostPos != null) {
        val (hostX, hostY) = toScreen(hostPos.latitude, hostPos.longitude)
        staticPinBoxes.add(ScreenBox(hostX, hostY, 10.0 * scale, 10.0 * scale)) // host marker
        staticBadgeBoxes.add(ScreenBox(hostX, hostY - 30.0 * scale, 36.0 * scale, 12.0 * scale)) // altitude badge above host
    }

    // Static pins: only host marker and altitude badge (saved places must never displace guests)
    val vpScreen = viewportCenter?.let { toScreen(it.latitude, it.longitude) }

    val result = mutableMapOf<String, GeoPoint>()
    val placedBoxes = mutableListOf<ScreenBox>()

    // Sort guests deterministically so closest to host are placed first
    val sortedGuests = guests.sortedWith(
        compareBy<LiveGuest> { g ->
            if (hostPos != null) {
                val dLat = (g.lat - hostPos.latitude) * mLat
                val dLng = (g.lng - hostPos.longitude) * mLng
                kotlin.math.hypot(dLat, dLng)
            } else 0.0
        }.thenBy { it.id }
    )

    for ((guestIdx, guest) in sortedGuests.withIndex()) {
        val (origX, origY) = toScreen(guest.lat, guest.lng)
        val distM = if (hostPos != null) {
            val dLat = (guest.lat - hostPos.latitude) * mLat
            val dLng = (guest.lng - hostPos.longitude) * mLng
            kotlin.math.hypot(dLat, dLng).toFloat()
        } else null
        val badgeText = GuestMarkerHelper.formatBadgeText(guest, distM, showGuestDistanceBadge)
        // Avatar circle centered at anchor (origX, origY); badge pill hangs below avatar.
        val avatarHalf = 14.0 * scale
        val badgeWidthDp = if (badgeText.isNotEmpty()) (badgeText.length * 5.2 + 14.0) * scale else 0.0
        val badgeHalfW = maxOf(avatarHalf, badgeWidthDp * 0.5)
        val badgeHalfH = 10.0 * scale
        val badgeCenterOffsetY = 16.0 * scale + 3.0 * scale + badgeHalfH

        fun boxesForOffset(dxDp: Double, dyDp: Double): List<ScreenBox> {
            val ax = origX + dxDp
            val ay = origY + dyDp
            val avatarBox = ScreenBox(ax, ay, avatarHalf, avatarHalf)
            return if (badgeText.isNotEmpty()) {
                val badgeBox = ScreenBox(ax, ay + badgeCenterOffsetY, badgeHalfW, badgeHalfH)
                listOf(avatarBox, badgeBox)
            } else {
                listOf(avatarBox)
            }
        }

        // Build tight compact candidates sorted strictly by distance from (0, 0)
        // so guests stay at (0, 0) whenever possible and shift by the absolute minimum needed (max 32dp).
        val nonZeroCandidates = ArrayList<Pair<Double, Double>>(24)
        // Ring 1 (~20-24dp): immediately clears an adjacent co-located guest
        nonZeroCandidates.add(Pair(0.0, -22.0 * scale))
        nonZeroCandidates.add(Pair(0.0, 22.0 * scale))
        nonZeroCandidates.add(Pair(-24.0 * scale, 0.0))
        nonZeroCandidates.add(Pair(24.0 * scale, 0.0))
        nonZeroCandidates.add(Pair(-18.0 * scale, -18.0 * scale))
        nonZeroCandidates.add(Pair(18.0 * scale, -18.0 * scale))
        nonZeroCandidates.add(Pair(-18.0 * scale, 18.0 * scale))
        nonZeroCandidates.add(Pair(18.0 * scale, 18.0 * scale))
        // Ring 2 (~28-32dp): for 3+ co-located guests
        nonZeroCandidates.add(Pair(0.0, -32.0 * scale))
        nonZeroCandidates.add(Pair(0.0, 32.0 * scale))
        nonZeroCandidates.add(Pair(-32.0 * scale, 0.0))
        nonZeroCandidates.add(Pair(32.0 * scale, 0.0))
        nonZeroCandidates.add(Pair(-26.0 * scale, -22.0 * scale))
        nonZeroCandidates.add(Pair(26.0 * scale, -22.0 * scale))
        nonZeroCandidates.add(Pair(-26.0 * scale, 22.0 * scale))
        nonZeroCandidates.add(Pair(26.0 * scale, 22.0 * scale))

        nonZeroCandidates.sortBy { (dx, dy) ->
            // Slight preference for West over East at equal radius to clear right-side map buttons
            kotlin.math.hypot(dx, dy) + if (dx < 0) -0.5 else 0.0
        }

        val candidates = ArrayList<Pair<Double, Double>>(nonZeroCandidates.size + 1)
        candidates.add(Pair(0.0, 0.0))
        candidates.addAll(nonZeroCandidates)

        fun viewportOverflowDp(dxDp: Double, dyDp: Double): Double {
            val vp = vpScreen ?: return 0.0
            if (dxDp == 0.0 && dyDp == 0.0) return 0.0
            val (vcX, vcY) = vp
            // Only enforce viewport bounds when the guest's real position is within visible screen bounds
            if (kotlin.math.abs(origX - vcX) > viewportWidthDp * 0.5 ||
                kotlin.math.abs(origY - vcY) > viewportHeightDp * 0.5) {
                return 0.0
            }
            val minSafeX = vcX - viewportWidthDp * 0.5 + badgeHalfW + 8.0
            val maxSafeX = vcX + viewportWidthDp * 0.5 - badgeHalfW - 58.0
            val minSafeY = vcY - viewportHeightDp * 0.5 + 145.0
            val maxSafeY = vcY + viewportHeightDp * 0.5 - 140.0
            val ax = origX + dxDp
            val ay = origY + dyDp
            val outL = if (minSafeX <= maxSafeX) (minSafeX - ax).coerceAtLeast(0.0) else 0.0
            val outR = if (minSafeX <= maxSafeX) (ax - maxSafeX).coerceAtLeast(0.0) else 0.0
            val outT = if (minSafeY <= maxSafeY) (minSafeY - ay).coerceAtLeast(0.0) else 0.0
            val outB = if (minSafeY <= maxSafeY) (ay - maxSafeY).coerceAtLeast(0.0) else 0.0
            return outL + outR + outT + outB
        }

        var bestOffset = candidates[0]
        var bestPenalty = Double.MAX_VALUE

        for (cand in candidates) {
            val testBoxes = boxesForOffset(cand.first, cand.second)
            val avatarBox = testBoxes.first()
            // Static pins collide only with the guest's circular avatar, never with the wide text badge below it
            val pinOverlap = staticPinBoxes.sumOf { sb -> avatarBox.overlapArea(sb, padding = 2.0) }
            val hostBadgeOverlap = testBoxes.sumOf { tb -> staticBadgeBoxes.sumOf { sb -> tb.overlapArea(sb, padding = 2.0) } }
            val staticOverlap = pinOverlap + hostBadgeOverlap
            val placedOverlap = testBoxes.sumOf { tb -> placedBoxes.sumOf { pb -> tb.overlapArea(pb, padding = 4.0 * scale) } }
            val totalOverlap = staticOverlap + placedOverlap * 2.0
            val vpOverflow = viewportOverflowDp(cand.first, cand.second)

            if (totalOverlap == 0.0 && vpOverflow == 0.0) {
                bestOffset = cand
                bestPenalty = 0.0
                break
            }
            val distPenalty = kotlin.math.hypot(cand.first, cand.second) * 1.5
            val penalty = totalOverlap * 10.0 + vpOverflow * 5.0 + distPenalty
            if (penalty < bestPenalty) {
                bestPenalty = penalty
                bestOffset = cand
            }
        }

        placedBoxes.addAll(boxesForOffset(bestOffset.first, bestOffset.second))

        if (bestOffset.first == 0.0 && bestOffset.second == 0.0) {
            result[guest.id] = guest.geoPoint
        } else {
            val (dLat, dLng) = fromScreenOffset(bestOffset.first, bestOffset.second)
            result[guest.id] = GeoPoint(guest.lat + dLat, guest.lng + dLng)
        }
    }

    return result
}

/**
 * Reusable Heat Map Legend composable.
 * Displays the thermal color scale / thermometer, expandable to detail tier breakdown on tap.
 */
@Composable
fun HeatMapLegend(
    onOpenHeatMapSettings: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var showLegendDetail by remember { mutableStateOf(false) }

    Surface(
        onClick = { showLegendDetail = !showLegendDetail },
        shape = RoundedCornerShape(12.dp),
        color = ComposeColor(0xF00F172A),
        border = androidx.compose.foundation.BorderStroke(1.dp, ComposeColor(0x80F97316)),
        shadowElevation = 6.dp,
        modifier = modifier
    ) {
        if (!showLegendDetail) {
            // Compact vertical thermometer bar
            Column(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Hot",
                    color = ComposeColor(0xFFD946EF),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false
                )
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .width(7.dp)
                        .height(85.dp)
                        .clip(RoundedCornerShape(3.5.dp))
                        .background(
                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                listOf(
                                    ComposeColor(0xFFD946EF), // 7 Magenta
                                    ComposeColor(0xFFEF4444), // 6 Red
                                    ComposeColor(0xFFF97316), // 5 Orange
                                    ComposeColor(0xFFEAB308), // 4 Yellow
                                    ComposeColor(0xFF10B981), // 3 Green
                                    ComposeColor(0xFF06B6D4), // 2 Cyan
                                    ComposeColor(0xFF2563EB)  // 1 Blue
                                )
                            )
                        )
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "1x",
                    color = ComposeColor(0xFF60A5FA),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false
                )
            }
        } else {
            // Detailed tier breakdown popup on click
            Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.width(135.dp)
                ) {
                    Text(
                        text = "🔥 Thermal Scale",
                        color = ComposeColor.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close Legend",
                        tint = ComposeColor.LightGray,
                        modifier = Modifier.size(12.dp)
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                val legendTiers = listOf(
                    ComposeColor(0xFFD946EF) to "20+ Hotspot",
                    ComposeColor(0xFFEF4444) to "13-19 passes",
                    ComposeColor(0xFFF97316) to "8-12 passes",
                    ComposeColor(0xFFEAB308) to "5-7 passes",
                    ComposeColor(0xFF10B981) to "3-4 passes",
                    ComposeColor(0xFF06B6D4) to "2 passes",
                    ComposeColor(0xFF2563EB) to "1 pass (Cold)"
                )
                legendTiers.forEach { (color, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(width = 16.dp, height = 5.dp)
                                .clip(RoundedCornerShape(2.5.dp))
                                .background(color)
                        )
                        Text(
                            text = label,
                            color = ComposeColor(0xFFE2E8F0),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                if (onOpenHeatMapSettings != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "⚙️ Filter / Options",
                        color = ComposeColor(0xFFF97316),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

/** Cached startup position (no filter state yet): the pipeline must snap to it, not blend. */
internal fun syntheticTrackState(fix: LocationFix): TrackState = TrackState(
    tElapsedMs = android.os.SystemClock.elapsedRealtime(),
    tUtcMs = fix.timestamp,
    lat = fix.lat,
    lng = fix.lng,
    altitudeM = null,
    speedMps = fix.speedMs.toDouble(),
    headingDeg = (fix.bearing ?: 0f).toDouble(),
    yawRateDegPerS = 0.0,
    posStdM = (fix.accuracy ?: 10f).toDouble(),
    headingStdDeg = 20.0,
    headingConfident = fix.bearing != null,
    isStationary = fix.isAnchored,
    rejectStreak = 0,
    isReset = true,
    accuracyM = fix.accuracy
)

internal fun normalizeAngle(deg: Float): Float {
    val mod = deg % 360f
    return if (mod < 0f) mod + 360f else mod
}

internal fun shortestAngularDelta(fromDeg: Float, toDeg: Float): Float {
    val normFrom = normalizeAngle(fromDeg)
    val normTo = normalizeAngle(toDeg)
    val diff = (normTo - normFrom + 540f) % 360f - 180f
    return if (diff < -180f) diff + 360f else diff
}

internal fun canRotateCourseUp(
    hasHeading: Boolean,
    isPhysicallyStationary: Boolean,
    isAnchored: Boolean,
    speedMs: Float,
    isRecording: Boolean,
    isInterpolatorStationary: Boolean
): Boolean {
    // Locomotion gating: when recording, 1.5 km/h allows responsive trail tracking.
    // When NOT recording, require verified vehicular locomotion (>= 10.0 km/h) and physical motion
    // to completely stifle indoor multipath swings and desk/couch jitter.
    val minSpeedForCourseUp = if (isRecording) 0.42f else 2.78f // 1.5 km/h vs 10.0 km/h
    return hasHeading &&
            !isPhysicallyStationary &&
            !isAnchored &&
            (speedMs >= minSpeedForCourseUp || (isRecording && !isInterpolatorStationary))
}

