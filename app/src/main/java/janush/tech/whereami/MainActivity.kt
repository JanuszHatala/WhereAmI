package janush.tech.whereami

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.KeyboardType
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import janush.tech.whereami.theme.WhereIAmTheme
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false) ||
            permissions.getOrDefault(Manifest.permission.ACCESS_COARSE_LOCATION, false)
        ) {
            viewModel.startTracking()
            AppStateManager.getInstance(this).ensureAutoStandbyServiceRunning()
        }
    }

    override fun onStart() {
        super.onStart()
        AppStateManager.getInstance(this).setAppForegroundState(true)
    }

    override fun onStop() {
        super.onStop()
        AppStateManager.getInstance(this).setAppForegroundState(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleNotificationIntent(intent)
        checkPermissionsAndStart()
        setContent {
            WhereIAmTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    LocationScreen(viewModel)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
    }

    private fun handleNotificationIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(CacheManager.EXTRA_OPEN_CACHE_MANAGER, false) == true ||
            intent?.action == CacheManager.ACTION_OPEN_CACHE_MANAGER
        ) {
            viewModel.openCacheManager()
        } else if (intent?.getBooleanExtra(LiveTrackingService.EXTRA_OPEN_LIVE_SHARING, false) == true ||
            intent?.action == LiveTrackingService.ACTION_OPEN_LIVE_SHARING
        ) {
            viewModel.openLiveSharing()
        }
    }

    private fun checkPermissionsAndStart() {
        val permissionsToRequest = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val locationGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        val notificationGranted = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true

        if (locationGranted && notificationGranted) {
            viewModel.startTracking()
        } else {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }
}

fun launchNavigation(context: android.content.Context, lat: Double, lng: Double) {
    val uri = Uri.parse("google.navigation:q=$lat,$lng")
    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
        setPackage("com.google.android.apps.maps")
    }
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
        val fallbackUri = Uri.parse("geo:$lat,$lng?q=$lat,$lng")
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, fallbackUri))
        } catch (_: Exception) {
            val webUri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$lat,$lng")
            context.startActivity(Intent(Intent.ACTION_VIEW, webUri))
        }
    }
}

fun openInGoogleMaps(context: android.content.Context, lat: Double, lng: Double, label: String = "") {
    val query = if (label.isNotBlank()) Uri.encode(label) else "$lat,$lng"
    val uri = Uri.parse("geo:$lat,$lng?q=$lat,$lng($query)")
    val intent = Intent(Intent.ACTION_VIEW, uri)
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
        val webUri = Uri.parse("https://maps.google.com/?q=$lat,$lng")
        context.startActivity(Intent(Intent.ACTION_VIEW, webUri))
    }
}

/**
 * Correctly aligned emoji in a Row context.
 * Noto Color Emoji has large ascender metrics; this pattern trims the excess
 * and centers the glyph so Row(CenterVertically) actually centers the visual glyph.
 */
/**
 * Standard Composable for rendering emojis with guaranteed baseline alignment.
 * Noto Color Emoji's glyph metrics sit near the bottom of its bounding box on Android,
 * while Latin uppercase letters occupy the higher cap-height region.
 * We apply a default upward offset (-maxOf(2.5f, fontSize.value * 0.28f).dp) so the emoji's
 * optical center is strictly aligned with the cap-height center and baseline of adjacent Latin text.
 */
@Composable
fun EmojiText(
    emoji: String,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    yOffsetDp: Dp = Dp.Unspecified
) {
    val effectiveOffset = if (yOffsetDp != Dp.Unspecified) {
        yOffsetDp
    } else {
        (-maxOf(2.5f, fontSize.value * 0.28f)).dp
    }
    Text(
        text = emoji,
        fontSize = fontSize,
        modifier = modifier.offset(y = effectiveOffset),
        style = TextStyle(
            platformStyle = PlatformTextStyle(includeFontPadding = false),
            lineHeightStyle = LineHeightStyle(
                alignment = LineHeightStyle.Alignment.Center,
                trim = LineHeightStyle.Trim.Both
            ),
            lineHeight = fontSize
        )
    )
}

