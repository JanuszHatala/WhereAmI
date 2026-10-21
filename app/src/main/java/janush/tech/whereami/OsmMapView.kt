package janush.tech.whereami

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
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import janush.tech.whereami.tracking.AndroidSensorRegistrar
import janush.tech.whereami.tracking.CompassArrow
import janush.tech.whereami.tracking.CompassHeadingSensor
import janush.tech.whereami.tracking.CompassPolicy
import janush.tech.whereami.tracking.GyroHeadingSensor
import janush.tech.whereami.tracking.GyroPolicy
import janush.tech.whereami.tracking.MotionCourse
import janush.tech.whereami.tracking.PencilTrail
import janush.tech.whereami.tracking.RenderPipeline
import janush.tech.whereami.tracking.CourseUpHeading
import janush.tech.whereami.tracking.RotationSlew
import janush.tech.whereami.tracking.TrackState
import janush.tech.whereami.tracking.TrackingBridge
import janush.tech.whereami.tracking.TrackingRuntime
import janush.tech.whereami.tracking.shortestDeltaDeg
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
    // Map controls: 42dp width + 12dp end padding = 54dp minimum.
    // Adding 14dp safe boundary clearance = 68dp rightControls.
    // In portrait, use actual measured composable bounds when available (topCardBottomPx, bottomControlsTopPx)
    // with fallback to density-scaled dp estimates.
    val (leftInset, rightInset, topInset, bottomInset) = if (isLandscape) {
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
        val rightControlsDp = 68f // 42dp button + 12dp margin + 14dp clearance
        val clearancePx = (12f * density).toInt()

        // Minimum bottom clearance must guarantee clear space above bottom toolbar and floating Recenter pill
        // Recenter button top is at 172dp when isRecording or isAutoStandby; 114dp otherwise.
        // Adding 14dp clearance gives 186dp / 128dp minimum bottom clearance.
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
    selectedTrips: List<TripRecord> = emptyList(),
    savedPlaces: List<SavedPlace> = emptyList(),
    boundaryPoints: List<GeoPoint>? = null,
    pinnedBoundaryPoints: List<GeoPoint>? = null,
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
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val currentOnMapClick by rememberUpdatedState(onMapClick)
    val currentOnOrientationChange by rememberUpdatedState(onOrientationModeChange)
    val currentOnGuestClick by rememberUpdatedState(onGuestClick)

    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val density = androidx.compose.ui.platform.LocalDensity.current.density

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

    LaunchedEffect(Unit) {
        Configuration.getInstance().apply {
            userAgentValue = "WhereIAmPersonalApp/1.1"
            tileFileSystemMaxQueueSize = 80
            // 300 MB persistent offline tile disk cache
            tileFileSystemCacheMaxBytes = 300L * 1024 * 1024
            tileFileSystemCacheTrimBytes = 250L * 1024 * 1024
            cacheMapTileCount = 100
        }
    }

    val minZoom = 3.0
    val maxZoom = 22.0
    var currentZoom by remember { mutableStateOf(16.0) }

    var mapView by remember { mutableStateOf<MapView?>(null) }
    var marker by remember { mutableStateOf<Marker?>(null) }
    var destMarker by remember { mutableStateOf<Marker?>(null) }
    var polyline by remember { mutableStateOf<Polyline?>(null) }
    val liveLine = remember { LiveTrackLine() }
    var boundaryPolygon by remember { mutableStateOf<org.osmdroid.views.overlay.Polygon?>(null) }
    var pinnedBoundaryPolygon by remember { mutableStateOf<org.osmdroid.views.overlay.Polygon?>(null) }
    var selectedTripPolylines by remember { mutableStateOf<List<Polyline>>(emptyList()) }
    var heatMapPolylines by remember { mutableStateOf<List<Polyline>>(emptyList()) }
    var guestMarkers by remember { mutableStateOf<List<Marker>>(emptyList()) }
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

    var fontScale by remember(activityProfile) {
        mutableStateOf(initialProfileConfig.fontScale)
    }

    val altitudeOverlay = remember(context) { AltitudeCursorBadgeOverlay(context) }
    var showAltitudeOnMap by remember { mutableStateOf(MapProfileConfigHelper.getShowAltitudeOnMap(context)) }
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
            destinationPoint = destinationPoint
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
    val latestDestinationAltitude by rememberUpdatedState(destinationAltitude)
    val latestDestinationAltitudeLoading by rememberUpdatedState(destinationAltitudeLoading)
    val latestDestinationPoint by rememberUpdatedState(destinationPoint)
    val latestAltitudeM by rememberUpdatedState(altitudeM)
    val latestIsAltitudeFallback by rememberUpdatedState(isAltitudeFallback)
    val latestAltitudeSourceMode by rememberUpdatedState(altitudeSourceMode)
    val latestSavedPlaces by rememberUpdatedState(savedPlaces)
    val latestShowAltitudeOnMap by rememberUpdatedState(showAltitudeOnMap)
    val latestFontScale by rememberUpdatedState(fontScale)
    val tripManagerRef = remember(context) { TripManager.getInstance(context) }
    val pencilTrail = remember { PencilTrail() }
    val motionCourse = remember { MotionCourse() }
    var committedThroughMs by remember { mutableStateOf(-1L) }
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
        map.setTileSource(tileSource)
        MapProfileConfigHelper.setBaseLayer(context, activityProfile, baseLayer)
        map.invalidate()
    }

    // Sync Profile-Specific Map Settings when ActivityProfile switches
    LaunchedEffect(activityProfile) {
        val config = MapProfileConfigHelper.getConfig(context, activityProfile)
        baseLayer = config.baseLayer
        showHikingOverlay = config.showHikingOverlay
        fontScale = config.fontScale
        if (orientationMode != config.orientationMode) {
            onOrientationModeChange?.invoke(config.orientationMode)
        }
    }

    // Dynamic Label / Font Scale
    LaunchedEffect(fontScale, mapView) {
        val map = mapView ?: return@LaunchedEffect
        map.tilesScaleFactor = fontScale.scaleFactor
        MapProfileConfigHelper.setFontScale(context, activityProfile, fontScale)
        map.invalidate()
    }

    // Dynamic Hiking / Tourist Trail Overlay
    LaunchedEffect(showHikingOverlay, mapView) {
        val map = mapView ?: return@LaunchedEffect
        MapProfileConfigHelper.setHikingOverlay(context, activityProfile, showHikingOverlay)
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
        } else {
            hikingOverlayRef?.let {
                map.overlays.remove(it)
            }
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
        var lastFedStateMs = Long.MIN_VALUE

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
                val pose = renderPipeline.frame(nowMs)

                if (pose != null && pose.lat != 0.0 && pose.lng != 0.0) {
                    // Display-only road pull (OSRM nearest via LocationManager); recorded track stays unsapped.
                    val (dispLat, dispLng) = TrackingRuntime.displaySnap.apply(pose.lat, pose.lng)
                    val gp = GeoPoint(dispLat, dispLng)
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
                    accuracyOverlay.updatePosition(dispLat, dispLng, effectiveAccuracy)

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
                        destinationPoint = latestDestinationPoint
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
                        }
                    }

                    // Live polyline = committed smoothed points (TripManager) + pencil trail (the cursor's own
                    // rendered path since the newest committed vertex) + cursor tip. The line therefore always
                    // ends under the cursor and never re-shapes ahead of it.
                    if (latestRecording) {
                        val line = polyline ?: Polyline(map).also {
                            it.outlinePaint.color = Color.parseColor("#EF4444") // Vivid Red
                            it.outlinePaint.strokeWidth = 12f
                            it.outlinePaint.strokeCap = Paint.Cap.ROUND
                            it.outlinePaint.strokeJoin = Paint.Join.ROUND
                            it.infoWindow = null
                            it.setOnClickListener { _, _, _ -> true }
                            addOverlayAboveTiles(map, it)
                            polyline = it
                        }
                        val before = pencilTrail.size
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
                                    val (la, ln) = TrackingRuntime.displaySnap.apply(it.lat, it.lng)
                                    pencilTrail.add(it.tMs, la, ln, it.speedMps, vehicle = vehiclePencil)
                                } > 0
                        val added = !pose.isStationary &&
                            pencilTrail.add(pose.renderTimeMs, dispLat, dispLng, pose.speedMps, vehicle = vehiclePencil)
                        if (added || backfilled || pencilTrail.size != before || !liveLine.hasTail) {
                            liveLine.rebuildTail(pencilTrail, gp, committedThroughMs)
                            line.setPoints(liveLine.points)
                        } else if (liveLine.moveTip(gp)) {
                            line.setPoints(liveLine.points)
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

    // Update live trip polyline (Vivid Red #EF4444)
    LaunchedEffect(trackPoints, isRecording) {
        val map = mapView ?: return@LaunchedEffect
        if (!isRecording) {
            liveLine.clear()
            pencilTrail.clear()
            committedThroughMs = -1L
            if (trackPoints.isEmpty()) {
                polyline?.let { map.overlays.remove(it) }
                polyline = null
                map.invalidate()
                return@LaunchedEffect
            }

            val line = polyline ?: Polyline(map).also {
                it.outlinePaint.color = Color.parseColor("#EF4444") // Vivid Red
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
            map.invalidate()
        } else {
            // When actively recording: committed points come from TripManager (smoothed track). Rebuild the
            // committed prefix whenever that list changes (resume, background gap, restore); the per-frame
            // loop appends the dynamic tail. This is the single source of the live polyline.
            // Display-only road snap (same offset as the cursor) so the red line does not sit in buildings.
            val snappedCommitted = trackPoints.map { p ->
                val (la, ln) = TrackingRuntime.displaySnap.apply(p.latitude, p.longitude)
                GeoPoint(la, ln)
            }
            liveLine.setCommitted(snappedCommitted)
            committedThroughMs = tripManagerRef.lastCommittedElapsedMs
            if (trackPoints.isEmpty()) pencilTrail.clear()
            pencilTrail.pruneThrough(committedThroughMs)
            val currentPt = marker?.position
                ?: locationFix?.let { GeoPoint(it.lat, it.lng) }
                ?: latLng?.let { GeoPoint(it.first, it.second) }
            val tip = currentPt ?: trackPoints.lastOrNull()
            if (tip != null) {
                liveLine.rebuildTail(pencilTrail, tip, committedThroughMs)
                polyline?.setPoints(liveLine.points)
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
    LaunchedEffect(activeGuests, showGuestDistanceBadge, latLng) {
        val map = mapView ?: return@LaunchedEffect
        guestMarkers.forEach { map.overlays.remove(it) }

        if (activeGuests.isEmpty()) {
            guestMarkers = emptyList()
            map.invalidate()
            return@LaunchedEffect
        }

        val hostLoc = latLng?.let { Triple(it.first, it.second, it.third) }
        val newGuestMarkers = mutableListOf<Marker>()

        activeGuests.forEach { guest ->
            val distMeters = if (hostLoc != null) {
                val results = FloatArray(1)
                android.location.Location.distanceBetween(hostLoc.first, hostLoc.second, guest.lat, guest.lng, results)
                results[0]
            } else null

            val iconResult = makeGuestMarkerIcon(context, guest, distMeters, showGuestDistanceBadge)
            val gm = Marker(map).apply {
                position = guest.geoPoint
                setAnchor(iconResult.anchorX, iconResult.anchorY)
                icon = iconResult.drawable
                title = guest.name
                val distStr = distMeters?.let { " • ${LiveGuest.formatDistance(it)}" } ?: ""
                val statusStr = if (guest.isInactive) " (inactive)" else ""
                snippet = "Guest$statusStr$distStr"
                infoWindow = null
                setOnMarkerClickListener { _, _ ->
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
            map.overlays.add(gm)
            newGuestMarkers.add(gm)
        }
        guestMarkers = newGuestMarkers
        map.invalidate()
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

    // Render Selected Past Trips with Distinct Colors (Phase 2)
    // Invariant: When an active trip is actively recording or trackPoints is present,
    // NEVER render past trips on the map.
    LaunchedEffect(selectedTrips, isRecording, trackPoints.size) {
        val map = mapView ?: return@LaunchedEffect
        // Remove existing past trip polylines
        selectedTripPolylines.forEach { map.overlays.remove(it) }
        selectedTripPolylines = emptyList()

        if (isRecording || trackPoints.isNotEmpty() || selectedTrips.isEmpty()) {
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
        selectedTrips.forEachIndexed { index, trip ->
            if (trip.points.size >= 2) {
                val colorHex = colors[index % colors.size]
                val line = Polyline(map).apply {
                    outlinePaint.color = Color.parseColor(colorHex)
                    outlinePaint.strokeWidth = 8f
                    outlinePaint.strokeCap = Paint.Cap.ROUND
                    outlinePaint.strokeJoin = Paint.Join.ROUND
                    infoWindow = null
                    setOnClickListener { _, _, _ -> true }
                    setPoints(trip.points)
                }
                addOverlayAboveTiles(map, line)
                newLines.add(line)
            }
        }
        selectedTripPolylines = newLines
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
    val allShownPoints = remember(trackPoints, selectedTrips, isRecording, activeGuests, latLng) {
        val pts = mutableListOf<GeoPoint>()
        if (selectedTrips.isNotEmpty() && !isRecording) {
            // Selected past trips take absolute priority for map fitting
            selectedTrips.forEach { trip ->
                pts.addAll(trip.points)
            }
        } else if (isRecording || trackPoints.isNotEmpty()) {
            // Active trip recording
            pts.addAll(trackPoints)
        } else if (activeGuests.isNotEmpty()) {
            // Live sharing group (only when no trip is recording or selected)
            activeGuests.forEach { guest ->
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
        if (fitTrackTrigger > 0L && allShownPoints.isNotEmpty()) {
            val map = mapView ?: return@LaunchedEffect
            isFollowing = false
            fitPointsToUnobstructedViewport(
                map = map,
                points = allShownPoints,
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
                        val locPrefs = ctx.getSharedPreferences("janush.tech.whereami.loc_prefs", Context.MODE_PRIVATE)
                        val savedLat = locPrefs.getFloat("cached_lat", Float.NaN)
                        if (!savedLat.isNaN()) savedLat.toDouble() else 52.0693
                    }
                    val initialLon = locationFix?.lng ?: latLng?.second ?: run {
                        val locPrefs = ctx.getSharedPreferences("janush.tech.whereami.loc_prefs", Context.MODE_PRIVATE)
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
                            if (destinationPoint != null || selectedSavedPlace != null) {
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
                        when (event.actionMasked) {
                            android.view.MotionEvent.ACTION_DOWN -> {
                                isUserDragging = true
                            }
                            android.view.MotionEvent.ACTION_MOVE -> {
                                isUserDragging = true
                                isFollowing = false
                            }
                            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                                isUserDragging = false
                            }
                        }
                        false
                    }

                    addMapListener(object : org.osmdroid.events.MapListener {
                        override fun onScroll(event: org.osmdroid.events.ScrollEvent?): Boolean {
                            if (isUserDragging) {
                                isFollowing = false
                            }
                            return false
                        }
                        override fun onZoom(event: org.osmdroid.events.ZoomEvent?): Boolean {
                            currentZoom = event?.zoomLevel ?: zoomLevelDouble
                            return false
                        }
                    })
                    mapView = this
                }
            },
            update = { }
        )

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
                        val map = mapView ?: return@clickable
                        map.controller.zoomIn()
                        currentZoom = map.zoomLevelDouble
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
                        val map = mapView ?: return@clickable
                        map.controller.zoomOut()
                        currentZoom = map.zoomLevelDouble
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
                        isFollowing = true
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
                        .clickable { onInstantShare.invoke() },
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

            // Fit Shown Trips
            if (allShownPoints.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(ComposeColor(0xCC1E293B))
                        .clickable {
                            val map = mapView ?: return@clickable
                            isFollowing = false
                            fitPointsToUnobstructedViewport(
                                map = map,
                                points = allShownPoints,
                                density = density,
                                isLandscape = isLandscape,
                                isCompact = isCompact,
                                measuredTopInsetPx = measuredTopInsetPx,
                                measuredBottomInsetPx = measuredBottomInsetPx,
                                isRecording = isRecording,
                                isAutoStandby = isAutoStandby,
                                onZoomCalculated = { currentZoom = it }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CropFree,
                        contentDescription = "Fit Shown Trips",
                        tint = ComposeColor(0xFF38BDF8),
                        modifier = Modifier.size(22.dp)
                    )
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
        }

        // Floating "📍 Recenter" Pill when user has panned away (Option B)
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
                onClick = {
                    isFollowing = true
                    effectiveLatLng?.let { pos ->
                        val gp = GeoPoint(pos.first, pos.second)
                        val centerGp = getOpticalCenter(mapView, gp, effectiveOpticalOffsetY)
                        mapView?.controller?.setCenter(centerGp)
                    }
                    mapView?.invalidate()
                },
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
        }

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
        MapSettingsDialog(
            activityProfile = activityProfile,
            currentBaseLayer = baseLayer,
            onBaseLayerChange = { baseLayer = it },
            hikingOverlayEnabled = showHikingOverlay,
            onHikingOverlayToggle = { showHikingOverlay = it },
            compassArrowEnabled = compassArrowEnabled,
            onCompassArrowToggle = {
                compassArrowEnabled = it
                MapProfileConfigHelper.setCompassArrow(context, it)
            },
            showAltitudeEnabled = showAltitudeOnMap,
            onShowAltitudeToggle = {
                showAltitudeOnMap = it
                altitudeOverlay.isEnabled = it
                MapProfileConfigHelper.setShowAltitudeOnMap(context, it)
                mapView?.invalidate()
                mapView?.postInvalidate()
            },
            currentAltitudeSourceMode = altitudeSourceMode,
            onAltitudeSourceModeChange = { onAltitudeSourceModeChange?.invoke(it) },
            currentFontScale = fontScale,
            onFontScaleChange = { fontScale = it },
            currentOrientationMode = orientationMode,
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
            currentLatLng = effectiveLatLng,
            onClearCache = {
                mapView?.tileProvider?.clearTileCache()
                hikingProviderRef?.clearTileCache()
                mapView?.invalidate()
                Toast.makeText(context, "Map tile cache cleared", Toast.LENGTH_SHORT).show()
            },
            onOpenCacheManager = {
                showSettingsDialog = false
                onOpenCacheManager?.invoke()
            },
            onResetProfileDefaults = {
                MapProfileConfigHelper.resetProfileDefaults(context, activityProfile)
                val cfg = MapProfileConfigHelper.getConfig(context, activityProfile)
                baseLayer = cfg.baseLayer
                showHikingOverlay = cfg.showHikingOverlay
                fontScale = cfg.fontScale
                currentOnOrientationChange?.invoke(cfg.orientationMode)
                Toast.makeText(context, "Reset defaults for ${activityProfile.displayName}", Toast.LENGTH_SHORT).show()
            },
            onResetAllProfileDefaults = {
                MapProfileConfigHelper.resetAllProfileDefaults(context)
                val cfg = MapProfileConfigHelper.getConfig(context, activityProfile)
                baseLayer = cfg.baseLayer
                showHikingOverlay = cfg.showHikingOverlay
                fontScale = cfg.fontScale
                currentOnOrientationChange?.invoke(cfg.orientationMode)
                Toast.makeText(context, "Reset all profile defaults", Toast.LENGTH_SHORT).show()
            },
            onDismiss = {
                showSettingsDialog = false
                mapView?.invalidate()
                onDismissMapSettings?.invoke()
            }
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            hikingProviderRef?.clearTileCache()
            mapView?.onDetach()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MapSettingsDialog(
    activityProfile: ActivityProfile,
    currentBaseLayer: MapBaseLayer,
    onBaseLayerChange: (MapBaseLayer) -> Unit,
    hikingOverlayEnabled: Boolean,
    onHikingOverlayToggle: (Boolean) -> Unit,
    compassArrowEnabled: Boolean = true,
    onCompassArrowToggle: (Boolean) -> Unit = {},
    showAltitudeEnabled: Boolean = true,
    onShowAltitudeToggle: (Boolean) -> Unit = {},
    currentAltitudeSourceMode: AltitudeSourceMode = AltitudeSourceMode.DEM,
    onAltitudeSourceModeChange: (AltitudeSourceMode) -> Unit = {},
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

    LaunchedEffect(cacheDownloadState) {
        if (cacheDownloadState !is CacheDownloadState.Idle) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
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
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MapBaseLayer.values().forEach { layer ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onBaseLayerChange(layer) }
                            .padding(vertical = 4.dp, horizontal = 6.dp)
                    ) {
                        RadioButton(
                            selected = (layer == currentBaseLayer),
                            onClick = { onBaseLayerChange(layer) },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = ComposeColor(0xFF38BDF8),
                                unselectedColor = ComposeColor(0xFF64748B)
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = layer.label,
                                fontSize = 14.sp,
                                color = if (layer == currentBaseLayer) ComposeColor.White else ComposeColor(0xFFCBD5E1),
                                fontWeight = if (layer == currentBaseLayer) FontWeight.Bold else FontWeight.Normal
                            )
                            if (layer == MapBaseLayer.FREEMAP_OUTDOOR) {
                                Text(
                                    text = stringResource(R.string.freemap_outdoor_description),
                                    fontSize = 11.sp,
                                    color = ComposeColor(0xFF10B981)
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

            // Map Font & Label Scaling
            Text(
                text = "Map Labels Size",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = ComposeColor(0xFF94A3B8)
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MapFontScale.values().forEach { scale ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onFontScaleChange(scale) }
                            .padding(vertical = 4.dp, horizontal = 6.dp)
                    ) {
                        RadioButton(
                            selected = (scale == currentFontScale),
                            onClick = { onFontScaleChange(scale) },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = ComposeColor(0xFF38BDF8),
                                unselectedColor = ComposeColor(0xFF64748B)
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = scale.label,
                            fontSize = 14.sp,
                            color = if (scale == currentFontScale) ComposeColor.White else ComposeColor(0xFFCBD5E1)
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
    showDistance: Boolean
): GuestMarkerIconResult {
    val density = context.resources.displayMetrics.density
    val avatarDiameter = (32f * density).toInt()

    val hasCustomName = GuestMarkerHelper.hasCustomName(guest.name)
    val badgeText = GuestMarkerHelper.formatBadgeText(guest, distanceMeters, showDistance)

    val badgeHeight = if (badgeText.isNotEmpty()) (18f * density).toInt() else 0
    val badgeSpacing = (3f * density).toInt()

    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 10f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    val textWidth = if (badgeText.isNotEmpty()) textPaint.measureText(badgeText) else 0f
    val badgeWidth = if (badgeText.isNotEmpty()) (textWidth + 14f * density).toInt() else 0

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
            color = Color.argb((alpha * 0.92f).toInt(), 15, 23, 42)
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(badgeRect, 6f * density, 6f * density, badgeBgPaint)

        val badgeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (guest.isInactive) Color.argb(alpha, 100, 116, 139) else baseColor
            style = Paint.Style.STROKE
            strokeWidth = 1f * density
        }
        canvas.drawRoundRect(badgeRect, 6f * density, 6f * density, badgeBorderPaint)

        val textY = badgeTop + (badgeHeight / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f)
        textPaint.alpha = alpha
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

