package janush.tech.whereami

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
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
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay

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
fun getOpticalCenter(mapView: MapView?, target: IGeoPoint, offsetPixelsY: Int): GeoPoint {
    if (mapView == null) return GeoPoint(target.latitude, target.longitude)
    return calculateOpticalCenter(
        lat = target.latitude,
        lon = target.longitude,
        zoom = mapView.zoomLevelDouble,
        mapOrientation = mapView.mapOrientation,
        offsetPixelsY = offsetPixelsY
    )
}

fun calculateOpticalCenter(
    lat: Double,
    lon: Double,
    zoom: Double,
    mapOrientation: Float,
    offsetPixelsY: Int
): GeoPoint {
    if (offsetPixelsY == 0) {
        return GeoPoint(lat, lon)
    }

    // Ground resolution in meters per pixel at latitude and current zoom level
    val metersPerPixel = (156543.03392 * kotlin.math.cos(Math.toRadians(lat))) / Math.pow(2.0, zoom)
    val distMeters = offsetPixelsY * metersPerPixel
    if (distMeters <= 0.1) {
        return GeoPoint(lat, lon)
    }

    // In OSMDroid, mapOrientation rotates the canvas clockwise around the screen center.
    // Therefore, Screen UP corresponds to geographic bearing (360 - mapOrientation) % 360.
    val rawOrientation = mapOrientation.toDouble()
    val bearingScreenUp = ((360.0 - (rawOrientation % 360.0)) + 360.0) % 360.0

    // Geodesic destination point projection along bearingScreenUp
    val rEarth = 6378137.0 // WGS84 equatorial radius in meters
    val delta = distMeters / rEarth
    val phi1 = Math.toRadians(lat)
    val lambda1 = Math.toRadians(lon)
    val theta = Math.toRadians(bearingScreenUp)

    val sinPhi2 = kotlin.math.sin(phi1) * kotlin.math.cos(delta) +
            kotlin.math.cos(phi1) * kotlin.math.sin(delta) * kotlin.math.cos(theta)
    val phi2 = kotlin.math.asin(sinPhi2)
    val y = kotlin.math.sin(theta) * kotlin.math.sin(delta) * kotlin.math.cos(phi1)
    val x = kotlin.math.cos(delta) - kotlin.math.sin(phi1) * sinPhi2
    val lambda2 = lambda1 + kotlin.math.atan2(y, x)

    return GeoPoint(Math.toDegrees(phi2), Math.toDegrees(lambda2))
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
    latLng: Triple<Double, Double, Float?>?, // (lat, lng, bearing-or-null)
    trackPoints: List<GeoPoint> = emptyList(),
    selectedTrips: List<TripRecord> = emptyList(),
    savedPlaces: List<SavedPlace> = emptyList(),
    boundaryPoints: List<GeoPoint>? = null,
    pinnedBoundaryPoints: List<GeoPoint>? = null,
    heatMapTracks: List<List<GeoPoint>> = emptyList(),
    showHeatMap: Boolean = false,
    onToggleHeatMap: (() -> Unit)? = null,
    fitTrackTrigger: Long = 0L,
    fitPlacesTrigger: Long = 0L,
    destinationPoint: GeoPoint? = null,
    onDestinationMarkerClick: (() -> Unit)? = null,
    onSavedPlaceClick: ((SavedPlace) -> Unit)? = null,
    onClearDestination: (() -> Unit)? = null,
    onMapClick: ((GeoPoint) -> Unit)? = null,
    activityProfile: ActivityProfile = ActivityProfile.CAR,
    isCompact: Boolean = true,
    orientationMode: MapOrientationMode = MapOrientationMode.NORTH,
    onOrientationModeChange: ((MapOrientationMode) -> Unit)? = null,
    onInstantShare: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val currentOnMapClick by rememberUpdatedState(onMapClick)
    val currentOnOrientationChange by rememberUpdatedState(onOrientationModeChange)

    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val density = androidx.compose.ui.platform.LocalDensity.current.density

    // Viewport-aware optical vertical offset:
    // In portrait, the top LocalityCard (~150dp compact, ~280dp normal) and bottom controls (~90dp)
    // obscure the map. Optical offset centers the user marker in the visible aperture between them.
    val opticalOffsetY = remember(isLandscape, isCompact, density) {
        if (isLandscape) 0
        else {
            val topObstructionDp = if (isCompact) 150f else 280f
            val bottomObstructionDp = 90f
            val offsetDp = (topObstructionDp - bottomObstructionDp) / 2f
            (offsetDp * density).toInt()
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
    var boundaryPolygon by remember { mutableStateOf<org.osmdroid.views.overlay.Polygon?>(null) }
    var pinnedBoundaryPolygon by remember { mutableStateOf<org.osmdroid.views.overlay.Polygon?>(null) }
    var selectedTripPolylines by remember { mutableStateOf<List<Polyline>>(emptyList()) }
    var heatMapPolylines by remember { mutableStateOf<List<Polyline>>(emptyList()) }
    var isFollowing by remember { mutableStateOf(true) }

    val prefs = remember(context) {
        context.getSharedPreferences("where_am_i_map_prefs", Context.MODE_PRIVATE)
    }

    var baseLayer by remember {
        val saved = prefs.getString("base_layer", MapBaseLayer.STANDARD.name) ?: MapBaseLayer.STANDARD.name
        mutableStateOf(try { MapBaseLayer.valueOf(saved) } catch (_: Exception) { MapBaseLayer.STANDARD })
    }

    var showHikingOverlay by remember {
        mutableStateOf(prefs.getBoolean("hiking_overlay", false))
    }

    var fontScale by remember {
        val saved = prefs.getString("font_scale", MapFontScale.NORMAL.name) ?: MapFontScale.NORMAL.name
        mutableStateOf(try { MapFontScale.valueOf(saved) } catch (_: Exception) { MapFontScale.NORMAL })
    }

    var showSettingsDialog by remember { mutableStateOf(false) }
    var hikingOverlayRef by remember { mutableStateOf<TilesOverlay?>(null) }
    var hikingProviderRef by remember { mutableStateOf<MapTileProviderBasic?>(null) }
    var lastFrozenBearing by remember { mutableStateOf<Float?>(null) }

    val snapHandler = remember { Handler(Looper.getMainLooper()) }
    val snapRunnable = remember { Runnable { isFollowing = true } }

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
        prefs.edit().putString("base_layer", baseLayer.name).apply()
        map.invalidate()
    }

    // Auto-recommend Freemap Outdoor when switching to Hiking profile if base layer is still STANDARD
    LaunchedEffect(activityProfile) {
        if (activityProfile == ActivityProfile.HIKING && baseLayer == MapBaseLayer.STANDARD) {
            baseLayer = MapBaseLayer.FREEMAP_OUTDOOR
        }
    }

    // Dynamic Label / Font Scale
    LaunchedEffect(fontScale, mapView) {
        val map = mapView ?: return@LaunchedEffect
        map.tilesScaleFactor = fontScale.scaleFactor
        prefs.edit().putString("font_scale", fontScale.name).apply()
        map.invalidate()
    }

    // Dynamic Hiking / Tourist Trail Overlay
    LaunchedEffect(showHikingOverlay, mapView) {
        val map = mapView ?: return@LaunchedEffect
        prefs.edit().putBoolean("hiking_overlay", showHikingOverlay).apply()
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

    // Update marker position & auto-rotation with Stationary Bearing Freeze
    LaunchedEffect(latLng, orientationMode) {
        val pos = latLng ?: return@LaunchedEffect
        val map = mapView ?: return@LaunchedEffect
        val gp = GeoPoint(pos.first, pos.second)
        val rawBearing = pos.third
        if (rawBearing != null) {
            lastFrozenBearing = rawBearing
        }
        // Stationary Bearing Freeze (MAP-R03): maintain last valid driving heading when stopped
        val effectiveBearing = rawBearing ?: lastFrozenBearing

        val targetMapOrientation = when (orientationMode) {
            MapOrientationMode.COURSE_UP -> if (effectiveBearing != null) -effectiveBearing else 0f
            MapOrientationMode.NORTH -> 0f
            MapOrientationMode.EAST -> 270f
            MapOrientationMode.SOUTH -> 180f
            MapOrientationMode.WEST -> 90f
        }

        if (map.mapOrientation != targetMapOrientation) {
            map.mapOrientation = targetMapOrientation
        }

        val m = marker ?: Marker(map).also {
            it.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            it.isFlat = false
            map.overlays.add(it)
            marker = it
        }

        m.position = gp
        m.isFlat = false
        // In OSMDroid, Marker rotation on canvas is -rotation when isFlat is false.
        // To make the arrow point at screen angle theta clockwise from top, set m.rotation = -theta.
        // In COURSE_UP (AUTO), the map is already rotated to face forward, so cursor points straight UP (0°).
        // In fixed cardinal modes, cursor points in travel direction relative to screen top.
        val topHeading = when (orientationMode) {
            MapOrientationMode.COURSE_UP -> effectiveBearing ?: 0f
            MapOrientationMode.NORTH -> 0f
            MapOrientationMode.EAST -> 90f
            MapOrientationMode.SOUTH -> 180f
            MapOrientationMode.WEST -> 270f
        }
        val screenAngle = if (effectiveBearing != null) (effectiveBearing - topHeading + 360f) % 360f else 0f
        m.rotation = -screenAngle
        m.icon = makeMarkerIcon(context, effectiveBearing != null)
        m.title = null

        if (isFollowing && destinationPoint == null) {
            val centerGp = getOpticalCenter(map, gp, opticalOffsetY)
            map.controller.animateTo(centerGp)
        }
        map.invalidate()
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
        snapHandler.removeCallbacks(snapRunnable)

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

        val centerGp = getOpticalCenter(map, destinationPoint, opticalOffsetY)
        map.controller.animateTo(centerGp)
        map.invalidate()
    }

    // Saved Places Custom Pins
    var savedPlaceMarkers by remember { mutableStateOf<List<Marker>>(emptyList()) }
    LaunchedEffect(savedPlaces) {
        val map = mapView ?: return@LaunchedEffect
        savedPlaceMarkers.forEach { map.overlays.remove(it) }

        val newMarkers = mutableListOf<Marker>()
        savedPlaces.forEach { place ->
            val spm = Marker(map).apply {
                position = place.geoPoint
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                icon = makeSavedPlaceIcon(context, place.category)
                infoWindow = null
                setOnMarkerClickListener { _, _ ->
                    onSavedPlaceClick?.invoke(place)
                    true
                }
            }
            map.overlays.add(spm)
            newMarkers.add(spm)
        }
        savedPlaceMarkers = newMarkers
        map.invalidate()
    }

    // Update live trip polyline (Vivid Red #EF4444)
    LaunchedEffect(trackPoints) {
        val map = mapView ?: return@LaunchedEffect
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
            map.overlays.add(0, it) // Add below marker
            polyline = it
        }

        line.setPoints(trackPoints)
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
            }
            map.overlays.add(0, poly)
            pinnedBoundaryPolygon = poly
        }
        map.invalidate()
    }

    // Render Selected Past Trips with Distinct Colors (Phase 2)
    LaunchedEffect(selectedTrips) {
        val map = mapView ?: return@LaunchedEffect
        // Remove existing past trip polylines
        selectedTripPolylines.forEach { map.overlays.remove(it) }

        val colors = listOf(
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
                    setPoints(trip.points)
                }
                map.overlays.add(0, line)
                newLines.add(line)
            }
        }
        selectedTripPolylines = newLines
        map.invalidate()
    }

    // Render Road / Path Heat Map Layer (Density of all recorded paths)
    LaunchedEffect(heatMapTracks, showHeatMap) {
        val map = mapView ?: return@LaunchedEffect
        heatMapPolylines.forEach { map.overlays.remove(it) }

        if (!showHeatMap || heatMapTracks.isEmpty()) {
            heatMapPolylines = emptyList()
            map.invalidate()
            return@LaunchedEffect
        }

        val lines = mutableListOf<Polyline>()
        heatMapTracks.forEach { track ->
            if (track.size >= 2) {
                val poly = Polyline(map).apply {
                    // Semitransparent warm coral glow for heat map tracks (#88EF4444)
                    outlinePaint.color = Color.parseColor("#88EF4444")
                    outlinePaint.strokeWidth = 14f
                    outlinePaint.strokeCap = Paint.Cap.ROUND
                    outlinePaint.strokeJoin = Paint.Join.ROUND
                    setPoints(track)
                }
                map.overlays.add(0, poly)
                lines.add(poly)

                // Inner core line (#CCF59E0B)
                val core = Polyline(map).apply {
                    outlinePaint.color = Color.parseColor("#CCF59E0B")
                    outlinePaint.strokeWidth = 6f
                    outlinePaint.strokeCap = Paint.Cap.ROUND
                    outlinePaint.strokeJoin = Paint.Join.ROUND
                    setPoints(track)
                }
                map.overlays.add(0, core)
                lines.add(core)
            }
        }
        heatMapPolylines = lines
        map.invalidate()
    }

    // Collect all visible points (active recording track + selected past trips)
    val allShownPoints = remember(trackPoints, selectedTrips) {
        val pts = mutableListOf<GeoPoint>()
        pts.addAll(trackPoints)
        selectedTrips.forEach { trip ->
            pts.addAll(trip.points)
        }
        pts
    }

    // Fit-to-track trigger (fits all shown trips: active and/or selected)
    LaunchedEffect(fitTrackTrigger) {
        if (fitTrackTrigger > 0L && allShownPoints.size >= 2) {
            val map = mapView ?: return@LaunchedEffect
            val box = BoundingBox.fromGeoPoints(allShownPoints)
            isFollowing = false
            snapHandler.removeCallbacks(snapRunnable)
            map.zoomToBoundingBox(box, true, 100)
            currentZoom = map.zoomLevelDouble
        }
    }

    // Fit-to-saved-places trigger
    LaunchedEffect(fitPlacesTrigger) {
        if (fitPlacesTrigger > 0L && savedPlaces.isNotEmpty()) {
            val map = mapView ?: return@LaunchedEffect
            isFollowing = false
            snapHandler.removeCallbacks(snapRunnable)
            if (savedPlaces.size == 1) {
                map.controller.animateTo(savedPlaces.first().geoPoint)
                map.controller.setZoom(16.0)
                currentZoom = 16.0
            } else {
                val box = BoundingBox.fromGeoPoints(savedPlaces.map { it.geoPoint })
                map.zoomToBoundingBox(box, true, 120)
                currentZoom = map.zoomLevelDouble
            }
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
                    minZoomLevel = minZoom
                    maxZoomLevel = maxZoom
                    controller.setZoom(16.0)
                    currentZoom = 16.0
                    // Disable blur-inducing tile upscaling; render crisp 1:1 pixel native tiles
                    isTilesScaledToDpi = false
                    zoomController.setVisibility(
                        org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER
                    )

                    // Map tap listener: tapping anywhere on map selects that point & reverse geocodes
                    val eventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
                        override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                            currentOnMapClick?.invoke(p)
                            return true
                        }
                        override fun longPressHelper(p: GeoPoint): Boolean {
                            return false
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
                                snapHandler.removeCallbacks(snapRunnable)
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
                                snapHandler.removeCallbacks(snapRunnable)
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

        // Floating Map Controls (Recenter/Refresh, Layers/Settings, Fit Track, Fit Places)
        // Zoom buttons (+/-) removed in favor of pinch-to-zoom; orientation moved to Map Settings
        // Positioned at BottomEnd (above bottom action pill / destination card) so it NEVER collides with Locality Card
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(
                    end = 12.dp,
                    bottom = if (destinationPoint != null) 230.dp else 105.dp
                ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Unified MyLocation & Refresh Button (FT2-12)
            IconButton(
                onClick = {
                    isFollowing = true
                    snapHandler.removeCallbacks(snapRunnable)
                    onClearDestination?.invoke()
                    mapView?.tileProvider?.clearTileCache()
                    hikingProviderRef?.clearTileCache()
                    latLng?.let { pos ->
                        val gp = GeoPoint(pos.first, pos.second)
                        val centerGp = getOpticalCenter(mapView, gp, opticalOffsetY)
                        mapView?.controller?.setCenter(centerGp)
                    }
                    mapView?.invalidate()
                },
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(if (isFollowing && destinationPoint == null) ComposeColor(0xEE0284C7) else ComposeColor(0xCC1E293B))
            ) {
                Icon(
                    imageVector = Icons.Default.MyLocation,
                    contentDescription = "Recenter & Refresh",
                    tint = ComposeColor.White
                )
            }

            // Map Layers & Settings (MAP-R01, MAP-R02)
            IconButton(
                onClick = { showSettingsDialog = true },
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(
                        if (showHikingOverlay || baseLayer != MapBaseLayer.STANDARD || fontScale != MapFontScale.NORMAL)
                            ComposeColor(0xFF0284C7)
                        else
                            ComposeColor(0xCC1E293B)
                    )
            ) {
                Icon(
                    imageVector = Icons.Default.Layers,
                    contentDescription = "Map Settings & Layers",
                    tint = ComposeColor.White
                )
            }

            // Instant Share Current Position
            if (onInstantShare != null && latLng != null) {
                IconButton(
                    onClick = { onInstantShare.invoke() },
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(ComposeColor(0xCC1E293B))
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Share Current Position",
                        tint = ComposeColor(0xFF38BDF8)
                    )
                }
            }

            // Fit Shown Trips (if at least 2 points exist from active recording or selected past trips)
            if (allShownPoints.size >= 2) {
                IconButton(
                    onClick = {
                        val map = mapView ?: return@IconButton
                        val box = BoundingBox.fromGeoPoints(allShownPoints)
                        isFollowing = false
                        snapHandler.removeCallbacks(snapRunnable)
                        map.zoomToBoundingBox(box, true, 100)
                    },
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(ComposeColor(0xCC1E293B))
                ) {
                    Icon(
                        imageVector = Icons.Default.CropFree,
                        contentDescription = "Fit Shown Trips",
                        tint = ComposeColor(0xFF38BDF8)
                    )
                }
            }
        }

        // Floating "📍 Recenter" Pill when user has panned away (Option B)
        if (!isFollowing && destinationPoint == null) {
            Surface(
                onClick = {
                    isFollowing = true
                    latLng?.let { pos ->
                        val gp = GeoPoint(pos.first, pos.second)
                        val centerGp = getOpticalCenter(mapView, gp, opticalOffsetY)
                        mapView?.controller?.setCenter(centerGp)
                    }
                    mapView?.invalidate()
                },
                shape = RoundedCornerShape(20.dp),
                color = ComposeColor(0xEE0284C7),
                shadowElevation = 6.dp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 110.dp)
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

    var showCacheManagerDialog by remember { mutableStateOf(false) }

    if (showCacheManagerDialog) {
        CacheManagerDialog(
            onDismissRequest = { showCacheManagerDialog = false }
        )
    }

    if (showSettingsDialog) {
        MapSettingsDialog(
            currentBaseLayer = baseLayer,
            onBaseLayerChange = { baseLayer = it },
            hikingOverlayEnabled = showHikingOverlay,
            onHikingOverlayToggle = { showHikingOverlay = it },
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
                        val b = latLng?.third ?: lastFrozenBearing
                        b?.let { mapView?.mapOrientation = -it }
                    }
                }
                mapView?.invalidate()
            },
            currentLatLng = latLng,
            onClearCache = {
                mapView?.tileProvider?.clearTileCache()
                hikingProviderRef?.clearTileCache()
                mapView?.invalidate()
                Toast.makeText(context, "Map tile cache cleared", Toast.LENGTH_SHORT).show()
            },
            onOpenCacheManager = { showCacheManagerDialog = true },
            onDismiss = { showSettingsDialog = false }
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            hikingProviderRef?.clearTileCache()
            mapView?.onDetach()
            snapHandler.removeCallbacks(snapRunnable)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MapSettingsDialog(
    currentBaseLayer: MapBaseLayer,
    onBaseLayerChange: (MapBaseLayer) -> Unit,
    hikingOverlayEnabled: Boolean,
    onHikingOverlayToggle: (Boolean) -> Unit,
    currentFontScale: MapFontScale,
    onFontScaleChange: (MapFontScale) -> Unit,
    currentOrientationMode: MapOrientationMode,
    onOrientationModeChange: (MapOrientationMode) -> Unit,
    currentLatLng: Triple<Double, Double, Float?>? = null,
    onClearCache: () -> Unit,
    onOpenCacheManager: () -> Unit,
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
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(label, fontSize = 11.sp, color = ComposeColor.White, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
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
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(label, fontSize = 11.sp, color = ComposeColor.White, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
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
                        color = ComposeColor.White
                    )
                    Text(
                        text = "Marked color-coded trails & summits (Waymarked Trails)",
                        fontSize = 11.sp,
                        color = ComposeColor(0xFF94A3B8)
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        try {
                            tileDir.deleteRecursively()
                            tileDir.mkdirs()
                            tileCacheSizeMb = 0L
                            onClearCache()
                        } catch (e: Exception) {
                            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ComposeColor(0xFFF87171)),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = "Clear Cache", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Clear Cache", fontSize = 12.sp)
                }

                val isDownloadingTiles = cacheDownloadState is CacheDownloadState.Downloading
                Button(
                    onClick = {
                        val lat = currentLatLng?.first ?: 50.0647
                        val lng = currentLatLng?.second ?: 19.9450
                        MapCacheHelper.startCachingRegion(
                            context,
                            lat,
                            lng,
                            isFreemapOutdoor = (currentBaseLayer == MapBaseLayer.FREEMAP_OUTDOOR)
                        )
                    },
                    enabled = !isDownloadingTiles,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ComposeColor(0xFF1E293B),
                        disabledContainerColor = ComposeColor(0xFF1E293B),
                        contentColor = ComposeColor(0xFF38BDF8),
                        disabledContentColor = ComposeColor(0xFF38BDF8).copy(alpha = 0.7f)
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        if (isDownloadingTiles) Icons.Default.Refresh else Icons.Default.Download,
                        contentDescription = "Pre-cache",
                        modifier = Modifier.size(16.dp),
                        tint = if (isDownloadingTiles) ComposeColor(0xFF38BDF8).copy(alpha = 0.7f) else ComposeColor(0xFF38BDF8)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (isDownloadingTiles) "Downloading..." else "Cache 5km", fontSize = 12.sp)
                }
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

            Spacer(modifier = Modifier.height(10.dp))

            Button(
                onClick = onOpenCacheManager,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = ComposeColor(0xFF0284C7)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.Storage, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Manage Storage, Boundaries & Pre-fetch", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
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
    val size = (context.resources.displayMetrics.density * 32).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val cx = size / 2f
    val cy = size / 2f
    val radius = size / 2f - 4

    val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    canvas.drawCircle(cx, cy, radius + 3, ringPaint)

    if (!hasBearing) {
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0EA5E9")
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy, radius, dotPaint)
    } else {
        // Draw arrow pointing North (0° up) - Marker rotation & flat orientation handle map alignment
        val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0EA5E9")
            style = Paint.Style.FILL
        }
        val path = Path().apply {
            moveTo(cx, cy - radius)
            lineTo(cx - radius * 0.55f, cy + radius * 0.7f)
            lineTo(cx, cy + radius * 0.25f)
            lineTo(cx + radius * 0.55f, cy + radius * 0.7f)
            close()
        }
        canvas.drawPath(path, arrowPaint)
    }

    return android.graphics.drawable.BitmapDrawable(context.resources, bmp)
}

private fun makeSavedPlaceIcon(context: Context, category: PlaceCategory): android.graphics.drawable.BitmapDrawable {
    val size = (context.resources.displayMetrics.density * 34).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val cx = size / 2f
    val cy = size / 2.6f
    val r = size / 3.2f

    val pinColor = when (category) {
        PlaceCategory.HOME -> Color.parseColor("#10B981")     // Emerald Green
        PlaceCategory.WORK -> Color.parseColor("#3B82F6")     // Blue
        PlaceCategory.FAMILY -> Color.parseColor("#EC4899")   // Pink
        PlaceCategory.SCHOOL -> Color.parseColor("#F59E0B")   // Amber
        PlaceCategory.FAVORITE -> Color.parseColor("#8B5CF6") // Purple
        PlaceCategory.CUSTOM -> Color.parseColor("#06B6D4")   // Cyan
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
    canvas.drawText(category.iconEmoji, cx, yPos, textPaint)

    return android.graphics.drawable.BitmapDrawable(context.resources, bmp)
}
