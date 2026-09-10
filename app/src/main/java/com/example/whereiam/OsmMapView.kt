package com.example.whereiam

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

enum class MapOrientationMode {
    NORTH,     // 0° (North at top)
    EAST,      // 90° (East at top)
    SOUTH,     // 180° (South at top)
    WEST,      // 270° (West at top)
    COURSE_UP  // Auto-rotates with GPS heading
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
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val currentOnMapClick by rememberUpdatedState(onMapClick)
    val currentOnOrientationChange by rememberUpdatedState(onOrientationModeChange)

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
    var selectedTripPolylines by remember { mutableStateOf<List<Polyline>>(emptyList()) }
    var heatMapPolylines by remember { mutableStateOf<List<Polyline>>(emptyList()) }
    var isFollowing by remember { mutableStateOf(true) }

    val snapHandler = remember { Handler(Looper.getMainLooper()) }
    val snapRunnable = remember { Runnable { isFollowing = true } }

    // Update marker position & auto-rotation
    LaunchedEffect(latLng, orientationMode) {
        val pos = latLng ?: return@LaunchedEffect
        val map = mapView ?: return@LaunchedEffect
        val gp = GeoPoint(pos.first, pos.second)
        val bearing = pos.third

        // In OSMDroid:
        // mapOrientation is clockwise in degrees.
        // NORTH: 0° (North up)
        // EAST: 270° (so 90° East is rotated to top)
        // SOUTH: 180° (so 180° South is rotated to top)
        // WEST: 90° (so 270° West is rotated to top)
        // COURSE_UP: -bearing (so heading is rotated to top)
        val targetMapOrientation = when (orientationMode) {
            MapOrientationMode.COURSE_UP -> if (bearing != null) -bearing else 0f
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
            MapOrientationMode.COURSE_UP -> bearing ?: 0f
            MapOrientationMode.NORTH -> 0f
            MapOrientationMode.EAST -> 90f
            MapOrientationMode.SOUTH -> 180f
            MapOrientationMode.WEST -> 270f
        }
        val screenAngle = if (bearing != null) (bearing - topHeading + 360f) % 360f else 0f
        m.rotation = -screenAngle
        m.icon = makeMarkerIcon(context, bearing != null)
        m.title = null

        if (isFollowing && destinationPoint == null) {
            map.controller.animateTo(gp)
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

        map.controller.animateTo(destinationPoint)
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
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    minZoomLevel = minZoom
                    maxZoomLevel = maxZoom
                    controller.setZoom(16.0)
                    currentZoom = 16.0
                    // Disable blur-inducing tile upscaling; render crisp 1:1 pixel native tiles
                    isTilesScaledToDpi = false
                    // Use SOFTWARE layer for reliable vector/path overlay rendering without GPU deadlock
                    setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
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

                    addMapListener(object : org.osmdroid.events.MapListener {
                        override fun onScroll(event: org.osmdroid.events.ScrollEvent?): Boolean {
                            isFollowing = false
                            snapHandler.removeCallbacks(snapRunnable)
                            if (destinationPoint == null) {
                                snapHandler.postDelayed(snapRunnable, 8_000)
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

        val canZoomIn = currentZoom < (maxZoom - 0.1)
        val canZoomOut = currentZoom > (minZoom + 0.1)

        // Floating Map Controls (+, -, Compass/Orientation, Recenter, Tile Refresh, Fit Track, Fit Places)
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
            // Zoom In
            IconButton(
                onClick = {
                    if (canZoomIn) {
                        mapView?.controller?.zoomIn()
                        currentZoom = mapView?.zoomLevelDouble ?: (currentZoom + 1.0)
                    }
                },
                enabled = canZoomIn,
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(if (canZoomIn) ComposeColor(0xCC1E293B) else ComposeColor(0x551E293B))
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Zoom In",
                    tint = if (canZoomIn) ComposeColor.White else ComposeColor(0x44FFFFFF)
                )
            }

            // Zoom Out
            IconButton(
                onClick = {
                    if (canZoomOut) {
                        mapView?.controller?.zoomOut()
                        currentZoom = mapView?.zoomLevelDouble ?: (currentZoom - 1.0)
                    }
                },
                enabled = canZoomOut,
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(if (canZoomOut) ComposeColor(0xCC1E293B) else ComposeColor(0x551E293B))
            ) {
                Icon(
                    imageVector = Icons.Default.Remove,
                    contentDescription = "Zoom Out",
                    tint = if (canZoomOut) ComposeColor.White else ComposeColor(0x44FFFFFF)
                )
            }

            // Compass / Cardinal Orientation Cycle (N 0° -> E 270° -> S 180° -> W 90° -> AUTO)
            IconButton(
                onClick = {
                    val nextMode = when (orientationMode) {
                        MapOrientationMode.NORTH -> MapOrientationMode.EAST
                        MapOrientationMode.EAST -> MapOrientationMode.SOUTH
                        MapOrientationMode.SOUTH -> MapOrientationMode.WEST
                        MapOrientationMode.WEST -> MapOrientationMode.COURSE_UP
                        MapOrientationMode.COURSE_UP -> MapOrientationMode.NORTH
                    }
                    currentOnOrientationChange?.invoke(nextMode)

                    when (nextMode) {
                        MapOrientationMode.NORTH -> mapView?.mapOrientation = 0f
                        MapOrientationMode.EAST -> mapView?.mapOrientation = 270f
                        MapOrientationMode.SOUTH -> mapView?.mapOrientation = 180f
                        MapOrientationMode.WEST -> mapView?.mapOrientation = 90f
                        MapOrientationMode.COURSE_UP -> {
                            latLng?.third?.let { b -> mapView?.mapOrientation = -b }
                        }
                    }
                    mapView?.invalidate()
                },
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        when (orientationMode) {
                            MapOrientationMode.COURSE_UP -> ComposeColor(0xFF0284C7) // Sky blue for auto follow
                            MapOrientationMode.NORTH -> ComposeColor(0xEE1E293B)     // Dark slate
                            else -> ComposeColor(0xFFD97706)                         // Amber for fixed manual rotations
                        }
                    )
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    when (orientationMode) {
                        MapOrientationMode.COURSE_UP -> {
                            Text(
                                text = "AUTO",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                color = ComposeColor.White,
                                textAlign = TextAlign.Center
                            )
                        }
                        MapOrientationMode.NORTH -> {
                            Text(
                                text = "N",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Black,
                                color = ComposeColor(0xFFEF4444),
                                textAlign = TextAlign.Center
                            )
                        }
                        MapOrientationMode.EAST -> {
                            Text(
                                text = "E",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Black,
                                color = ComposeColor.White,
                                textAlign = TextAlign.Center
                            )
                        }
                        MapOrientationMode.SOUTH -> {
                            Text(
                                text = "S",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Black,
                                color = ComposeColor.White,
                                textAlign = TextAlign.Center
                            )
                        }
                        MapOrientationMode.WEST -> {
                            Text(
                                text = "W",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Black,
                                color = ComposeColor.White,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            // Recenter
            IconButton(
                onClick = {
                    isFollowing = true
                    snapHandler.removeCallbacks(snapRunnable)
                    onClearDestination?.invoke()
                    latLng?.let { pos ->
                        mapView?.controller?.animateTo(GeoPoint(pos.first, pos.second))
                    }
                },
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(if (isFollowing && destinationPoint == null) ComposeColor(0xEE0284C7) else ComposeColor(0xCC1E293B))
            ) {
                Icon(
                    imageVector = Icons.Default.MyLocation,
                    contentDescription = "Recenter",
                    tint = ComposeColor.White
                )
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
    }

    DisposableEffect(Unit) {
        onDispose {
            mapView?.onDetach()
            snapHandler.removeCallbacks(snapRunnable)
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