data class LocationShareTarget(
    val title: String,
    val placeName: String,
    val addressOrCoords: String,
    val latitude: Double,
    val longitude: Double
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity

    val locationData by viewModel.locationData.collectAsState()
    val displayLanguage by viewModel.displayLanguage.collectAsState()
    val currentLatLng by viewModel.currentLatLng.collectAsState()
    val currentLocationFix by viewModel.currentLocationFix.collectAsState()
    val keepScreenOn by viewModel.keepScreenOn.collectAsState()
    val activeTrip by viewModel.activeTrip.collectAsState()
    val tripMode by viewModel.tripMode.collectAsState()
    val activityProfile by viewModel.activityProfile.collectAsState()
    val autoStopMinutes by viewModel.autoStopMinutes.collectAsState()
    val savedTrips by viewModel.savedTrips.collectAsState()
    val selectedTripIds by viewModel.selectedTripIds.collectAsState()
    val showBorders by viewModel.showBorders.collectAsState()
    val boundaryPoints by viewModel.boundaryPoints.collectAsState()
    val pinnedBoundaryPoints by viewModel.pinnedBoundaryPoints.collectAsState()
    val fitTrackTrigger by viewModel.fitTrackTrigger.collectAsState()
    val fitPlacesTrigger by viewModel.fitPlacesTrigger.collectAsState()
    val destinationPoint by viewModel.destinationPoint.collectAsState()
    val destinationItem by viewModel.destinationItem.collectAsState()
    val savedPlaces by viewModel.savedPlaces.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val localityCardStyle by viewModel.localityCardStyle.collectAsState()
    val compactStreetNames by viewModel.compactStreetNames.collectAsState()
    val orientationMode by viewModel.orientationMode.collectAsState()
    val showHeatMap by viewModel.showHeatMap.collectAsState()
    val heatMapFilterState by viewModel.heatMapFilterState.collectAsState()
    var showHeatMapSettingsDialog by remember { mutableStateOf(false) }
    var fitHeatMapTrigger by remember { mutableStateOf(0L) }

    val powerPolicy by viewModel.powerPolicy.collectAsState()
    val isCharging by viewModel.isCharging.collectAsState()
    val lifecycleMode by viewModel.lifecycleMode.collectAsState()

    val selectedTripsList = remember(savedTrips, selectedTripIds) {
        savedTrips.filter { selectedTripIds.contains(it.id) }
    }

    // Invariant: When an active trip is recording, show ONLY active trip pauses.
    // Never show past trip pauses alongside an ongoing recording.
    val allDisplayPauses = remember(activeTrip, selectedTripsList) {
        val list = mutableListOf<TripPause>()
        if (activeTrip != null) {
            list.addAll(activeTrip!!.pauses)
        } else {
            selectedTripsList.forEach { list.addAll(it.pauses) }
        }
        list
    }

    var showTripsSheet by remember { mutableStateOf(false) }
    var sheetTab by remember { mutableStateOf(0) } // 0: Trip History, 1: Saved Places, 2: Stats
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showMapSettingsDialog by remember { mutableStateOf(false) }
    var profileToConfigure by remember { mutableStateOf<ActivityProfile?>(null) }
    var showSearchDialog by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var tripToRename by remember { mutableStateOf<TripRecord?>(null) }
    var renameInputText by remember { mutableStateOf("") }
    var tripToDelete by remember { mutableStateOf<TripRecord?>(null) }
    var showMergeConfirmationDialog by remember { mutableStateOf(false) }
    var showResetDefaultsConfirm by remember { mutableStateOf(false) }

    // Save Place Dialog State
    var showSavePlaceDialog by remember { mutableStateOf(false) }
    var placeToSaveCoords by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var placeToSaveLocality by remember { mutableStateOf("") }
    var placeToSaveStreet by remember { mutableStateOf("") }
    var placeToSaveName by remember { mutableStateOf("") }
    var placeToSaveCategory by remember { mutableStateOf(PlaceCategory.FAVORITE) }
    var placeToSaveColor by remember { mutableStateOf("") }

    // Measured Layout Positions for Exact Optical Viewport Centering
    var topCardBottomPx by remember { mutableStateOf(0) }
    var bottomControlsTopPx by remember { mutableStateOf(0) }
    var rootScreenHeightPx by remember { mutableStateOf(0) }
    var rootScreenWidthPx by remember { mutableStateOf(0) }
    val density = androidx.compose.ui.platform.LocalDensity.current.density

    // Selected Saved Place Details Modal State
    var selectedSavedPlace by remember { mutableStateOf<SavedPlace?>(null) }
    var placeToDelete by remember { mutableStateOf<SavedPlace?>(null) }
    var editingSavedPlace by remember { mutableStateOf<SavedPlace?>(null) }
    var showDestinationDetailsCard by remember { mutableStateOf(false) }
    val liveSharingManager = remember { LiveSharingManager.getInstance(context) }
    val liveSession by liveSharingManager.currentSession.collectAsState()
    val staticLiveId by liveSharingManager.staticLiveId.collectAsState()
    val usbConnectionManager = remember { UsbConnectionManager.getInstance(context) }
    val isUsbConnected by usbConnectionManager.isUsbConnected.collectAsState()
    val isTripPaused by viewModel.isTripPaused.collectAsState()
    var showActiveTripRouteDialog by remember { mutableStateOf(false) }
    var showLiveShareDialog by remember { mutableStateOf(false) }
    var showLiveQuickShareModal by remember { mutableStateOf(false) }
    var showLiveQrCodeModal by remember { mutableStateOf(false) }
    var qrCodeTargetUrl by remember { mutableStateOf("") }
    var qrCodeTargetTitle by remember { mutableStateOf("") }
    var activeLocationShareTarget by remember { mutableStateOf<LocationShareTarget?>(null) }
    val showCacheManagerDialog by viewModel.showCacheManagerDialog.collectAsState()
    val requestedDialogTarget by viewModel.requestedDialogTarget.collectAsState()

    val dismissAllDialogs = {
        showTripsSheet = false
        showSettingsSheet = false
        profileToConfigure = null
        showSearchDialog = false
        tripToRename = null
        tripToDelete = null
        showMergeConfirmationDialog = false
        showResetDefaultsConfirm = false
        showSavePlaceDialog = false
        placeToSaveCoords = null
        selectedSavedPlace = null
        placeToDelete = null
        editingSavedPlace = null
        showDestinationDetailsCard = false
        showActiveTripRouteDialog = false
        showLiveShareDialog = false
        showLiveQuickShareModal = false
        showLiveQrCodeModal = false
        showHeatMapSettingsDialog = false
        showMapSettingsDialog = false
        activeLocationShareTarget = null
        viewModel.dismissCacheManager()
    }

    LaunchedEffect(requestedDialogTarget) {
        when (requestedDialogTarget) {
            MainViewModel.AppDialogTarget.CACHE_MANAGER -> {
                if (!showCacheManagerDialog) {
                    dismissAllDialogs()
                    viewModel.openCacheManager()
                }
                viewModel.consumeDialogTarget()
            }
            MainViewModel.AppDialogTarget.LIVE_SHARING -> {
                if (!showLiveShareDialog) {
                    dismissAllDialogs()
                    showLiveShareDialog = true
                }
                viewModel.consumeDialogTarget()
            }
            MainViewModel.AppDialogTarget.NONE -> {}
        }
    }

    // Keep Screen On handler
    DisposableEffect(keepScreenOn) {
        if (keepScreenOn) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val primaryPlace = locationData.primaryPlace
    val secondaryPlace = locationData.secondaryPlace
    val hierarchySubtitle = remember(primaryPlace) { LocationManager.formatHierarchy(primaryPlace) }

    val shareCurrentLocation = remember(currentLatLng, primaryPlace, secondaryPlace) {
        {
            val lat = currentLatLng?.first ?: 0.0
            val lng = currentLatLng?.second ?: 0.0
            val parts = mutableListOf<String>()
            val street = secondaryPlace?.street ?: primaryPlace?.street
            if (!street.isNullOrBlank()) parts.add(street)
            val road = secondaryPlace?.roadRef ?: primaryPlace?.roadRef
            if (!road.isNullOrBlank() && street != road) parts.add("[$road]")
            primaryPlace?.city?.takeIf { it.isNotBlank() && it != "Unknown City" }?.let { parts.add(it) }
            primaryPlace?.country?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
            val address = if (parts.isEmpty()) "Current Location" else parts.joinToString(", ")
            activeLocationShareTarget = LocationShareTarget(
                title = "Share Current Position",
                placeName = primaryPlace?.city?.takeIf { it.isNotBlank() && it != "Unknown City" } ?: "Current Location",
                addressOrCoords = address,
                latitude = lat,
                longitude = lng
            )
        }
    }
    val isCompact = localityCardStyle == LocalityCardStyle.COMPACT

    // Calendar-accurate timestamp boundaries for local time filters
    val (startOfTodayMs, startOfWeekMs, startOfMonthMs, startOfYearMs) = remember {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val todayMs = cal.timeInMillis

        val weekCal = Calendar.getInstance().apply {
            timeInMillis = todayMs
            set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
        }
        val weekMs = weekCal.timeInMillis

        val monthCal = Calendar.getInstance().apply {
            timeInMillis = todayMs
            set(Calendar.DAY_OF_MONTH, 1)
        }
        val monthMs = monthCal.timeInMillis

        val yearCal = Calendar.getInstance().apply {
            timeInMillis = todayMs
            set(Calendar.DAY_OF_YEAR, 1)
        }
        val yearMs = yearCal.timeInMillis

        listOf(todayMs, weekMs, monthMs, yearMs)
    }

    val heatMapTracks = remember(savedTrips, activeTrip, heatMapFilterState, startOfTodayMs, startOfWeekMs, startOfMonthMs, startOfYearMs) {
        val tracks = mutableListOf<List<GeoPoint>>()
        val filtered = savedTrips.filter { trip ->
            val matchesProfile = heatMapFilterState.activityProfiles.isEmpty() || heatMapFilterState.activityProfiles.contains(trip.activityProfile)
            val matchesDate = when (heatMapFilterState.datePeriod) {
                "TODAY" -> trip.startTime >= startOfTodayMs
                "WEEK" -> trip.startTime >= startOfWeekMs
                "MONTH" -> trip.startTime >= startOfMonthMs
                "YEAR" -> trip.startTime >= startOfYearMs
                else -> true
            }
            matchesProfile && matchesDate
        }
        filtered.forEach { tracks.add(it.points) }

        val currentActiveTrip = activeTrip
        if (heatMapFilterState.includeActiveTrip && currentActiveTrip != null && currentActiveTrip.points.isNotEmpty()) {
            val matchesProfile = heatMapFilterState.activityProfiles.isEmpty() || heatMapFilterState.activityProfiles.contains(currentActiveTrip.activityProfile)
            val matchesDate = when (heatMapFilterState.datePeriod) {
                "TODAY" -> currentActiveTrip.startTime >= startOfTodayMs
                "WEEK" -> currentActiveTrip.startTime >= startOfWeekMs
                "MONTH" -> currentActiveTrip.startTime >= startOfMonthMs
                "YEAR" -> currentActiveTrip.startTime >= startOfYearMs
                else -> true
            }
            if (matchesProfile && matchesDate) {
                tracks.add(currentActiveTrip.points)
            }
        }
        tracks
    }

    val heatMapTitle = remember(heatMapFilterState) {
        val parts = mutableListOf<String>()
        if (heatMapFilterState.datePeriod != "ALL") {
            val p = when (heatMapFilterState.datePeriod) {
                "TODAY" -> "Today"
                "WEEK" -> "This Wk"
                "MONTH" -> "This Mo"
                "YEAR" -> "This Yr"
                else -> heatMapFilterState.datePeriod
            }
            parts.add(p)
        }
        if (heatMapFilterState.activityProfiles.isNotEmpty()) {
            parts.add(heatMapFilterState.activityProfiles.joinToString(", ") { it.displayName })
        }
        if (heatMapFilterState.minVisits > 1) {
            parts.add("${heatMapFilterState.minVisits}+")
        }
        if (parts.isEmpty()) "Heat Map" else "Heat Map (${parts.joinToString(", ")})"
    }

    val measuredOpticalOffsetX = remember(isLandscape, density) {
        if (isLandscape) {
            // In landscape, left floating panel is up to 380dp + 16dp padding = ~396dp.
            // Half of this is ~198dp, shifting the GPS focus rightward to center it perfectly
            // in the unobstructed map area.
            (198f * density).toInt()
        } else {
            null
        }
    }

    val measuredOpticalOffsetY = remember(isLandscape, topCardBottomPx, bottomControlsTopPx, rootScreenHeightPx, destinationPoint, selectedSavedPlace, density) {
        if (isLandscape) {
            // In landscape, slight upward bias (-16dp) so vehicle cursor sits clear above the bottom controls
            -(16f * density).toInt()
        } else if (topCardBottomPx > 0 && rootScreenHeightPx > 0) {
            // Bottom clearance accounts for bottom controls + floating action buttons column (~200dp, or ~300dp with destination/saved card)
            val hasActiveTarget = destinationPoint != null || selectedSavedPlace != null
            val bottomClearanceDp = if (hasActiveTarget) 300f else 200f
            val maxBottomAllowedPx = rootScreenHeightPx - (bottomClearanceDp * density).toInt()
            val effectiveBottomPx = if (bottomControlsTopPx > 0) {
                minOf(bottomControlsTopPx, maxBottomAllowedPx)
            } else {
                maxBottomAllowedPx
            }
            val apertureCenter = (topCardBottomPx + effectiveBottomPx) / 2
            // Upward optical bias of 24dp so cursor sits comfortably in upper half of clear aperture
            val upwardBiasPx = (24f * density).toInt()
            (apertureCenter - (rootScreenHeightPx / 2)) - upwardBiasPx
        } else {
            null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned {
                rootScreenWidthPx = it.size.width
                rootScreenHeightPx = it.size.height
            }
    ) {
        // ── Fullscreen Map Canvas (Both Portrait and Landscape) ──────────────
        OsmMapView(
            latLng = currentLatLng,
            locationFix = currentLocationFix,
            trackPoints = activeTrip?.points ?: emptyList(),
            selectedTrips = selectedTripsList,
            savedPlaces = savedPlaces,
            boundaryPoints = boundaryPoints,
            pinnedBoundaryPoints = pinnedBoundaryPoints,
            heatMapTracks = heatMapTracks,
            showHeatMap = showHeatMap,
            onToggleHeatMap = { viewModel.toggleShowHeatMap() },
            onOpenHeatMapSettings = { showHeatMapSettingsDialog = true },
            heatMapFilterActive = heatMapFilterState.hasActiveFilter,
            heatMapTitle = heatMapTitle,
            heatMapConsolidate = heatMapFilterState.consolidateCorridors,
            heatMapMinVisits = heatMapFilterState.minVisits,
            fitTrackTrigger = fitTrackTrigger,
            fitHeatMapTrigger = fitHeatMapTrigger,
            fitPlacesTrigger = fitPlacesTrigger,
            destinationPoint = destinationPoint,
            selectedSavedPlace = selectedSavedPlace,
            onDestinationMarkerClick = { showDestinationDetailsCard = true },
            onSavedPlaceClick = { sp ->
                selectedSavedPlace = sp
                viewModel.setDestination(null)
            },
            onClearDestination = {
                selectedSavedPlace = null
                viewModel.setDestination(null)
                viewModel.clearPinnedBorders()
            },
            onMapClick = { gp ->
                selectedSavedPlace = null
                viewModel.selectMapPoint(gp)
            },
            activityProfile = activityProfile,
            isCompact = localityCardStyle == LocalityCardStyle.COMPACT,
            opticalOffsetX = measuredOpticalOffsetX,
            opticalOffsetY = measuredOpticalOffsetY,
            measuredTopInsetPx = topCardBottomPx,
            measuredBottomInsetPx = bottomControlsTopPx,
            isRecording = activeTrip != null,
            pauses = allDisplayPauses,
            orientationMode = orientationMode,
            onOrientationModeChange = { viewModel.setOrientationMode(it) },
            onInstantShare = shareCurrentLocation,
            onClearSelectedTrips = { viewModel.clearTripSelection() },
            onOpenCacheManager = {
                dismissAllDialogs()
                viewModel.openCacheManager()
            },
            showMapSettingsExternal = showMapSettingsDialog,
            onDismissMapSettings = { showMapSettingsDialog = false },
            modifier = Modifier.fillMaxSize()
        )

        // ── Floating Header Panels (Side-by-Side in Landscape, Top/Bottom in Portrait) ──
        if (isLandscape) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    if (!showTripsSheet) {
                        LocalityCard(
                            locationData = locationData,
                            activeTrip = activeTrip,
                            liveSession = liveSession,
                            savedPlaces = savedPlaces,
                            localityCardStyle = localityCardStyle,
                            compactStreetNames = compactStreetNames,
                            activityProfile = activityProfile,
                            primaryPlace = primaryPlace,
                            secondaryPlace = secondaryPlace,
                            hierarchySubtitle = hierarchySubtitle,
                            currentLatLng = currentLatLng,
                            onShowActiveTripRoute = {
                                dismissAllDialogs()
                                showActiveTripRouteDialog = true
                            },
                            onShowLiveShare = {
                                dismissAllDialogs()
                                if (liveSession?.isActive == true) {
                                    showLiveQuickShareModal = true
                                } else {
                                    showLiveShareDialog = true
                                }
                            },
                            onOpenSavedPlaces = {
                                dismissAllDialogs()
                                sheetTab = 1
                                showTripsSheet = true
                            },
                            onSetLocalityCardStyle = { viewModel.setLocalityCardStyle(it) },
                            onSetActivityProfile = { viewModel.setActivityProfile(it) },
                            modifier = Modifier.widthIn(max = 360.dp)
                        )
                    }

                    if (showHeatMap) {
                        HeatMapLegend(
                            onOpenHeatMapSettings = { showHeatMapSettingsDialog = true }
                        )
                    }
                }

                val activeTargetPoint = selectedSavedPlace?.geoPoint ?: destinationPoint
                if (!showTripsSheet && activeTargetPoint != null) {
                    DestinationPlaceCard(
                        destinationPoint = activeTargetPoint,
                        destinationItem = destinationItem,
                        savedPlace = selectedSavedPlace,
                        onClearDestination = {
                            selectedSavedPlace = null
                            viewModel.setDestination(null)
                            viewModel.clearPinnedBorders()
                        },
                        onNavigate = { lat, lng -> launchNavigation(context, lat, lng) },
                        onGoogleMaps = { lat, lng, title -> openInGoogleMaps(context, lat, lng, title) },
                        onSavePlace = { lat, lng, item ->
                            placeToSaveCoords = lat to lng
                            placeToSaveLocality = item?.localityName ?: item?.subtitle?.split(",")?.firstOrNull()?.trim() ?: ""
                            placeToSaveStreet = item?.title ?: ""
                            placeToSaveName = item?.title ?: "My Place"
                            placeToSaveCategory = PlaceCategory.FAVORITE
                            showSavePlaceDialog = true
                        },
                        onEditSavedPlace = { sp ->
                            editingSavedPlace = sp
                        },
                        onDeleteSavedPlace = { sp ->
                            placeToDelete = sp
                        },
                        onTogglePinBorders = {
                            if (selectedSavedPlace != null) {
                                val loc = selectedSavedPlace!!.locality.ifBlank { selectedSavedPlace!!.name }
                                viewModel.togglePinnedBorders(loc, "pl", null, selectedSavedPlace!!.geoPoint)
                            } else {
                                val loc = destinationItem?.localityName ?: destinationItem?.subtitle?.split(",")?.firstOrNull()?.trim()
                                viewModel.togglePinnedBorders(loc, destinationItem?.countryCode ?: "pl", destinationItem?.municipalityName, destinationPoint)
                            }
                        },
                        isPinBorderVisible = pinnedBoundaryPoints != null,
                        onShare = {
                            if (selectedSavedPlace != null) {
                                val sp = selectedSavedPlace!!
                                val addr = listOfNotNull(sp.street.takeIf { it.isNotBlank() }, sp.locality.takeIf { it.isNotBlank() }).joinToString(", ")
                                activeLocationShareTarget = LocationShareTarget(
                                    title = "Share Saved Place",
                                    placeName = sp.name,
                                    addressOrCoords = addr.ifBlank { String.format(Locale.US, "%.5f, %.5f", sp.latitude, sp.longitude) },
                                    latitude = sp.latitude,
                                    longitude = sp.longitude
                                )
                            } else {
                                val destPt = destinationPoint!!
                                val title = destinationItem?.title ?: "Pinned Location"
                                val subtitle = destinationItem?.subtitle ?: ""
                                val addressLine = if (subtitle.isNotBlank()) "$title, $subtitle" else title
                                activeLocationShareTarget = LocationShareTarget(
                                    title = "Share Pinned Location",
                                    placeName = title,
                                    addressOrCoords = addressLine,
                                    latitude = destPt.latitude,
                                    longitude = destPt.longitude
                                )
                            }
                        },
                        modifier = Modifier.widthIn(max = 380.dp)
                    )
                }
            }
        } else {
            // Portrait: LocalityCard at TopCenter, DestinationPlaceCard at BottomCenter
            if (!showTripsSheet) {
                LocalityCard(
                    locationData = locationData,
                    activeTrip = activeTrip,
                    liveSession = liveSession,
                    savedPlaces = savedPlaces,
                    localityCardStyle = localityCardStyle,
                    compactStreetNames = compactStreetNames,
                    activityProfile = activityProfile,
                    primaryPlace = primaryPlace,
                    secondaryPlace = secondaryPlace,
                    hierarchySubtitle = hierarchySubtitle,
                    currentLatLng = currentLatLng,
                    onShowActiveTripRoute = {
                        dismissAllDialogs()
                        showActiveTripRouteDialog = true
                    },
                    onShowLiveShare = {
                        dismissAllDialogs()
                        if (liveSession?.isActive == true) {
                            showLiveQuickShareModal = true
                        } else {
                            showLiveShareDialog = true
                        }
                    },
                    onOpenSavedPlaces = {
                        dismissAllDialogs()
                        sheetTab = 1
                        showTripsSheet = true
                    },
                    onSetLocalityCardStyle = { viewModel.setLocalityCardStyle(it) },
                    onSetActivityProfile = { viewModel.setActivityProfile(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = if (isCompact) 12.dp else 16.dp,
                            end = if (isCompact) 12.dp else 16.dp,
                            top = if (isCompact) 52.dp else 56.dp,
                            bottom = 8.dp
                        )
                        .onGloballyPositioned {
                            topCardBottomPx = (it.positionInRoot().y + it.size.height).toInt()
                        }
                        .align(Alignment.TopCenter)
                )
            }

            val activeTargetPoint = selectedSavedPlace?.geoPoint ?: destinationPoint
            if (!showTripsSheet && activeTargetPoint != null) {
                DestinationPlaceCard(
                    destinationPoint = activeTargetPoint,
                    destinationItem = destinationItem,
                    savedPlace = selectedSavedPlace,
                    onClearDestination = {
                        selectedSavedPlace = null
                        viewModel.setDestination(null)
                        viewModel.clearPinnedBorders()
                    },
                    onNavigate = { lat, lng -> launchNavigation(context, lat, lng) },
                    onGoogleMaps = { lat, lng, title -> openInGoogleMaps(context, lat, lng, title) },
                    onSavePlace = { lat, lng, item ->
                        placeToSaveCoords = lat to lng
                        placeToSaveLocality = item?.localityName ?: item?.subtitle?.split(",")?.firstOrNull()?.trim() ?: ""
                        placeToSaveStreet = item?.title ?: ""
                        placeToSaveName = item?.title ?: "My Place"
                        placeToSaveCategory = PlaceCategory.FAVORITE
                        showSavePlaceDialog = true
                    },
                    onEditSavedPlace = { sp ->
                        editingSavedPlace = sp
                    },
                    onDeleteSavedPlace = { sp ->
                        placeToDelete = sp
                    },
                    onTogglePinBorders = {
                        if (selectedSavedPlace != null) {
                            val loc = selectedSavedPlace!!.locality.ifBlank { selectedSavedPlace!!.name }
                            viewModel.togglePinnedBorders(loc, "pl", null, selectedSavedPlace!!.geoPoint)
                        } else {
                            val loc = destinationItem?.localityName ?: destinationItem?.subtitle?.split(",")?.firstOrNull()?.trim()
                            viewModel.togglePinnedBorders(loc, destinationItem?.countryCode ?: "pl", destinationItem?.municipalityName, destinationPoint)
                        }
                    },
                    isPinBorderVisible = pinnedBoundaryPoints != null,
                    onShare = {
                        if (selectedSavedPlace != null) {
                            val sp = selectedSavedPlace!!
                            val addr = listOfNotNull(sp.street.takeIf { it.isNotBlank() }, sp.locality.takeIf { it.isNotBlank() }).joinToString(", ")
                            activeLocationShareTarget = LocationShareTarget(
                                title = "Share Saved Place",
                                placeName = sp.name,
                                addressOrCoords = addr.ifBlank { String.format(Locale.US, "%.5f, %.5f", sp.latitude, sp.longitude) },
                                latitude = sp.latitude,
                                longitude = sp.longitude
                            )
                        } else {
                            val destPt = destinationPoint!!
                            val title = destinationItem?.title ?: "Pinned Location"
                            val subtitle = destinationItem?.subtitle ?: ""
                            val addressLine = if (subtitle.isNotBlank()) "$title, $subtitle" else title
                            activeLocationShareTarget = LocationShareTarget(
                                title = "Share Pinned Location",
                                placeName = title,
                                addressOrCoords = addressLine,
                                latitude = destPt.latitude,
                                longitude = destPt.longitude
                            )
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 16.dp, end = 16.dp, bottom = 96.dp)
                        .fillMaxWidth()
                        .onGloballyPositioned {
                            bottomControlsTopPx = it.positionInRoot().y.toInt()
                        }
                )
            }
        }

        // ── Floating Compact Recording HUD Bar (Above Bottom Controls in Portrait only) ────
        if (!showTripsSheet && !isLandscape && activeTrip != null) {
            val recTrip = activeTrip!!
            var recTickerNow by remember { mutableStateOf(System.currentTimeMillis()) }
            LaunchedEffect(recTrip.startTime) {
                while (true) {
                    recTickerNow = System.currentTimeMillis()
                    kotlinx.coroutines.delay(1000L)
                }
            }
            val elapsedMs = maxOf(0L, recTickerNow - recTrip.startTime)
            val elapsedSec = elapsedMs / 1000L
            val recHrs = elapsedSec / 3600L
            val recMins = (elapsedSec % 3600L) / 60L
            val recSecs = elapsedSec % 60L
            val elapsedStr = if (recHrs > 0) {
                String.format(Locale.getDefault(), "%d:%02d:%02d", recHrs, recMins, recSecs)
            } else {
                String.format(Locale.getDefault(), "%02d:%02d", recMins, recSecs)
            }
            val recDistKm = recTrip.distanceMeters / 1000.0

            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(
                        start = 16.dp,
                        end = 16.dp,
                        bottom = if (isLandscape) 70.dp else 82.dp
                    )
                    .widthIn(max = if (isLandscape) 360.dp else 380.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = Color(0xF20F172A),
                border = BorderStroke(1.dp, if (isTripPaused) Color(0xFFF59E0B) else Color(0xFFEF4444)),
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Recording indicator dot + Elapsed Time + Distance
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(if (isTripPaused) Color(0xFFF59E0B) else Color(0xFFEF4444))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isTripPaused) "PAUSED" else elapsedStr,
                            color = if (isTripPaused) Color(0xFFFBBF24) else Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false,
                            style = TextStyle(
                                platformStyle = PlatformTextStyle(includeFontPadding = false),
                                lineHeightStyle = LineHeightStyle(
                                    alignment = LineHeightStyle.Alignment.Center,
                                    trim = LineHeightStyle.Trim.Both
                                ),
                                lineHeight = 13.sp
                            )
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = String.format(Locale.getDefault(), "• %.2f km", recDistKm),
                            color = Color(0xFF38BDF8),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                            style = TextStyle(
                                platformStyle = PlatformTextStyle(includeFontPadding = false),
                                lineHeightStyle = LineHeightStyle(
                                    alignment = LineHeightStyle.Alignment.Center,
                                    trim = LineHeightStyle.Trim.Both
                                ),
                                lineHeight = 12.sp
                            )
                        )
                    }

                    // Direct Pause / Resume Button (Replaced duplicate STOP button)
                    Button(
                        onClick = {
                            if (isTripPaused) viewModel.resumeTrip() else viewModel.pauseTrip()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isTripPaused) Color(0xFFD97706) else Color(0xFF334155),
                            contentColor = Color.White
                        ),
                        border = if (isTripPaused) null else BorderStroke(1.dp, Color(0xFFF59E0B)),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Icon(
                            imageVector = if (isTripPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                            contentDescription = if (isTripPaused) "Resume" else "Pause",
                            modifier = Modifier.size(13.dp),
                            tint = Color.White
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isTripPaused) "RESUME" else "PAUSE",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }

        // ── Floating Bottom Toolbar (Bottom Center) ──────────────────────────
        if (!showTripsSheet) {
            val recTrip = activeTrip
            var toolbarTickerNow by remember { mutableStateOf(System.currentTimeMillis()) }
            LaunchedEffect(recTrip?.startTime) {
                if (recTrip != null) {
                    while (true) {
                        toolbarTickerNow = System.currentTimeMillis()
                        kotlinx.coroutines.delay(1000L)
                    }
                }
            }
            val elapsedMs = if (recTrip != null) maxOf(0L, toolbarTickerNow - recTrip.startTime) else 0L
            val elapsedSec = elapsedMs / 1000L
            val recHrs = elapsedSec / 3600L
            val recMins = (elapsedSec % 3600L) / 60L
            val recSecs = elapsedSec % 60L
            val elapsedStr = if (recHrs > 0) {
                String.format(Locale.getDefault(), "%d:%02d:%02d", recHrs, recMins, recSecs)
            } else {
                String.format(Locale.getDefault(), "%02d:%02d", recMins, recSecs)
            }
            val recDistKm = (recTrip?.distanceMeters ?: 0.0) / 1000.0

            MainBottomControlsCard(
                keepScreenOn = keepScreenOn,
                isRecording = activeTrip != null,
                onToggleKeepScreenOn = { viewModel.toggleKeepScreenOn() },
                onToggleTripRecording = {
                    if (activeTrip != null) viewModel.stopManualTrip() else viewModel.startManualTrip()
                },
                onShowTripsSheet = {
                    dismissAllDialogs()
                    showTripsSheet = true
                },
                onShowSearch = {
                    dismissAllDialogs()
                    showSearchDialog = true
                },
                onSaveLocation = {
                    dismissAllDialogs()
                    val lat = currentLatLng?.first
                    val lng = currentLatLng?.second
                    if (lat != null && lng != null) {
                        val nearbySaved = savedPlaces
                            .map { sp ->
                                val results = FloatArray(1)
                                android.location.Location.distanceBetween(lat, lng, sp.latitude, sp.longitude, results)
                                sp to results[0]
                            }
                            .filter { (sp, dist) -> dist <= maxOf(sp.radiusMeters, 50f) }
                            .minByOrNull { it.second }
                            ?.first

                        if (nearbySaved != null) {
                            android.widget.Toast.makeText(
                                context,
                                "Location already saved as \"${nearbySaved.name}\" (${nearbySaved.category.displayName})",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                            editingSavedPlace = nearbySaved
                        } else {
                            val currentPlace = locationData.primaryPlace
                            placeToSaveCoords = lat to lng
                            placeToSaveLocality = currentPlace?.city ?: ""
                            placeToSaveStreet = currentPlace?.street ?: ""
                            placeToSaveName = currentPlace?.let { if (!it.street.isNullOrBlank()) "${it.city}, ${it.street}" else it.city } ?: "My Location"
                            placeToSaveCategory = PlaceCategory.FAVORITE
                            placeToSaveColor = ""
                            showSavePlaceDialog = true
                        }
                    } else {
                        android.widget.Toast.makeText(
                            context,
                            "Waiting for current location fix...",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                onShowSettings = {
                    dismissAllDialogs()
                    showSettingsSheet = true
                },
                onShowMapSettings = {
                    dismissAllDialogs()
                    showMapSettingsDialog = true
                },
                landscapeHudElapsed = if (isLandscape && activeTrip != null) elapsedStr else null,
                landscapeHudDistKm = if (isLandscape && activeTrip != null) recDistKm else null,
                landscapeIsPaused = isTripPaused,
                onLandscapePauseResume = {
                    if (isTripPaused) viewModel.resumeTrip() else viewModel.pauseTrip()
                },
                landscapeShowHeatMap = isLandscape && showHeatMap,
                heatMapFilterActive = heatMapFilterState.hasActiveFilter,
                onToggleHeatMap = { viewModel.toggleShowHeatMap() },
                onOpenHeatMapSettings = { showHeatMapSettingsDialog = true },
                onFitHeatMap = { fitHeatMapTrigger = System.currentTimeMillis() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(
                        start = 12.dp,
                        end = 12.dp,
                        bottom = if (isLandscape) 12.dp else 20.dp
                    )
                    .widthIn(max = if (isLandscape) {
                        when {
                            showHeatMap && activeTrip != null -> 700.dp
                            showHeatMap -> 520.dp
                            activeTrip != null -> 580.dp
                            else -> 420.dp
                        }
                    } else 440.dp)
                    .fillMaxWidth()
                    .onGloballyPositioned {
                        bottomControlsTopPx = it.positionInRoot().y.toInt()
                    }
            )
        }
    }

        // ── 4. Places Passed & Trips History Full-Screen Screen Overlay ────────
        if (showTripsSheet) {
            LaunchedEffect(Unit) {
                viewModel.loadSavedTrips()
            }
            BackHandler {
                showTripsSheet = false
            }

            var tripSearchQuery by remember { mutableStateOf("") }
            var selectedActivityFilter by remember { mutableStateOf<ActivityProfile?>(null) }
            var selectedDateFilter by remember { mutableStateOf("ALL") } // ALL, TODAY, WEEK, MONTH
            var tripGroupBy by remember { mutableStateOf(TripGroupBy.DATE) }
            var collapsedGroupIds by remember { mutableStateOf<Set<String>>(emptySet()) }
            var expandedPauseTripIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
            var expandedRouteTripIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
            var selectedTripForDetail by remember { mutableStateOf<TripRecord?>(null) }
            // Dropdown expanded states for compact filter row
            var dateFilterExpanded by remember { mutableStateOf(false) }
            var activityFilterExpanded by remember { mutableStateOf(false) }
            var groupByExpanded by remember { mutableStateOf(false) }

            // Calendar-accurate timestamp boundaries for local time filters
            val (startOfTodayMs, startOfWeekMs, startOfMonthMs) = remember {
                val cal = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val todayMs = cal.timeInMillis

                cal.set(Calendar.DAY_OF_WEEK, cal.firstDayOfWeek)
                val weekMs = cal.timeInMillis

                cal.set(Calendar.DAY_OF_MONTH, 1)
                val monthMs = cal.timeInMillis

                Triple(todayMs, weekMs, monthMs)
            }

            // Filter trips based on search text, activity mode, and date range
            val filteredTrips = remember(savedTrips, tripSearchQuery, selectedActivityFilter, selectedDateFilter, startOfTodayMs, startOfWeekMs, startOfMonthMs) {
                savedTrips.filter { trip ->
                    // 1. Search Query (matches title or visited localities)
                    val matchesQuery = tripSearchQuery.isBlank() ||
                            trip.title.contains(tripSearchQuery, ignoreCase = true) ||
                            trip.placesVisited.any { it.placeName.contains(tripSearchQuery, ignoreCase = true) }

                    // 2. Exact Calendar Date Range
                    val matchesDate = when (selectedDateFilter) {
                        "TODAY" -> trip.startTime >= startOfTodayMs
                        "WEEK" -> trip.startTime >= startOfWeekMs
                        "MONTH" -> trip.startTime >= startOfMonthMs
                        else -> true
                    }

                    // 3. Activity Profile (approximate based on max speed)
                    val matchesProfile = when (selectedActivityFilter) {
                        ActivityProfile.CAR -> trip.maxSpeedKmh > 35f
                        ActivityProfile.CYCLING -> trip.maxSpeedKmh in 12f..35f
                        ActivityProfile.MTB -> trip.maxSpeedKmh in 8f..40f
                        ActivityProfile.RUNNING -> trip.maxSpeedKmh in 6f..16f
                        ActivityProfile.HIKING -> trip.maxSpeedKmh <= 9f
                        ActivityProfile.WALKING -> trip.maxSpeedKmh <= 7f
                        null -> true
                    }

                    matchesQuery && matchesDate && matchesProfile
                }
            }

            val tripGroups = remember(filteredTrips, tripGroupBy) {
                TripGroupingHelper.groupTrips(filteredTrips, tripGroupBy)
            }

            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color(0xFF0F172A)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .systemBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    // Header: Consolidated single-row in Landscape, 2 rows in Portrait
                    if (isLandscape) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Trips & Places",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            TabRow(
                                selectedTabIndex = sheetTab,
                                containerColor = Color(0xFF1E293B),
                                contentColor = Color(0xFF38BDF8),
                                modifier = Modifier
                                    .width(360.dp)
                                    .clip(RoundedCornerShape(10.dp))
                            ) {
                                Tab(
                                    selected = sheetTab == 0,
                                    onClick = { sheetTab = 0 },
                                    text = {
                                        Text(
                                            text = "Trips (${savedTrips.size})",
                                            fontWeight = if (sheetTab == 0) FontWeight.Bold else FontWeight.Normal,
                                            color = if (sheetTab == 0) Color(0xFF38BDF8) else Color.LightGray,
                                            fontSize = 12.sp,
                                            maxLines = 1
                                        )
                                    }
                                )
                                Tab(
                                    selected = sheetTab == 1,
                                    onClick = { sheetTab = 1 },
                                    text = {
                                        Text(
                                            text = "Places (${savedPlaces.size})",
                                            fontWeight = if (sheetTab == 1) FontWeight.Bold else FontWeight.Normal,
                                            color = if (sheetTab == 1) Color(0xFF10B981) else Color.LightGray,
                                            fontSize = 12.sp,
                                            maxLines = 1
                                        )
                                    }
                                )
                                Tab(
                                    selected = sheetTab == 2,
                                    onClick = { sheetTab = 2 },
                                    text = {
                                        Text(
                                            text = "Stats",
                                            fontWeight = if (sheetTab == 2) FontWeight.Bold else FontWeight.Normal,
                                            color = if (sheetTab == 2) Color(0xFFFBBF24) else Color.LightGray,
                                            fontSize = 12.sp,
                                            maxLines = 1
                                        )
                                    }
                                )
                            }
                            IconButton(
                                onClick = { showTripsSheet = false },
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF1E293B))
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Close",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Trips & Places",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            IconButton(
                                onClick = { showTripsSheet = false },
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF1E293B))
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Close",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        // 3-Tab Header: Places, Trips, Stats (Single concise words, no emoji)
                        TabRow(
                            selectedTabIndex = sheetTab,
                            containerColor = Color(0xFF1E293B),
                            contentColor = Color(0xFF38BDF8),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                        ) {
                            Tab(
                                selected = sheetTab == 0,
                                onClick = { sheetTab = 0 },
                                text = {
                                    Text(
                                        text = "Trips (${savedTrips.size})",
                                        fontWeight = if (sheetTab == 0) FontWeight.Bold else FontWeight.Normal,
                                        color = if (sheetTab == 0) Color(0xFF38BDF8) else Color.LightGray,
                                        fontSize = 13.sp,
                                        maxLines = 1
                                    )
                                }
                            )
                            Tab(
                                selected = sheetTab == 1,
                                onClick = { sheetTab = 1 },
                                text = {
                                    Text(
                                        text = "Places (${savedPlaces.size})",
                                        fontWeight = if (sheetTab == 1) FontWeight.Bold else FontWeight.Normal,
                                        color = if (sheetTab == 1) Color(0xFF10B981) else Color.LightGray,
                                        fontSize = 13.sp,
                                        maxLines = 1
                                    )
                                }
                            )
                            Tab(
                                selected = sheetTab == 2,
                                onClick = { sheetTab = 2 },
                                text = {
                                    Text(
                                        text = "Stats",
                                        fontWeight = if (sheetTab == 2) FontWeight.Bold else FontWeight.Normal,
                                        color = if (sheetTab == 2) Color(0xFFFBBF24) else Color.LightGray,
                                        fontSize = 13.sp,
                                        maxLines = 1
                                    )
                                }
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    // ── Tab 1: Saved Places ─────────────────────────────────────
                    if (sheetTab == 1) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "My Locations",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (savedPlaces.isNotEmpty()) {
                                    Button(
                                        onClick = {
                                            showTripsSheet = false
                                            viewModel.triggerFitPlaces()
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Icon(Icons.Default.CropFree, contentDescription = "Fit Map", modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Fit Map", fontSize = 12.sp)
                                    }
                                }

                                Button(
                                    onClick = {
                                        val lat = currentLatLng?.first
                                        val lng = currentLatLng?.second
                                        val currentPlace = locationData.primaryPlace
                                        if (lat != null && lng != null) {
                                            placeToSaveCoords = lat to lng
                                            placeToSaveLocality = currentPlace?.city ?: ""
                                            placeToSaveStreet = currentPlace?.street ?: ""
                                            placeToSaveName = currentPlace?.city ?: "My Place"
                                            placeToSaveCategory = PlaceCategory.FAVORITE
                                            showSavePlaceDialog = true
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Add Here", fontSize = 12.sp)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        if (savedPlaces.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No saved places yet.\nSave your Home, Work, or favorite spots via the bookmark icon.",
                                    color = Color.LightGray,
                                    fontSize = 14.sp,
                                    textAlign = TextAlign.Center
                                )
                            }
                        } else {
                            val placeRows = remember(savedPlaces, isLandscape) {
                                if (isLandscape) savedPlaces.chunked(2) else savedPlaces.chunked(1)
                            }
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(placeRows) { rowPlaces ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        rowPlaces.forEach { place ->
                                            Box(modifier = Modifier.weight(1f)) {
                                                Card(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clickable {
                                                            showTripsSheet = false
                                                            selectedSavedPlace = place
                                                            viewModel.setDestination(null)
                                                        },
                                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                                                    shape = RoundedCornerShape(14.dp)
                                                ) {
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(12.dp),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        val pinColorHex = place.getEffectiveColorHex()
                                                        val pinComposeColor = try {
                                                            Color(android.graphics.Color.parseColor(pinColorHex))
                                                        } catch (_: Exception) {
                                                            Color(0xFF10B981)
                                                        }

                                                        Column(
                                                            modifier = Modifier.weight(1f)
                                                        ) {
                                                            Row(
                                                                verticalAlignment = Alignment.CenterVertically,
                                                                modifier = Modifier.fillMaxWidth()
                                                            ) {
                                                                Box(
                                                                    modifier = Modifier.size(22.dp),
                                                                    contentAlignment = Alignment.Center
                                                                ) {
                                                                    val emojiLift = when (place.category) {
                                                                        PlaceCategory.WORK, PlaceCategory.SCHOOL, PlaceCategory.HOME -> (-2.2).dp
                                                                        PlaceCategory.CUSTOM -> (-1.5).dp
                                                                        else -> (-2.0).dp
                                                                    }
                                                                    EmojiText(
                                                                        emoji = place.category.iconEmoji,
                                                                        fontSize = 18.sp,
                                                                        yOffsetDp = emojiLift
                                                                    )
                                                                }
                                                                Spacer(modifier = Modifier.width(8.dp))
                                                                Text(
                                                                    text = place.name,
                                                                    color = Color.White,
                                                                    fontWeight = FontWeight.Bold,
                                                                    fontSize = 14.sp,
                                                                    maxLines = 1,
                                                                    overflow = TextOverflow.Ellipsis,
                                                                    modifier = Modifier.weight(1f, fill = false)
                                                                )
                                                                Spacer(modifier = Modifier.width(6.dp))
                                                                Surface(
                                                                    color = Color(0xFF0F172A),
                                                                    shape = RoundedCornerShape(4.dp)
                                                                ) {
                                                                    Row(
                                                                        verticalAlignment = Alignment.CenterVertically,
                                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                                    ) {
                                                                        Box(
                                                                            modifier = Modifier
                                                                                .size(6.dp)
                                                                                .clip(CircleShape)
                                                                                .background(pinComposeColor)
                                                                        )
                                                                        Spacer(modifier = Modifier.width(4.dp))
                                                                        Text(
                                                                            text = place.category.displayName,
                                                                            color = pinComposeColor,
                                                                            fontSize = 10.sp,
                                                                            fontWeight = FontWeight.SemiBold,
                                                                            maxLines = 1,
                                                                            softWrap = false
                                                                        )
                                                                    }
                                                                }
                                                            }
                                                            val sub = listOfNotNull(
                                                                place.street.takeIf { it.isNotBlank() },
                                                                place.locality.takeIf { it.isNotBlank() }
                                                            ).joinToString(", ")
                                                            if (sub.isNotBlank()) {
                                                                Text(
                                                                    sub,
                                                                    color = Color(0xFF94A3B8),
                                                                    fontSize = 11.sp,
                                                                    maxLines = 1,
                                                                    overflow = TextOverflow.Ellipsis,
                                                                    modifier = Modifier.padding(start = 30.dp, top = 2.dp)
                                                                )
                                                            }
                                                        }

                                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                                            // Navigate To
                                                            IconButton(
                                                                onClick = {
                                                                    launchNavigation(context, place.latitude, place.longitude)
                                                                },
                                                                modifier = Modifier.size(30.dp)
                                                            ) {
                                                                Icon(
                                                                    imageVector = Icons.Default.Navigation,
                                                                    contentDescription = "Navigate To",
                                                                    tint = Color(0xFF38BDF8),
                                                                    modifier = Modifier.size(18.dp)
                                                                )
                                                            }
                                                            Spacer(modifier = Modifier.width(2.dp))

                                                            // Open in Google Maps
                                                            IconButton(
                                                                onClick = {
                                                                    openInGoogleMaps(context, place.latitude, place.longitude, place.name)
                                                                },
                                                                modifier = Modifier.size(30.dp)
                                                            ) {
                                                                Icon(
                                                                    imageVector = Icons.Default.Map,
                                                                    contentDescription = "Open in Google Maps",
                                                                    tint = Color(0xFF10B981),
                                                                    modifier = Modifier.size(18.dp)
                                                                )
                                                            }
                                                            Spacer(modifier = Modifier.width(2.dp))

                                                            // Edit
                                                            IconButton(
                                                                onClick = { editingSavedPlace = place },
                                                                modifier = Modifier.size(30.dp)
                                                            ) {
                                                                Icon(
                                                                    imageVector = Icons.Default.Edit,
                                                                    contentDescription = "Edit Saved Place",
                                                                    tint = Color(0xFFFBBF24),
                                                                    modifier = Modifier.size(18.dp)
                                                                )
                                                            }
                                                            Spacer(modifier = Modifier.width(2.dp))

                                                            // Delete (with confirmation dialog)
                                                            IconButton(
                                                                onClick = { placeToDelete = place },
                                                                modifier = Modifier.size(30.dp)
                                                            ) {
                                                                Icon(
                                                                    imageVector = Icons.Default.Delete,
                                                                    contentDescription = "Delete Saved Place",
                                                                    tint = Color(0xFFF87171),
                                                                    modifier = Modifier.size(18.dp)
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        if (isLandscape && rowPlaces.size == 1) {
                                            Spacer(modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // ── Tab 0: Trip History ─────────────────────────────────────
                    if (sheetTab == 0) {
                        // Active Trip Recording Banner
                        if (activeTrip != null) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            modifier = Modifier.weight(1f, fill = false),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("●", color = Color(0xFFEF4444), fontSize = 14.sp)
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "Active Trip",
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF38BDF8),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            var showActiveProfileMenu by remember { mutableStateOf(false) }
                                            Box {
                                                Surface(
                                                    shape = RoundedCornerShape(8.dp),
                                                    color = Color(0xFF0F172A),
                                                    modifier = Modifier.clickable { showActiveProfileMenu = true }
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        EmojiText(
                                                            emoji = activityProfile.iconEmoji,
                                                            fontSize = 12.sp
                                                        )
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                        Text(
                                                            text = activityProfile.displayName,
                                                            fontSize = 11.sp,
                                                            fontWeight = FontWeight.SemiBold,
                                                            color = Color(0xFF38BDF8),
                                                            style = TextStyle(
                                                                platformStyle = PlatformTextStyle(includeFontPadding = false),
                                                                lineHeightStyle = LineHeightStyle(
                                                                    alignment = LineHeightStyle.Alignment.Center,
                                                                    trim = LineHeightStyle.Trim.Both
                                                                ),
                                                                lineHeight = 11.sp
                                                            )
                                                        )
                                                        Spacer(modifier = Modifier.width(2.dp))
                                                        Icon(
                                                            Icons.Default.ArrowDropDown,
                                                            contentDescription = "Switch profile",
                                                            tint = Color(0xFF94A3B8),
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                    }
                                                }

                                                DropdownMenu(
                                                    expanded = showActiveProfileMenu,
                                                    onDismissRequest = { showActiveProfileMenu = false },
                                                    modifier = Modifier.background(Color(0xFF1E293B))
                                                ) {
                                                    ActivityProfile.values().forEach { profile ->
                                                        DropdownMenuItem(
                                                            text = {
                                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                                    EmojiText(emoji = profile.iconEmoji, fontSize = 14.sp)
                                                                    Spacer(modifier = Modifier.width(8.dp))
                                                                    Text(
                                                                        text = profile.displayName,
                                                                        color = Color.White,
                                                                        fontSize = 13.sp,
                                                                        style = TextStyle(
                                                                            platformStyle = PlatformTextStyle(includeFontPadding = false),
                                                                            lineHeightStyle = LineHeightStyle(
                                                                                alignment = LineHeightStyle.Alignment.Center,
                                                                                trim = LineHeightStyle.Trim.Both
                                                                            ),
                                                                            lineHeight = 13.sp
                                                                        )
                                                                    )
                                                                }
                                                            },
                                                            onClick = {
                                                                showActiveProfileMenu = false
                                                                viewModel.setActivityProfile(profile)
                                                            }
                                                        )
                                                    }
                                                }
                                            }
                                            Button(
                                                onClick = {
                                                    showTripsSheet = false
                                                    viewModel.triggerFitTrack()
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier.defaultMinSize(minWidth = 65.dp)
                                            ) {
                                                Text("Fit Map", fontSize = 11.sp, maxLines = 1, softWrap = false)
                                            }
                                        }
                                    }

                                    val places = activeTrip!!.placesVisited
                                    if (places.isEmpty()) {
                                        Text(
                                            text = "Moving... localities will appear as you travel.",
                                            color = Color.LightGray,
                                            fontSize = 12.sp,
                                            modifier = Modifier.padding(top = 4.dp)
                                        )
                                    } else {
                                        val summary = places.takeLast(3).joinToString(" → ") { it.placeName }
                                        Text(
                                            text = "Recent: $summary",
                                            color = Color.White,
                                            fontSize = 12.sp,
                                            modifier = Modifier.padding(top = 4.dp)
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        // ── Compact Filter & Search Row (unified for portrait + landscape) ──
                        if (isLandscape) {
                            // Landscape: search + dropdowns in one row
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = tripSearchQuery,
                                    onValueChange = { tripSearchQuery = it },
                                    placeholder = { Text("Filter trips...", color = Color.Gray, fontSize = 12.sp) },
                                    singleLine = true,
                                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search", tint = Color.Gray, modifier = Modifier.size(16.dp)) },
                                    trailingIcon = {
                                        if (tripSearchQuery.isNotEmpty()) {
                                            IconButton(onClick = { tripSearchQuery = "" }, modifier = Modifier.size(20.dp)) {
                                                Icon(Icons.Default.Close, contentDescription = "Clear", tint = Color.Gray, modifier = Modifier.size(14.dp))
                                            }
                                        }
                                    },
                                    modifier = Modifier.width(190.dp).height(46.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White,
                                        focusedBorderColor = Color(0xFF38BDF8),
                                        unfocusedBorderColor = Color(0xFF334155),
                                        focusedContainerColor = Color(0xFF1E293B),
                                        unfocusedContainerColor = Color(0xFF1E293B)
                                    ),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                TripFilterDropdowns(
                                    selectedDateFilter = selectedDateFilter,
                                    onDateFilterChange = { selectedDateFilter = it },
                                    dateFilterExpanded = dateFilterExpanded,
                                    onDateExpandChange = { dateFilterExpanded = it },
                                    selectedActivityFilter = selectedActivityFilter,
                                    onActivityFilterChange = { selectedActivityFilter = it },
                                    activityFilterExpanded = activityFilterExpanded,
                                    onActivityExpandChange = { activityFilterExpanded = it },
                                    tripGroupBy = tripGroupBy,
                                    onGroupByChange = { tripGroupBy = it },
                                    groupByExpanded = groupByExpanded,
                                    onGroupByExpandChange = { groupByExpanded = it },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        } else {
                            // Portrait: search field, then compact dropdowns row below
                            OutlinedTextField(
                                value = tripSearchQuery,
                                onValueChange = { tripSearchQuery = it },
                                placeholder = { Text("Filter trips by name or locality...", color = Color.Gray, fontSize = 13.sp) },
                                singleLine = true,
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search", tint = Color.Gray, modifier = Modifier.size(18.dp)) },
                                trailingIcon = {
                                    if (tripSearchQuery.isNotEmpty()) {
                                        IconButton(onClick = { tripSearchQuery = "" }, modifier = Modifier.size(24.dp)) {
                                            Icon(Icons.Default.Close, contentDescription = "Clear", tint = Color.Gray, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = Color(0xFF38BDF8),
                                    unfocusedBorderColor = Color(0xFF334155),
                                    focusedContainerColor = Color(0xFF1E293B),
                                    unfocusedContainerColor = Color(0xFF1E293B)
                                ),
                                shape = RoundedCornerShape(10.dp)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            TripFilterDropdowns(
                                selectedDateFilter = selectedDateFilter,
                                onDateFilterChange = { selectedDateFilter = it },
                                dateFilterExpanded = dateFilterExpanded,
                                onDateExpandChange = { dateFilterExpanded = it },
                                selectedActivityFilter = selectedActivityFilter,
                                onActivityFilterChange = { selectedActivityFilter = it },
                                activityFilterExpanded = activityFilterExpanded,
                                onActivityExpandChange = { activityFilterExpanded = it },
                                tripGroupBy = tripGroupBy,
                                onGroupByChange = { tripGroupBy = it },
                                groupByExpanded = groupByExpanded,
                                onGroupByExpandChange = { groupByExpanded = it }
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                        }

                        // Multi-Select Action Bar (Fit Map, Merge, Select All / Deselect All)
                        val allFilteredSelected = filteredTrips.isNotEmpty() && filteredTrips.all { selectedTripIds.contains(it.id) }

                        // Action Bar: Selection Actions & Select All
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (selectedTripIds.isNotEmpty()) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        color = Color(0xFF1E293B),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(start = 8.dp, end = 4.dp, top = 2.dp, bottom = 2.dp)
                                        ) {
                                            Text(
                                                text = "${selectedTripIds.size} selected",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF38BDF8)
                                            )
                                            IconButton(
                                                onClick = { viewModel.clearTripSelection() },
                                                modifier = Modifier.size(20.dp)
                                            ) {
                                                Icon(Icons.Default.Close, contentDescription = "Clear", tint = Color(0xFF94A3B8), modifier = Modifier.size(12.dp))
                                            }
                                        }
                                    }

                                    if (selectedTripIds.size >= 2) {
                                        Button(
                                            onClick = { showMergeConfirmationDialog = true },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6)),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                            modifier = Modifier.height(28.dp),
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Icon(Icons.Default.CallMerge, contentDescription = "Merge", tint = Color.White, modifier = Modifier.size(13.dp))
                                            Spacer(modifier = Modifier.width(3.dp))
                                            Text("Merge", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                        }
                                    }

                                    Button(
                                        onClick = {
                                            showTripsSheet = false
                                            viewModel.triggerFitTrack()
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        modifier = Modifier.height(28.dp),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Icon(Icons.Default.CropFree, contentDescription = "Fit Map", tint = Color.White, modifier = Modifier.size(13.dp))
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Text("Fit Map", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            } else {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (filteredTrips.size != savedTrips.size) {
                                        Text(
                                            text = "Filtered: ${filteredTrips.size} of ${savedTrips.size}",
                                            fontSize = 11.sp,
                                            color = Color(0xFF94A3B8)
                                        )
                                    }
                                    val filteredTripsWithPauses = filteredTrips.filter { it.pauses.isNotEmpty() }
                                    if (filteredTripsWithPauses.isNotEmpty()) {
                                        val allPausesExpanded = filteredTripsWithPauses.all { expandedPauseTripIds.contains(it.id) }
                                        TextButton(
                                            onClick = {
                                                expandedPauseTripIds = if (allPausesExpanded) {
                                                    emptySet()
                                                } else {
                                                    filteredTripsWithPauses.map { it.id }.toSet()
                                                }
                                            },
                                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                                            modifier = Modifier.height(26.dp)
                                        ) {
                                            Text(
                                                text = if (allPausesExpanded) "Collapse Pauses" else "Expand Pauses",
                                                color = Color(0xFFF59E0B),
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                    }
                                }
                            }

                            TextButton(
                                onClick = {
                                    if (allFilteredSelected) {
                                        viewModel.clearTripSelection()
                                    } else {
                                        viewModel.setSelectedTripIds(filteredTrips.map { it.id }.toSet())
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = if (allFilteredSelected) "Deselect All" else "Select All (${filteredTrips.size})",
                                    color = Color(0xFF38BDF8),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        // Filtered Past Trips List (Full Screen Scrollable Area)
                        if (filteredTrips.isEmpty() && activeTrip == null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (savedTrips.isEmpty()) "No recorded trips yet." else "No trips match the active filter.",
                                    color = Color.LightGray,
                                    fontSize = 14.sp
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                tripGroups.forEach { group ->
                                    if (tripGroupBy != TripGroupBy.NONE) {
                                        item(key = "header_${group.id}") {
                                            val isCollapsed = collapsedGroupIds.contains(group.id)
                                            Surface(
                                                onClick = {
                                                    collapsedGroupIds = if (isCollapsed) {
                                                        collapsedGroupIds - group.id
                                                    } else {
                                                        collapsedGroupIds + group.id
                                                    }
                                                },
                                                color = Color(0xFF0F172A),
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(top = 4.dp, bottom = 2.dp)
                                            ) {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = "${if (isCollapsed) "▶" else "▼"} ${group.title.uppercase(Locale.getDefault())} (${group.count})",
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color(0xFF38BDF8)
                                                    )
                                                    Text(
                                                        text = if (isCollapsed) "Tap to expand" else "Tap to collapse",
                                                        fontSize = 10.sp,
                                                        color = Color(0xFF64748B)
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    val isGroupCollapsed = tripGroupBy != TripGroupBy.NONE && collapsedGroupIds.contains(group.id)
                                    if (!isGroupCollapsed) {
                                        val tripRows = if (isLandscape) group.trips.chunked(2) else group.trips.chunked(1)
                                        items(tripRows, key = { row -> row.map { it.id }.joinToString("_") }) { rowTrips ->
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                rowTrips.forEach { trip ->
                                                    Box(modifier = Modifier.weight(1f)) {
                                                        val isSelected = selectedTripIds.contains(trip.id)
                                                        Card(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .clickable { viewModel.toggleTripSelection(trip.id) },
                                                            colors = CardDefaults.cardColors(
                                                                containerColor = if (isSelected) Color(0xFF1E3A5F) else Color(0xFF1E293B)
                                                            ),
                                                            shape = RoundedCornerShape(12.dp)
                                                        ) {
                                                            Column(modifier = Modifier.padding(12.dp)) {
                                                                Row(
                                                                    modifier = Modifier.fillMaxWidth(),
                                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                                    verticalAlignment = Alignment.CenterVertically
                                                                ) {
                                                                    val dateStr = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(trip.startTime))
                                                                    val timeStr = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(trip.startTime))
                                                                    val defaultTitle = "$dateStr, $timeStr"
                                                                    val displayTitle = if (trip.title.isNotBlank()) trip.title else defaultTitle

                                                                    Checkbox(
                                                                        checked = isSelected,
                                                                        onCheckedChange = { viewModel.toggleTripSelection(trip.id) },
                                                                        colors = CheckboxDefaults.colors(
                                                                            checkedColor = Color(0xFF38BDF8),
                                                                            uncheckedColor = Color.LightGray
                                                                        )
                                                                    )

                                                                     Column(modifier = Modifier.weight(1f).padding(horizontal = 6.dp)) {
                                                                        Text(
                                                                            text = displayTitle,
                                                                            color = Color.White,
                                                                            fontWeight = FontWeight.Bold,
                                                                            fontSize = 14.sp,
                                                                            maxLines = 2,
                                                                            overflow = TextOverflow.Ellipsis
                                                                        )
                                                                        if (trip.title.isNotBlank()) {
                                                                            Text(
                                                                                text = defaultTitle,
                                                                                color = Color(0xFF64748B),
                                                                                fontSize = 11.sp,
                                                                                maxLines = 1,
                                                                                overflow = TextOverflow.Ellipsis
                                                                            )
                                                                        }
                                                                    }

                                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                                        IconButton(
                                                                            onClick = {
                                                                                GpxExporter.shareGpx(context, trip)
                                                                            },
                                                                            modifier = Modifier.size(28.dp)
                                                                        ) {
                                                                            Icon(
                                                                                Icons.Default.Share,
                                                                                contentDescription = "Export GPX",
                                                                                tint = Color(0xFF10B981),
                                                                                modifier = Modifier.size(18.dp)
                                                                            )
                                                                        }
                                                                        Spacer(modifier = Modifier.width(4.dp))
                                                                        IconButton(
                                                                            onClick = {
                                                                                tripToRename = trip
                                                                                renameInputText = if (trip.title.isNotBlank()) trip.title else defaultTitle
                                                                            },
                                                                            modifier = Modifier.size(28.dp)
                                                                        ) {
                                                                            Icon(
                                                                                Icons.Default.Edit,
                                                                                contentDescription = "Rename",
                                                                                tint = Color(0xFF38BDF8),
                                                                                modifier = Modifier.size(18.dp)
                                                                            )
                                                                        }
                                                                        Spacer(modifier = Modifier.width(4.dp))
                                                                        IconButton(
                                                                            onClick = { tripToDelete = trip },
                                                                            modifier = Modifier.size(28.dp)
                                                                        ) {
                                                                            Icon(
                                                                                Icons.Default.Delete,
                                                                                contentDescription = "Delete",
                                                                                tint = Color(0xFFF87171),
                                                                                modifier = Modifier.size(18.dp)
                                                                            )
                                                                        }
                                                                    }
                                                                }

                                                                Row(
                                                                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                                    verticalAlignment = Alignment.CenterVertically
                                                                ) {
                                                                    Row(
                                                                        verticalAlignment = Alignment.CenterVertically,
                                                                        modifier = Modifier.weight(1f).padding(end = 8.dp)
                                                                    ) {
                                                                        EmojiText(
                                                                            emoji = trip.activityProfile.iconEmoji,
                                                                            fontSize = 13.sp
                                                                        )
                                                                        Spacer(modifier = Modifier.width(6.dp))
                                                                        val distKm = trip.distanceMeters / 1000.0
                                                                        Text(
                                                                            text = String.format(Locale.getDefault(), "Distance: %.2f km • Max: %.1f km/h", distKm, trip.maxSpeedKmh),
                                                                            color = Color(0xFF38BDF8),
                                                                            fontSize = 12.sp,
                                                                            maxLines = 1,
                                                                            softWrap = false,
                                                                            overflow = TextOverflow.Ellipsis,
                                                                            style = TextStyle(
                                                                                platformStyle = PlatformTextStyle(includeFontPadding = false),
                                                                                lineHeightStyle = LineHeightStyle(
                                                                                    alignment = LineHeightStyle.Alignment.Center,
                                                                                    trim = LineHeightStyle.Trim.Both
                                                                                ),
                                                                                lineHeight = 12.sp
                                                                            )
                                                                        )
                                                                    }

                                                                    Button(
                                                                        onClick = { selectedTripForDetail = trip },
                                                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                                                                        shape = RoundedCornerShape(8.dp),
                                                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                                                                        modifier = Modifier.height(28.dp)
                                                                    ) {
                                                                        Text("Details", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                                                    }
                                                                }

                                                                if (trip.placesVisited.isNotEmpty()) {
                                                                    val routeItems = remember(trip.id, trip.placesVisited) {
                                                                        val items = mutableListOf<String>()
                                                                        trip.placesVisited.forEach { place ->
                                                                            val icon = if (place.placeKind != PlaceKind.LOCALITY) "${place.placeKind.iconEmoji} " else ""
                                                                            val itemStr = "$icon${place.placeName}"
                                                                            if (items.lastOrNull() != itemStr) {
                                                                                items.add(itemStr)
                                                                            }
                                                                        }
                                                                        items
                                                                    }

                                                                    if (routeItems.isNotEmpty()) {
                                                                        val isRouteExpanded = expandedRouteTripIds.contains(trip.id)
                                                                        val fullRouteText = "Route: " + routeItems.joinToString(" → ")
                                                                        var hasRouteOverflow by remember(trip.id, fullRouteText) { mutableStateOf(false) }

                                                                        Column(
                                                                            modifier = Modifier
                                                                                .fillMaxWidth()
                                                                                .padding(top = 4.dp)
                                                                                .clickable(enabled = hasRouteOverflow || isRouteExpanded) {
                                                                                    expandedRouteTripIds = if (isRouteExpanded) {
                                                                                        expandedRouteTripIds - trip.id
                                                                                    } else {
                                                                                        expandedRouteTripIds + trip.id
                                                                                    }
                                                                                }
                                                                        ) {
                                                                            Text(
                                                                                text = fullRouteText,
                                                                                color = Color(0xFFCBD5E1),
                                                                                fontSize = 11.sp,
                                                                                maxLines = if (isRouteExpanded) Int.MAX_VALUE else 3,
                                                                                overflow = TextOverflow.Ellipsis,
                                                                                lineHeight = 15.sp,
                                                                                onTextLayout = { textLayoutResult ->
                                                                                    if (!isRouteExpanded) {
                                                                                        if (textLayoutResult.hasVisualOverflow || textLayoutResult.lineCount > 3) {
                                                                                            hasRouteOverflow = true
                                                                                        }
                                                                                    }
                                                                                }
                                                                            )
                                                                            if (hasRouteOverflow || isRouteExpanded) {
                                                                                Text(
                                                                                    text = if (isRouteExpanded) "▲ Show less" else "▼ Show full route (${routeItems.size} places)",
                                                                                    color = Color(0xFF38BDF8),
                                                                                    fontSize = 10.sp,
                                                                                    fontWeight = FontWeight.SemiBold,
                                                                                    modifier = Modifier.padding(top = 2.dp)
                                                                                )
                                                                            }
                                                                        }
                                                                    }
                                                                }

                                                                // Pauses & Trip Splitting
                                                                if (trip.pauses.isNotEmpty()) {
                                                                    Spacer(modifier = Modifier.height(6.dp))
                                                                    val isPausesExpanded = expandedPauseTripIds.contains(trip.id)
                                                                    Surface(
                                                                        onClick = {
                                                                            expandedPauseTripIds = if (isPausesExpanded) {
                                                                                expandedPauseTripIds - trip.id
                                                                            } else {
                                                                                expandedPauseTripIds + trip.id
                                                                            }
                                                                        },
                                                                        color = Color.Transparent,
                                                                        shape = RoundedCornerShape(4.dp)
                                                                    ) {
                                                                        Row(
                                                                            verticalAlignment = Alignment.CenterVertically,
                                                                            modifier = Modifier.padding(vertical = 2.dp)
                                                                        ) {
                                                                            Icon(
                                                                                imageVector = if (isPausesExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                                                                                contentDescription = if (isPausesExpanded) "Collapse Pauses" else "Expand Pauses",
                                                                                tint = Color(0xFFF59E0B),
                                                                                modifier = Modifier.size(14.dp)
                                                                            )
                                                                            Spacer(modifier = Modifier.width(3.dp))
                                                                            Icon(
                                                                                imageVector = Icons.Default.PauseCircle,
                                                                                contentDescription = null,
                                                                                tint = Color(0xFFF59E0B),
                                                                                modifier = Modifier.size(13.dp)
                                                                            )
                                                                            Spacer(modifier = Modifier.width(4.dp))
                                                                            Text(
                                                                                text = "Rest Pauses (${trip.pauses.size})",
                                                                                fontSize = 11.sp,
                                                                                fontWeight = FontWeight.SemiBold,
                                                                                color = Color(0xFFF59E0B),
                                                                                style = TextStyle(
                                                                                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                                                                                    lineHeightStyle = LineHeightStyle(
                                                                                        alignment = LineHeightStyle.Alignment.Center,
                                                                                        trim = LineHeightStyle.Trim.Both
                                                                                    ),
                                                                                    lineHeight = 11.sp
                                                                                )
                                                                            )
                                                                        }
                                                                    }

                                                                    if (isPausesExpanded) {
                                                                        val tripCumulativeDistances = remember(trip.points) { calculateCumulativeDistances(trip.points) }
                                                                        trip.pauses.forEachIndexed { pauseIdx, pause ->
                                                                            val pauseTimeStr = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(pause.startTime))
                                                                            val durMin = (pause.durationMs / 60000L).coerceAtLeast(1)
                                                                            val pauseDistKm = calculatePauseDistanceMeters(trip, pause, tripCumulativeDistances) / 1000.0
                                                                            Row(
                                                                                modifier = Modifier
                                                                                    .fillMaxWidth()
                                                                                    .padding(vertical = 2.dp)
                                                                                    .background(Color(0xFF0F172A), RoundedCornerShape(6.dp))
                                                                                    .padding(horizontal = 8.dp, vertical = 5.dp),
                                                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                                                verticalAlignment = Alignment.CenterVertically
                                                                            ) {
                                                                                Column(
                                                                                    modifier = Modifier.weight(1f).padding(end = 6.dp)
                                                                                ) {
                                                                                    Row(
                                                                                        verticalAlignment = Alignment.CenterVertically,
                                                                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                                                    ) {
                                                                                        Text(
                                                                                            text = "#${pauseIdx + 1} at $pauseTimeStr",
                                                                                            fontSize = 11.sp,
                                                                                            fontWeight = FontWeight.SemiBold,
                                                                                            color = Color(0xFFE2E8F0)
                                                                                        )
                                                                                        Text(
                                                                                            text = "($durMin min rest)",
                                                                                            fontSize = 11.sp,
                                                                                            color = Color(0xFFFBBF24)
                                                                                        )
                                                                                    }
                                                                                    Text(
                                                                                        text = String.format(Locale.getDefault(), "at %.2f km", pauseDistKm),
                                                                                        fontSize = 10.sp,
                                                                                        color = Color(0xFF38BDF8)
                                                                                    )
                                                                                }
                                                                                TextButton(
                                                                                    onClick = {
                                                                                        viewModel.splitTripAtPause(trip.id, pauseIdx)
                                                                                        android.widget.Toast.makeText(context, "Split trip at pause #${pauseIdx + 1}", android.widget.Toast.LENGTH_SHORT).show()
                                                                                    },
                                                                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                                                                    modifier = Modifier.height(26.dp)
                                                                                ) {
                                                                                    Icon(
                                                                                        imageVector = Icons.Default.ContentCut,
                                                                                        contentDescription = "Split",
                                                                                        tint = Color(0xFF38BDF8),
                                                                                        modifier = Modifier.size(12.dp)
                                                                                    )
                                                                                    Spacer(modifier = Modifier.width(4.dp))
                                                                                    Text("Split Here", fontSize = 10.sp, color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold)
                                                                                }
                                                                            }
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                                if (isLandscape && rowTrips.size == 1) {
                                                    Spacer(modifier = Modifier.weight(1f))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // ── Tab 2: Lifetime Statistics & Visited Places Breakdown ───
                    if (sheetTab == 2) {
                        val totalDistanceKm = remember(savedTrips) { savedTrips.sumOf { it.distanceMeters } / 1000.0 }
                        val maxSpeedOverall = remember(savedTrips) { savedTrips.maxOfOrNull { it.maxSpeedKmh } ?: 0f }
                        val allVisitedPlaces = remember(savedTrips) {
                            computeRealisticVisitCounts(savedTrips)
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) {
                            // Lifetime Summary Cards Row
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(IntrinsicSize.Min),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Card(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight(),
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(12.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Text("Total Distance", fontSize = 11.sp, color = Color(0xFF94A3B8))
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = String.format(Locale.getDefault(), "%.1f km", totalDistanceKm),
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF38BDF8)
                                        )
                                    }
                                }

                                Card(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight(),
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(12.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Text("Total Trips", fontSize = 11.sp, color = Color(0xFF94A3B8))
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "${savedTrips.size}",
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF10B981)
                                        )
                                    }
                                }

                                Card(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight(),
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(12.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Text("Top Speed", fontSize = 11.sp, color = Color(0xFF94A3B8))
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = String.format(Locale.getDefault(), "%.0f km/h", maxSpeedOverall),
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFFFBBF24)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            Text(
                                text = "Frequent Localities (${allVisitedPlaces.size})",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "Localities recorded across all your completed trips",
                                fontSize = 12.sp,
                                color = Color(0xFF94A3B8)
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            if (allVisitedPlaces.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "No locality statistics yet.\nComplete trips to accumulate place analytics.",
                                        color = Color.LightGray,
                                        fontSize = 14.sp,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            } else {
                                val maxVisits = remember(allVisitedPlaces) { allVisitedPlaces.maxOfOrNull { it.second } ?: 1 }
                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    items(allVisitedPlaces) { (place, count) ->
                                        Card(
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Icon(
                                                            Icons.Default.Place,
                                                            contentDescription = null,
                                                            tint = Color(0xFF38BDF8),
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                        Spacer(modifier = Modifier.width(8.dp))
                                                        Text(
                                                            text = place,
                                                            fontSize = 14.sp,
                                                            fontWeight = FontWeight.Medium,
                                                            color = Color.White
                                                        )
                                                    }
                                                    Surface(
                                                        color = Color(0xFF0F172A),
                                                        shape = RoundedCornerShape(6.dp)
                                                    ) {
                                                        Text(
                                                            text = "$count ${if (count == 1) "visit" else "visits"}",
                                                            color = Color(0xFFFBBF24),
                                                            fontSize = 12.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                                        )
                                                    }
                                                }
                                                Spacer(modifier = Modifier.height(6.dp))
                                                LinearProgressIndicator(
                                                    progress = { count.toFloat() / maxVisits },
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(4.dp)
                                                        .clip(RoundedCornerShape(2.dp)),
                                                    color = Color(0xFF38BDF8),
                                                    trackColor = Color(0xFF0F172A)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            selectedTripForDetail?.let { currentDetailTrip ->
                // Find latest trip record from savedTrips if updated
                val liveTrip = savedTrips.find { it.id == currentDetailTrip.id } ?: currentDetailTrip
                TripDetailDialog(
                    trip = liveTrip,
                    onDismissRequest = { selectedTripForDetail = null },
                    onShowOnMap = { trip ->
                        viewModel.setSelectedTripIds(setOf(trip.id))
                        selectedTripForDetail = null
                        showTripsSheet = false
                        viewModel.triggerFitTrack()
                    },
                    onRename = { trip ->
                        tripToRename = trip
                        val dateStr = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(trip.startTime))
                        val timeStr = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(trip.startTime))
                        val defaultTitle = "$dateStr, $timeStr"
                        renameInputText = if (trip.title.isNotBlank()) trip.title else defaultTitle
                    },
                    onDelete = { trip ->
                        tripToDelete = trip
                        selectedTripForDetail = null
                    },
                    onShare = { trip ->
                        GpxExporter.shareGpx(context, trip)
                    },
                    onUpdateProfile = { tripId, profile ->
                        viewModel.updateTripActivityProfile(tripId, profile)
                    },
                    onSplitPause = { tripId, pauseIdx ->
                        viewModel.splitTripAtPause(tripId, pauseIdx)
                        selectedTripForDetail = null
                        android.widget.Toast.makeText(context, "Split trip at pause #${pauseIdx + 1}", android.widget.Toast.LENGTH_SHORT).show()
                    },
                    onDeletePause = { tripId, pauseIdx ->
                        viewModel.deleteTripPause(tripId, pauseIdx)
                        android.widget.Toast.makeText(context, "Removed pause #${pauseIdx + 1}", android.widget.Toast.LENGTH_SHORT).show()
                    },
                    onMergePause = { tripId, pauseIdx ->
                        viewModel.mergeTripPauses(tripId, pauseIdx)
                        android.widget.Toast.makeText(context, "Merged pause #${pauseIdx + 1} with next", android.widget.Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }

    // Rename Trip Dialog
    if (tripToRename != null) {
        val saveRenameAction = {
            tripToRename?.let { trip ->
                val cleaned = renameInputText.trim().replace(Regex("\\s+"), " ").take(MAX_TRIP_TITLE_LENGTH)
                viewModel.renameTrip(trip.id, cleaned)
            }
            tripToRename = null
        }

        AlertDialog(
            onDismissRequest = { tripToRename = null },
            title = { Text("Rename Trip", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = renameInputText,
                        onValueChange = { input ->
                            val sanitized = input.replace("\r", " ").replace("\n", " ")
                            if (sanitized.length <= MAX_TRIP_TITLE_LENGTH) {
                                renameInputText = sanitized
                            }
                        },
                        label = { Text("Trip Title") },
                        singleLine = false,
                        minLines = 2,
                        maxLines = 3,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                            capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences
                        ),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                            onDone = { saveRenameAction() }
                        ),
                        trailingIcon = {
                            if (renameInputText.isNotEmpty()) {
                                IconButton(
                                    onClick = { renameInputText = "" },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Clear",
                                        tint = Color.LightGray,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        },
                        supportingText = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                Text(
                                    text = "${renameInputText.length} / $MAX_TRIP_TITLE_LENGTH",
                                    fontSize = 11.sp,
                                    color = if (renameInputText.length >= MAX_TRIP_TITLE_LENGTH) Color(0xFFF59E0B) else Color(0xFF94A3B8)
                                )
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF38BDF8),
                            unfocusedBorderColor = Color(0xFF64748B)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = saveRenameAction,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { tripToRename = null }) {
                    Text("Cancel", color = Color.White)
                }
            },
            containerColor = Color(0xFF1E293B)
        )
    }

    // ── 5. Quick Settings & Options Dialog ─────────────────────────────────────
    if (showSettingsSheet) {
        val settingsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showSettingsSheet = false },
            sheetState = settingsSheetState,
            containerColor = Color(0xFF0F172A),
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .align(Alignment.CenterHorizontally)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .navigationBarsPadding()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "App Settings",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    IconButton(
                        onClick = { showSettingsSheet = false },
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1E293B))
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))

                // Activity Profiles Hub: Manage configurations independently of active profile - Promoted to Top
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Activity Profiles & Settings",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                        Text(
                            text = "Configure auto-start, timeouts, and recording modes for each activity independently",
                            fontSize = 12.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ActivityProfile.values().forEach { profile ->
                        val isCurrentActive = activityProfile == profile
                        val profileMode = viewModel.getTripModeForProfile(profile)
                        val profileSpeed = viewModel.getAutoStartSpeedForProfile(profile)
                        val profileTimeout = viewModel.getAutoStopMinutesForProfile(profile)

                        val speedFormatted = if (profileSpeed % 1f == 0f) "${profileSpeed.toInt()} km/h" else "%.1f km/h".format(profileSpeed)

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isCurrentActive) Color(0xFF1E293B) else Color(0xFF161F30),
                            border = BorderStroke(1.dp, if (isCurrentActive) Color(0xFF0284C7) else Color(0xFF334155)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { profileToConfigure = profile }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    EmojiText(emoji = profile.iconEmoji, fontSize = 20.sp)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = profile.displayName,
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White
                                            )
                                            if (isCurrentActive) {
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Surface(
                                                    shape = RoundedCornerShape(6.dp),
                                                    color = Color(0xFF0284C7)
                                                ) {
                                                    Text(
                                                        text = "ACTIVE",
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color.White,
                                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                                    )
                                                }
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = if (profileMode == TripMode.AUTO) {
                                                "Auto-start: >$speedFormatted • Stop: ${profileTimeout}m"
                                            } else {
                                                "Manual recording • Stop: ${profileTimeout}m"
                                            },
                                            fontSize = 12.sp,
                                            color = Color(0xFF94A3B8)
                                        )
                                    }
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Tune,
                                        contentDescription = "Configure Profile",
                                        tint = Color(0xFF38BDF8),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Locality Card Style (Normal vs Compact)
                Text(
                    text = "Locality Card Layout",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                Text(
                    text = "Normal provides maximum readability; Compact minimizes card height on map",
                    fontSize = 12.sp,
                    color = Color(0xFF94A3B8),
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    listOf(
                        LocalityCardStyle.NORMAL to "Normal",
                        LocalityCardStyle.COMPACT to "Compact"
                    ).forEach { (style, label) ->
                        val isSelected = localityCardStyle == style
                        Button(
                            onClick = { viewModel.setLocalityCardStyle(style) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isSelected) Color(0xFF0284C7) else Color(0xFF1E293B),
                                contentColor = Color.White
                            ),
                            border = if (isSelected) null else BorderStroke(1.dp, Color(0xFF475569)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(label, fontSize = 14.sp, color = Color.White)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Compact Street Names Toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF1E293B))
                        .clickable { viewModel.toggleCompactStreetNames() }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Compact Street Names",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                        Text(
                            text = "Abbreviate honorifics and first names (e.g. ul. J. Lompy). Always enabled in Android Auto.",
                            fontSize = 12.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = compactStreetNames,
                        onCheckedChange = { viewModel.toggleCompactStreetNames() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFF0284C7),
                            uncheckedThumbColor = Color(0xFF94A3B8),
                            uncheckedTrackColor = Color(0xFF334155)
                        )
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Map Layers: City/Town/Village Borders (Phase 2)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Show City/Town Borders",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                        Text(
                            text = "Highlights territorial boundaries of the current locality on the map",
                            fontSize = 12.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }
                    Switch(
                        checked = showBorders,
                        onCheckedChange = { viewModel.toggleShowBorders() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFF0284C7),
                            uncheckedThumbColor = Color.LightGray,
                            uncheckedTrackColor = Color(0xFF1E293B)
                        )
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Traversal Heat Map Layer Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Road & Path Heat Map",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                        Text(
                            text = "Visualizes all previously traveled routes as glowing heat corridors",
                            fontSize = 12.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }
                    Switch(
                        checked = showHeatMap,
                        onCheckedChange = { viewModel.toggleShowHeatMap() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFFF97316),
                            uncheckedThumbColor = Color.LightGray,
                            uncheckedTrackColor = Color(0xFF1E293B)
                        )
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Telemetry Logging & Diagnostics
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Diagnostic Telemetry Log",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                        Text(
                            text = "Export timestamped GPS events, hysteresis triggers, and auto-stop decisions",
                            fontSize = 12.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }
                    Button(
                        onClick = { TelemetryLogger.shareTelemetry(context) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "Export Telemetry", modifier = Modifier.size(16.dp), tint = Color(0xFF38BDF8))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Export", fontSize = 12.sp, color = Color(0xFF38BDF8))
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
                TextButton(
                    onClick = {
                        try {
                            val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:" + context.packageName)
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            android.widget.Toast.makeText(context, "Open battery settings manually for WhereAmI", android.widget.Toast.LENGTH_LONG).show()
                        }
                    },
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Text("⚡ Allow Unrestricted Background Battery (Prevents OS Killing)", fontSize = 12.sp, color = Color(0xFF38BDF8))
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Power & Battery Profile Section
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Power & Battery Profile",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                    if (isCharging) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF065F46)
                        ) {
                            Text(
                                text = "⚡ Charging",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF34D399),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
                Text(
                    text = "Current Mode: ${lifecycleMode.displayName} • ${powerPolicy.description}",
                    fontSize = 12.sp,
                    color = Color(0xFF94A3B8),
                    modifier = Modifier.padding(top = 2.dp, bottom = 6.dp)
                )
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    BatteryPowerPolicy.values().forEach { policy ->
                        val isSelected = powerPolicy == policy
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isSelected) Color(0xFF0284C7) else Color(0xFF1E293B),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.setPowerPolicy(policy) }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = policy.displayName,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White
                                    )
                                    Text(
                                        text = policy.description,
                                        fontSize = 11.sp,
                                        color = if (isSelected) Color(0xFFE0F2FE) else Color(0xFF94A3B8)
                                    )
                                }
                                if (isSelected) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = "Selected",
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }



                Spacer(modifier = Modifier.height(20.dp))

                // Language Selector
                Text(
                    text = "Display Language",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    listOf(
                        DisplayLanguage.EN to "EN",
                        DisplayLanguage.PL to "PL",
                        DisplayLanguage.NATIVE to "Native"
                    ).forEach { (lang, label) ->
                        val isSelected = displayLanguage == lang
                        Button(
                            onClick = { viewModel.setDisplayLanguage(lang) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isSelected) Color(0xFF0284C7) else Color(0xFF1E293B),
                                contentColor = Color.White
                            ),
                            border = if (isSelected) null else BorderStroke(1.dp, Color(0xFF475569)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(label, color = Color.White)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Storage & Offline Data Management
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            text = "Storage & Offline Caches",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                        Text(
                            text = "Manage map tiles, boundaries, spatial cache, and battery-safe pre-fetching",
                            fontSize = 12.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }
                    Button(
                        onClick = {
                            dismissAllDialogs()
                            viewModel.openCacheManager()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Storage, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Manage", fontSize = 12.sp, color = Color.White)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Profile Map Defaults Reset Section
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            viewModel.resetMapProfileDefaults(activityProfile)
                            android.widget.Toast.makeText(context, "Reset defaults for ${activityProfile.displayName}", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF38BDF8)),
                        border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.5f)),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Reset ${activityProfile.displayName} Map", fontSize = 11.sp, color = Color(0xFF38BDF8), maxLines = 1, softWrap = false)
                    }

                    OutlinedButton(
                        onClick = {
                            viewModel.resetAllMapProfileDefaults()
                            android.widget.Toast.makeText(context, "Reset all profile map defaults", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFF59E0B)),
                        border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.5f)),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Reset All Maps", fontSize = 11.sp, color = Color(0xFFF59E0B), maxLines = 1, softWrap = false)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    onClick = { showResetDefaultsConfirm = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444)),
                    border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFFEF4444))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Restore All Default Settings", fontSize = 13.sp, color = Color(0xFFEF4444), fontWeight = FontWeight.SemiBold)
                }

                Spacer(modifier = Modifier.height(20.dp))

                val pkgInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                Text(
                    text = "Where Am I v${pkgInfo.versionName} • Settings saved automatically",
                    color = Color(0xFF64748B),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    if (showCacheManagerDialog) {
        CacheManagerDialog(
            onDismissRequest = { viewModel.dismissCacheManager() }
        )
    }

    // ── 6. Search Dialog Overlay (City / Street / Place) ────────────────────────
    if (showSearchDialog) {
        val searchFocusRequester = remember { FocusRequester() }
        val keyboardController = LocalSoftwareKeyboardController.current

        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(200)
            searchFocusRequester.requestFocus()
            keyboardController?.show()
        }

        AlertDialog(
            onDismissRequest = {
                showSearchDialog = false
                viewModel.clearSearch()
            },
            title = {
                Text(
                    text = "Find Place or Street",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = {
                            searchQuery = it
                            viewModel.search(it)
                        },
                        label = { Text("Search city, street or address") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(searchFocusRequester),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF38BDF8),
                            unfocusedBorderColor = Color(0xFF64748B)
                        )
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    if (isSearching) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 12.dp)
                        ) {
                            CircularProgressIndicator(
                                color = Color(0xFF38BDF8),
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("Searching...", color = Color.LightGray, fontSize = 13.sp)
                        }
                    } else if (searchResults.isNotEmpty()) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 240.dp)
                        ) {
                            items(searchResults) { item ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            viewModel.setDestination(item)
                                            showSearchDialog = false
                                            viewModel.clearSearch()
                                        }
                                        .padding(vertical = 8.dp, horizontal = 4.dp)
                                ) {
                                    Text(
                                        text = item.title,
                                        color = Color(0xFF38BDF8),
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp
                                    )
                                    if (item.subtitle.isNotBlank()) {
                                        Text(
                                            text = item.subtitle,
                                            color = Color(0xFF94A3B8),
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                                HorizontalDivider(color = Color(0x22FFFFFF))
                            }
                        }
                    } else if (searchQuery.trim().length >= 2) {
                        Text(
                            text = "No places found. Try another search term.",
                            color = Color.LightGray,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }
            },
            confirmButton = {
                if (destinationPoint != null) {
                    TextButton(
                        onClick = {
                            viewModel.setDestinationPoint(null)
                            showSearchDialog = false
                        }
                    ) {
                        Text("Clear Pin", color = Color(0xFFF87171))
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showSearchDialog = false
                        viewModel.clearSearch()
                    }
                ) {
                    Text("Close", color = Color.White)
                }
            },
            containerColor = Color(0xFF1E293B)
        )
    }

    // ── 7. Save Place Dialog Modal ─────────────────────────────────────────────
    if (showSavePlaceDialog && placeToSaveCoords != null) {
        val coords = placeToSaveCoords!!

        // Auto reverse-geocode coords if street/locality is missing or placeholder
        LaunchedEffect(coords) {
            val resolved = SearchHelper.reverseGeocode(context, org.osmdroid.util.GeoPoint(coords.first, coords.second))
            if (placeToSaveStreet.isBlank()) {
                placeToSaveStreet = resolved.title
            }
            if (placeToSaveLocality.isBlank()) {
                val loc = resolved.subtitle.split(",").firstOrNull()?.trim() ?: ""
                placeToSaveLocality = loc
            }
        }

        AlertDialog(
            onDismissRequest = { showSavePlaceDialog = false },
            title = {
                Text(
                    text = "Save as My Place",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                ) {
                    Text(
                        text = "Category",
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )

                    // Category Selector Pills (Scrollable Row with icon and text)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        PlaceCategory.values().forEach { cat ->
                            val isSelected = placeToSaveCategory == cat
                            Surface(
                                onClick = {
                                    placeToSaveCategory = cat
                                    if (placeToSaveName.isBlank() || PlaceCategory.values().any { it.displayName == placeToSaveName }) {
                                        placeToSaveName = cat.displayName
                                    }
                                },
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) Color(0xFF10B981) else Color(0xFF1E293B),
                                border = if (isSelected) null else androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF475569)),
                                modifier = Modifier.height(36.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center,
                                    modifier = Modifier.fillMaxHeight().padding(horizontal = 10.dp)
                                ) {
                                    EmojiText(
                                        emoji = cat.iconEmoji,
                                        fontSize = 14.sp
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = cat.displayName,
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        style = TextStyle(
                                            platformStyle = PlatformTextStyle(includeFontPadding = false),
                                            lineHeightStyle = LineHeightStyle(
                                                alignment = LineHeightStyle.Alignment.Center,
                                                trim = LineHeightStyle.Trim.Both
                                            ),
                                            lineHeight = 12.sp
                                        )
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "Pin Color",
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PRESET_PIN_COLORS.forEach { opt ->
                            val isColorSelected = if (opt.hex.isEmpty()) placeToSaveColor.isEmpty() else placeToSaveColor.equals(opt.hex, ignoreCase = true)
                            val chipBg = if (opt.hex.isEmpty()) {
                                when (placeToSaveCategory) {
                                    PlaceCategory.FAVORITE -> Color(0xFF8B5CF6)
                                    PlaceCategory.HOME -> Color(0xFF10B981)
                                    PlaceCategory.WORK -> Color(0xFF3B82F6)
                                    PlaceCategory.FAMILY -> Color(0xFFEC4899)
                                    PlaceCategory.SCHOOL -> Color(0xFFF59E0B)
                                    PlaceCategory.CUSTOM -> Color(0xFF06B6D4)
                                }
                            } else {
                                Color(android.graphics.Color.parseColor(opt.hex))
                            }

                            Surface(
                                onClick = { placeToSaveColor = opt.hex },
                                shape = RoundedCornerShape(8.dp),
                                color = if (isColorSelected) chipBg.copy(alpha = 0.25f) else Color(0xFF1E293B),
                                border = if (isColorSelected) androidx.compose.foundation.BorderStroke(2.dp, chipBg) else androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF475569)),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(12.dp)
                                             .clip(CircleShape)
                                            .background(chipBg)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = opt.label,
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = if (isColorSelected) FontWeight.Bold else FontWeight.Normal,
                                        style = androidx.compose.ui.text.TextStyle(
                                            platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)
                                        )
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    OutlinedTextField(
                        value = placeToSaveName,
                        onValueChange = { if (it.length <= 30) placeToSaveName = it },
                        label = { Text("Place Name") },
                        supportingText = {
                            Text(
                                text = "${placeToSaveName.length}/30",
                                color = Color(0xFF94A3B8),
                                fontSize = 11.sp,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.End
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF10B981),
                            unfocusedBorderColor = Color(0xFF64748B)
                        )
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    OutlinedTextField(
                        value = placeToSaveStreet,
                        onValueChange = { placeToSaveStreet = it },
                        label = { Text("Street & Number") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF10B981),
                            unfocusedBorderColor = Color(0xFF64748B)
                        )
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    OutlinedTextField(
                        value = placeToSaveLocality,
                        onValueChange = { placeToSaveLocality = it },
                        label = { Text("Locality / City") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF10B981),
                            unfocusedBorderColor = Color(0xFF64748B)
                        )
                    )

                    Text(
                        text = String.format(Locale.US, "Coordinates: %.5f, %.5f", coords.first, coords.second),
                        color = Color(0xFF64748B),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val c = placeToSaveCoords
                        if (c != null) {
                            viewModel.savePlace(
                                name = placeToSaveName.ifBlank { placeToSaveCategory.displayName },
                                category = placeToSaveCategory,
                                geoPoint = org.osmdroid.util.GeoPoint(c.first, c.second),
                                locality = placeToSaveLocality.trim(),
                                street = placeToSaveStreet.trim(),
                                colorHex = placeToSaveColor
                            )
                        }
                        showSavePlaceDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSavePlaceDialog = false }) {
                    Text("Cancel", color = Color.White)
                }
            },
            containerColor = Color(0xFF1E293B)
        )
    }

    // ── 8. Delete Saved Place Confirmation Dialog ──────────────────────────
    if (placeToDelete != null) {
        val sp = placeToDelete!!
        AlertDialog(
            onDismissRequest = { placeToDelete = null },
            title = {
                Text("Delete Saved Place", color = Color.White, fontWeight = FontWeight.Bold)
            },
            text = {
                Text("Are you sure you want to delete '${sp.name}'?", color = Color(0xFFCBD5E1))
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteSavedPlace(sp.id)
                        placeToDelete = null
                        selectedSavedPlace = null
                        viewModel.clearPinnedBorders()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { placeToDelete = null }) {
                    Text("Cancel", color = Color.White)
                }
            },
            containerColor = Color(0xFF1E293B)
        )
    }

    // ── 8a. Delete Trip Confirmation Dialog ─────────────────────────────────
    if (tripToDelete != null) {
        val trip = tripToDelete!!
        AlertDialog(
            onDismissRequest = { tripToDelete = null },
            title = {
                Text("Delete Trip?", color = Color.White, fontWeight = FontWeight.Bold)
            },
            text = {
                val dateStr = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(trip.startTime))
                val timeStr = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(trip.startTime))
                val defaultTitle = "$dateStr, $timeStr"
                val displayTitle = if (trip.title.isNotBlank()) trip.title else defaultTitle
                Text("Are you sure you want to permanently delete trip '$displayTitle'? This cannot be undone.", color = Color(0xFFCBD5E1))
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteTrip(trip.id)
                        tripToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { tripToDelete = null }) {
                    Text("Cancel", color = Color.White)
                }
            },
            containerColor = Color(0xFF1E293B)
        )
    }

    // ── 8a-1. Merge Trips Confirmation Dialog ────────────────────────────────
    if (showMergeConfirmationDialog && selectedTripsList.size >= 2) {
        val sortedSelectedTrips = remember(selectedTripsList) {
            selectedTripsList.sortedBy { it.startTime }
        }
        val distinctProfiles = remember(sortedSelectedTrips) {
            sortedSelectedTrips.map { it.activityProfile }.distinct()
        }
        val hasProfileMismatch = distinctProfiles.size > 1

        val earliestTrip = sortedSelectedTrips.first()
        val latestTrip = sortedSelectedTrips.last()
        val defaultMergedTitle = remember(sortedSelectedTrips) {
            val dateStr = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(earliestTrip.startTime))
            val timeStr = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(earliestTrip.startTime))
            val standardTitle = "$dateStr, $timeStr"

            val customTitles = sortedSelectedTrips.mapNotNull { trip ->
                val tripDate = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(trip.startTime))
                val tripTime = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(trip.startTime))
                val defaultName = "$tripDate, $tripTime"
                if (trip.title.isNotBlank() && trip.title != defaultName) trip.title else null
            }
            if (customTitles.isNotEmpty()) {
                customTitles.joinToString(" + ")
            } else {
                standardTitle
            }
        }

        var mergeTitleInput by remember(sortedSelectedTrips) { mutableStateOf(defaultMergedTitle) }
        var chosenProfile by remember(sortedSelectedTrips) { mutableStateOf(earliestTrip.activityProfile) }

        val totalPoints = remember(sortedSelectedTrips) { sortedSelectedTrips.sumOf { it.points.size } }
        val totalDistKm = remember(sortedSelectedTrips) { sortedSelectedTrips.sumOf { it.distanceMeters } / 1000.0 }

        AlertDialog(
            onDismissRequest = { showMergeConfirmationDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CallMerge,
                        contentDescription = null,
                        tint = Color(0xFF8B5CF6),
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Merge ${sortedSelectedTrips.size} Trips",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (hasProfileMismatch) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0x33F59E0B),
                            border = BorderStroke(1.dp, Color(0xFFF59E0B)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    EmojiText(emoji = "⚠️", fontSize = 14.sp)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Activity Profile Mismatch",
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFFBBF24),
                                        fontSize = 13.sp
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Selected trips have different activity profiles (${distinctProfiles.joinToString(", ") { "${it.iconEmoji} ${it.displayName}" }}). Choose which profile to assign to the merged trip:",
                                    color = Color(0xFFCBD5E1),
                                    fontSize = 11.sp
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    distinctProfiles.forEach { profile ->
                                        val isChosen = chosenProfile == profile
                                        Surface(
                                            onClick = { chosenProfile = profile },
                                            shape = RoundedCornerShape(6.dp),
                                            color = if (isChosen) Color(0xFF8B5CF6) else Color(0xFF1E293B),
                                            border = BorderStroke(1.dp, if (isChosen) Color(0xFFA78BFA) else Color(0xFF475569)),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp),
                                                horizontalArrangement = Arrangement.Center,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                EmojiText(emoji = profile.iconEmoji, fontSize = 12.sp)
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = profile.displayName,
                                                    fontSize = 11.sp,
                                                    fontWeight = if (isChosen) FontWeight.Bold else FontWeight.Normal,
                                                    color = Color.White,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Trip overview metrics
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF0F172A),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = String.format(Locale.getDefault(), "Total Distance: %.2f km • Points: %,d", totalDistKm, totalPoints),
                                color = Color(0xFF38BDF8),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            val gapsCount = sortedSelectedTrips.size - 1
                            Text(
                                text = "Intermediate gaps will be converted into $gapsCount rest pause(s).",
                                color = Color(0xFF94A3B8),
                                fontSize = 11.sp
                            )
                        }
                    }

                    // Proposed Editable Title
                    Text(
                        text = "Merged Trip Title:",
                        color = Color(0xFFCBD5E1),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    OutlinedTextField(
                        value = mergeTitleInput,
                        onValueChange = { mergeTitleInput = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF8B5CF6),
                            unfocusedBorderColor = Color(0xFF475569)
                        ),
                        placeholder = { Text("Trip title...", color = Color.Gray) }
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val titleToUse = mergeTitleInput.trim().ifBlank { defaultMergedTitle }
                        viewModel.mergeSelectedTrips(
                            customTitle = titleToUse,
                            targetProfile = chosenProfile
                        )
                        showMergeConfirmationDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Merge Trips")
                }
            },
            dismissButton = {
                TextButton(onClick = { showMergeConfirmationDialog = false }) {
                    Text("Cancel", color = Color.White)
                }
            },
            containerColor = Color(0xFF1E293B)
        )
    }

    // ── 8a-2. Restore Defaults Confirmation Dialog ──────────────────────────
    if (showResetDefaultsConfirm) {
        AlertDialog(
            onDismissRequest = { showResetDefaultsConfirm = false },
            title = {
                Text("Restore Default Settings?", color = Color.White, fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    "This will restore all settings (map orientation, activity profile, auto-stop timer, locality style, and battery policies) to their factory defaults. Your saved trips and places will not be deleted.",
                    color = Color(0xFFCBD5E1)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.resetAllSettingsToDefaults()
                        showResetDefaultsConfirm = false
                        android.widget.Toast.makeText(context, "Settings restored to defaults", android.widget.Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Restore Defaults")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDefaultsConfirm = false }) {
                    Text("Cancel", color = Color.White)
                }
            },
            containerColor = Color(0xFF1E293B)
        )
    }

    // ── 8b. Edit Saved Place Dialog ─────────────────────────────────────────
    if (editingSavedPlace != null) {
        val placeToEdit = editingSavedPlace!!
        var editName by remember(placeToEdit.id) { mutableStateOf(placeToEdit.name) }
        var editCategory by remember(placeToEdit.id) { mutableStateOf(placeToEdit.category) }
        var editStreet by remember(placeToEdit.id) { mutableStateOf(placeToEdit.street) }
        var editLocality by remember(placeToEdit.id) { mutableStateOf(placeToEdit.locality) }
        var editRadius by remember(placeToEdit.id) { mutableFloatStateOf(placeToEdit.radiusMeters) }
        var editColor by remember(placeToEdit.id) { mutableStateOf(placeToEdit.colorHex) }

        AlertDialog(
            onDismissRequest = { editingSavedPlace = null },
            title = {
                Text("Edit Saved Place", color = Color.White, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = "Category",
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )

                    // Category Selector Pills
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        PlaceCategory.values().forEach { cat ->
                            val isSelected = editCategory == cat
                            Surface(
                                onClick = { editCategory = cat },
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) Color(0xFF10B981) else Color(0xFF1E293B),
                                border = if (isSelected) null else androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF475569)),
                                modifier = Modifier.height(36.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center,
                                    modifier = Modifier.fillMaxHeight().padding(horizontal = 10.dp)
                                ) {
                                    EmojiText(
                                        emoji = cat.iconEmoji,
                                        fontSize = 14.sp
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = cat.displayName,
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        style = TextStyle(
                                            platformStyle = PlatformTextStyle(includeFontPadding = false),
                                            lineHeightStyle = LineHeightStyle(
                                                alignment = LineHeightStyle.Alignment.Center,
                                                trim = LineHeightStyle.Trim.Both
                                            ),
                                            lineHeight = 12.sp
                                        )
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "Pin Color",
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PRESET_PIN_COLORS.forEach { opt ->
                            val isColorSelected = if (opt.hex.isEmpty()) editColor.isEmpty() else editColor.equals(opt.hex, ignoreCase = true)
                            val chipBg = if (opt.hex.isEmpty()) {
                                when (editCategory) {
                                    PlaceCategory.FAVORITE -> Color(0xFF8B5CF6)
                                    PlaceCategory.HOME -> Color(0xFF10B981)
                                    PlaceCategory.WORK -> Color(0xFF3B82F6)
                                    PlaceCategory.FAMILY -> Color(0xFFEC4899)
                                    PlaceCategory.SCHOOL -> Color(0xFFF59E0B)
                                    PlaceCategory.CUSTOM -> Color(0xFF06B6D4)
                                }
                            } else {
                                Color(android.graphics.Color.parseColor(opt.hex))
                            }

                            Surface(
                                onClick = { editColor = opt.hex },
                                shape = RoundedCornerShape(8.dp),
                                color = if (isColorSelected) chipBg.copy(alpha = 0.25f) else Color(0xFF1E293B),
                                border = if (isColorSelected) androidx.compose.foundation.BorderStroke(2.dp, chipBg) else androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF475569)),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(12.dp)
                                            .clip(CircleShape)
                                            .background(chipBg)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = opt.label,
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = if (isColorSelected) FontWeight.Bold else FontWeight.Normal,
                                        style = androidx.compose.ui.text.TextStyle(
                                            platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)
                                        )
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    OutlinedTextField(
                        value = editName,
                        onValueChange = { if (it.length <= 30) editName = it },
                        label = { Text("Place Name") },
                        supportingText = {
                            Text(
                                text = "${editName.length}/30",
                                color = Color(0xFF94A3B8),
                                fontSize = 11.sp,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.End
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF10B981),
                            unfocusedBorderColor = Color(0xFF64748B)
                        )
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    OutlinedTextField(
                        value = editStreet,
                        onValueChange = { editStreet = it },
                        label = { Text("Street & Number") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF10B981),
                            unfocusedBorderColor = Color(0xFF64748B)
                        )
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    OutlinedTextField(
                        value = editLocality,
                        onValueChange = { editLocality = it },
                        label = { Text("Locality / City") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF10B981),
                            unfocusedBorderColor = Color(0xFF64748B)
                        )
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Arrival Geofence Radius: ${editRadius.toInt()} m",
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(50f, 100f, 200f, 500f).forEach { r ->
                            val isRadSelected = editRadius == r
                            Surface(
                                onClick = { editRadius = r },
                                shape = RoundedCornerShape(12.dp),
                                color = if (isRadSelected) Color(0xFF0284C7) else Color(0xFF0F172A),
                                modifier = Modifier.weight(1f).height(30.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "${r.toInt()}m",
                                        fontSize = 11.sp,
                                        color = Color.White,
                                        fontWeight = if (isRadSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }
                    }

                    Text(
                        text = String.format(Locale.US, "Coordinates: %.5f, %.5f", placeToEdit.latitude, placeToEdit.longitude),
                        color = Color(0xFF64748B),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val updated = placeToEdit.copy(
                            name = editName.ifBlank { editCategory.displayName },
                            category = editCategory,
                            street = editStreet.trim(),
                            locality = editLocality.trim(),
                            radiusMeters = editRadius,
                            colorHex = editColor
                        )
                        viewModel.updateSavedPlace(updated)
                        editingSavedPlace = null
                        if (selectedSavedPlace?.id == updated.id) {
                            selectedSavedPlace = updated
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Save Changes")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingSavedPlace = null }) {
                    Text("Cancel", color = Color.White)
                }
            },
            containerColor = Color(0xFF1E293B)
        )
    }

    // ── Active Trip Visited Places Timeline Dialog ──────────────────────────
    if (showActiveTripRouteDialog && activeTrip != null) {
        Dialog(onDismissRequest = { showActiveTripRouteDialog = false }) {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .fillMaxHeight(0.85f)
                    .padding(8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(18.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                EmojiText(emoji = "🚩", fontSize = 18.sp)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Current Trip Route",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    style = TextStyle(
                                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                                        lineHeightStyle = LineHeightStyle(
                                            alignment = LineHeightStyle.Alignment.Center,
                                            trim = LineHeightStyle.Trim.Both
                                        ),
                                        lineHeight = 18.sp
                                    )
                                )
                            }
                            Text(
                                text = "${activeTrip!!.placesVisited.size} places • ${(activeTrip!!.distanceMeters / 1000.0).let { String.format(Locale.getDefault(), "%.1f km", it) }}",
                                fontSize = 12.sp,
                                color = Color(0xFF94A3B8)
                            )
                        }
                        IconButton(
                            onClick = { showActiveTripRouteDialog = false },
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF1E293B))
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (activeTrip!!.placesVisited.isEmpty()) {
                        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text("No intermediate places recorded yet. Continue traveling to record route.", color = Color(0xFF64748B), fontSize = 13.sp, textAlign = TextAlign.Center)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(activeTrip!!.placesVisited) { place ->
                                val isFirst = activeTrip!!.placesVisited.firstOrNull() == place
                                val isLast = activeTrip!!.placesVisited.lastOrNull() == place
                                val timeStr = java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(place.timestamp))
                                val distStr = String.format(Locale.getDefault(), "%.1f km", place.distanceAtEntryMeters / 1000.0)

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color(0xFF1E293B))
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(if (isLast) Color(0xFF10B981) else if (isFirst) Color(0xFF38BDF8) else Color(0xFFFBBF24))
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = place.placeName,
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White,
                                                modifier = Modifier.weight(1f, fill = false),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = timeStr,
                                                fontSize = 12.sp,
                                                color = Color(0xFF38BDF8),
                                                fontWeight = FontWeight.SemiBold,
                                                softWrap = false
                                            )
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = place.hierarchySubtitle,
                                                fontSize = 11.sp,
                                                color = Color(0xFF94A3B8),
                                                modifier = Modifier.weight(1f, fill = false),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = distStr,
                                                fontSize = 11.sp,
                                                color = Color(0xFF64748B),
                                                softWrap = false
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { showActiveTripRouteDialog = false },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Close")
                    }
                }
            }
        }
    }

    // ── Live Location Sharing Management Dialog ─────────────────────────────
    if (showLiveShareDialog) {
        val prefs = context.getSharedPreferences("where_am_i_live_share_prefs", android.content.Context.MODE_PRIVATE)
        var inputTitle by remember {
            mutableStateOf(liveSession?.title ?: prefs.getString(LiveSharingManager.KEY_PREF_TITLE, "My Live Hike") ?: "My Live Hike")
        }
        var selectedProvider by remember {
            val saved = prefs.getString(LiveSharingManager.KEY_PREF_PROVIDER, LiveShareProvider.SYNOLOGY.name) ?: LiveShareProvider.SYNOLOGY.name
            val prov = try { LiveShareProvider.valueOf(saved) } catch (_: Exception) { LiveShareProvider.SYNOLOGY }
            mutableStateOf(liveSession?.provider ?: prov)
        }
        var providerDropdownExpanded by remember { mutableStateOf(false) }
        var durationDropdownExpanded by remember { mutableStateOf(false) }
        var selectedInterval by remember { mutableStateOf(liveSession?.syncIntervalMinutes ?: prefs.getInt("active_session_interval", 5)) }
        var selectedDuration by remember { mutableStateOf(prefs.getInt(LiveSharingManager.KEY_PREF_DURATION, 6)) } // 0 = permanent
        var upcomingSessionId by remember { mutableStateOf(LiveSharingManager.generate10CharSlug()) }
        var advancedControlsExpanded by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            liveSharingManager.refreshSessionStatus()
        }

        ModalBottomSheet(
            onDismissRequest = { showLiveShareDialog = false },
            containerColor = Color(0xFF0F172A),
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 8.dp)
                    .navigationBarsPadding()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("📡", fontSize = 22.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Live Location Sharing",
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    IconButton(
                        onClick = { showLiveShareDialog = false },
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1E293B))
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                if (liveSession?.isActive == true) {
                    // ── Active Session View (Tier 1 & Tier 2) ──
                    val session = liveSession!!
                    val tripViewerUrl = session.getViewerUrl(useStatic = false)
                    val personalViewerUrl = session.getViewerUrl(useStatic = true, staticId = staticLiveId)

                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            var isEditingTitle by remember { mutableStateOf(false) }
                            var editingTitleText by remember(session.title) { mutableStateOf(session.title) }

                            // Live countdown / elapsed ticker
                            var tickerNow by remember { mutableStateOf(System.currentTimeMillis()) }
                            LaunchedEffect(Unit) {
                                while (true) {
                                    tickerNow = System.currentTimeMillis()
                                    kotlinx.coroutines.delay(1000L)
                                }
                            }
                            val formattedRemaining = remember(tickerNow, session.expiresAt, session.createdAt) {
                                if (session.expiresAt <= 0L) {
                                    val elapsed = maxOf(0L, tickerNow - session.createdAt)
                                    val hrs = elapsed / 3600000L
                                    val mins = (elapsed % 3600000L) / 60000L
                                    val secs = (elapsed % 60000L) / 1000L
                                    if (hrs > 0) "⏱️ ${hrs}h ${mins}m ${secs}s (Continuous)" else "⏱️ ${mins}m ${secs}s (Continuous)"
                                } else {
                                    val rem = maxOf(0L, session.expiresAt - tickerNow)
                                    if (rem <= 0L) {
                                        "Expired"
                                    } else {
                                        val hrs = rem / 3600000L
                                        val mins = (rem % 3600000L) / 60000L
                                        val secs = (rem % 60000L) / 1000L
                                        if (hrs > 0) "⏱️ ${hrs}h ${mins}m ${secs}s left" else "⏱️ ${mins}m ${secs}s left"
                                    }
                                }
                            }

                            if (isEditingTitle) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedTextField(
                                            value = editingTitleText,
                                            onValueChange = {
                                                if (it.length <= 40) editingTitleText = it
                                            },
                                            singleLine = false,
                                            minLines = 1,
                                            maxLines = 2,
                                            modifier = Modifier.weight(1f),
                                            textStyle = LocalTextStyle.current.copy(fontSize = 15.sp),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedTextColor = Color.White,
                                                unfocusedTextColor = Color.White,
                                                focusedBorderColor = Color(0xFF38BDF8),
                                                unfocusedBorderColor = Color(0xFF64748B)
                                            )
                                        )
                                        Surface(
                                            onClick = {
                                                val trimmed = editingTitleText.trim()
                                                if (trimmed.isNotBlank()) {
                                                    liveSharingManager.renameSession(trimmed)
                                                    isEditingTitle = false
                                                    android.widget.Toast.makeText(context, "Session renamed", android.widget.Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                            shape = RoundedCornerShape(8.dp),
                                            color = Color(0xFF065F46),
                                            modifier = Modifier.size(38.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    Icons.Default.Check,
                                                    contentDescription = "Save Title",
                                                    tint = Color(0xFF34D399),
                                                    modifier = Modifier.size(22.dp)
                                                )
                                            }
                                        }
                                        Surface(
                                            onClick = {
                                                editingTitleText = session.title
                                                isEditingTitle = false
                                            },
                                            shape = RoundedCornerShape(8.dp),
                                            color = Color(0xFF334155),
                                            modifier = Modifier.size(38.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    Icons.Default.Close,
                                                    contentDescription = "Cancel",
                                                    tint = Color.LightGray,
                                                    modifier = Modifier.size(22.dp)
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        text = "${editingTitleText.length}/40",
                                        fontSize = 10.sp,
                                        color = if (editingTitleText.length >= 40) Color(0xFFEF4444) else Color(0xFF94A3B8),
                                        modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                                    )
                                }
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = session.title,
                                        fontSize = 17.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Surface(
                                        onClick = {
                                            editingTitleText = session.title
                                            isEditingTitle = true
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        color = Color(0xFF334155),
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                Icons.Default.Edit,
                                                contentDescription = "Rename Session",
                                                tint = Color(0xFF38BDF8),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Combined Status, Views & Time Row (Responsive)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (session.isPaused) Color(0x33F59E0B) else Color(0x3310B981))
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = if (session.isPaused) "PAUSED" else "ACTIVE",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (session.isPaused) Color(0xFFF59E0B) else Color(0xFF10B981)
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color(0xFF334155))
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        EmojiText(emoji = "👥", fontSize = 11.sp)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "${session.viewCount} views",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF38BDF8),
                                            style = TextStyle(
                                                platformStyle = PlatformTextStyle(includeFontPadding = false),
                                                lineHeightStyle = LineHeightStyle(
                                                    alignment = LineHeightStyle.Alignment.Center,
                                                    trim = LineHeightStyle.Trim.Both
                                                ),
                                                lineHeight = 11.sp
                                            )
                                        )
                                    }
                                }
                                Text(
                                    text = formattedRemaining,
                                    fontSize = 11.sp,
                                    color = Color(0xFFFBBF24),
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // ── Primary Action Bar: [Stop] [Pause / Resume] [Sync Now] ──
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                liveSharingManager.stopSession()
                                android.widget.Toast.makeText(context, "Live sharing stopped", android.widget.Toast.LENGTH_SHORT).show()
                            },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("⏹️ Stop", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                if (session.isPaused) {
                                    liveSharingManager.resumeSession()
                                    android.widget.Toast.makeText(context, "All links resumed", android.widget.Toast.LENGTH_SHORT).show()
                                } else {
                                    liveSharingManager.pauseSession()
                                    android.widget.Toast.makeText(context, "All links paused", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (session.isPaused) Color(0xFF059669) else Color(0xFFD97706)
                            ),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                            modifier = Modifier.weight(1.1f)
                        ) {
                            Text(if (session.isPaused) "▶️ Resume" else "⏸️ Pause", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                liveSharingManager.syncNow()
                                android.widget.Toast.makeText(context, "Syncing live telemetry...", android.widget.Toast.LENGTH_SHORT).show()
                            },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Sync, contentDescription = "Sync", modifier = Modifier.size(14.dp), tint = Color.White)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Sync", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // ── Permanent Inline Streaming Controls Card ──
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            // Live Privacy
                            Text("Live Map Privacy (Viewer display):", fontSize = 11.sp, color = Color(0xFF94A3B8))
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        liveSharingManager.setTrailVisible(true)
                                        android.widget.Toast.makeText(context, "Visitors see full trail", android.widget.Toast.LENGTH_SHORT).show()
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (session.trailVisible) Color(0xFF0284C7) else Color(0xFF0F172A)
                                    ),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        EmojiText(emoji = "🗺️", fontSize = 11.sp)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "Full Trail",
                                            fontSize = 11.sp,
                                            fontWeight = if (session.trailVisible) FontWeight.Bold else FontWeight.Normal,
                                            style = TextStyle(
                                                platformStyle = PlatformTextStyle(includeFontPadding = false),
                                                lineHeightStyle = LineHeightStyle(
                                                    alignment = LineHeightStyle.Alignment.Center,
                                                    trim = LineHeightStyle.Trim.Both
                                                ),
                                                lineHeight = 11.sp
                                            )
                                        )
                                    }
                                }
                                Button(
                                    onClick = {
                                        liveSharingManager.setTrailVisible(false)
                                        android.widget.Toast.makeText(context, "Visitors see current pin only", android.widget.Toast.LENGTH_SHORT).show()
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (!session.trailVisible) Color(0xFF0284C7) else Color(0xFF0F172A)
                                    ),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        EmojiText(emoji = "📍", fontSize = 11.sp)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "Position Only",
                                            fontSize = 11.sp,
                                            fontWeight = if (!session.trailVisible) FontWeight.Bold else FontWeight.Normal,
                                            style = TextStyle(
                                                platformStyle = PlatformTextStyle(includeFontPadding = false),
                                                lineHeightStyle = LineHeightStyle(
                                                    alignment = LineHeightStyle.Alignment.Center,
                                                    trim = LineHeightStyle.Trim.Both
                                                ),
                                                lineHeight = 11.sp
                                            )
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Live Update Frequency
                            Text("Live Update Frequency:", fontSize = 11.sp, color = Color(0xFF94A3B8))
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(1, 2, 5, 10).forEach { mins ->
                                    val isSel = (session.syncIntervalMinutes == mins)
                                    Button(
                                        onClick = {
                                            liveSharingManager.setSyncInterval(mins)
                                            android.widget.Toast.makeText(context, "Update interval set to ${mins}m", android.widget.Toast.LENGTH_SHORT).show()
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (isSel) Color(0xFF0284C7) else Color(0xFF0F172A)
                                        ),
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("${mins}m", fontSize = 12.sp, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Duration Adjustments
                            if (session.expiresAt <= 0L) {
                                Button(
                                    onClick = {
                                        liveSharingManager.adjustSession(6.0)
                                        android.widget.Toast.makeText(context, "Switched to 6h timed duration", android.widget.Toast.LENGTH_SHORT).show()
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("⏱ Switch to Timed Duration (+6h)", fontSize = 12.sp, color = Color(0xFFCBD5E1))
                                }
                            } else {
                                Text("Adjust Sharing Duration:", fontSize = 11.sp, color = Color(0xFF94A3B8))
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            liveSharingManager.adjustSession(-1.0)
                                            android.widget.Toast.makeText(context, "Reduced session by -1 hour", android.widget.Toast.LENGTH_SHORT).show()
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A)),
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                                        modifier = Modifier.weight(1f)
                                    ) { Text("-1h", fontSize = 11.sp) }

                                    Button(
                                        onClick = {
                                            liveSharingManager.adjustSession(-0.5)
                                            android.widget.Toast.makeText(context, "Reduced session by -30 min", android.widget.Toast.LENGTH_SHORT).show()
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A)),
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                                        modifier = Modifier.weight(1f)
                                    ) { Text("-30m", fontSize = 11.sp) }

                                    Button(
                                        onClick = {
                                            liveSharingManager.adjustSession(1.0)
                                            android.widget.Toast.makeText(context, "Extended session by +1 hour", android.widget.Toast.LENGTH_SHORT).show()
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A)),
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                                        modifier = Modifier.weight(1f)
                                    ) { Text("+1h", fontSize = 11.sp) }

                                    Button(
                                        onClick = {
                                            liveSharingManager.adjustSession(6.0)
                                            android.widget.Toast.makeText(context, "Extended session by +6 hours", android.widget.Toast.LENGTH_SHORT).show()
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A)),
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                                        modifier = Modifier.weight(1f)
                                    ) { Text("+6h", fontSize = 11.sp) }
                                }
                                Button(
                                    onClick = {
                                        liveSharingManager.makeSessionPermanent()
                                        android.widget.Toast.makeText(context, "Switched to continuous (no expiry)", android.widget.Toast.LENGTH_SHORT).show()
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A)),
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                                ) {
                                    Text("∞ Switch to Continuous (No Expiry)", fontSize = 12.sp, color = Color(0xFF38BDF8))
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // ── TIER 1: 1-Tap Quick Action Cards ──
                    Text("SHARE LINKS (1-TAP ACTION)", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8))
                    Spacer(modifier = Modifier.height(6.dp))

                    // Card 1: 🎫 Trip Link (Random)
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("🎫", fontSize = 14.sp)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Trip Link (Single Use)", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                }
                                if (session.isRandomPaused) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(Color(0x33F59E0B))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text("PAUSED", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF59E0B))
                                    }
                                }
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable {
                                        try {
                                            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(tripViewerUrl))
                                            context.startActivity(browserIntent)
                                        } catch (_: Exception) {}
                                    }
                            ) {
                                Text(
                                    text = tripViewerUrl,
                                    fontSize = 11.sp,
                                    color = Color(0xFF38BDF8),
                                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("↗", fontSize = 12.sp, color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Button(
                                    onClick = {
                                        val sendIntent = Intent().apply {
                                            action = Intent.ACTION_SEND
                                            putExtra(Intent.EXTRA_TEXT, "Follow my live track on WhereAmI: $tripViewerUrl")
                                            type = "text/plain"
                                        }
                                        context.startActivity(Intent.createChooser(sendIntent, "Share Trip Link"))
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Share, contentDescription = "Share", modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Share", fontSize = 11.sp)
                                }
                                Button(
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("WhereAmI Trip Link", tripViewerUrl))
                                        android.widget.Toast.makeText(context, "Trip link copied", android.widget.Toast.LENGTH_SHORT).show()
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155), contentColor = Color.White),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(14.dp), tint = Color.White)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Copy", fontSize = 11.sp, color = Color.White)
                                }
                                Button(
                                    onClick = {
                                        if (session.isRandomPaused) {
                                            liveSharingManager.resumeRandomLink()
                                            android.widget.Toast.makeText(context, "Trip link resumed", android.widget.Toast.LENGTH_SHORT).show()
                                        } else {
                                            liveSharingManager.pauseRandomLink()
                                            android.widget.Toast.makeText(context, "Trip link paused", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (session.isRandomPaused) Color(0xFF059669) else Color(0xFF475569)
                                    ),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                                    modifier = Modifier.weight(1.1f)
                                ) {
                                    Text(if (session.isRandomPaused) "▶ Resume" else "⏸ Pause", fontSize = 11.sp)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Card 2: 📡 Personal Link (Static)
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("📡", fontSize = 14.sp)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Personal Link (Permanent)", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                }
                                if (session.isPersonalPaused) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(Color(0x33F59E0B))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text("PAUSED", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF59E0B))
                                    }
                                }
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable {
                                        try {
                                            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(personalViewerUrl))
                                            context.startActivity(browserIntent)
                                        } catch (_: Exception) {}
                                    }
                            ) {
                                Text(
                                    text = personalViewerUrl,
                                    fontSize = 11.sp,
                                    color = Color(0xFF10B981),
                                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("↗", fontSize = 12.sp, color = Color(0xFF10B981), fontWeight = FontWeight.Bold)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Button(
                                    onClick = {
                                        val sendIntent = Intent().apply {
                                            action = Intent.ACTION_SEND
                                            putExtra(Intent.EXTRA_TEXT, "Follow my live journey anytime on WhereAmI: $personalViewerUrl")
                                            type = "text/plain"
                                        }
                                        context.startActivity(Intent.createChooser(sendIntent, "Share Personal Link"))
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Share, contentDescription = "Share", modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Share", fontSize = 11.sp)
                                }
                                Button(
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("WhereAmI Personal Link", personalViewerUrl))
                                        android.widget.Toast.makeText(context, "Personal link copied", android.widget.Toast.LENGTH_SHORT).show()
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155), contentColor = Color.White),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(14.dp), tint = Color.White)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Copy", fontSize = 11.sp, color = Color.White)
                                }
                                Button(
                                    onClick = {
                                        if (session.isPersonalPaused) {
                                            liveSharingManager.resumePersonalLink()
                                            android.widget.Toast.makeText(context, "Personal link resumed", android.widget.Toast.LENGTH_SHORT).show()
                                        } else {
                                            liveSharingManager.pausePersonalLink()
                                            android.widget.Toast.makeText(context, "Personal link paused", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (session.isPersonalPaused) Color(0xFF059669) else Color(0xFF475569)
                                    ),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                                    modifier = Modifier.weight(1.1f)
                                ) {
                                    Text(if (session.isPersonalPaused) "▶ Resume" else "⏸ Pause", fontSize = 11.sp)
                                }
                            }
                        }
                    }
                } else {
                    // ── Create New Session View ──
                    Text("Session Title", fontSize = 13.sp, color = Color(0xFF94A3B8))
                    OutlinedTextField(
                        value = inputTitle,
                        onValueChange = { if (it.length <= 40) inputTitle = it },
                        singleLine = false,
                        minLines = 1,
                        maxLines = 2,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF0284C7),
                            unfocusedBorderColor = Color(0xFF334155)
                        ),
                        supportingText = {
                            Text(
                                text = "${inputTitle.length}/40",
                                fontSize = 10.sp,
                                color = if (inputTitle.length >= 40) Color(0xFFEF4444) else Color(0xFF94A3B8)
                            )
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // TOP ACTION: Start Live Sharing Session Button
                    Button(
                        onClick = {
                            val serverUrl = when (selectedProvider) {
                                LiveShareProvider.LOCAL -> "http://127.0.0.1:3003"
                                LiveShareProvider.SYNOLOGY -> "https://whereami.janush.tech"
                            }
                            prefs.edit()
                                .putString(LiveSharingManager.KEY_PREF_TITLE, inputTitle)
                                .putString(LiveSharingManager.KEY_PREF_PROVIDER, selectedProvider.name)
                                .putInt(LiveSharingManager.KEY_PREF_DURATION, selectedDuration)
                                .putInt("active_session_interval", selectedInterval)
                                .apply()
                            val s = liveSharingManager.startSession(
                                title = inputTitle,
                                durationHours = selectedDuration,
                                serverUrl = serverUrl,
                                provider = selectedProvider,
                                syncIntervalMinutes = selectedInterval,
                                customSlug = upcomingSessionId
                            )
                            upcomingSessionId = LiveSharingManager.generate10CharSlug()
                            showLiveShareDialog = false
                            showLiveQuickShareModal = true
                            android.widget.Toast.makeText(context, "Started Live Share: ${s.id}", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Start", modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Start Live Sharing Session", fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Upcoming Trip Session ID (with Pre-generation Refresh)
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Trip Session ID (Single Use)", fontSize = 11.sp, color = Color(0xFF94A3B8))
                                Text(upcomingSessionId, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8))
                            }
                            IconButton(
                                onClick = {
                                    upcomingSessionId = LiveSharingManager.generate10CharSlug()
                                    android.widget.Toast.makeText(context, "New Trip ID generated", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = "Regenerate Trip ID", tint = Color(0xFF38BDF8), modifier = Modifier.size(20.dp))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Personal Static Live ID (Permanent)
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Personal Static Live ID (Permanent)", fontSize = 11.sp, color = Color(0xFF94A3B8))
                                Text(staticLiveId, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF10B981))
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = {
                                        val newId = liveSharingManager.regenerateStaticLiveId()
                                        android.widget.Toast.makeText(context, "Regenerated Personal ID: $newId", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Regenerate Static ID", tint = Color(0xFF10B981), modifier = Modifier.size(20.dp))
                                }
                                IconButton(
                                    onClick = {
                                        val serverUrl = when (selectedProvider) {
                                            LiveShareProvider.LOCAL -> "http://127.0.0.1:3003"
                                            LiveShareProvider.SYNOLOGY -> "https://whereami.janush.tech"
                                        }
                                        val dummySession = LiveSession(
                                            id = staticLiveId,
                                            title = "",
                                            createdAt = 0L,
                                            expiresAt = 0L,
                                            isActive = false,
                                            serverUrl = serverUrl,
                                            provider = selectedProvider,
                                            syncIntervalMinutes = selectedInterval
                                        )
                                        val staticUrl = dummySession.getViewerUrl(useStatic = true, staticId = staticLiveId)
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                        val clip = android.content.ClipData.newPlainText("WhereAmI Static Live Link", staticUrl)
                                        clipboard.setPrimaryClip(clip)
                                        android.widget.Toast.makeText(context, "Personal link copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                ) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy Static Link", tint = Color.LightGray, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text("Share Target / Provider", fontSize = 13.sp, color = Color(0xFF94A3B8))

                    // Provider Dropdown
                    Box(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Surface(
                            onClick = { providerDropdownExpanded = true },
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF1E293B),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val providerLabel = when (selectedProvider) {
                                    LiveShareProvider.SYNOLOGY -> "🌐 whereami.janush.tech (Public)"
                                    LiveShareProvider.LOCAL -> "💻 Local Test Server (Port 3003)"
                                }
                                Text(providerLabel, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Icon(Icons.Default.ArrowDropDown, contentDescription = "Select", tint = Color(0xFF94A3B8))
                            }
                        }

                        DropdownMenu(
                            expanded = providerDropdownExpanded,
                            onDismissRequest = { providerDropdownExpanded = false },
                            modifier = Modifier.background(Color(0xFF1E293B))
                        ) {
                            DropdownMenuItem(
                                text = { Text("🌐 whereami.janush.tech (Public)", color = Color.White, fontSize = 13.sp) },
                                onClick = {
                                    selectedProvider = LiveShareProvider.SYNOLOGY
                                    providerDropdownExpanded = false
                                    prefs.edit().putString(LiveSharingManager.KEY_PREF_PROVIDER, LiveShareProvider.SYNOLOGY.name).apply()
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = if (isUsbConnected) "💻 Local Test Server (Port 3003)" else "💻 Local Test Server (Requires USB)",
                                        color = if (isUsbConnected) Color.White else Color.Gray,
                                        fontSize = 13.sp
                                    )
                                },
                                onClick = {
                                    if (isUsbConnected) {
                                        selectedProvider = LiveShareProvider.LOCAL
                                        providerDropdownExpanded = false
                                        prefs.edit().putString(LiveSharingManager.KEY_PREF_PROVIDER, LiveShareProvider.LOCAL.name).apply()
                                    } else {
                                        android.widget.Toast.makeText(context, "Connect phone via USB cable to use Local Test Server", android.widget.Toast.LENGTH_LONG).show()
                                    }
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = when (selectedProvider) {
                            LiveShareProvider.SYNOLOGY -> "📤 Shared link: whereami.janush.tech/live/… — accessible to anyone."
                            LiveShareProvider.LOCAL -> if (isUsbConnected) "🔌 USB Active • Viewer at localhost:3003 (ADB port-forwarded)" else "⚠️ USB Disconnected — connect USB cable or switch to Public server."
                        },
                        fontSize = 11.sp,
                        color = if (selectedProvider == LiveShareProvider.LOCAL && !isUsbConnected) Color(0xFFF87171) else Color(0xFF38BDF8)
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Sync Interval (Battery Optimization)", fontSize = 13.sp, color = Color(0xFF94A3B8))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(1 to "1m", 2 to "2m", 5 to "5m", 10 to "10m").forEach { (mins, label) ->
                            val isSel = selectedInterval == mins
                            Button(
                                onClick = { selectedInterval = mins },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isSel) Color(0xFF0284C7) else Color(0xFF1E293B)
                                ),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(label, fontSize = 12.sp)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Session Lifetime", fontSize = 13.sp, color = Color(0xFF94A3B8))

                    val lifetimeOptions = listOf(
                        2 to "⏳ 2 hours",
                        6 to "⏳ 6 hours (Recommended)",
                        12 to "⏳ 12 hours",
                        24 to "⏳ 24 hours (Full Day)",
                        0 to "♾️ Permanent (No limit until stopped)"
                    )

                    Box(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Surface(
                            onClick = { durationDropdownExpanded = true },
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF1E293B),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val currentLifetimeLabel = lifetimeOptions.firstOrNull { it.first == selectedDuration }?.second
                                    ?: "$selectedDuration hours"
                                Text(currentLifetimeLabel, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Icon(Icons.Default.ArrowDropDown, contentDescription = "Select Lifetime", tint = Color(0xFF94A3B8))
                            }
                        }

                        DropdownMenu(
                            expanded = durationDropdownExpanded,
                            onDismissRequest = { durationDropdownExpanded = false },
                            modifier = Modifier.background(Color(0xFF1E293B))
                        ) {
                            lifetimeOptions.forEach { (hrs, label) ->
                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = label,
                                                color = if (selectedDuration == hrs) Color(0xFF38BDF8) else Color.White,
                                                fontSize = 13.sp,
                                                fontWeight = if (selectedDuration == hrs) FontWeight.Bold else FontWeight.Normal
                                            )
                                            if (selectedDuration == hrs) {
                                                Icon(
                                                    Icons.Default.Check,
                                                    contentDescription = "Selected",
                                                    tint = Color(0xFF38BDF8),
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    },
                                    onClick = {
                                        selectedDuration = hrs
                                        durationDropdownExpanded = false
                                        prefs.edit().putInt(LiveSharingManager.KEY_PREF_DURATION, hrs).apply()
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (activeLocationShareTarget != null) {
        LocationShareDialog(
            target = activeLocationShareTarget!!,
            onDismiss = { activeLocationShareTarget = null }
        )
    }

    if (profileToConfigure != null) {
        ProfileConfigSheet(
            profile = profileToConfigure!!,
            isCurrentActive = activityProfile == profileToConfigure,
            viewModel = viewModel,
            onSetAsActive = {
                viewModel.setActivityProfile(profileToConfigure!!)
            },
            onDismiss = { profileToConfigure = null }
        )
    }

    if (showHeatMapSettingsDialog) {
        HeatMapSettingsDialog(
            filterState = heatMapFilterState,
            onApplyFilter = { newState ->
                viewModel.updateHeatMapFilter(newState)
            },
            onResetFilter = {
                viewModel.resetHeatMapFilter()
            },
            onDismiss = { showHeatMapSettingsDialog = false }
        )
    }

    // ── Live Sharing Quick Share 1-Tap Modal ─────────────────────────────────
    if (showLiveQuickShareModal && liveSession != null && liveSession!!.isActive) {
        val session = liveSession!!
        val tripViewerUrl = session.getViewerUrl(useStatic = false)
        val personalViewerUrl = session.getViewerUrl(useStatic = true, staticId = staticLiveId)

        ModalBottomSheet(
            onDismissRequest = { showLiveQuickShareModal = false },
            containerColor = Color(0xFF0F172A),
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .navigationBarsPadding()
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("📡", fontSize = 22.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Live Sharing Active",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = session.title,
                                fontSize = 13.sp,
                                color = Color(0xFF38BDF8),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    IconButton(
                        onClick = { showLiveQuickShareModal = false },
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1E293B))
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Action 1: 1-Tap Share Trip Link
                Button(
                    onClick = {
                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "Live Trip: ${session.title}")
                            putExtra(Intent.EXTRA_TEXT, "Follow my live trip \"${session.title}\": $tripViewerUrl")
                        }
                        context.startActivity(Intent.createChooser(sendIntent, "Share Live Trip Link"))
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(46.dp)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Share Trip Link (Single-Use)", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Action 2: 1-Tap Share Personal Permanent Link
                Button(
                    onClick = {
                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "My Live Location")
                            putExtra(Intent.EXTRA_TEXT, "Follow my live location: $personalViewerUrl")
                        }
                        context.startActivity(Intent.createChooser(sendIntent, "Share Personal Link"))
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(46.dp)
                ) {
                    Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Share Personal Link (Permanent)", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Action 3 & 4: Copy Trip Link & Show QR Code
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Live Trip URL", tripViewerUrl))
                            Toast.makeText(context, "Trip URL copied to clipboard!", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF38BDF8)),
                        border = BorderStroke(1.dp, Color(0xFF38BDF8)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).height(44.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Copy Link", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }

                    OutlinedButton(
                        onClick = {
                            qrCodeTargetUrl = tripViewerUrl
                            qrCodeTargetTitle = session.title
                            showLiveQrCodeModal = true
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFBBF24)),
                        border = BorderStroke(1.dp, Color(0xFFFBBF24)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).height(44.dp)
                    ) {
                        Icon(Icons.Default.QrCode, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("QR Code", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Secondary Actions: Manage Session & Stop Session
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = {
                            showLiveQuickShareModal = false
                            showLiveShareDialog = true
                        }
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFF94A3B8))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Manage Session", color = Color(0xFF94A3B8), fontSize = 13.sp)
                    }

                    TextButton(
                        onClick = {
                            liveSharingManager.stopSession()
                            showLiveQuickShareModal = false
                            Toast.makeText(context, "Live sharing stopped", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Text("Stop Sharing", color = Color(0xFFEF4444), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    // ── Live QR Code Dialog (Offline-Generated via QrCodeHelper) ─────────────
    if (showLiveQrCodeModal && qrCodeTargetUrl.isNotBlank()) {
        val qrBitmap = remember(qrCodeTargetUrl) {
            QrCodeHelper.generateQrBitmap(qrCodeTargetUrl, 512)
        }
        AlertDialog(
            onDismissRequest = { showLiveQrCodeModal = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.QrCode, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Scan to Follow Live", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (qrCodeTargetTitle.isNotBlank()) {
                        Text(
                            text = qrCodeTargetTitle,
                            color = Color(0xFF94A3B8),
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    Box(
                        modifier = Modifier
                            .size(240.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White)
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.foundation.Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "QR Code",
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = qrCodeTargetUrl,
                        fontSize = 11.sp,
                        color = Color(0xFF64748B),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { showLiveQrCodeModal = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
                ) {
                    Text("Done")
                }
            },
            containerColor = Color(0xFF0F172A)
        )
    }
}

@Composable
fun LocationShareDialog(
    target: LocationShareTarget,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coordsStr = remember(target.latitude, target.longitude) {
        String.format(Locale.US, "%.5f, %.5f", target.latitude, target.longitude)
    }
    val googleMapsUrl = remember(coordsStr) { "https://maps.google.com/?q=$coordsStr" }
    val fullShareText = remember(target, coordsStr, googleMapsUrl) {
        val placeInfo = if (target.placeName.isNotBlank() && target.placeName != target.addressOrCoords) {
            "${target.placeName}\n${target.addressOrCoords}"
        } else {
            target.addressOrCoords
        }
        "${target.title}:\n$placeInfo\nCoordinates: $coordsStr\nMap: $googleMapsUrl"
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
            modifier = Modifier
                .widthIn(max = 540.dp)
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        EmojiText(emoji = "📍", fontSize = 22.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = target.title,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            style = TextStyle(
                                platformStyle = PlatformTextStyle(includeFontPadding = false),
                                lineHeightStyle = LineHeightStyle(
                                    alignment = LineHeightStyle.Alignment.Center,
                                    trim = LineHeightStyle.Trim.Both
                                ),
                                lineHeight = 18.sp
                            )
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1E293B))
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        if (target.placeName.isNotBlank()) {
                            Text("Location Name:", fontSize = 11.sp, color = Color(0xFF94A3B8))
                            Text(
                                text = target.placeName,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                        if (target.addressOrCoords.isNotBlank() && target.addressOrCoords != target.placeName) {
                            Text("Address / Hierarchy:", fontSize = 11.sp, color = Color(0xFF94A3B8))
                            Text(
                                text = target.addressOrCoords,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Normal,
                                color = Color(0xFFCBD5E1)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                        Text("GPS Coordinates:", fontSize = 11.sp, color = Color(0xFF94A3B8))
                        Text(
                            text = coordsStr,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF38BDF8)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Native Share Sheet
                Button(
                    onClick = {
                        val sendIntent = Intent().apply {
                            action = Intent.ACTION_SEND
                            putExtra(Intent.EXTRA_TEXT, fullShareText)
                            type = "text/plain"
                        }
                        context.startActivity(Intent.createChooser(sendIntent, target.title))
                        onDismiss()
                    },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Share, contentDescription = "Share", modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Share via App (WhatsApp, SMS, etc.)", fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Copy to Clipboard and Open in Maps
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            val clip = android.content.ClipData.newPlainText("Coordinates", coordsStr)
                            clipboard.setPrimaryClip(clip)
                            android.widget.Toast.makeText(context, "Coordinates copied: $coordsStr", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155), contentColor = Color.White),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy Coords", modifier = Modifier.size(16.dp), tint = Color(0xFF38BDF8))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Copy Coords", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    }

                    Button(
                        onClick = {
                            val mapIntent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:${target.latitude},${target.longitude}?q=${target.latitude},${target.longitude}(${Uri.encode(target.placeName)})"))
                            try {
                                context.startActivity(mapIntent)
                            } catch (_: Exception) {
                                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(googleMapsUrl))
                                context.startActivity(browserIntent)
                            }
                            onDismiss()
                        },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155), contentColor = Color.White),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Map, contentDescription = "Open Map", modifier = Modifier.size(16.dp), tint = Color(0xFF38BDF8))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Open Map", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileConfigSheet(
    profile: ActivityProfile,
    isCurrentActive: Boolean,
    viewModel: MainViewModel,
    onSetAsActive: () -> Unit,
    onDismiss: () -> Unit
) {
    val initialTripMode = remember(profile) { viewModel.getTripModeForProfile(profile) }
    var currentTripMode by remember(profile) { mutableStateOf(initialTripMode) }

    val initialAutoStartSpeed = remember(profile) { viewModel.getAutoStartSpeedForProfile(profile) }
    var autoStartSpeed by remember(profile) { mutableStateOf(initialAutoStartSpeed) }
    var showCustomSpeedDialog by remember { mutableStateOf(false) }
    var customSpeedInput by remember { mutableStateOf("") }
    var autoStartSpeedDropdownExpanded by remember { mutableStateOf(false) }

    val speedPresetOptions = remember(profile) {
        when (profile) {
            ActivityProfile.CAR -> listOf(5.0f to "5 km/h", 10.0f to "10 km/h (Default)", 15.0f to "15 km/h", 20.0f to "20 km/h", 25.0f to "25 km/h", 30.0f to "30 km/h")
            ActivityProfile.CYCLING -> listOf(3.0f to "3 km/h", 5.0f to "5 km/h", 7.0f to "7 km/h (Default)", 10.0f to "10 km/h", 12.0f to "12 km/h", 15.0f to "15 km/h")
            ActivityProfile.MTB -> listOf(3.0f to "3 km/h", 4.0f to "4 km/h", 6.0f to "6 km/h (Default)", 8.0f to "8 km/h", 10.0f to "10 km/h", 12.0f to "12 km/h")
            ActivityProfile.RUNNING -> listOf(3.0f to "3 km/h", 4.0f to "4 km/h", 5.0f to "5 km/h (Default)", 6.0f to "6 km/h", 8.0f to "8 km/h", 10.0f to "10 km/h")
            ActivityProfile.HIKING -> listOf(1.5f to "1.5 km/h", 2.0f to "2.0 km/h", 2.5f to "2.5 km/h (Default)", 3.0f to "3.0 km/h", 4.0f to "4.0 km/h", 5.0f to "5.0 km/h")
            ActivityProfile.WALKING -> listOf(1.5f to "1.5 km/h", 2.0f to "2.0 km/h", 2.5f to "2.5 km/h (Default)", 3.0f to "3.0 km/h", 4.0f to "4.0 km/h", 5.0f to "5.0 km/h")
        }
    }
    val isCustomSpeed = speedPresetOptions.none { kotlin.math.abs(it.first - autoStartSpeed) < 0.05f }

    val initialAutoStartSec = remember(profile) { viewModel.getAutoStartSecondsForProfile(profile) }
    var autoStartSec by remember(profile) { mutableStateOf(initialAutoStartSec) }
    var showCustomAutoStartDialog by remember { mutableStateOf(false) }
    var customAutoStartInput by remember { mutableStateOf("") }
    val autoStartPresetOptions = listOf(10 to "10s", 15 to "15s", 30 to "30s", 60 to "1m", 120 to "2m", 300 to "5m")
    val isCustomAutoStart = autoStartPresetOptions.none { it.first == autoStartSec }
    var autoStartDropdownExpanded by remember { mutableStateOf(false) }

    val initialAutoStopMin = remember(profile) { viewModel.getAutoStopMinutesForProfile(profile) }
    var autoStopMin by remember(profile) { mutableStateOf(initialAutoStopMin) }
    var showCustomAutoStopDialog by remember { mutableStateOf(false) }
    var customAutoStopInput by remember { mutableStateOf("") }
    val autoStopPresetOptions = listOf(1 to "1m", 3 to "3m", 5 to "5m", 10 to "10m", 15 to "15m", 20 to "20m")
    val isCustomAutoStop = autoStopPresetOptions.none { it.first == autoStopMin }
    var autoStopDropdownExpanded by remember { mutableStateOf(false) }

    val autoResumeOnMotion by viewModel.autoResumeOnMotion.collectAsState()
    val configSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = configSheetState,
        containerColor = Color(0xFF0F172A),
        tonalElevation = 8.dp
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .navigationBarsPadding()
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    EmojiText(emoji = profile.iconEmoji, fontSize = 24.sp)
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${profile.displayName} Profile",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            if (isCurrentActive) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = Color(0xFF0284C7)
                                ) {
                                    Text(
                                        text = "ACTIVE",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                        Text(
                            text = "Independent activity configuration",
                            fontSize = 12.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1E293B))
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            if (!isCurrentActive) {
                Spacer(modifier = Modifier.height(14.dp))
                Button(
                    onClick = {
                        onSetAsActive()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    EmojiText(emoji = profile.iconEmoji, fontSize = 16.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Set as Current Recording Profile", fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Trip Recording Mode (Manual vs Auto)
            Text(
                text = "Recording Mode",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val isManual = currentTripMode == TripMode.MANUAL
                Button(
                    onClick = {
                        currentTripMode = TripMode.MANUAL
                        viewModel.setTripModeForProfile(profile, TripMode.MANUAL)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isManual) Color(0xFF0284C7) else Color(0xFF1E293B),
                        contentColor = Color.White
                    ),
                    border = if (isManual) null else BorderStroke(1.dp, Color(0xFF475569)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "Manual",
                        color = Color.White,
                        fontWeight = if (isManual) FontWeight.Bold else FontWeight.Medium
                    )
                }
                val isAuto = currentTripMode == TripMode.AUTO
                Button(
                    onClick = {
                        currentTripMode = TripMode.AUTO
                        viewModel.setTripModeForProfile(profile, TripMode.AUTO)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isAuto) Color(0xFF0284C7) else Color(0xFF1E293B),
                        contentColor = Color.White
                    ),
                    border = if (isAuto) null else BorderStroke(1.dp, Color(0xFF475569)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "Automatic",
                        color = Color.White,
                        fontWeight = if (isAuto) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }

            if (currentTripMode == TripMode.AUTO) {
                val speedDisplay = if (autoStartSpeed % 1f == 0f) ">${autoStartSpeed.toInt()} km/h" else ">%.1f km/h".format(autoStartSpeed)
                val durDisplay = if (autoStartSec >= 60 && autoStartSec % 60 == 0) "${autoStartSec / 60}m" else "${autoStartSec}s"

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    color = Color(0xFF1E293B),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("⚡", fontSize = 14.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Auto-start: starts recording when speed exceeds $speedDisplay for $durDisplay (or moves >25m). Rest pauses detected after 45s stationary, auto-stops after ${autoStopMin}m.",
                            fontSize = 12.sp,
                            color = Color(0xFF94A3B8),
                            lineHeight = 16.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Auto-Start Speed Threshold
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Auto-Start Speed",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        modifier = Modifier.weight(1f).padding(end = 12.dp)
                    )

                    Box {
                        val selectedSpeedLabel = speedPresetOptions.firstOrNull { kotlin.math.abs(it.first - autoStartSpeed) < 0.05f }?.second
                            ?: (if (autoStartSpeed % 1f == 0f) "Custom: ${autoStartSpeed.toInt()} km/h" else "Custom: %.1f km/h".format(autoStartSpeed))
                        Surface(
                            onClick = { autoStartSpeedDropdownExpanded = true },
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFF1E293B),
                            border = BorderStroke(1.dp, Color(0xFF475569)),
                            modifier = Modifier.width(135.dp).height(40.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = selectedSpeedLabel,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White,
                                    maxLines = 1
                                )
                                Icon(
                                    imageVector = Icons.Default.ArrowDropDown,
                                    contentDescription = "Select Auto-Start Speed",
                                    tint = Color(0xFF38BDF8),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        DropdownMenu(
                            expanded = autoStartSpeedDropdownExpanded,
                            onDismissRequest = { autoStartSpeedDropdownExpanded = false },
                            modifier = Modifier.background(Color(0xFF0F172A))
                        ) {
                            speedPresetOptions.forEach { (spd, label) ->
                                val isMatch = kotlin.math.abs(spd - autoStartSpeed) < 0.05f
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = label,
                                            color = if (isMatch) Color(0xFF38BDF8) else Color.White,
                                            fontWeight = if (isMatch) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    onClick = {
                                        autoStartSpeed = spd
                                        viewModel.setAutoStartSpeedForProfile(profile, spd)
                                        autoStartSpeedDropdownExpanded = false
                                    }
                                )
                            }
                            HorizontalDivider(color = Color(0xFF334155))
                            DropdownMenuItem(
                                text = {
                                    val customLabel = if (isCustomSpeed) {
                                        if (autoStartSpeed % 1f == 0f) "Custom (${autoStartSpeed.toInt()} km/h)..." else "Custom (%.1f km/h)...".format(autoStartSpeed)
                                    } else "Custom..."
                                    Text(
                                        text = customLabel,
                                        color = if (isCustomSpeed) Color(0xFF38BDF8) else Color.White,
                                        fontWeight = if (isCustomSpeed) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                onClick = {
                                    autoStartSpeedDropdownExpanded = false
                                    customSpeedInput = if (autoStartSpeed % 1f == 0f) autoStartSpeed.toInt().toString() else "%.1f".format(autoStartSpeed)
                                    showCustomSpeedDialog = true
                                }
                            )
                        }
                    }
                }

                if (showCustomSpeedDialog) {
                    AlertDialog(
                        onDismissRequest = { showCustomSpeedDialog = false },
                        title = { Text("Custom Auto-Start Speed", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
                        text = {
                            Column {
                                Text("Enter minimum speed (1.0 to 150.0 km/h) for ${profile.displayName}:", color = Color(0xFFCBD5E1), fontSize = 13.sp)
                                Spacer(modifier = Modifier.height(10.dp))
                                OutlinedTextField(
                                    value = customSpeedInput,
                                    onValueChange = { input ->
                                        if (input.count { it == '.' || it == ',' } <= 1 && input.all { it.isDigit() || it == '.' || it == ',' } && input.length <= 6) {
                                            customSpeedInput = input
                                        }
                                    },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White,
                                        focusedBorderColor = Color(0xFF38BDF8),
                                        unfocusedBorderColor = Color(0xFF64748B)
                                    ),
                                    suffix = { Text("km/h", color = Color(0xFF94A3B8)) }
                                )
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    val normalized = customSpeedInput.replace(',', '.')
                                    val speedVal = normalized.toFloatOrNull()?.coerceIn(1.0f, 150.0f) ?: profile.autoStartSpeedKmh
                                    autoStartSpeed = speedVal
                                    viewModel.setAutoStartSpeedForProfile(profile, speedVal)
                                    showCustomSpeedDialog = false
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
                            ) {
                                Text("Set")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showCustomSpeedDialog = false }) {
                                Text("Cancel", color = Color.White)
                            }
                        },
                        containerColor = Color(0xFF0F172A)
                    )
                }

                // Auto-Start Delay
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Auto-Start Delay",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        modifier = Modifier.weight(1f).padding(end = 12.dp)
                    )

                    Box {
                        val selectedLabel = autoStartPresetOptions.firstOrNull { it.first == autoStartSec }?.second
                            ?: (if (autoStartSec >= 60 && autoStartSec % 60 == 0) "Custom: ${autoStartSec / 60}m" else "Custom: ${autoStartSec}s")
                        Surface(
                            onClick = { autoStartDropdownExpanded = true },
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFF1E293B),
                            border = BorderStroke(1.dp, Color(0xFF475569)),
                            modifier = Modifier.width(115.dp).height(40.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = selectedLabel,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White,
                                    maxLines = 1
                                )
                                Icon(
                                    imageVector = Icons.Default.ArrowDropDown,
                                    contentDescription = "Select Auto-Start Delay",
                                    tint = Color(0xFF38BDF8),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        DropdownMenu(
                            expanded = autoStartDropdownExpanded,
                            onDismissRequest = { autoStartDropdownExpanded = false },
                            modifier = Modifier.background(Color(0xFF0F172A))
                        ) {
                            autoStartPresetOptions.forEach { (sec, label) ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = label,
                                            color = if (autoStartSec == sec) Color(0xFF38BDF8) else Color.White,
                                            fontWeight = if (autoStartSec == sec) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    onClick = {
                                        autoStartSec = sec
                                        viewModel.setAutoStartSecondsForProfile(profile, sec)
                                        autoStartDropdownExpanded = false
                                    }
                                )
                            }
                            HorizontalDivider(color = Color(0xFF334155))
                            DropdownMenuItem(
                                text = {
                                    val customLabel = if (isCustomAutoStart) "Custom (${if (autoStartSec >= 60) "${autoStartSec / 60}m" else "${autoStartSec}s"})..." else "Custom..."
                                    Text(
                                        text = customLabel,
                                        color = if (isCustomAutoStart) Color(0xFF38BDF8) else Color.White,
                                        fontWeight = if (isCustomAutoStart) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                onClick = {
                                    autoStartDropdownExpanded = false
                                    customAutoStartInput = if (autoStartSec >= 60) (autoStartSec / 60).toString() else "1"
                                    showCustomAutoStartDialog = true
                                }
                            )
                        }
                    }
                }

                if (showCustomAutoStartDialog) {
                    AlertDialog(
                        onDismissRequest = { showCustomAutoStartDialog = false },
                        title = { Text("Custom Auto-Start Delay", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
                        text = {
                            Column {
                                Text("Enter movement delay in minutes (1 to 1440 min / max 24h) for ${profile.displayName}:", color = Color(0xFFCBD5E1), fontSize = 13.sp)
                                Spacer(modifier = Modifier.height(10.dp))
                                OutlinedTextField(
                                    value = customAutoStartInput,
                                    onValueChange = { input ->
                                        if (input.all { it.isDigit() } && input.length <= 5) {
                                            customAutoStartInput = input
                                        }
                                    },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White,
                                        focusedBorderColor = Color(0xFF38BDF8),
                                        unfocusedBorderColor = Color(0xFF64748B)
                                    ),
                                    suffix = { Text("min", color = Color(0xFF94A3B8)) }
                                )
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    val minVal = customAutoStartInput.toIntOrNull()?.coerceIn(1, 1440) ?: 1
                                    val secVal = minVal * 60
                                    autoStartSec = secVal
                                    viewModel.setAutoStartSecondsForProfile(profile, secVal)
                                    showCustomAutoStartDialog = false
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
                            ) {
                                Text("Set")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showCustomAutoStartDialog = false }) {
                                Text("Cancel", color = Color.White)
                            }
                        },
                        containerColor = Color(0xFF0F172A)
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = "Auto-resume on motion when paused",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                        Text(
                            text = "Automatically continues recording if motion detected while paused",
                            fontSize = 11.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }
                    Switch(
                        checked = autoResumeOnMotion,
                        onCheckedChange = { viewModel.setAutoResumeOnMotion(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFF0284C7),
                            uncheckedThumbColor = Color(0xFF94A3B8),
                            uncheckedTrackColor = Color(0xFF334155)
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Auto-Stop Stationary Timeout
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Auto-Stop Timeout",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    modifier = Modifier.weight(1f).padding(end = 12.dp)
                )

                Box {
                    val selectedLabel = autoStopPresetOptions.firstOrNull { it.first == autoStopMin }?.second
                        ?: "Custom: ${autoStopMin}m"
                    Surface(
                        onClick = { autoStopDropdownExpanded = true },
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF1E293B),
                        border = BorderStroke(1.dp, Color(0xFF475569)),
                        modifier = Modifier.width(115.dp).height(40.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = selectedLabel,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                                maxLines = 1
                            )
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = "Select Auto-Stop Timeout",
                                tint = Color(0xFF10B981),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = autoStopDropdownExpanded,
                        onDismissRequest = { autoStopDropdownExpanded = false },
                        modifier = Modifier.background(Color(0xFF0F172A))
                    ) {
                        autoStopPresetOptions.forEach { (mins, label) ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = label,
                                        color = if (autoStopMin == mins) Color(0xFF10B981) else Color.White,
                                        fontWeight = if (autoStopMin == mins) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                onClick = {
                                    autoStopMin = mins
                                    viewModel.setAutoStopMinutesForProfile(profile, mins)
                                    autoStopDropdownExpanded = false
                                }
                            )
                        }
                        HorizontalDivider(color = Color(0xFF334155))
                        DropdownMenuItem(
                            text = {
                                val customLabel = if (isCustomAutoStop) "Custom (${autoStopMin}m)..." else "Custom..."
                                Text(
                                    text = customLabel,
                                    color = if (isCustomAutoStop) Color(0xFF10B981) else Color.White,
                                    fontWeight = if (isCustomAutoStop) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            onClick = {
                                autoStopDropdownExpanded = false
                                customAutoStopInput = autoStopMin.toString()
                                showCustomAutoStopDialog = true
                            }
                        )
                    }
                }
            }

            if (showCustomAutoStopDialog) {
                AlertDialog(
                    onDismissRequest = { showCustomAutoStopDialog = false },
                    title = { Text("Custom Auto-Stop Timeout", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
                    text = {
                        Column {
                            Text("Enter stationary timeout in minutes (1 to 1440 min / max 24h) for ${profile.displayName}:", color = Color(0xFFCBD5E1), fontSize = 13.sp)
                            Spacer(modifier = Modifier.height(10.dp))
                            OutlinedTextField(
                                value = customAutoStopInput,
                                onValueChange = { input ->
                                    if (input.all { it.isDigit() } && input.length <= 5) {
                                        customAutoStopInput = input
                                    }
                                },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = Color(0xFF38BDF8),
                                    unfocusedBorderColor = Color(0xFF64748B)
                                ),
                                suffix = { Text("min", color = Color(0xFF94A3B8)) }
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                val minVal = customAutoStopInput.toIntOrNull()?.coerceIn(1, 1440) ?: 5
                                autoStopMin = minVal
                                viewModel.setAutoStopMinutesForProfile(profile, minVal)
                                showCustomAutoStopDialog = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
                        ) {
                            Text("Set")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showCustomAutoStopDialog = false }) {
                            Text("Cancel", color = Color.White)
                        }
                    },
                    containerColor = Color(0xFF0F172A)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun HeatMapSettingsDialog(
    filterState: HeatMapFilterState,
    onApplyFilter: (HeatMapFilterState) -> Unit,
    onResetFilter: () -> Unit,
    onDismiss: () -> Unit
) {
    var selectedPeriod by remember(filterState.datePeriod) { mutableStateOf(filterState.datePeriod) }
    var selectedProfiles by remember(filterState.activityProfiles) { mutableStateOf(filterState.activityProfiles) }
    var selectedMinVisits by remember(filterState.minVisits) { mutableIntStateOf(filterState.minVisits) }
    var consolidate by remember(filterState.consolidateCorridors) { mutableStateOf(filterState.consolidateCorridors) }
    var includeActive by remember(filterState.includeActiveTrip) { mutableStateOf(filterState.includeActiveTrip) }

    var dateDropdownExpanded by remember { mutableStateOf(false) }
    var activityDropdownExpanded by remember { mutableStateOf(false) }
    var densityDropdownExpanded by remember { mutableStateOf(false) }

    val periodLabels = mapOf(
        "ALL" to "All Time",
        "TODAY" to "Today",
        "WEEK" to "This Week",
        "MONTH" to "This Month",
        "YEAR" to "This Year"
    )

    val densityLabels = mapOf(
        1 to "All Passes (1+)",
        2 to "Frequent Passes (2+)",
        4 to "High Traffic (4+)",
        7 to "Hotspots Only (7+)"
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
            border = BorderStroke(1.dp, Color(0xFFF97316)),
            modifier = Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Whatshot,
                            contentDescription = null,
                            tint = Color(0xFFF97316),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Heat Map Controls",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.LightGray,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Text(
                    text = "Configure thermal intensity, consolidated corridors, and trip filters.",
                    fontSize = 12.sp,
                    color = Color(0xFF94A3B8),
                    modifier = Modifier.padding(top = 4.dp, bottom = 14.dp)
                )

                // 1. Date Period Filter Dropdown
                Text(
                    text = "DATE PERIOD",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFF97316),
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Box(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF1E293B))
                            .clickable { dateDropdownExpanded = true }
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = periodLabels[selectedPeriod] ?: "All Time",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = null,
                                tint = Color(0xFFCBD5E1),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    DropdownMenu(
                        expanded = dateDropdownExpanded,
                        onDismissRequest = { dateDropdownExpanded = false },
                        modifier = Modifier
                            .background(Color(0xFF1E293B))
                            .widthIn(min = 200.dp)
                    ) {
                        periodLabels.forEach { (code, label) ->
                            val isSelected = selectedPeriod == code
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = label,
                                            color = if (isSelected) Color(0xFFF97316) else Color.White,
                                            fontSize = 13.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                        )
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null,
                                                tint = Color(0xFFF97316),
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    selectedPeriod = code
                                    dateDropdownExpanded = false
                                    onApplyFilter(
                                        filterState.copy(
                                            datePeriod = code,
                                            activityProfiles = selectedProfiles,
                                            minVisits = selectedMinVisits,
                                            consolidateCorridors = consolidate,
                                            includeActiveTrip = includeActive
                                        )
                                    )
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 2. Activity Mode Multi-Select Dropdown
                Text(
                    text = "ACTIVITY MODES (MULTI-SELECT)",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFF97316),
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Box(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF1E293B))
                            .clickable { activityDropdownExpanded = true }
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (selectedProfiles.isEmpty()) {
                                Text(
                                    text = "All Modes",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            } else if (selectedProfiles.size == 1) {
                                val single = selectedProfiles.first()
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    EmojiText(emoji = single.iconEmoji, fontSize = 14.sp)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = single.displayName,
                                        color = Color(0xFFF97316),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = TextStyle(
                                            platformStyle = PlatformTextStyle(includeFontPadding = false),
                                            lineHeightStyle = LineHeightStyle(
                                                alignment = LineHeightStyle.Alignment.Center,
                                                trim = LineHeightStyle.Trim.Both
                                            ),
                                            lineHeight = 13.sp
                                        )
                                    )
                                }
                            } else {
                                Text(
                                    text = selectedProfiles.joinToString(", ") { "${it.iconEmoji} ${it.displayName}" },
                                    color = Color(0xFFF97316),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = null,
                                tint = Color(0xFFCBD5E1),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    DropdownMenu(
                        expanded = activityDropdownExpanded,
                        onDismissRequest = { activityDropdownExpanded = false },
                        modifier = Modifier
                            .background(Color(0xFF1E293B))
                            .widthIn(min = 240.dp)
                    ) {
                        // "All Modes" option
                        DropdownMenuItem(
                            text = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "All Modes",
                                        color = if (selectedProfiles.isEmpty()) Color(0xFFF97316) else Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = if (selectedProfiles.isEmpty()) FontWeight.Bold else FontWeight.Normal
                                    )
                                    if (selectedProfiles.isEmpty()) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = Color(0xFFF97316),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            },
                            onClick = {
                                selectedProfiles = emptySet()
                                onApplyFilter(
                                    filterState.copy(
                                        activityProfiles = emptySet(),
                                        datePeriod = selectedPeriod,
                                        minVisits = selectedMinVisits,
                                        consolidateCorridors = consolidate,
                                        includeActiveTrip = includeActive
                                    )
                                )
                            }
                        )

                        ActivityProfile.values().forEach { profile ->
                            val isChecked = selectedProfiles.contains(profile)
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            EmojiText(emoji = profile.iconEmoji, fontSize = 14.sp)
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = profile.displayName,
                                                color = if (isChecked) Color(0xFFF97316) else Color.White,
                                                fontSize = 13.sp,
                                                fontWeight = if (isChecked) FontWeight.Bold else FontWeight.Normal,
                                                style = TextStyle(
                                                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                                                    lineHeightStyle = LineHeightStyle(
                                                        alignment = LineHeightStyle.Alignment.Center,
                                                        trim = LineHeightStyle.Trim.Both
                                                    ),
                                                    lineHeight = 13.sp
                                                )
                                            )
                                        }
                                        Checkbox(
                                            checked = isChecked,
                                            onCheckedChange = null,
                                            colors = CheckboxDefaults.colors(
                                                checkedColor = Color(0xFFF97316),
                                                uncheckedColor = Color(0xFF64748B),
                                                checkmarkColor = Color.White
                                            ),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                },
                                onClick = {
                                    val newProfiles = if (isChecked) {
                                        selectedProfiles - profile
                                    } else {
                                        selectedProfiles + profile
                                    }
                                    selectedProfiles = newProfiles
                                    onApplyFilter(
                                        filterState.copy(
                                            activityProfiles = newProfiles,
                                            datePeriod = selectedPeriod,
                                            minVisits = selectedMinVisits,
                                            consolidateCorridors = consolidate,
                                            includeActiveTrip = includeActive
                                        )
                                    )
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 3. Minimum Route Density Dropdown
                Text(
                    text = "MINIMUM ROUTE VISITS (DENSITY)",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFF97316),
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Box(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF1E293B))
                            .clickable { densityDropdownExpanded = true }
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = densityLabels[selectedMinVisits] ?: "All Passes (1+)",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = null,
                                tint = Color(0xFFCBD5E1),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    DropdownMenu(
                        expanded = densityDropdownExpanded,
                        onDismissRequest = { densityDropdownExpanded = false },
                        modifier = Modifier
                            .background(Color(0xFF1E293B))
                            .widthIn(min = 220.dp)
                    ) {
                        densityLabels.forEach { (thresh, label) ->
                            val isSelected = selectedMinVisits == thresh
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = label,
                                            color = if (isSelected) Color(0xFFF97316) else Color.White,
                                            fontSize = 13.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                        )
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null,
                                                tint = Color(0xFFF97316),
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    selectedMinVisits = thresh
                                    densityDropdownExpanded = false
                                    onApplyFilter(
                                        filterState.copy(
                                            minVisits = thresh,
                                            datePeriod = selectedPeriod,
                                            activityProfiles = selectedProfiles,
                                            consolidateCorridors = consolidate,
                                            includeActiveTrip = includeActive
                                        )
                                    )
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 4. Rendering Options (Consolidation & Active Trip)
                Text(
                    text = "DISPLAY OPTIONS",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFF97316),
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(6.dp))

                // Switch: Consolidate corridors
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF1E293B))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                text = "Consolidate overlapping corridors",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                            Text(
                                text = "Merges duplicate GPS tracks along the same street into a single bold thermal backbone.",
                                fontSize = 11.sp,
                                color = Color(0xFF94A3B8)
                            )
                        }
                        Switch(
                            checked = consolidate,
                            onCheckedChange = {
                                consolidate = it
                                onApplyFilter(
                                    filterState.copy(
                                        consolidateCorridors = it,
                                        datePeriod = selectedPeriod,
                                        activityProfiles = selectedProfiles,
                                        minVisits = selectedMinVisits,
                                        includeActiveTrip = includeActive
                                    )
                                )
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color(0xFFF97316),
                                uncheckedThumbColor = Color(0xFF94A3B8),
                                uncheckedTrackColor = Color(0xFF334155)
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Switch: Include active trip
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF1E293B))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                text = "Include today's active recording",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                            Text(
                                text = "Instantly reflects currently recorded GPS points on the heat map before saving.",
                                fontSize = 11.sp,
                                color = Color(0xFF94A3B8)
                            )
                        }
                        Switch(
                            checked = includeActive,
                            onCheckedChange = {
                                includeActive = it
                                onApplyFilter(
                                    filterState.copy(
                                        includeActiveTrip = it,
                                        datePeriod = selectedPeriod,
                                        activityProfiles = selectedProfiles,
                                        minVisits = selectedMinVisits,
                                        consolidateCorridors = consolidate
                                    )
                                )
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color(0xFFF97316),
                                uncheckedThumbColor = Color(0xFF94A3B8),
                                uncheckedTrackColor = Color(0xFF334155)
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 5. Thermal Scale Legend
                Text(
                    text = "THERMAL INTENSITY SCALE (7 LEVELS)",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFF97316),
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(14.dp)
                        .clip(RoundedCornerShape(7.dp))
                ) {
                    val tierColors = listOf(
                        Color(0xFF2563EB), // 1 Blue
                        Color(0xFF06B6D4), // 2 Cyan
                        Color(0xFF10B981), // 3 Green
                        Color(0xFFEAB308), // 4 Yellow
                        Color(0xFFF97316), // 5 Orange
                        Color(0xFFEF4444), // 6 Red
                        Color(0xFFD946EF)  // 7 Magenta
                    )
                    tierColors.forEach { c ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .background(c)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("1 Pass (Cold)", fontSize = 10.sp, color = Color(0xFF60A5FA))
                    Text("Moderate", fontSize = 10.sp, color = Color(0xFFEAB308))
                    Text("Hotspot (20+)", fontSize = 10.sp, color = Color(0xFFF472B6), fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Bottom Buttons: Reset & Done
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (filterState.hasActiveFilter) {
                        TextButton(
                            onClick = {
                                onResetFilter()
                                selectedPeriod = "ALL"
                                selectedProfiles = emptySet()
                                selectedMinVisits = 1
                                consolidate = true
                                includeActive = true
                            }
                        ) {
                            Text("Reset Defaults", color = Color(0xFFEF4444), fontSize = 13.sp)
                        }
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }

                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF97316)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Apply & Close", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/**
 * Calculates realistic visited locality counts across trips using a journey transition model:
 * 1. Chronologically sorts trips by start time.
 * 2. Filters out stationary test trips (< 100 meters).
 * 3. Sanitizes intra-trip visited places by collapsing consecutive duplicates and filtering micro-ping-pong border bounces.
 * 4. Tracks cross-trip locality continuity: starting/stopping multiple trips in the same locality without traveling to another
 *    does NOT count as a new visit.
 */
fun computeRealisticVisitCounts(trips: List<TripRecord>): List<Pair<String, Int>> {
    val sortedTrips = trips.sortedBy { it.startTime }
    var lastKnownLocality: String? = null
    val visitCounts = mutableMapOf<String, Int>()

    for (trip in sortedTrips) {
        // Skip stationary trips under 100m
        if (trip.distanceMeters < 100.0) continue
        if (trip.placesVisited.isEmpty()) continue

        // Extract sequence of valid place names
        val rawNames = trip.placesVisited
            .map { it.placeName.trim() }
            .filter { it.isNotBlank() && it != "Unknown City" && it != "--" }

        if (rawNames.isEmpty()) continue

        // Collapse consecutive duplicates
        val deduplicated = mutableListOf<String>()
        for (name in rawNames) {
            if (deduplicated.isEmpty() || !deduplicated.last().equals(name, ignoreCase = true)) {
                deduplicated.add(name)
            }
        }

        // Filter transit border ping-pong bounces: A -> B -> A where B is transiently touched along border
        val cleanedSequence = mutableListOf<String>()
        for (i in deduplicated.indices) {
            val name = deduplicated[i]
            if (i > 0 && i < deduplicated.size - 1) {
                val prev = deduplicated[i - 1]
                val next = deduplicated[i + 1]
                if (prev.equals(next, ignoreCase = true) && !name.equals(prev, ignoreCase = true)) {
                    // Transient border oscillation
                    continue
                }
            }
            if (cleanedSequence.isEmpty() || !cleanedSequence.last().equals(name, ignoreCase = true)) {
                cleanedSequence.add(name)
            }
        }

        // Count transitions: user must actually transition into a different locality
        for (name in cleanedSequence) {
            if (lastKnownLocality == null || !lastKnownLocality.equals(name, ignoreCase = true)) {
                visitCounts[name] = (visitCounts[name] ?: 0) + 1
                lastKnownLocality = name
            }
        }
    }

    return visitCounts.toList().sortedByDescending { it.second }
}

@Composable
private fun LocalityCard(
    locationData: LocationData,
    activeTrip: TripRecord?,
    liveSession: LiveSession?,
    savedPlaces: List<SavedPlace>,
    localityCardStyle: LocalityCardStyle,
    compactStreetNames: Boolean = false,
    activityProfile: ActivityProfile,
    primaryPlace: PlaceInfo?,
    secondaryPlace: PlaceInfo?,
    hierarchySubtitle: String,
    currentLatLng: Triple<Double, Double, Float?>?,
    onShowActiveTripRoute: () -> Unit,
    onShowLiveShare: () -> Unit,
    onOpenSavedPlaces: () -> Unit = {},
    onSetLocalityCardStyle: (LocalityCardStyle) -> Unit,
    onSetActivityProfile: (ActivityProfile) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isCompact = localityCardStyle == LocalityCardStyle.COMPACT

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(if (isCompact) 14.dp else 22.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xEE0F172A)),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = if (isCompact) 12.dp else 16.dp, vertical = if (isCompact) 6.dp else 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            var tickerNow by remember { mutableStateOf(System.currentTimeMillis()) }
            LaunchedEffect(liveSession?.isActive) {
                if (liveSession?.isActive == true) {
                    while (true) {
                        tickerNow = System.currentTimeMillis()
                        kotlinx.coroutines.delay(5000L)
                    }
                }
            }
            val liveTimerText = remember(tickerNow, liveSession?.expiresAt, liveSession?.createdAt) {
                if (liveSession == null) ""
                else if (liveSession.expiresAt <= 0L) {
                    val elapsed = maxOf(0L, tickerNow - liveSession.createdAt)
                    val hrs = elapsed / 3600000L
                    val mins = (elapsed % 3600000L) / 60000L
                    if (hrs > 0) "${hrs}h ${mins}m" else "${mins}m"
                } else {
                    val rem = maxOf(0L, liveSession.expiresAt - tickerNow)
                    if (rem <= 0L) "Exp"
                    else {
                        val hrs = rem / 3600000L
                        val mins = (rem % 3600000L) / 60000L
                        if (hrs > 0) "${hrs}h ${mins}m" else "${mins}m"
                    }
                }
            }

            if (locationData.isLoading && primaryPlace == null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(vertical = if (isCompact) 4.dp else 8.dp)
                ) {
                    CircularProgressIndicator(
                        color = Color(0xFF38BDF8),
                        modifier = Modifier.size(if (isCompact) 16.dp else 20.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Finding your location...", color = Color.LightGray, fontSize = if (isCompact) 13.sp else 15.sp)
                }
            } else if (locationData.error != null && primaryPlace == null) {
                Text(
                    text = "Error: ${locationData.error}",
                    color = Color(0xFFF87171),
                    textAlign = TextAlign.Center,
                    fontSize = if (isCompact) 13.sp else 15.sp
                )
            } else if (isCompact) {
                // ══════════════════════════════════════════════════════════════
                // TRUE COMPACT MODE (3 Clean Non-Truncating Rows, Full Detail, ~75dp)
                // ══════════════════════════════════════════════════════════════
                val primaryCity = primaryPlace?.city ?: "Unknown"
                val secondaryCity = secondaryPlace?.city
                val displayCity = if (!secondaryCity.isNullOrEmpty() && !secondaryCity.equals(primaryCity, ignoreCase = true)) {
                    "$primaryCity ($secondaryCity)"
                } else primaryCity

                val rawStreet = primaryPlace?.street
                val effectiveStreet = if (compactStreetNames) {
                    StreetAbbreviator.abbreviate(rawStreet, primaryPlace?.countryCode)
                } else rawStreet

                val streetOrRoad = listOfNotNull(
                    effectiveStreet?.takeIf { it.isNotBlank() },
                    primaryPlace?.roadRef?.takeIf { it.isNotBlank() }
                ).joinToString(" • ")

                // Check if current location matches any saved place
                val nearbySavedPlace = remember(currentLatLng, savedPlaces) {
                    val lat = currentLatLng?.first ?: return@remember null
                    val lng = currentLatLng?.second ?: return@remember null
                    savedPlaces.firstOrNull { sp ->
                        val results = FloatArray(1)
                        android.location.Location.distanceBetween(lat, lng, sp.latitude, sp.longitude, results)
                        results[0] <= sp.radiusMeters
                    }
                }

                val speedKmh = (locationData.speedMs ?: 0f) * 3.6f
                val speedStr = String.format(Locale.getDefault(), "%.1f km/h", speedKmh)
                val paceStr = if (speedKmh > 1.0f) {
                    val min = (60f / speedKmh).toInt()
                    val sec = ((60f / speedKmh - min) * 60).toInt()
                    String.format(Locale.getDefault(), "%d:%02d min/km", min, sec)
                } else "- min/km"

                var profileMenuExpanded by remember { mutableStateOf(false) }

                // ── Row 1: Primary Locality Name + Badges on Left, Action Buttons on Right ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clickable(enabled = activeTrip != null) {
                                onShowActiveTripRoute()
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = displayCity,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Compact Action Icons: Google Maps, Live Sharing, Saved Place, Expand
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Open in Google Maps
                        IconButton(
                            onClick = {
                                val lat = currentLatLng?.first
                                val lng = currentLatLng?.second
                                if (lat != null && lng != null) {
                                    openInGoogleMaps(context, lat, lng, primaryPlace?.city ?: "")
                                }
                            },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Map,
                                contentDescription = "Open in Google Maps",
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // Live Sharing Satellite Icon / Pill
                        if (liveSession?.isActive == true) {
                            Surface(
                                onClick = onShowLiveShare,
                                shape = RoundedCornerShape(12.dp),
                                color = if (liveSession.isPaused) Color(0x33F59E0B) else Color(0x3310B981),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .padding(horizontal = 8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Sensors,
                                        contentDescription = "Live Sharing Active",
                                        tint = if (liveSession.isPaused) Color(0xFFF59E0B) else Color(0xFF10B981),
                                        modifier = Modifier.size(18.dp)
                                    )
                                    if (liveTimerText.isNotEmpty()) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = liveTimerText,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (liveSession.isPaused) Color(0xFFF59E0B) else Color(0xFF10B981),
                                            style = TextStyle(
                                                platformStyle = PlatformTextStyle(includeFontPadding = false),
                                                lineHeightStyle = LineHeightStyle(
                                                    alignment = LineHeightStyle.Alignment.Center,
                                                    trim = LineHeightStyle.Trim.Both
                                                ),
                                                lineHeight = 11.sp
                                            )
                                        )
                                        if (liveSession.viewCount > 0) {
                                            Spacer(modifier = Modifier.width(3.dp))
                                            Text(
                                                text = "•",
                                                fontSize = 11.sp,
                                                color = if (liveSession.isPaused) Color(0xFFF59E0B) else Color(0xFF10B981)
                                            )
                                            Spacer(modifier = Modifier.width(3.dp))
                                            EmojiText(emoji = "👥", fontSize = 11.sp)
                                            Spacer(modifier = Modifier.width(2.dp))
                                            Text(
                                                text = "${liveSession.viewCount}",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (liveSession.isPaused) Color(0xFFF59E0B) else Color(0xFF10B981),
                                                style = TextStyle(
                                                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                                                    lineHeightStyle = LineHeightStyle(
                                                        alignment = LineHeightStyle.Alignment.Center,
                                                        trim = LineHeightStyle.Trim.Both
                                                    ),
                                                    lineHeight = 11.sp
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            // Permanent Live Sharing Transmission Icon when stopped / inactive (1-tap to start)
                            IconButton(
                                onClick = onShowLiveShare,
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Sensors,
                                    contentDescription = "Start Live Sharing",
                                    tint = Color(0xFF38BDF8),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        // Saved Place Indicator (Clickable to open Places tab)
                        if (nearbySavedPlace != null) {
                            Surface(
                                onClick = onOpenSavedPlaces,
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0x3310B981),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .padding(horizontal = 8.dp)
                                ) {
                                    EmojiText(
                                        emoji = nearbySavedPlace.category.iconEmoji,
                                        fontSize = 15.sp
                                    )
                                }
                            }
                        }

                        // Direct Expand Toggle (switches to Normal mode)
                        IconButton(
                            onClick = { onSetLocalityCardStyle(LocalityCardStyle.NORMAL) },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ExpandMore,
                                contentDescription = "Expand Locality Card",
                                tint = Color(0xFFCBD5E1),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }

                // ── Row 2: Dedicated Full-Width Street Name & Number (NEVER Truncated) ──
                if (streetOrRoad.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 1.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        EmojiText(emoji = "📍", fontSize = 13.sp)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = streetOrRoad,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFFBBF24), // Vivid Amber Gold
                            maxLines = 2,
                            style = TextStyle(
                                platformStyle = PlatformTextStyle(includeFontPadding = false),
                                lineHeightStyle = LineHeightStyle(
                                    alignment = LineHeightStyle.Alignment.Center,
                                    trim = LineHeightStyle.Trim.Both
                                ),
                                lineHeight = 13.sp
                            )
                        )
                    }
                }

                // ── Row 3: Administrative Hierarchy & Country on Left, Profile + Speed Pill on Right ──
                val fullHierarchyText = buildString {
                    if (hierarchySubtitle.isNotEmpty()) append(hierarchySubtitle)
                    val country = primaryPlace?.country
                    if (!country.isNullOrBlank() && country != "Unknown Country") {
                        if (isNotEmpty()) append(" • ")
                        append(country)
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (fullHierarchyText.isNotEmpty()) fullHierarchyText else (primaryPlace?.country ?: ""),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Normal,
                        color = Color(0xFF94A3B8),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    // Visited Places Badge (Compact Mode)
                    if (activeTrip != null) {
                        Surface(
                            onClick = onShowActiveTripRoute,
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0x330284C7),
                            border = BorderStroke(1.dp, Color(0x5538BDF8))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                EmojiText(emoji = "🚩", fontSize = 11.sp, yOffsetDp = 0.dp)
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = "${activeTrip.placesVisited.size}",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF38BDF8),
                                    softWrap = false,
                                    style = TextStyle(
                                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                                        lineHeightStyle = LineHeightStyle(
                                            alignment = LineHeightStyle.Alignment.Center,
                                            trim = LineHeightStyle.Trim.Both
                                        ),
                                        lineHeight = 11.sp
                                    )
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                    }

                    // Profile & Speed Pill
                    Box {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF1E293B))
                                .clickable { profileMenuExpanded = true }
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            EmojiText(
                                emoji = activityProfile.iconEmoji,
                                fontSize = 11.sp
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            val metricText = when (activityProfile) {
                                ActivityProfile.CAR, ActivityProfile.CYCLING, ActivityProfile.MTB -> speedStr
                                ActivityProfile.WALKING, ActivityProfile.RUNNING, ActivityProfile.HIKING -> paceStr
                            }
                            Text(
                                text = metricText,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF38BDF8),
                                style = TextStyle(
                                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                                    lineHeightStyle = LineHeightStyle(
                                        alignment = LineHeightStyle.Alignment.Center,
                                        trim = LineHeightStyle.Trim.Both
                                    ),
                                    lineHeight = 11.sp
                                )
                            )
                        }

                        DropdownMenu(
                            expanded = profileMenuExpanded,
                            onDismissRequest = { profileMenuExpanded = false },
                            modifier = Modifier.background(Color(0xFF0F172A))
                        ) {
                            ActivityProfile.values().forEach { profile ->
                                val isSelected = activityProfile == profile
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            EmojiText(emoji = profile.iconEmoji, fontSize = 13.sp)
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = profile.displayName,
                                                color = if (isSelected) Color(0xFF38BDF8) else Color.White,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                fontSize = 13.sp,
                                                style = TextStyle(
                                                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                                                    lineHeightStyle = LineHeightStyle(
                                                        alignment = LineHeightStyle.Alignment.Center,
                                                        trim = LineHeightStyle.Trim.Both
                                                    ),
                                                    lineHeight = 13.sp
                                                )
                                            )
                                        }
                                    },
                                    onClick = {
                                        onSetActivityProfile(profile)
                                        profileMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            } else {
                // ══════════════════════════════════════════════════════════════
                // NORMAL SPATIOUS MODE
                // ══════════════════════════════════════════════════════════════
                val primaryCity = primaryPlace?.city ?: "Unknown"
                val secondaryCity = secondaryPlace?.city

                // Check if current location matches any saved place
                val nearbySavedPlace = remember(currentLatLng, savedPlaces) {
                    val lat = currentLatLng?.first ?: return@remember null
                    val lng = currentLatLng?.second ?: return@remember null
                    savedPlaces.firstOrNull { sp ->
                        val results = FloatArray(1)
                        android.location.Location.distanceBetween(lat, lng, sp.latitude, sp.longitude, results)
                        results[0] <= sp.radiusMeters
                    }
                }

                // Top Utilities Row: Maps & Live Badge on Left, Saved Place / Collapse on Right
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // ── Left Utility Icons: Google Maps, Live Share Satellite / Pill ──
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Open in Google Maps
                        IconButton(
                            onClick = {
                                val lat = currentLatLng?.first
                                val lng = currentLatLng?.second
                                if (lat != null && lng != null) {
                                    openInGoogleMaps(context, lat, lng, primaryPlace?.city ?: "")
                                }
                            },
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF1E293B))
                        ) {
                            Icon(
                                imageVector = Icons.Default.Map,
                                contentDescription = "Open in Google Maps",
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        // Live Sharing Satellite Icon / Pill
                        if (liveSession?.isActive == true) {
                            Surface(
                                onClick = onShowLiveShare,
                                shape = RoundedCornerShape(20.dp),
                                color = if (liveSession.isPaused) Color(0x33F59E0B) else Color(0x3310B981),
                                modifier = Modifier.height(40.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .padding(horizontal = 10.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Sensors,
                                        contentDescription = "Live Sharing Active",
                                        tint = if (liveSession.isPaused) Color(0xFFF59E0B) else Color(0xFF10B981),
                                        modifier = Modifier.size(20.dp)
                                    )
                                    if (liveTimerText.isNotEmpty()) {
                                        Spacer(modifier = Modifier.width(5.dp))
                                        Text(
                                            text = liveTimerText,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (liveSession.isPaused) Color(0xFFF59E0B) else Color(0xFF10B981),
                                            style = TextStyle(
                                                platformStyle = PlatformTextStyle(includeFontPadding = false),
                                                lineHeightStyle = LineHeightStyle(
                                                    alignment = LineHeightStyle.Alignment.Center,
                                                    trim = LineHeightStyle.Trim.Both
                                                ),
                                                lineHeight = 12.sp
                                            )
                                        )
                                        if (liveSession.viewCount > 0) {
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = "•",
                                                fontSize = 12.sp,
                                                color = if (liveSession.isPaused) Color(0xFFF59E0B) else Color(0xFF10B981)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            EmojiText(emoji = "👥", fontSize = 12.sp)
                                            Spacer(modifier = Modifier.width(2.dp))
                                            Text(
                                                text = "${liveSession.viewCount}",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (liveSession.isPaused) Color(0xFFF59E0B) else Color(0xFF10B981),
                                                style = TextStyle(
                                                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                                                    lineHeightStyle = LineHeightStyle(
                                                        alignment = LineHeightStyle.Alignment.Center,
                                                        trim = LineHeightStyle.Trim.Both
                                                    ),
                                                    lineHeight = 12.sp
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            // Permanent Live Sharing Transmission Icon when stopped / inactive (1-tap to start)
                            IconButton(
                                onClick = onShowLiveShare,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF1E293B))
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Sensors,
                                    contentDescription = "Start Live Sharing",
                                    tint = Color(0xFF38BDF8),
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }

                    // ── Right Utility Icons: Saved Places & Direct Collapse ──
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (nearbySavedPlace == null) {
                            // Quick Access Bookmark Icon Button when no saved place nearby
                            IconButton(
                                onClick = onOpenSavedPlaces,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF1E293B))
                            ) {
                                Icon(
                                    imageVector = Icons.Default.BookmarkBorder,
                                    contentDescription = "Saved Places",
                                    tint = Color(0xFF10B981),
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        } else if (nearbySavedPlace.name.length <= 12) {
                            // Short Place Name (<= 12 chars): Fits compactly in Row 1!
                            Surface(
                                onClick = onOpenSavedPlaces,
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0x3310B981),
                                border = BorderStroke(1.dp, Color(0x5510B981)),
                                modifier = Modifier.height(40.dp).widthIn(max = 150.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .padding(horizontal = 8.dp)
                                ) {
                                    EmojiText(
                                        emoji = nearbySavedPlace.category.iconEmoji,
                                        fontSize = 15.sp
                                    )
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text(
                                        text = nearbySavedPlace.name,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF10B981),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = TextStyle(
                                            platformStyle = PlatformTextStyle(includeFontPadding = false),
                                            lineHeightStyle = LineHeightStyle(
                                                alignment = LineHeightStyle.Alignment.Center,
                                                trim = LineHeightStyle.Trim.Both
                                            ),
                                            lineHeight = 12.sp
                                        )
                                    )
                                }
                            }
                        }

                        // Direct Collapse Toggle (switches to Compact mode)
                        IconButton(
                            onClick = { onSetLocalityCardStyle(LocalityCardStyle.COMPACT) },
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF1E293B))
                        ) {
                            Icon(
                                imageVector = Icons.Default.ExpandLess,
                                contentDescription = "Collapse Locality Card",
                                tint = Color(0xFFCBD5E1),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }

                // ── Dedicated Bookmarked Place Line: Only if Long (> 12 chars) to avoid Row 1 overflow ──
                if (nearbySavedPlace != null && nearbySavedPlace.name.length > 12) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        onClick = onOpenSavedPlaces,
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0x3310B981),
                        border = BorderStroke(1.dp, Color(0x5510B981)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            EmojiText(
                                emoji = nearbySavedPlace.category.iconEmoji,
                                fontSize = 13.sp
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = nearbySavedPlace.name,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF10B981),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                                style = TextStyle(
                                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                                    lineHeightStyle = LineHeightStyle(
                                        alignment = LineHeightStyle.Alignment.Center,
                                        trim = LineHeightStyle.Trim.Both
                                    ),
                                    lineHeight = 12.sp
                                )
                            )
                        }
                    }
                }


                // Main Place Name (Largest Font, Top Priority, 100% clean horizontal width, multi-line wrap)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = activeTrip != null) {
                            onShowActiveTripRoute()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = primaryCity,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        softWrap = true,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(
                            platformStyle = PlatformTextStyle(includeFontPadding = false),
                            lineHeightStyle = LineHeightStyle(
                                alignment = LineHeightStyle.Alignment.Center,
                                trim = LineHeightStyle.Trim.Both
                            ),
                            lineHeight = 32.sp
                        )
                    )
                }

                if (!secondaryCity.isNullOrEmpty() && !secondaryCity.equals(primaryCity, ignoreCase = true)) {
                    Text(
                        text = "($secondaryCity)",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF94A3B8),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 0.dp),
                        style = TextStyle(
                            platformStyle = PlatformTextStyle(includeFontPadding = false),
                            lineHeightStyle = LineHeightStyle(
                                alignment = LineHeightStyle.Alignment.Center,
                                trim = LineHeightStyle.Trim.Both
                            ),
                            lineHeight = 16.sp
                        )
                    )
                }

                // Street Name & Road Number
                val rawStreet = primaryPlace?.street
                val effectiveStreet = if (compactStreetNames) {
                    StreetAbbreviator.abbreviate(rawStreet, primaryPlace?.countryCode)
                } else rawStreet

                val streetOrRoad = listOfNotNull(
                    effectiveStreet?.takeIf { it.isNotBlank() },
                    primaryPlace?.roadRef?.takeIf { it.isNotBlank() }
                ).joinToString(" • ")

                if (streetOrRoad.isNotEmpty()) {
                    Text(
                        text = streetOrRoad,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFFFBBF24), // Amber gold for street
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 1.dp),
                        style = TextStyle(
                            platformStyle = PlatformTextStyle(includeFontPadding = false),
                            lineHeightStyle = LineHeightStyle(
                                alignment = LineHeightStyle.Alignment.Center,
                                trim = LineHeightStyle.Trim.Both
                            ),
                            lineHeight = 15.sp
                        )
                    )
                }

                // Administrative Hierarchy (Gmina, Powiat, Województwo, Country)
                val fullHierarchyNormal = buildString {
                    if (hierarchySubtitle.isNotEmpty()) append(hierarchySubtitle)
                    val country = primaryPlace?.country?.takeIf { it.isNotBlank() && it != "Unknown Country" }
                    if (!country.isNullOrBlank()) {
                        if (isNotEmpty()) append(" • ")
                        append(country)
                    }
                }

                if (fullHierarchyNormal.isNotEmpty()) {
                    Text(
                        text = fullHierarchyNormal,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF38BDF8),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 1.dp),
                        style = TextStyle(
                            platformStyle = PlatformTextStyle(includeFontPadding = false),
                            lineHeightStyle = LineHeightStyle(
                                alignment = LineHeightStyle.Alignment.Center,
                                trim = LineHeightStyle.Trim.Both
                            ),
                            lineHeight = 13.sp
                        )
                    )
                }

                // Profile-Aware Speed & Pace Line with Sleek Dropdown Picker
                val speedKmh = (locationData.speedMs ?: 0f) * 3.6f
                val speedStr = String.format(Locale.getDefault(), "%.1f km/h", speedKmh)
                val paceStr = if (speedKmh > 1.0f) {
                    val min = (60f / speedKmh).toInt()
                    val sec = ((60f / speedKmh - min) * 60).toInt()
                    String.format(Locale.getDefault(), "%d:%02d min/km", min, sec)
                } else "- min/km"

                var profileMenuExpanded by remember { mutableStateOf(false) }

                Row(
                    modifier = Modifier
                        .padding(top = 3.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF1E293B))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Visited Places Badge (Left of Activity Profile)
                    if (activeTrip != null) {
                        Surface(
                            onClick = onShowActiveTripRoute,
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0x330284C7),
                            border = BorderStroke(1.dp, Color(0x5538BDF8))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                EmojiText(emoji = "🚩", fontSize = 12.sp, yOffsetDp = 0.dp)
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = "${activeTrip.placesVisited.size}",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF38BDF8),
                                    softWrap = false,
                                    style = TextStyle(
                                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                                        lineHeightStyle = LineHeightStyle(
                                            alignment = LineHeightStyle.Alignment.Center,
                                            trim = LineHeightStyle.Trim.Both
                                        ),
                                        lineHeight = 12.sp
                                    )
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }

                    // Compact Dropdown Trigger Pill
                    Box {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { profileMenuExpanded = true }
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            EmojiText(
                                emoji = activityProfile.iconEmoji,
                                fontSize = 13.sp
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = activityProfile.displayName.uppercase(),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFBBF24),
                                maxLines = 1,
                                softWrap = false,
                                style = TextStyle(
                                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                                    lineHeightStyle = LineHeightStyle(
                                        alignment = LineHeightStyle.Alignment.Center,
                                        trim = LineHeightStyle.Trim.Both
                                    ),
                                    lineHeight = 11.sp
                                )
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = "Select Activity Profile",
                                tint = Color(0xFFFBBF24),
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        DropdownMenu(
                            expanded = profileMenuExpanded,
                            onDismissRequest = { profileMenuExpanded = false },
                            modifier = Modifier.background(Color(0xFF0F172A))
                        ) {
                            ActivityProfile.values().forEach { profile ->
                                val isSelected = activityProfile == profile
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            EmojiText(emoji = profile.iconEmoji, fontSize = 14.sp)
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = profile.displayName,
                                                color = if (isSelected) Color(0xFF38BDF8) else Color.White,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                fontSize = 14.sp,
                                                style = TextStyle(
                                                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                                                    lineHeightStyle = LineHeightStyle(
                                                        alignment = LineHeightStyle.Alignment.Center,
                                                        trim = LineHeightStyle.Trim.Both
                                                    ),
                                                    lineHeight = 14.sp
                                                )
                                            )
                                        }
                                    },
                                    onClick = {
                                        onSetActivityProfile(profile)
                                        profileMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // Metric display: CAR, CYCLING & MTB show Speed-first; WALKING, RUNNING & HIKING show Pace-first
                    when (activityProfile) {
                        ActivityProfile.CAR -> {
                            Text(
                                text = speedStr,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF38BDF8),
                                maxLines = 1,
                                softWrap = false,
                                style = TextStyle(
                                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                                    lineHeightStyle = LineHeightStyle(
                                        alignment = LineHeightStyle.Alignment.Center,
                                        trim = LineHeightStyle.Trim.Both
                                    ),
                                    lineHeight = 15.sp
                                )
                            )
                        }
                        ActivityProfile.CYCLING, ActivityProfile.MTB -> {
                            Text(
                                text = speedStr,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF38BDF8),
                                maxLines = 1,
                                softWrap = false,
                                style = TextStyle(
                                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                                    lineHeightStyle = LineHeightStyle(
                                        alignment = LineHeightStyle.Alignment.Center,
                                        trim = LineHeightStyle.Trim.Both
                                    ),
                                    lineHeight = 15.sp
                                )
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = "($paceStr)",
                                fontSize = 12.sp,
                                color = Color(0xFF94A3B8),
                                maxLines = 1,
                                softWrap = false,
                                style = TextStyle(
                                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                                    lineHeightStyle = LineHeightStyle(
                                        alignment = LineHeightStyle.Alignment.Center,
                                        trim = LineHeightStyle.Trim.Both
                                    ),
                                    lineHeight = 12.sp
                                )
                            )
                        }
                        ActivityProfile.WALKING, ActivityProfile.RUNNING, ActivityProfile.HIKING -> {
                            Text(
                                text = paceStr,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF38BDF8),
                                maxLines = 1,
                                softWrap = false,
                                style = TextStyle(
                                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                                    lineHeightStyle = LineHeightStyle(
                                        alignment = LineHeightStyle.Alignment.Center,
                                        trim = LineHeightStyle.Trim.Both
                                    ),
                                    lineHeight = 15.sp
                                )
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = "($speedStr)",
                                fontSize = 12.sp,
                                color = Color(0xFF94A3B8),
                                maxLines = 1,
                                softWrap = false,
                                style = TextStyle(
                                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                                    lineHeightStyle = LineHeightStyle(
                                        alignment = LineHeightStyle.Alignment.Center,
                                        trim = LineHeightStyle.Trim.Both
                                    ),
                                    lineHeight = 12.sp
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MainBottomControlsCard(
    keepScreenOn: Boolean,
    isRecording: Boolean,
    onToggleKeepScreenOn: () -> Unit,
    onToggleTripRecording: () -> Unit,
    onShowTripsSheet: () -> Unit,
    onShowSearch: () -> Unit,
    onSaveLocation: () -> Unit,
    onShowSettings: () -> Unit,
    onShowMapSettings: () -> Unit,
    landscapeHudElapsed: String? = null,
    landscapeHudDistKm: Double? = null,
    landscapeIsPaused: Boolean = false,
    onLandscapePauseResume: (() -> Unit)? = null,
    landscapeShowHeatMap: Boolean = false,
    heatMapFilterActive: Boolean = false,
    onToggleHeatMap: (() -> Unit)? = null,
    onOpenHeatMapSettings: (() -> Unit)? = null,
    onFitHeatMap: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xEE0F172A)),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // 1. Keep Screen On Toggle Button
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (keepScreenOn) Color(0xFF0284C7) else Color(0xFF1E293B))
                    .clickable { onToggleKeepScreenOn() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (keepScreenOn) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                    contentDescription = "Keep Screen On",
                    tint = if (keepScreenOn) Color.White else Color(0xFF94A3B8),
                    modifier = Modifier.size(20.dp)
                )
            }

            // 2. Trip Recording Pill (Start / Stop / REC status)
            Row(
                modifier = Modifier
                    .height(40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (isRecording) Color(0x33EF4444) else Color(0xFF1E293B))
                    .clickable { onToggleTripRecording() }
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isRecording) Color(0xFFEF4444) else Color(0xFF10B981))
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isRecording) "STOP" else "REC",
                    color = if (isRecording) Color(0xFFEF4444) else Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }

            // 3. Trips & Places History Dialog Button (Prominent Sky Blue)
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF0284C7))
                    .clickable { onShowTripsSheet() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = "Trips & Places History",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }

            // 4. Quick Search Button (Emerald Green)
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF10B981))
                    .clickable { onShowSearch() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search Location",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }

            // 5. Quick Save Current Location as My Place (Dark Emerald Green)
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF059669))
                    .clickable { onSaveLocation() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.BookmarkAdd,
                    contentDescription = "Save Current Location",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }

            // 6. Map Settings Button (Layers icon with overlaid ⚙️ gear badge) — left of main Settings
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clickable { onShowMapSettings() },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(Color(0xFF1E293B)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Layers,
                        contentDescription = "Map Settings",
                        tint = Color(0xFF38BDF8),
                        modifier = Modifier.size(22.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset(x = (-3).dp, y = (-3).dp)
                        .size(21.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF0F172A))
                        .border(1.2.dp, Color(0xFF334155), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        tint = Color(0xFFFBBF24),
                        modifier = Modifier.size(15.dp)
                    )
                }
            }

            // 7. Settings & Language Dialog Button (Amber Gold / Slate)
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1E293B))
                    .clickable { onShowSettings() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = Color(0xFFFBBF24),
                    modifier = Modifier.size(22.dp)
                )
            }

            // 8. Landscape Heat Map Inline Controls (shown directly inside bottom toolbar in landscape)
            if (landscapeShowHeatMap) {
                Spacer(modifier = Modifier.width(4.dp))
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xF20F172A),
                    border = BorderStroke(1.dp, Color(0xFFF97316))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Heat Map icon
                        Icon(
                            imageVector = Icons.Default.Whatshot,
                            contentDescription = "Heat Map",
                            tint = Color(0xFFF97316),
                            modifier = Modifier.size(16.dp)
                        )

                        // Filter button
                        if (onOpenHeatMapSettings != null) {
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (heatMapFilterActive) Color(0xFFEA580C) else Color(0xFF1E293B))
                                    .clickable { onOpenHeatMapSettings() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Tune,
                                    contentDescription = "Heat Map Filter",
                                    tint = if (heatMapFilterActive) Color.White else Color(0xFFF97316),
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }

                        // Fit All button
                        if (onFitHeatMap != null) {
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF1E293B))
                                    .clickable { onFitHeatMap() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CropFree,
                                    contentDescription = "Fit Heat Map",
                                    tint = Color(0xFF38BDF8),
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }

                        // Close button
                        if (onToggleHeatMap != null) {
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF7F1D1D))
                                    .clickable { onToggleHeatMap() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close Heat Map",
                                    tint = Color(0xFFFCA5A5),
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }
                    }
                }
            }

            // 9. Landscape Inline HUD (shown on the very right inside bottom toolbar in landscape recording)
            if (landscapeHudElapsed != null && landscapeHudDistKm != null) {
                Spacer(modifier = Modifier.width(4.dp))
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xF20F172A),
                    border = BorderStroke(1.dp, if (landscapeIsPaused) Color(0xFFF59E0B) else Color(0xFFEF4444))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(if (landscapeIsPaused) Color(0xFFF59E0B) else Color(0xFFEF4444))
                        )
                        Text(
                            text = if (landscapeIsPaused) "PAUSED" else landscapeHudElapsed,
                            color = if (landscapeIsPaused) Color(0xFFFBBF24) else Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false,
                            style = TextStyle(
                                platformStyle = PlatformTextStyle(includeFontPadding = false),
                                lineHeightStyle = LineHeightStyle(
                                    alignment = LineHeightStyle.Alignment.Center,
                                    trim = LineHeightStyle.Trim.Both
                                ),
                                lineHeight = 11.sp
                            )
                        )
                        Text(
                            text = String.format(Locale.getDefault(), "• %.2f km", landscapeHudDistKm),
                            color = Color(0xFF38BDF8),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                            style = TextStyle(
                                platformStyle = PlatformTextStyle(includeFontPadding = false),
                                lineHeightStyle = LineHeightStyle(
                                    alignment = LineHeightStyle.Alignment.Center,
                                    trim = LineHeightStyle.Trim.Both
                                ),
                                lineHeight = 10.sp
                            )
                        )
                        if (onLandscapePauseResume != null) {
                            Button(
                                onClick = onLandscapePauseResume,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (landscapeIsPaused) Color(0xFFD97706) else Color(0xFF334155),
                                    contentColor = Color.White
                                ),
                                border = if (landscapeIsPaused) null else BorderStroke(1.dp, Color(0xFFF59E0B)),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(26.dp)
                            ) {
                                Icon(
                                    imageVector = if (landscapeIsPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                    contentDescription = if (landscapeIsPaused) "Resume" else "Pause",
                                    modifier = Modifier.size(11.dp),
                                    tint = Color.White
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = if (landscapeIsPaused) "RESUME" else "PAUSE",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DestinationPlaceCard(
    destinationPoint: org.osmdroid.util.GeoPoint,
    destinationItem: SearchResultItem?,
    savedPlace: SavedPlace? = null,
    onClearDestination: () -> Unit,
    onNavigate: (Double, Double) -> Unit,
    onGoogleMaps: (Double, Double, String) -> Unit,
    onSavePlace: ((Double, Double, SearchResultItem?) -> Unit)? = null,
    onEditSavedPlace: ((SavedPlace) -> Unit)? = null,
    onDeleteSavedPlace: ((SavedPlace) -> Unit)? = null,
    onTogglePinBorders: () -> Unit,
    isPinBorderVisible: Boolean,
    onShare: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFA0F172A)),
        elevation = CardDefaults.cardElevation(defaultElevation = 10.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    if (savedPlace != null) {
                        EmojiText(
                            emoji = savedPlace.category.iconEmoji,
                            fontSize = 18.sp,
                            modifier = Modifier.padding(end = 6.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Place,
                            contentDescription = "Destination Pin",
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    val titleText = savedPlace?.name ?: destinationItem?.title ?: "Selected Location"
                    Text(
                        text = titleText,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        lineHeight = 18.sp,
                        maxLines = 2
                    )
                }

                IconButton(
                    onClick = onClearDestination,
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1E293B))
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Exit Destination",
                        tint = Color(0xFF94A3B8),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            val subtitleText = if (savedPlace != null) {
                listOfNotNull(
                    savedPlace.street.takeIf { it.isNotBlank() },
                    savedPlace.locality.takeIf { it.isNotBlank() }
                ).joinToString(", ")
            } else {
                destinationItem?.subtitle ?: ""
            }

            if (subtitleText.isNotBlank()) {
                Text(
                    text = subtitleText,
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp,
                    maxLines = 2,
                    modifier = Modifier.padding(top = 2.dp, start = 26.dp)
                )
            }

            // Row 1: Navigation, Map, & Share Actions
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Button(
                    onClick = {
                        onNavigate(destinationPoint.latitude, destinationPoint.longitude)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                    modifier = Modifier.weight(1f).height(38.dp)
                ) {
                    Icon(Icons.Default.Navigation, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(3.dp))
                    Text("Navigate", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                }

                val gmapsTitle = savedPlace?.name ?: destinationItem?.title ?: ""
                Button(
                    onClick = {
                        onGoogleMaps(destinationPoint.latitude, destinationPoint.longitude, gmapsTitle)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                    modifier = Modifier.weight(1f).height(38.dp)
                ) {
                    Icon(Icons.Default.Map, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(3.dp))
                    Text("Maps", color = Color.White, fontSize = 11.sp, maxLines = 1, softWrap = false)
                }

                Button(
                    onClick = onShare,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                    modifier = Modifier.weight(1f).height(38.dp)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(3.dp))
                    Text("Share", color = Color.White, fontSize = 11.sp, maxLines = 1, softWrap = false)
                }
            }

            // Row 2: Secondary Context Actions
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (savedPlace != null) {
                    // Saved Place: Edit, Delete, Borders
                    Button(
                        onClick = { onEditSavedPlace?.invoke(savedPlace) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                        modifier = Modifier.weight(1f).height(38.dp)
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, tint = Color(0xFFFBBF24), modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(3.dp))
                        Text("Edit", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                    }

                    Button(
                        onClick = { onDeleteSavedPlace?.invoke(savedPlace) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                        modifier = Modifier.weight(1f).height(38.dp)
                    ) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = Color(0xFFEF4444), modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(3.dp))
                        Text("Delete", color = Color(0xFFF87171), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                    }
                } else {
                    // Unsaved Pin: Save, Clear Pin, Borders
                    Button(
                        onClick = {
                            onSavePlace?.invoke(destinationPoint.latitude, destinationPoint.longitude, destinationItem)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                        modifier = Modifier.weight(1f).height(38.dp)
                    ) {
                        Icon(Icons.Default.BookmarkAdd, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(3.dp))
                        Text("Save", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                    }

                    Button(
                        onClick = onClearDestination,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                        modifier = Modifier.weight(1f).height(38.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null, tint = Color(0xFF94A3B8), modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(3.dp))
                        Text("Clear Pin", color = Color(0xFFE2E8F0), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                    }
                }

                Button(
                    onClick = onTogglePinBorders,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isPinBorderVisible) Color(0xFFDC2626) else Color(0xFF1E293B)
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                    modifier = Modifier.weight(1f).height(38.dp)
                ) {
                    Icon(
                        imageVector = if (isPinBorderVisible) Icons.Default.LayersClear else Icons.Default.Layers,
                        contentDescription = null,
                        tint = if (isPinBorderVisible) Color.White else Color(0xFFEF4444),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = if (isPinBorderVisible) "Hide" else "Borders",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}

@Composable
fun TripFilterDropdowns(
    selectedDateFilter: String,
    onDateFilterChange: (String) -> Unit,
    dateFilterExpanded: Boolean,
    onDateExpandChange: (Boolean) -> Unit,
    selectedActivityFilter: ActivityProfile?,
    onActivityFilterChange: (ActivityProfile?) -> Unit,
    activityFilterExpanded: Boolean,
    onActivityExpandChange: (Boolean) -> Unit,
    tripGroupBy: TripGroupBy,
    onGroupByChange: (TripGroupBy) -> Unit,
    groupByExpanded: Boolean,
    onGroupByExpandChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 1. Date Range Dropdown
        Box(modifier = Modifier.weight(1f)) {
            val dateLabel = when (selectedDateFilter) {
                "TODAY" -> "Today"
                "WEEK" -> "This Week"
                "MONTH" -> "This Month"
                else -> "All Time"
            }
            Surface(
                onClick = { onDateExpandChange(true) },
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF1E293B),
                border = BorderStroke(1.dp, if (selectedDateFilter != "ALL") Color(0xFF0284C7) else Color(0xFF334155)),
                modifier = Modifier.fillMaxWidth().height(36.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = dateLabel,
                        color = if (selectedDateFilter != "ALL") Color(0xFF38BDF8) else Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Icon(
                        Icons.Default.ArrowDropDown,
                        contentDescription = null,
                        tint = Color(0xFF94A3B8),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            DropdownMenu(
                expanded = dateFilterExpanded,
                onDismissRequest = { onDateExpandChange(false) },
                modifier = Modifier.background(Color(0xFF1E293B))
            ) {
                listOf(
                    "ALL" to "All Time",
                    "TODAY" to "Today",
                    "WEEK" to "This Week",
                    "MONTH" to "This Month"
                ).forEach { (key, label) ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                label,
                                color = if (selectedDateFilter == key) Color(0xFF38BDF8) else Color.White,
                                fontSize = 12.sp,
                                fontWeight = if (selectedDateFilter == key) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        onClick = {
                            onDateFilterChange(key)
                            onDateExpandChange(false)
                        }
                    )
                }
            }
        }

        // 2. Activity Profile Dropdown
        Box(modifier = Modifier.weight(1f)) {
            Surface(
                onClick = { onActivityExpandChange(true) },
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF1E293B),
                border = BorderStroke(1.dp, if (selectedActivityFilter != null) Color(0xFF10B981) else Color(0xFF334155)),
                modifier = Modifier.fillMaxWidth().height(36.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        if (selectedActivityFilter != null) {
                            EmojiText(emoji = selectedActivityFilter.iconEmoji, fontSize = 12.sp)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = selectedActivityFilter.displayName,
                                color = Color(0xFF34D399),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = TextStyle(
                                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                                    lineHeightStyle = LineHeightStyle(
                                        alignment = LineHeightStyle.Alignment.Center,
                                        trim = LineHeightStyle.Trim.Both
                                    ),
                                    lineHeight = 11.sp
                                )
                            )
                        } else {
                            Text(
                                text = "All Modes",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Icon(
                        Icons.Default.ArrowDropDown,
                        contentDescription = null,
                        tint = Color(0xFF94A3B8),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            DropdownMenu(
                expanded = activityFilterExpanded,
                onDismissRequest = { onActivityExpandChange(false) },
                modifier = Modifier.background(Color(0xFF1E293B))
            ) {
                listOf(
                    null to "All Modes",
                    ActivityProfile.CAR to "Driving",
                    ActivityProfile.CYCLING to "Cycling",
                    ActivityProfile.MTB to "MTB",
                    ActivityProfile.HIKING to "Hiking",
                    ActivityProfile.RUNNING to "Running",
                    ActivityProfile.WALKING to "Walking"
                ).forEach { (profile, label) ->
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (profile != null) {
                                    EmojiText(emoji = profile.iconEmoji, fontSize = 13.sp)
                                    Spacer(modifier = Modifier.width(6.dp))
                                }
                                Text(
                                    text = label,
                                    color = if (selectedActivityFilter == profile) Color(0xFF10B981) else Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = if (selectedActivityFilter == profile) FontWeight.Bold else FontWeight.Normal,
                                    style = TextStyle(
                                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                                        lineHeightStyle = LineHeightStyle(
                                            alignment = LineHeightStyle.Alignment.Center,
                                            trim = LineHeightStyle.Trim.Both
                                        ),
                                        lineHeight = 12.sp
                                    )
                                )
                            }
                        },
                        onClick = {
                            onActivityFilterChange(profile)
                            onActivityExpandChange(false)
                        }
                    )
                }
            }
        }

        // 3. Group By Dropdown
        Box(modifier = Modifier.weight(1f)) {
            val groupLabel = "Group: ${tripGroupBy.name.lowercase(Locale.getDefault()).replaceFirstChar { it.uppercase(Locale.getDefault()) }}"
            Surface(
                onClick = { onGroupByExpandChange(true) },
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF1E293B),
                border = BorderStroke(1.dp, if (tripGroupBy != TripGroupBy.NONE) Color(0xFF8B5CF6) else Color(0xFF334155)),
                modifier = Modifier.fillMaxWidth().height(36.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = groupLabel,
                        color = if (tripGroupBy != TripGroupBy.NONE) Color(0xFFA78BFA) else Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Icon(
                        Icons.Default.ArrowDropDown,
                        contentDescription = null,
                        tint = Color(0xFF94A3B8),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            DropdownMenu(
                expanded = groupByExpanded,
                onDismissRequest = { onGroupByExpandChange(false) },
                modifier = Modifier.background(Color(0xFF1E293B))
            ) {
                listOf(
                    TripGroupBy.DATE to "Date",
                    TripGroupBy.ACTIVITY to "Activity",
                    TripGroupBy.NONE to "None"
                ).forEach { (mode, label) ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                "Group by $label",
                                color = if (tripGroupBy == mode) Color(0xFF8B5CF6) else Color.White,
                                fontSize = 12.sp,
                                fontWeight = if (tripGroupBy == mode) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        onClick = {
                            onGroupByChange(mode)
                            onGroupByExpandChange(false)
                        }
                    )
                }
            }
        }
    }
}

private fun formatDurationShort(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val h = totalSec / 3600L
    val m = (totalSec % 3600L) / 60L
    val s = totalSec % 60L
    return when {
        h > 0 -> String.format(Locale.getDefault(), "%dh %02dm", h, m)
        m > 0 -> String.format(Locale.getDefault(), "%dm %02ds", m, s)
        else -> String.format(Locale.getDefault(), "%ds", s)
    }
}

private sealed class RouteTimelineItem(val timestamp: Long) {
    data class Place(
        val place: VisitedPlace,
        val orderNumber: Int,
        val legDistanceMeters: Double,
        val legDurationMs: Long
    ) : RouteTimelineItem(place.timestamp)

    data class Pause(
        val pause: TripPause,
        val pauseIndex: Int,
        val pauseDistanceMeters: Double,
        val legDistanceMeters: Double,
        val legDurationMs: Long
    ) : RouteTimelineItem(pause.startTime)
}

private sealed class RawTimelineEvent(val startTime: Long) {
    class Place(val place: VisitedPlace) : RawTimelineEvent(place.timestamp)
    class Pause(val pause: TripPause, val pauseIndex: Int, val pauseDistanceMeters: Double) : RawTimelineEvent(pause.startTime)
}

@Composable
fun TripDetailDialog(
    trip: TripRecord,
    onDismissRequest: () -> Unit,
    onShowOnMap: (TripRecord) -> Unit,
    onRename: (TripRecord) -> Unit,
    onDelete: (TripRecord) -> Unit,
    onShare: (TripRecord) -> Unit,
    onUpdateProfile: (Long, ActivityProfile) -> Unit,
    onSplitPause: (Long, Int) -> Unit,
    onDeletePause: (Long, Int) -> Unit = { _, _ -> },
    onMergePause: (Long, Int) -> Unit = { _, _ -> }
) {
    val shortDateFmt = SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault())
    val startDateStr = shortDateFmt.format(Date(trip.startTime))
    val endDateStr = if (trip.endTime != null && trip.endTime > trip.startTime) {
        shortDateFmt.format(Date(trip.endTime))
    } else null

    val startCal = Calendar.getInstance().apply { timeInMillis = trip.startTime }
    val endCal = trip.endTime?.let { Calendar.getInstance().apply { timeInMillis = it } }
    val isSameDay = endCal == null || (
        startCal.get(Calendar.YEAR) == endCal.get(Calendar.YEAR) &&
        startCal.get(Calendar.DAY_OF_YEAR) == endCal.get(Calendar.DAY_OF_YEAR)
    )

    val startTimeStr = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(trip.startTime))
    val endTimeStr = if (trip.endTime != null && trip.endTime > trip.startTime) {
        DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(trip.endTime))
    } else {
        "In Progress / Live"
    }

    val cumulativeDistances = remember(trip.points) { calculateCumulativeDistances(trip.points) }

    val timelineItems = remember(trip, cumulativeDistances) {
        val totalTripDist = (cumulativeDistances.lastOrNull() ?: trip.distanceMeters).coerceAtLeast(0.0)
        val tripEndTime = trip.endTime ?: (trip.startTime + 60_000L)
        val items = mutableListOf<RouteTimelineItem>()

        // 1. Resolve pause distances
        val sortedPauses = trip.pauses.sortedBy { it.startTime }.mapIndexed { idx, p ->
            val dist = calculatePauseDistanceMeters(trip, p, cumulativeDistances)
            p to dist
        }

        // Helper to find the active place at timestamp t
        fun getActivePlaceAt(t: Long): VisitedPlace {
            return trip.placesVisited.lastOrNull { it.timestamp <= t }
                ?: trip.placesVisited.firstOrNull()
                ?: VisitedPlace(
                    placeName = trip.title.ifBlank { "Trip Locality" },
                    hierarchySubtitle = "",
                    timestamp = trip.startTime,
                    latitude = trip.points.firstOrNull()?.latitude ?: 0.0,
                    longitude = trip.points.firstOrNull()?.longitude ?: 0.0,
                    distanceAtEntryMeters = 0.0
                )
        }

        // 2. Define moving intervals bounded by pauses:
        var placeOrder = 1
        var curTime = trip.startTime
        var curDist = 0.0

        for (pauseIdx in 0..sortedPauses.size) {
            val nextPause = sortedPauses.getOrNull(pauseIdx)
            val intervalEndTime = nextPause?.first?.startTime ?: tripEndTime
            val intervalEndDist = nextPause?.second ?: totalTripDist

            // Find any places entered strictly inside (curTime, intervalEndTime]
            val placesInInterval = trip.placesVisited.filter {
                it.timestamp > curTime && it.timestamp <= intervalEndTime
            }.sortedBy { it.timestamp }

            var legStartTime = curTime
            var legStartDist = curDist

            if (placesInInterval.isEmpty()) {
                // User remained in the currently active place for the entire interval
                val activePlace = getActivePlaceAt(curTime)
                val legDist = (intervalEndDist - legStartDist).coerceAtLeast(0.0)
                val legDur = (intervalEndTime - legStartTime).coerceAtLeast(0L)
                val displayPlace = activePlace.copy(timestamp = legStartTime, distanceAtEntryMeters = legStartDist)
                items.add(RouteTimelineItem.Place(displayPlace, placeOrder++, legDist, legDur))
            } else {
                // There were place transitions within this interval
                // First leg in active place before the first transition:
                val firstTransition = placesInInterval.first()
                val activePlace = getActivePlaceAt(curTime)
                val firstLegDist = (firstTransition.distanceAtEntryMeters - legStartDist).coerceAtLeast(0.0)
                val firstLegDur = (firstTransition.timestamp - legStartTime).coerceAtLeast(0L)
                val displayPlace = activePlace.copy(timestamp = legStartTime, distanceAtEntryMeters = legStartDist)
                items.add(RouteTimelineItem.Place(displayPlace, placeOrder++, firstLegDist, firstLegDur))

                legStartTime = firstTransition.timestamp
                legStartDist = firstTransition.distanceAtEntryMeters

                // Intermediate transitions
                for (pIdx in 0 until placesInInterval.size) {
                    val p = placesInInterval[pIdx]
                    val nextTargetTime = if (pIdx < placesInInterval.size - 1) placesInInterval[pIdx + 1].timestamp else intervalEndTime
                    val nextTargetDist = if (pIdx < placesInInterval.size - 1) placesInInterval[pIdx + 1].distanceAtEntryMeters else intervalEndDist
                    val pLegDist = (nextTargetDist - p.distanceAtEntryMeters).coerceAtLeast(0.0)
                    val pLegDur = (nextTargetTime - p.timestamp).coerceAtLeast(0L)
                    items.add(RouteTimelineItem.Place(p, placeOrder++, pLegDist, pLegDur))
                }
            }

            // If there is a pause at the end of this interval, emit the pause item
            if (nextPause != null) {
                val pause = nextPause.first
                val pauseDist = nextPause.second
                val travelDist = (pauseDist - curDist).coerceAtLeast(0.0)
                val travelDur = (pause.startTime - curTime).coerceAtLeast(0L)
                items.add(RouteTimelineItem.Pause(pause, pauseIdx, pauseDist, travelDist, travelDur))

                val pauseEnd = pause.endTime ?: (pause.startTime + pause.durationMs)
                curTime = pauseEnd
                curDist = pauseDist
            }
        }
        items
    }

    val durationMs = if (trip.endTime != null && trip.endTime > trip.startTime) trip.endTime - trip.startTime else 0L
    val durationSec = durationMs / 1000L
    val durHours = durationSec / 3600L
    val durMinutes = (durationSec % 3600L) / 60L
    val durSeconds = durationSec % 60L
    val durationFormatted = when {
        durHours > 0 -> String.format(Locale.getDefault(), "%dh %02dm %02ds", durHours, durMinutes, durSeconds)
        durMinutes > 0 -> String.format(Locale.getDefault(), "%dm %02ds", durMinutes, durSeconds)
        durationSec > 0 -> String.format(Locale.getDefault(), "%ds", durationSec)
        else -> "N/A"
    }

    val totalPauseDurationMs = trip.pauses.sumOf { it.durationMs }
    val movingDurationMs = (durationMs - totalPauseDurationMs).coerceAtLeast(0L)
    val movingSec = movingDurationMs / 1000L
    val movHours = movingSec / 3600L
    val movMinutes = (movingSec % 3600L) / 60L
    val movSeconds = movingSec % 60L
    val movingFormatted = when {
        movHours > 0 -> String.format(Locale.getDefault(), "%dh %02dm %02ds", movHours, movMinutes, movSeconds)
        movMinutes > 0 -> String.format(Locale.getDefault(), "%dm %02ds", movMinutes, movSeconds)
        movingSec > 0 -> String.format(Locale.getDefault(), "%ds", movingSec)
        else -> "N/A"
    }

    var showProfileMenu by remember { mutableStateOf(false) }
    var pauseToDeleteIndex by remember { mutableStateOf<Int?>(null) }
    var pauseToMergeIndex by remember { mutableStateOf<Int?>(null) }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFF0F172A)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                // ── Top Navigation & Actions Bar ──────────────────────
                Surface(
                    color = Color(0xFF1E293B),
                    tonalElevation = 4.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f).padding(end = 8.dp)
                        ) {
                            IconButton(onClick = onDismissRequest) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Column {
                                Text(
                                    text = if (trip.title.isNotBlank()) trip.title else "Trip Details",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = "$startDateStr • $startTimeStr",
                                    fontSize = 11.sp,
                                    color = Color(0xFF94A3B8),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { onShare(trip) }) {
                                Icon(Icons.Default.Share, contentDescription = "Export GPX", tint = Color(0xFF10B981))
                            }
                            IconButton(onClick = { onRename(trip) }) {
                                Icon(Icons.Default.Edit, contentDescription = "Rename", tint = Color(0xFF38BDF8))
                            }
                            IconButton(onClick = { onDelete(trip) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFF87171))
                            }
                        }
                    }
                }

                // ── Scrollable Body Content ───────────────────────────
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 1. Map Thumbnail Card
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(230.dp)
                            ) {
                                if (trip.points.size >= 2) {
                                    key(trip.id, trip.pauses.size, trip.points.size) {
                                        AndroidView(
                                            factory = { ctx ->
                                                MapView(ctx).apply {
                                                    setMultiTouchControls(false)
                                                    setBuiltInZoomControls(false)
                                                    setTileSource(TileSourceFactory.MAPNIK)
                                                    val line = Polyline(this).apply {
                                                        outlinePaint.color = android.graphics.Color.parseColor("#EF4444")
                                                        outlinePaint.strokeWidth = 8f
                                                        outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                                                        outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
                                                        setPoints(trip.points)
                                                    }
                                                    overlays.add(line)
                                                    trip.points.firstOrNull()?.let { startPt ->
                                                        val startMarker = Marker(this).apply {
                                                            position = startPt
                                                            title = "Trip Start"
                                                            icon = makeStartMarkerIcon(ctx)
                                                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                                                            infoWindow = null
                                                            setOnMarkerClickListener { _, _ -> true }
                                                        }
                                                        overlays.add(startMarker)
                                                    }
                                                    trip.points.lastOrNull()?.let { endPt ->
                                                        val endMarker = Marker(this).apply {
                                                            position = endPt
                                                            title = "Trip End"
                                                            icon = makeEndMarkerIcon(ctx)
                                                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                                                            infoWindow = null
                                                            setOnMarkerClickListener { _, _ -> true }
                                                        }
                                                        overlays.add(endMarker)
                                                    }
                                                    trip.pauses.forEachIndexed { pauseIdx, pause ->
                                                        val durText = when {
                                                            pause.durationMs < 60_000L -> "${pause.durationMs / 1000}s"
                                                            pause.durationMs < 3600_000L -> "${pause.durationMs / 60000}m"
                                                            else -> "${pause.durationMs / 3600000}h ${(pause.durationMs % 3600000) / 60000}m"
                                                        }
                                                        val pauseMarker = Marker(this).apply {
                                                            position = GeoPoint(pause.latitude, pause.longitude)
                                                            title = "Stop #${pauseIdx + 1} ($durText)"
                                                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                                                            icon = makePauseIcon(ctx, durText)
                                                            infoWindow = null
                                                            setOnMarkerClickListener { _, _ -> true }
                                                        }
                                                        overlays.add(pauseMarker)
                                                    }
                                                    post {
                                                        try {
                                                            val allPts = trip.points + trip.pauses.map { GeoPoint(it.latitude, it.longitude) }
                                                            if (allPts.isNotEmpty()) {
                                                                val bb = BoundingBox.fromGeoPoints(allPts)
                                                                zoomToBoundingBox(bb, false, 48)
                                                            }
                                                        } catch (ignored: Exception) {}
                                                    }
                                                }
                                            },
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                } else {
                                    Box(
                                        modifier = Modifier.fillMaxSize().background(Color(0xFF0F172A)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("No map coordinates recorded for this trip", color = Color.Gray, fontSize = 12.sp)
                                    }
                                }

                                // Overlay "Show on Map" button
                                Button(
                                    onClick = { onShowOnMap(trip) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(10.dp)
                                ) {
                                    Icon(Icons.Default.Explore, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Show on Map", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    // 2. Key Metrics Grid
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Distance Card
                        Card(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("TOTAL DISTANCE", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8), letterSpacing = 0.5.sp)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = String.format(Locale.getDefault(), "%.2f km", trip.distanceMeters / 1000.0),
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF38BDF8)
                                )
                            }
                        }

                        // Duration Card (Total & Moving Time)
                        Card(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("TOTAL / MOVING TIME", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8), letterSpacing = 0.5.sp)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = durationFormatted,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF10B981)
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Moving: $movingFormatted",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF38BDF8)
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Speed Card
                        Card(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("SPEED (MAX / AVG)", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8), letterSpacing = 0.5.sp)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = String.format(Locale.getDefault(), "%.1f / %.1f km/h", trip.maxSpeedKmh, trip.avgSpeedKmh),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFFBBF24)
                                )
                            }
                        }

                        // Activity Profile Card
                        Card(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("ACTIVITY PROFILE", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8), letterSpacing = 0.5.sp)
                                Spacer(modifier = Modifier.height(4.dp))
                                Box {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = Color(0xFF0F172A),
                                        modifier = Modifier.clickable { showProfileMenu = true }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            EmojiText(
                                                emoji = trip.activityProfile.iconEmoji,
                                                fontSize = 13.sp
                                            )
                                            Spacer(modifier = Modifier.width(5.dp))
                                            Text(
                                                text = trip.activityProfile.displayName,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Color(0xFF34D399),
                                                style = TextStyle(
                                                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                                                    lineHeightStyle = LineHeightStyle(
                                                        alignment = LineHeightStyle.Alignment.Center,
                                                        trim = LineHeightStyle.Trim.Both
                                                    ),
                                                    lineHeight = 12.sp
                                                )
                                            )
                                            Spacer(modifier = Modifier.width(2.dp))
                                            Icon(
                                                Icons.Default.ArrowDropDown,
                                                contentDescription = "Change profile",
                                                tint = Color(0xFF94A3B8),
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }

                                    DropdownMenu(
                                        expanded = showProfileMenu,
                                        onDismissRequest = { showProfileMenu = false },
                                        modifier = Modifier.background(Color(0xFF1E293B))
                                    ) {
                                        ActivityProfile.values().forEach { profile ->
                                            DropdownMenuItem(
                                                text = {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        EmojiText(emoji = profile.iconEmoji, fontSize = 14.sp)
                                                        Spacer(modifier = Modifier.width(8.dp))
                                                        Text(
                                                            text = profile.displayName,
                                                            color = Color.White,
                                                            fontSize = 13.sp,
                                                            style = TextStyle(
                                                                platformStyle = PlatformTextStyle(includeFontPadding = false),
                                                                lineHeightStyle = LineHeightStyle(
                                                                    alignment = LineHeightStyle.Alignment.Center,
                                                                    trim = LineHeightStyle.Trim.Both
                                                                ),
                                                                lineHeight = 13.sp
                                                            )
                                                        )
                                                    }
                                                },
                                                onClick = {
                                                    showProfileMenu = false
                                                    onUpdateProfile(trip.id, profile)
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 3. Time Details Card
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("SCHEDULE & TIMING", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8), letterSpacing = 0.5.sp)
                            if (isSameDay) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Date:", fontSize = 12.sp, color = Color(0xFF94A3B8))
                                    Text(startDateStr, fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Medium)
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Trip Start:", fontSize = 12.sp, color = Color(0xFF94A3B8))
                                    Text(startTimeStr, fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Medium)
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Trip End:", fontSize = 12.sp, color = Color(0xFF94A3B8))
                                    Text(endTimeStr, fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Medium)
                                }
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Trip Start:", fontSize = 12.sp, color = Color(0xFF94A3B8))
                                    Text("$startDateStr, $startTimeStr", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Medium)
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Trip End:", fontSize = 12.sp, color = Color(0xFF94A3B8))
                                    Text(if (endDateStr != null) "$endDateStr, $endTimeStr" else endTimeStr, fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Medium)
                                }
                            }
                            if (trip.points.isNotEmpty()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Recorded GPS Fixes:", fontSize = 12.sp, color = Color(0xFF94A3B8))
                                    Text("${trip.points.size} points", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }

                    // 4. Unified Route Timeline (Visited Localities & Interleaved Rest Stops)
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val totalPlaces = timelineItems.count { it is RouteTimelineItem.Place }
                            val totalPauses = timelineItems.count { it is RouteTimelineItem.Pause }
                            val timelineTitle = when {
                                totalPauses > 0 -> "ROUTE TIMELINE ($totalPlaces ${if (totalPlaces == 1) "place" else "places"}, $totalPauses ${if (totalPauses == 1) "pause" else "pauses"})"
                                else -> "VISITED LOCALITIES ($totalPlaces)"
                            }
                            Text(
                                text = timelineTitle,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF38BDF8),
                                letterSpacing = 0.5.sp
                            )

                            if (timelineItems.isEmpty()) {
                                Text(
                                    text = "No distinct locality transitions or pauses recorded during this trip.",
                                    fontSize = 12.sp,
                                    color = Color(0xFF94A3B8),
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                            } else {
                                timelineItems.forEach { item ->
                                    when (item) {
                                        is RouteTimelineItem.Place -> {
                                            val place = item.place
                                            val entryTime = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(place.timestamp))
                                            val entryDistKm = place.distanceAtEntryMeters / 1000.0
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .background(Color(0xFF0F172A), RoundedCornerShape(8.dp))
                                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    modifier = Modifier.weight(1f).padding(end = 8.dp)
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(24.dp)
                                                            .background(Color(0xFF0284C7), CircleShape),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Text(
                                                            text = "${item.orderNumber}",
                                                            fontSize = 11.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = Color.White,
                                                            textAlign = TextAlign.Center,
                                                            style = LocalTextStyle.current.copy(
                                                                platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                                                                lineHeight = 11.sp
                                                            )
                                                        )
                                                    }
                                                    Spacer(modifier = Modifier.width(10.dp))
                                                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                                        Text(
                                                            text = place.placeName,
                                                            fontSize = 13.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = Color.White,
                                                            style = LocalTextStyle.current.copy(
                                                                platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                                                                lineHeight = 14.sp
                                                            )
                                                        )
                                                        if (place.hierarchySubtitle.isNotBlank()) {
                                                            Text(
                                                                text = place.hierarchySubtitle,
                                                                fontSize = 11.sp,
                                                                color = Color(0xFF94A3B8),
                                                                style = LocalTextStyle.current.copy(
                                                                    platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                                                                    lineHeight = 12.sp
                                                                )
                                                            )
                                                        }
                                                    }
                                                }

                                                Column(
                                                    horizontalAlignment = Alignment.End,
                                                    verticalArrangement = Arrangement.spacedBy(1.dp)
                                                ) {
                                                    Text(
                                                        text = entryTime,
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = Color(0xFF38BDF8),
                                                        style = LocalTextStyle.current.copy(
                                                            platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                                                            lineHeight = 13.sp
                                                        )
                                                    )
                                                    Text(
                                                        text = String.format(Locale.getDefault(), "at %.2f km", entryDistKm),
                                                        fontSize = 10.sp,
                                                        color = Color(0xFF94A3B8),
                                                        style = LocalTextStyle.current.copy(
                                                            platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                                                            lineHeight = 11.sp
                                                        )
                                                    )
                                                    val allPlacesInTimeline = timelineItems.filterIsInstance<RouteTimelineItem.Place>()
                                                    val isLastPlace = item == allPlacesInTimeline.lastOrNull() && allPlacesInTimeline.size > 1
                                                    if (item.orderNumber == 1) {
                                                        Text(
                                                            text = "Trip Start",
                                                            fontSize = 10.sp,
                                                            fontWeight = FontWeight.Medium,
                                                            color = Color(0xFF10B981),
                                                            style = LocalTextStyle.current.copy(
                                                                platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                                                                lineHeight = 11.sp
                                                            )
                                                        )
                                                    } else if (isLastPlace) {
                                                        Text(
                                                            text = "Trip End",
                                                            fontSize = 10.sp,
                                                            fontWeight = FontWeight.Medium,
                                                            color = Color(0xFF38BDF8),
                                                            style = LocalTextStyle.current.copy(
                                                                platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                                                                lineHeight = 11.sp
                                                            )
                                                        )
                                                    }
                                                    if (item.legDistanceMeters > 0.0 || item.legDurationMs > 0L) {
                                                        val legKm = item.legDistanceMeters / 1000.0
                                                        val legTimeStr = formatDurationShort(item.legDurationMs)
                                                        Text(
                                                            text = String.format(Locale.getDefault(), "+%.2f km • %s", legKm, legTimeStr),
                                                            fontSize = 10.sp,
                                                            fontWeight = FontWeight.Medium,
                                                            color = Color(0xFF38BDF8),
                                                            style = LocalTextStyle.current.copy(
                                                                platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                                                                lineHeight = 11.sp
                                                            )
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                        is RouteTimelineItem.Pause -> {
                                            val pause = item.pause
                                            val pauseTimeStr = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(pause.startTime))
                                            val pauseEndTime = pause.endTime ?: (pause.startTime + pause.durationMs)
                                            val pauseEndTimeStr = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(pauseEndTime))
                                            val durMin = (pause.durationMs / 60000L).coerceAtLeast(1)
                                            val pauseDistKm = item.pauseDistanceMeters / 1000.0
                                            val legKm = item.legDistanceMeters / 1000.0
                                            val legTimeStr = formatDurationShort(item.legDurationMs)
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp))
                                                    .border(1.dp, Color(0x66F59E0B), RoundedCornerShape(8.dp))
                                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                                            ) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(24.dp)
                                                            .background(Color(0xFFD97706), CircleShape),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.Pause,
                                                            contentDescription = "Pause",
                                                            tint = Color.White,
                                                            modifier = Modifier.size(13.dp)
                                                        )
                                                    }
                                                    Spacer(modifier = Modifier.width(10.dp))
                                                    Column(
                                                        modifier = Modifier.weight(1f),
                                                        verticalArrangement = Arrangement.spacedBy(1.dp)
                                                    ) {
                                                        Text(
                                                            text = "Stop #${item.pauseIndex + 1} ($durMin min rest)",
                                                            fontSize = 12.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = Color(0xFFFBBF24),
                                                            style = LocalTextStyle.current.copy(
                                                                platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                                                                lineHeight = 13.sp
                                                            )
                                                        )
                                                        Text(
                                                            text = String.format(Locale.getDefault(), "%s – %s • at %.2f km", pauseTimeStr, pauseEndTimeStr, pauseDistKm),
                                                            fontSize = 11.sp,
                                                            color = Color(0xFF94A3B8),
                                                            style = LocalTextStyle.current.copy(
                                                                platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                                                                lineHeight = 12.sp
                                                            )
                                                        )
                                                        if (item.legDurationMs > 0 || item.legDistanceMeters > 0) {
                                                            Text(
                                                                text = String.format(Locale.getDefault(), "+%.2f km • %s travel to stop", legKm, legTimeStr),
                                                                fontSize = 10.sp,
                                                                fontWeight = FontWeight.Medium,
                                                                color = Color(0xFF38BDF8),
                                                                style = LocalTextStyle.current.copy(
                                                                    platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                                                                    lineHeight = 11.sp
                                                                )
                                                            )
                                                        }
                                                    }
                                                }

                                                Spacer(modifier = Modifier.height(4.dp))

                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Button(
                                                        onClick = { onSplitPause(trip.id, item.pauseIndex) },
                                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                        shape = RoundedCornerShape(6.dp),
                                                        modifier = Modifier.height(26.dp)
                                                    ) {
                                                        Text("✂️ Split", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                                                    }

                                                    if (item.pauseIndex < trip.pauses.size - 1) {
                                                        Button(
                                                            onClick = { pauseToMergeIndex = item.pauseIndex },
                                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706)),
                                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                            shape = RoundedCornerShape(6.dp),
                                                            modifier = Modifier.height(28.dp)
                                                        ) {
                                                            Text("🔗 Merge", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                                                        }
                                                    }

                                                    Button(
                                                        onClick = { pauseToDeleteIndex = item.pauseIndex },
                                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7F1D1D)),
                                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                        shape = RoundedCornerShape(6.dp),
                                                        modifier = Modifier.height(28.dp)
                                                    ) {
                                                        Text("🗑️ Remove", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFFCA5A5))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 6. Action Buttons Footer
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { onShowOnMap(trip) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().height(44.dp)
                        ) {
                            Icon(Icons.Default.Explore, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Show Route on Map", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { onShare(trip) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f).height(40.dp)
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Share GPX", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }

                            Button(
                                onClick = { onDelete(trip) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f).height(40.dp)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Delete", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }

    if (pauseToDeleteIndex != null) {
        val idx = pauseToDeleteIndex!!
        AlertDialog(
            onDismissRequest = { pauseToDeleteIndex = null },
            title = { Text("Remove Stop #${idx + 1}?", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "This will remove the rest stop marker from the trip timeline. The GPS recorded route will remain unchanged.",
                    color = Color(0xFFCBD5E1)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeletePause(trip.id, idx)
                        pauseToDeleteIndex = null
                    }
                ) {
                    Text("Remove", color = Color(0xFFEF4444), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pauseToDeleteIndex = null }) {
                    Text("Cancel", color = Color.Gray)
                }
            },
            containerColor = Color(0xFF1E293B)
        )
    }

    if (pauseToMergeIndex != null) {
        val idx = pauseToMergeIndex!!
        AlertDialog(
            onDismissRequest = { pauseToMergeIndex = null },
            title = { Text("Merge Stops #${idx + 1} & #${idx + 2}?", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "This will combine Stop #${idx + 1} and Stop #${idx + 2} into a single consolidated rest stop spanning the entire duration.",
                    color = Color(0xFFCBD5E1)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onMergePause(trip.id, idx)
                        pauseToMergeIndex = null
                    }
                ) {
                    Text("Merge", color = Color(0xFFF59E0B), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pauseToMergeIndex = null }) {
                    Text("Cancel", color = Color.Gray)
                }
            },
            containerColor = Color(0xFF1E293B)
        )
    }
}


