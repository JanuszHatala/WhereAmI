package com.example.whereiam

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.whereiam.theme.WhereIAmTheme
import java.text.DateFormat
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

    private fun checkPermissionsAndStart() {
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.startTracking()
        } else {
            requestPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity

    val locationData by viewModel.locationData.collectAsState()
    val displayLanguage by viewModel.displayLanguage.collectAsState()
    val currentLatLng by viewModel.currentLatLng.collectAsState()
    val keepScreenOn by viewModel.keepScreenOn.collectAsState()
    val activeTrip by viewModel.activeTrip.collectAsState()
    val tripMode by viewModel.tripMode.collectAsState()
    val activityProfile by viewModel.activityProfile.collectAsState()
    val autoStopMinutes by viewModel.autoStopMinutes.collectAsState()
    val savedTrips by viewModel.savedTrips.collectAsState()
    val selectedTripIds by viewModel.selectedTripIds.collectAsState()
    val showBorders by viewModel.showBorders.collectAsState()
    val boundaryPoints by viewModel.boundaryPoints.collectAsState()
    val fitTrackTrigger by viewModel.fitTrackTrigger.collectAsState()
    val fitPlacesTrigger by viewModel.fitPlacesTrigger.collectAsState()
    val destinationPoint by viewModel.destinationPoint.collectAsState()
    val destinationItem by viewModel.destinationItem.collectAsState()
    val savedPlaces by viewModel.savedPlaces.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val localityCardStyle by viewModel.localityCardStyle.collectAsState()
    val orientationMode by viewModel.orientationMode.collectAsState()
    val showHeatMap by viewModel.showHeatMap.collectAsState()

    val powerPolicy by viewModel.powerPolicy.collectAsState()
    val isCharging by viewModel.isCharging.collectAsState()
    val lifecycleMode by viewModel.lifecycleMode.collectAsState()

    val selectedTripsList = remember(savedTrips, selectedTripIds) {
        savedTrips.filter { selectedTripIds.contains(it.id) }
    }

    var showTripsSheet by remember { mutableStateOf(false) }
    var sheetTab by remember { mutableStateOf(0) } // 0: Trip History, 1: Saved Places, 2: Stats
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showSearchDialog by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var tripToRename by remember { mutableStateOf<TripRecord?>(null) }
    var renameInputText by remember { mutableStateOf("") }

    // Save Place Dialog State
    var showSavePlaceDialog by remember { mutableStateOf(false) }
    var placeToSaveCoords by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var placeToSaveLocality by remember { mutableStateOf("") }
    var placeToSaveStreet by remember { mutableStateOf("") }
    var placeToSaveName by remember { mutableStateOf("") }
    var placeToSaveCategory by remember { mutableStateOf(PlaceCategory.HOME) }

    // Selected Saved Place Details Modal State
    var selectedSavedPlace by remember { mutableStateOf<SavedPlace?>(null) }
    var showDestinationDetailsCard by remember { mutableStateOf(false) }
    val liveSharingManager = remember { LiveSharingManager.getInstance(context) }
    val liveSession by liveSharingManager.currentSession.collectAsState()
    val staticLiveId by liveSharingManager.staticLiveId.collectAsState()
    val usbConnectionManager = remember { UsbConnectionManager.getInstance(context) }
    val isUsbConnected by usbConnectionManager.isUsbConnected.collectAsState()
    var showActiveTripRouteDialog by remember { mutableStateOf(false) }
    var showLiveShareDialog by remember { mutableStateOf(false) }
    var showInstantShareDialog by remember { mutableStateOf(false) }

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
    val isCompact = localityCardStyle == LocalityCardStyle.COMPACT
    val heatMapTracks = remember(savedTrips) { savedTrips.map { it.points } }

    if (isLandscape) {
        Row(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(0.38f)
                    .fillMaxHeight()
                    .background(Color(0xFF0F172A))
                    .padding(8.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!showTripsSheet) {
                        LocalityCard(
                            locationData = locationData,
                            activeTrip = activeTrip,
                            liveSession = liveSession,
                            savedPlaces = savedPlaces,
                            localityCardStyle = localityCardStyle,
                            activityProfile = activityProfile,
                            primaryPlace = primaryPlace,
                            secondaryPlace = secondaryPlace,
                            hierarchySubtitle = hierarchySubtitle,
                            currentLatLng = currentLatLng,
                            onShowActiveTripRoute = { showActiveTripRouteDialog = true },
                            onShowLiveShare = { showLiveShareDialog = true },
                            onSetLocalityCardStyle = { viewModel.setLocalityCardStyle(it) },
                            onSetActivityProfile = { viewModel.setActivityProfile(it) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    if (!showTripsSheet && destinationPoint != null) {
                        DestinationPlaceCard(
                            destinationPoint = destinationPoint!!,
                            destinationItem = destinationItem,
                            onClearDestination = { viewModel.setDestination(null) },
                            onNavigate = { lat, lng -> launchNavigation(context, lat, lng) },
                            onGoogleMaps = { lat, lng, title -> openInGoogleMaps(context, lat, lng, title) },
                            onSavePlace = { lat, lng, item ->
                                placeToSaveCoords = lat to lng
                                placeToSaveLocality = item?.subtitle?.split(",")?.firstOrNull()?.trim() ?: ""
                                placeToSaveStreet = item?.title ?: ""
                                placeToSaveName = item?.title ?: "My Place"
                                placeToSaveCategory = PlaceCategory.HOME
                                showSavePlaceDialog = true
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                if (!showTripsSheet) {
                    MainBottomControlsCard(
                        keepScreenOn = keepScreenOn,
                        isRecording = activeTrip != null,
                        onToggleKeepScreenOn = { viewModel.toggleKeepScreenOn() },
                        onToggleTripRecording = {
                            if (activeTrip != null) viewModel.stopManualTrip() else viewModel.startManualTrip()
                        },
                        onShowTripsSheet = { showTripsSheet = true },
                        onShowSearch = { showSearchDialog = true },
                        onSaveLocation = {
                            val lat = currentLatLng?.first
                            val lng = currentLatLng?.second
                            if (lat != null && lng != null) {
                                val currentPlace = locationData.primaryPlace
                                placeToSaveCoords = lat to lng
                                placeToSaveLocality = currentPlace?.city ?: ""
                                placeToSaveStreet = currentPlace?.street ?: ""
                                placeToSaveName = currentPlace?.let { if (!it.street.isNullOrBlank()) "${it.city}, ${it.street}" else it.city } ?: "My Location"
                                placeToSaveCategory = PlaceCategory.HOME
                                showSavePlaceDialog = true
                            }
                        },
                        onShowSettings = { showSettingsSheet = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    )
                }
            }

            Box(modifier = Modifier.weight(0.62f).fillMaxHeight()) {
                OsmMapView(
                    latLng = currentLatLng,
                    trackPoints = activeTrip?.points ?: emptyList(),
                    selectedTrips = selectedTripsList,
                    savedPlaces = savedPlaces,
                    boundaryPoints = boundaryPoints,
                    heatMapTracks = heatMapTracks,
                    showHeatMap = showHeatMap,
                    onToggleHeatMap = { viewModel.toggleShowHeatMap() },
                    fitTrackTrigger = fitTrackTrigger,
                    fitPlacesTrigger = fitPlacesTrigger,
                    destinationPoint = destinationPoint,
                    onDestinationMarkerClick = { showDestinationDetailsCard = true },
                    onSavedPlaceClick = { sp -> selectedSavedPlace = sp },
                    onClearDestination = { viewModel.setDestination(null) },
                    onMapClick = { gp -> viewModel.selectMapPoint(gp) },
                    activityProfile = activityProfile,
                    isCompact = localityCardStyle == LocalityCardStyle.COMPACT,
                    orientationMode = orientationMode,
                    onOrientationModeChange = { viewModel.setOrientationMode(it) },
                    onInstantShare = { showInstantShareDialog = true },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    } else {
        Box(modifier = Modifier.fillMaxSize()) {
            OsmMapView(
                latLng = currentLatLng,
                trackPoints = activeTrip?.points ?: emptyList(),
                selectedTrips = selectedTripsList,
                savedPlaces = savedPlaces,
                boundaryPoints = boundaryPoints,
                heatMapTracks = heatMapTracks,
                showHeatMap = showHeatMap,
                onToggleHeatMap = { viewModel.toggleShowHeatMap() },
                fitTrackTrigger = fitTrackTrigger,
                fitPlacesTrigger = fitPlacesTrigger,
                destinationPoint = destinationPoint,
                onDestinationMarkerClick = { showDestinationDetailsCard = true },
                onSavedPlaceClick = { sp -> selectedSavedPlace = sp },
                onClearDestination = { viewModel.setDestination(null) },
                onMapClick = { gp -> viewModel.selectMapPoint(gp) },
                activityProfile = activityProfile,
                isCompact = localityCardStyle == LocalityCardStyle.COMPACT,
                orientationMode = orientationMode,
                onOrientationModeChange = { viewModel.setOrientationMode(it) },
                onInstantShare = { showInstantShareDialog = true },
                modifier = Modifier.fillMaxSize()
            )

            if (!showTripsSheet) {
                LocalityCard(
                    locationData = locationData,
                    activeTrip = activeTrip,
                    liveSession = liveSession,
                    savedPlaces = savedPlaces,
                    localityCardStyle = localityCardStyle,
                    activityProfile = activityProfile,
                    primaryPlace = primaryPlace,
                    secondaryPlace = secondaryPlace,
                    hierarchySubtitle = hierarchySubtitle,
                    currentLatLng = currentLatLng,
                    onShowActiveTripRoute = { showActiveTripRouteDialog = true },
                    onShowLiveShare = { showLiveShareDialog = true },
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
                        .align(Alignment.TopCenter)
                )
            }

            if (!showTripsSheet && destinationPoint != null) {
                DestinationPlaceCard(
                    destinationPoint = destinationPoint!!,
                    destinationItem = destinationItem,
                    onClearDestination = { viewModel.setDestination(null) },
                    onNavigate = { lat, lng -> launchNavigation(context, lat, lng) },
                    onGoogleMaps = { lat, lng, title -> openInGoogleMaps(context, lat, lng, title) },
                    onSavePlace = { lat, lng, item ->
                        placeToSaveCoords = lat to lng
                        placeToSaveLocality = item?.subtitle?.split(",")?.firstOrNull()?.trim() ?: ""
                        placeToSaveStreet = item?.title ?: ""
                        placeToSaveName = item?.title ?: "My Place"
                        placeToSaveCategory = PlaceCategory.HOME
                        showSavePlaceDialog = true
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 16.dp, end = 16.dp, bottom = 96.dp)
                        .fillMaxWidth()
                )
            }

            if (!showTripsSheet) {
                MainBottomControlsCard(
                    keepScreenOn = keepScreenOn,
                    isRecording = activeTrip != null,
                    onToggleKeepScreenOn = { viewModel.toggleKeepScreenOn() },
                    onToggleTripRecording = {
                        if (activeTrip != null) viewModel.stopManualTrip() else viewModel.startManualTrip()
                    },
                    onShowTripsSheet = { showTripsSheet = true },
                    onShowSearch = { showSearchDialog = true },
                    onSaveLocation = {
                        val lat = currentLatLng?.first
                        val lng = currentLatLng?.second
                        if (lat != null && lng != null) {
                            val currentPlace = locationData.primaryPlace
                            placeToSaveCoords = lat to lng
                            placeToSaveLocality = currentPlace?.city ?: ""
                            placeToSaveStreet = currentPlace?.street ?: ""
                            placeToSaveName = currentPlace?.let { if (!it.street.isNullOrBlank()) "${it.city}, ${it.street}" else it.city } ?: "My Location"
                            placeToSaveCategory = PlaceCategory.HOME
                            showSavePlaceDialog = true
                        }
                    },
                    onShowSettings = { showSettingsSheet = true },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 12.dp, vertical = 20.dp)
                        .widthIn(max = 440.dp)
                        .fillMaxWidth()
                )
            }
        }
    }

        // ── 4. Places Passed & Trips History Full-Screen Screen Overlay ────────
        if (showTripsSheet) {
            BackHandler {
                showTripsSheet = false
            }

            var tripSearchQuery by remember { mutableStateOf("") }
            var selectedActivityFilter by remember { mutableStateOf<ActivityProfile?>(null) }
            var selectedDateFilter by remember { mutableStateOf("ALL") } // ALL, TODAY, WEEK, MONTH

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
                    // Top Bar with Close Button
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Places & Trips",
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
                                        shape = RoundedCornerShape(8.dp)
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
                                            placeToSaveCategory = PlaceCategory.HOME
                                            showSavePlaceDialog = true
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    shape = RoundedCornerShape(8.dp)
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
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(savedPlaces) { place ->
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                showTripsSheet = false
                                                viewModel.setDestination(
                                                    SearchResultItem(
                                                        title = "${place.category.iconEmoji} ${place.name}",
                                                        subtitle = listOfNotNull(
                                                            place.street.takeIf { it.isNotBlank() },
                                                            place.locality.takeIf { it.isNotBlank() }
                                                        ).joinToString(", "),
                                                        geoPoint = place.geoPoint
                                                    )
                                                )
                                            },
                                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                                        shape = RoundedCornerShape(14.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(14.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Text(place.category.iconEmoji, fontSize = 24.sp)
                                                Spacer(modifier = Modifier.width(10.dp))
                                                Column {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Text(
                                                            place.name,
                                                            color = Color.White,
                                                            fontWeight = FontWeight.Bold,
                                                            fontSize = 15.sp
                                                        )
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Surface(
                                                            color = Color(0xFF0F172A),
                                                            shape = RoundedCornerShape(4.dp)
                                                        ) {
                                                            Text(
                                                                text = place.category.displayName,
                                                                color = Color(0xFF10B981),
                                                                fontSize = 10.sp,
                                                                fontWeight = FontWeight.SemiBold,
                                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                            )
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
                                                            fontSize = 12.sp,
                                                            modifier = Modifier.padding(top = 2.dp)
                                                        )
                                                    }
                                                }
                                            }

                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                // Navigate To
                                                IconButton(
                                                    onClick = {
                                                        launchNavigation(context, place.latitude, place.longitude)
                                                    },
                                                    modifier = Modifier.size(32.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Navigation,
                                                        contentDescription = "Navigate To",
                                                        tint = Color(0xFF38BDF8),
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                }
                                                Spacer(modifier = Modifier.width(4.dp))

                                                // Open in Google Maps
                                                IconButton(
                                                    onClick = {
                                                        openInGoogleMaps(context, place.latitude, place.longitude, place.name)
                                                    },
                                                    modifier = Modifier.size(32.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Map,
                                                        contentDescription = "Open in Google Maps",
                                                        tint = Color(0xFF10B981),
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                }
                                                Spacer(modifier = Modifier.width(4.dp))

                                                // Delete
                                                IconButton(
                                                    onClick = { viewModel.deleteSavedPlace(place.id) },
                                                    modifier = Modifier.size(32.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Delete,
                                                        contentDescription = "Delete Saved Place",
                                                        tint = Color(0xFFF87171),
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                }
                                            }
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
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("●", color = Color(0xFFEF4444), fontSize = 14.sp)
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "Active Trip Recording",
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF38BDF8)
                                            )
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically) {
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
                                                        Text(
                                                            text = "${activityProfile.iconEmoji} ${activityProfile.displayName}",
                                                            fontSize = 11.sp,
                                                            fontWeight = FontWeight.SemiBold,
                                                            color = Color(0xFF38BDF8)
                                                        )
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
                                                                    Text(profile.iconEmoji, fontSize = 14.sp)
                                                                    Spacer(modifier = Modifier.width(8.dp))
                                                                    Text(profile.displayName, color = Color.White, fontSize = 13.sp)
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
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Button(
                                                onClick = {
                                                    showTripsSheet = false
                                                    viewModel.triggerFitTrack()
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                shape = RoundedCornerShape(8.dp)
                                            ) {
                                                Text("Fit Map", fontSize = 11.sp)
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

                        // Search and Filter Bar (contentPadding ensures placeholder text is never vertically cut off)
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

                        // Filter Chips Row (Activity & Date Range)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Date Range Chips
                            listOf(
                                "ALL" to "All Time",
                                "TODAY" to "Today",
                                "WEEK" to "This Week",
                                "MONTH" to "This Month"
                            ).forEach { (key, label) ->
                                val selected = selectedDateFilter == key
                                FilterChip(
                                    selected = selected,
                                    onClick = { selectedDateFilter = key },
                                    label = { Text(label, fontSize = 11.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Color(0xFF0284C7),
                                        selectedLabelColor = Color.White,
                                        containerColor = Color(0xFF1E293B),
                                        labelColor = Color.LightGray
                                    )
                                )
                            }

                            Spacer(modifier = Modifier.width(4.dp))

                            // Activity Mode Chips
                            listOf(
                                null to "All Modes",
                                ActivityProfile.CAR to "🚗 Driving",
                                ActivityProfile.CYCLING to "🚴 Cycling",
                                ActivityProfile.MTB to "🚵 MTB",
                                ActivityProfile.HIKING to "🥾 Hiking",
                                ActivityProfile.RUNNING to "🏃 Running",
                                ActivityProfile.WALKING to "🚶 Walking"
                            ).forEach { (profile, label) ->
                                val selected = selectedActivityFilter == profile
                                FilterChip(
                                    selected = selected,
                                    onClick = { selectedActivityFilter = profile },
                                    label = { Text(label, fontSize = 11.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Color(0xFF10B981),
                                        selectedLabelColor = Color.White,
                                        containerColor = Color(0xFF1E293B),
                                        labelColor = Color.LightGray
                                    )
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Multi-Select Action Bar (Fit Map, Merge, Select All / Deselect All)
                        val allFilteredSelected = filteredTrips.isNotEmpty() && filteredTrips.all { selectedTripIds.contains(it.id) }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Trips (${filteredTrips.size}${if (filteredTrips.size != savedTrips.size) " of ${savedTrips.size}" else ""})",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (selectedTripIds.size >= 2) {
                                    Button(
                                        onClick = { viewModel.mergeSelectedTrips() },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6)),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                        modifier = Modifier.height(30.dp),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Icon(Icons.Default.CallMerge, contentDescription = "Merge", tint = Color.White, modifier = Modifier.size(13.dp))
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Text("Merge (${selectedTripIds.size})", color = Color.White, fontSize = 11.sp)
                                    }
                                }

                                if (selectedTripIds.isNotEmpty()) {
                                    Button(
                                        onClick = {
                                            showTripsSheet = false
                                            viewModel.triggerFitTrack()
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                        modifier = Modifier.height(30.dp),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Icon(Icons.Default.CropFree, contentDescription = "Fit Map", tint = Color.White, modifier = Modifier.size(13.dp))
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Text("Fit", color = Color.White, fontSize = 11.sp)
                                    }

                                    TextButton(
                                        onClick = { viewModel.clearTripSelection() },
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text("Clear (${selectedTripIds.size})", color = Color(0xFFF87171), fontSize = 11.sp)
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
                                        text = if (allFilteredSelected) "Deselect" else "Select All (${filteredTrips.size})",
                                        color = Color(0xFF38BDF8),
                                        fontSize = 11.sp
                                    )
                                }
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
                                items(filteredTrips) { trip ->
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
                                                        fontSize = 14.sp
                                                    )
                                                    if (trip.title.isNotBlank()) {
                                                        Text(
                                                            text = defaultTitle,
                                                            color = Color(0xFF64748B),
                                                            fontSize = 11.sp
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
                                                        onClick = { viewModel.deleteTrip(trip.id) },
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

                                            var showProfileMenu by remember { mutableStateOf(false) }
                                            Row(
                                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                val distKm = trip.distanceMeters / 1000.0
                                                Text(
                                                    text = String.format(Locale.getDefault(), "Distance: %.2f km • Max: %.1f km/h", distKm, trip.maxSpeedKmh),
                                                    color = Color(0xFF38BDF8),
                                                    fontSize = 12.sp
                                                )

                                                Box {
                                                    Surface(
                                                        shape = RoundedCornerShape(8.dp),
                                                        color = Color(0xFF0F172A),
                                                        modifier = Modifier.clickable { showProfileMenu = true }
                                                    ) {
                                                        Row(
                                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Text(
                                                                text = "${trip.activityProfile.iconEmoji} ${trip.activityProfile.displayName}",
                                                                fontSize = 11.sp,
                                                                fontWeight = FontWeight.SemiBold,
                                                                color = Color(0xFF34D399)
                                                            )
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
                                                                        Text(profile.iconEmoji, fontSize = 14.sp)
                                                                        Spacer(modifier = Modifier.width(8.dp))
                                                                        Text(profile.displayName, color = Color.White, fontSize = 13.sp)
                                                                    }
                                                                },
                                                                onClick = {
                                                                    showProfileMenu = false
                                                                    viewModel.updateTripActivityProfile(trip.id, profile)
                                                                }
                                                            )
                                                        }
                                                    }
                                                }
                                            }

                                            if (trip.placesVisited.isNotEmpty()) {
                                                val placesSummary = trip.placesVisited.joinToString(" → ") { it.placeName }
                                                Text(
                                                    text = "Route: $placesSummary",
                                                    color = Color(0xFFCBD5E1),
                                                    fontSize = 11.sp,
                                                    maxLines = 2,
                                                    modifier = Modifier.padding(top = 4.dp)
                                                )
                                            }

                                            // Pauses & Trip Splitting
                                            if (trip.pauses.isNotEmpty()) {
                                                Spacer(modifier = Modifier.height(6.dp))
                                                Text(
                                                    text = "⏸️ Rest Pauses (${trip.pauses.size}):",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = Color(0xFFF59E0B)
                                                )
                                                trip.pauses.forEachIndexed { pauseIdx, pause ->
                                                    val pauseTimeStr = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(pause.startTime))
                                                    val durMin = (pause.durationMs / 60000L).coerceAtLeast(1)
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(vertical = 2.dp)
                                                            .background(Color(0xFF0F172A), RoundedCornerShape(6.dp))
                                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Text(
                                                            text = "#${pauseIdx + 1} at $pauseTimeStr (${durMin} min rest)",
                                                            fontSize = 11.sp,
                                                            color = Color(0xFFE2E8F0)
                                                        )
                                                        TextButton(
                                                            onClick = {
                                                                viewModel.splitTripAtPause(trip.id, pauseIdx)
                                                                android.widget.Toast.makeText(context, "Split trip at pause #${pauseIdx + 1}", android.widget.Toast.LENGTH_SHORT).show()
                                                            },
                                                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                                            modifier = Modifier.height(26.dp)
                                                        ) {
                                                            Text("✂️ Split Here", fontSize = 10.sp, color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold)
                                                        }
                                                    }
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
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Card(
                                    modifier = Modifier.weight(1f),
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("Total Distance", fontSize = 11.sp, color = Color(0xFF94A3B8))
                                        Text(
                                            text = String.format(Locale.getDefault(), "%.1f km", totalDistanceKm),
                                            fontSize = 17.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF38BDF8)
                                        )
                                    }
                                }

                                Card(
                                    modifier = Modifier.weight(1f),
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("Total Trips", fontSize = 11.sp, color = Color(0xFF94A3B8))
                                        Text(
                                            text = "${savedTrips.size}",
                                            fontSize = 17.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF10B981)
                                        )
                                    }
                                }

                                Card(
                                    modifier = Modifier.weight(1f),
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("Top Speed", fontSize = 11.sp, color = Color(0xFF94A3B8))
                                        Text(
                                            text = String.format(Locale.getDefault(), "%.0f km/h", maxSpeedOverall),
                                            fontSize = 17.sp,
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
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 14.dp, vertical = 10.dp),
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
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

    // Rename Trip Dialog
    if (tripToRename != null) {
        AlertDialog(
            onDismissRequest = { tripToRename = null },
            title = { Text("Rename Trip", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = renameInputText,
                    onValueChange = { renameInputText = it },
                    label = { Text("Trip Title") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF38BDF8),
                        unfocusedBorderColor = Color(0xFF64748B)
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        tripToRename?.let { trip ->
                            viewModel.renameTrip(trip.id, renameInputText.trim())
                        }
                        tripToRename = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { tripToRename = null }) {
                    Text("Cancel", color = Color.LightGray)
                }
            },
            containerColor = Color(0xFF1E293B)
        )
    }

    // ── 5. Quick Settings & Options Dialog ─────────────────────────────────────
    if (showSettingsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSettingsSheet = false },
            containerColor = Color(0xFF0F172A),
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .navigationBarsPadding()
            ) {
                Text(
                    text = "App Settings",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(16.dp))

                // Activity Profile Selector (Driving, Cycling, Running, Walking)
                Text(
                    text = "Activity Profile",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                var settingsProfileMenuExpanded by remember { mutableStateOf(false) }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { settingsProfileMenuExpanded = true },
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "${activityProfile.iconEmoji}  ${activityProfile.displayName}",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                val thresholdInfo = if (activityProfile == ActivityProfile.WALKING || activityProfile == ActivityProfile.RUNNING) {
                                    "Pace-first"
                                } else "Speed-first"
                                Text(
                                    text = "($thresholdInfo)",
                                    fontSize = 12.sp,
                                    color = Color(0xFF94A3B8)
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = "Select Profile",
                                tint = Color(0xFF38BDF8)
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = settingsProfileMenuExpanded,
                        onDismissRequest = { settingsProfileMenuExpanded = false },
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                            .background(Color(0xFF0F172A))
                    ) {
                        ActivityProfile.values().forEach { profile ->
                            val isSelected = activityProfile == profile
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "${profile.iconEmoji}  ${profile.displayName}",
                                            color = if (isSelected) Color(0xFF38BDF8) else Color.White,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            fontSize = 15.sp
                                        )
                                        Text(
                                            text = "Auto-start: >${profile.autoStartSpeedKmh.toInt()} km/h",
                                            color = Color(0xFF64748B),
                                            fontSize = 12.sp
                                        )
                                    }
                                },
                                onClick = {
                                    viewModel.setActivityProfile(profile)
                                    settingsProfileMenuExpanded = false
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Trip Recording Mode (Manual vs Auto)
                Text(
                    text = "Trip Recording Mode",
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
                    Button(
                        onClick = { viewModel.setTripMode(TripMode.MANUAL) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (tripMode == TripMode.MANUAL) Color(0xFF0284C7) else Color(0xFF1E293B)
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Manual")
                    }
                    Button(
                        onClick = { viewModel.setTripMode(TripMode.AUTO) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (tripMode == TripMode.AUTO) Color(0xFF0284C7) else Color(0xFF1E293B)
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Automatic")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Auto-Stop Stationary Timeout
                Text(
                    text = "Auto-Stop Trip Timeout (stationary)",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(1, 3, 5, 10).forEach { mins ->
                        val isSelected = autoStopMinutes == mins
                        Button(
                            onClick = { viewModel.setAutoStopMinutes(mins) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isSelected) Color(0xFF0284C7) else Color(0xFF1E293B)
                            ),
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                        ) {
                            Text("${mins}m", fontSize = 13.sp)
                        }
                    }
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

                // ── Offline Map Cache Management ──────────────────────────────
                Text(
                    text = "Offline Map Cache",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                val tileDir = remember { org.osmdroid.config.Configuration.getInstance().osmdroidTileCache }
                val cacheDownloadState by MapCacheHelper.downloadState.collectAsState()
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
                    color = Color(0xFF94A3B8),
                    modifier = Modifier.padding(top = 2.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            try {
                                tileDir.deleteRecursively()
                                tileDir.mkdirs()
                                tileCacheSizeMb = 0L
                                android.widget.Toast.makeText(context, "Offline map cache cleared", android.widget.Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                android.widget.Toast.makeText(context, "Error: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = "Clear Cache", modifier = Modifier.size(16.dp), tint = Color(0xFFF87171))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Clear Cache", color = Color(0xFFF87171), fontSize = 13.sp)
                    }
                    val isDownloadingTiles = cacheDownloadState is CacheDownloadState.Downloading
                    Button(
                        onClick = {
                            val lat = currentLatLng?.first ?: 50.0647
                            val lng = currentLatLng?.second ?: 19.9450
                            MapCacheHelper.startCachingRegion(context, lat, lng)
                        },
                        enabled = !isDownloadingTiles,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF1E293B),
                            disabledContainerColor = Color(0xFF1E293B).copy(alpha = 0.5f)
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = "Pre-cache", modifier = Modifier.size(16.dp), tint = Color(0xFF38BDF8))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isDownloadingTiles) "Downloading..." else "Cache Region", color = Color(0xFF38BDF8), fontSize = 13.sp)
                    }
                }

                // Download progress & feedback
                when (val state = cacheDownloadState) {
                    is CacheDownloadState.Downloading -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF1E293B))
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
                                    color = Color(0xFF38BDF8)
                                )
                                Text(
                                    text = "${state.percent}%",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF38BDF8)
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { state.percent / 100f },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = Color(0xFF38BDF8),
                                trackColor = Color(0xFF0F172A)
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
                                    Text("Cancel", fontSize = 11.sp, color = Color(0xFFF87171))
                                }
                            }
                        }
                    }
                    is CacheDownloadState.Completed -> {
                        Text(
                            text = "✅ Cached ${state.totalTiles} tiles (+${String.format(Locale.getDefault(), "%.1f", state.addedMb)} MB)",
                            fontSize = 12.sp,
                            color = Color(0xFF10B981),
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                    is CacheDownloadState.Failed -> {
                        Text(
                            text = "❌ Cache download failed: ${state.error}",
                            fontSize = 12.sp,
                            color = Color(0xFFEF4444),
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                    else -> {}
                }

                Spacer(modifier = Modifier.height(20.dp))

                // ── Live Location Sharing (Synology & Cloud) ────────────────────
                Text(
                    text = "Live Location Sharing",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                Text(
                    text = if (liveSession?.isActive == true) "Active sharing: ${liveSession!!.id} (Sync: ${liveSession!!.syncIntervalMinutes}m)"
                           else "Share real-time position, route line, speed, and mountain peaks with friends & family",
                    fontSize = 12.sp,
                    color = if (liveSession?.isActive == true) Color(0xFF10B981) else Color(0xFF94A3B8),
                    modifier = Modifier.padding(top = 2.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { showLiveShareDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = if (liveSession?.isActive == true) Color(0xFF059669) else Color(0xFF0284C7)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.ShareLocation, contentDescription = "Live Share", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (liveSession?.isActive == true) "Manage Live Share" else "Start Live Share", fontSize = 13.sp)
                    }
                }
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
                                containerColor = if (isSelected) Color(0xFF0284C7) else Color(0xFF1E293B)
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(label, fontSize = 14.sp)
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
                                containerColor = if (isSelected) Color(0xFF0284C7) else Color(0xFF1E293B)
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(label)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

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
                    Text("Close", color = Color.LightGray)
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
                        listOf(
                            PlaceCategory.HOME,
                            PlaceCategory.WORK,
                            PlaceCategory.FAMILY,
                            PlaceCategory.FAVORITE,
                            PlaceCategory.CUSTOM
                        ).forEach { cat ->
                            val isSelected = placeToSaveCategory == cat
                            Surface(
                                onClick = {
                                    placeToSaveCategory = cat
                                    if (placeToSaveName.isBlank() || PlaceCategory.values().any { it.displayName == placeToSaveName }) {
                                        placeToSaveName = cat.displayName
                                    }
                                },
                                shape = RoundedCornerShape(20.dp),
                                color = if (isSelected) Color(0xFF10B981) else Color(0xFF1E293B),
                                border = if (isSelected) null else androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF475569)),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text(cat.iconEmoji, fontSize = 14.sp)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = cat.displayName,
                                        color = if (isSelected) Color.White else Color.LightGray,
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    OutlinedTextField(
                        value = placeToSaveName,
                        onValueChange = { placeToSaveName = it },
                        label = { Text("Place Name") },
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
                                street = placeToSaveStreet.trim()
                            )
                        }
                        showSavePlaceDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSavePlaceDialog = false }) {
                    Text("Cancel", color = Color.LightGray)
                }
            },
            containerColor = Color(0xFF1E293B)
        )
    }

    // ── 8. Saved Place Details Dialog (when tapping a saved place marker on map) ────
    if (selectedSavedPlace != null) {
        val sp = selectedSavedPlace!!
        AlertDialog(
            onDismissRequest = { selectedSavedPlace = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(sp.category.iconEmoji, fontSize = 24.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(sp.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
            },
            text = {
                Column(modifier = Modifier.padding(top = 4.dp)) {
                    val addr = listOfNotNull(
                        sp.street.takeIf { it.isNotBlank() },
                        sp.locality.takeIf { it.isNotBlank() }
                    ).joinToString(", ")
                    if (addr.isNotBlank()) {
                        Text(text = "📍 $addr", color = Color.White, fontSize = 14.sp)
                    }
                    Text(
                        text = String.format(Locale.US, "Coordinates: %.5f, %.5f", sp.latitude, sp.longitude),
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                launchNavigation(context, sp.latitude, sp.longitude)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Navigation, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Navigate", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }

                        Button(
                            onClick = {
                                openInGoogleMaps(context, sp.latitude, sp.longitude, sp.name)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Map, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Maps", color = Color.White, fontSize = 12.sp)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteSavedPlace(sp.id)
                        selectedSavedPlace = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedSavedPlace = null }) {
                    Text("Close", color = Color.LightGray)
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
                    .fillMaxWidth()
                    .fillMaxHeight(0.75f)
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
                            Text(
                                text = "🚩 Current Trip Route",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "${activeTrip!!.placesVisited.size} places • ${(activeTrip!!.distanceMeters / 1000.0).let { String.format(Locale.getDefault(), "%.1f km", it) }}",
                                fontSize = 12.sp,
                                color = Color(0xFF94A3B8)
                            )
                        }
                        IconButton(onClick = { showActiveTripRouteDialog = false }) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.LightGray)
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
        val prefs = context.getSharedPreferences("where_i_am_live_share_prefs", android.content.Context.MODE_PRIVATE)
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
        var activeShareModeStatic by remember { mutableStateOf(prefs.getBoolean(LiveSharingManager.KEY_PREF_LINK_MODE_STATIC, false)) }

        Dialog(
            onDismissRequest = { showLiveShareDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("📡", fontSize = 20.sp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Live Location Sharing",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                        IconButton(onClick = { showLiveShareDialog = false }) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.LightGray)
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    if (liveSession?.isActive == true) {
                        // ── Active Session View ──
                        val session = liveSession!!
                        val viewerUrl = session.getViewerUrl(useStatic = activeShareModeStatic, staticId = staticLiveId)
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                var isEditingTitle by remember { mutableStateOf(false) }
                                var editingTitleText by remember(session.title) { mutableStateOf(session.title) }

                                if (isEditingTitle) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        OutlinedTextField(
                                            value = editingTitleText,
                                            onValueChange = { editingTitleText = it },
                                            singleLine = true,
                                            modifier = Modifier.weight(1f),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedTextColor = Color.White,
                                                unfocusedTextColor = Color.White,
                                                focusedBorderColor = Color(0xFF38BDF8),
                                                unfocusedBorderColor = Color(0xFF64748B)
                                            )
                                        )
                                        IconButton(onClick = {
                                            if (editingTitleText.isNotBlank()) {
                                                liveSharingManager.renameSession(editingTitleText)
                                                isEditingTitle = false
                                                android.widget.Toast.makeText(context, "Session renamed", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        }) {
                                            Icon(Icons.Default.Check, contentDescription = "Save Title", tint = Color(0xFF10B981))
                                        }
                                        IconButton(onClick = {
                                            editingTitleText = session.title
                                            isEditingTitle = false
                                        }) {
                                            Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color.LightGray)
                                        }
                                    }
                                } else {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.weight(1f, fill = false)
                                        ) {
                                            Text(
                                                text = session.title,
                                                fontSize = 16.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            IconButton(
                                                onClick = {
                                                    editingTitleText = session.title
                                                    isEditingTitle = true
                                                },
                                                modifier = Modifier.size(28.dp).padding(start = 4.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.Edit,
                                                    contentDescription = "Rename Session",
                                                    tint = Color(0xFF38BDF8),
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(if (session.isPaused) Color(0x33F59E0B) else Color(0x3310B981))
                                                .padding(horizontal = 8.dp, vertical = 3.dp)
                                        ) {
                                            Text(
                                                text = if (session.isPaused) "PAUSED" else "ACTIVE",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (session.isPaused) Color(0xFFF59E0B) else Color(0xFF10B981)
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(6.dp))
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = "Trip Slug: ${session.id}",
                                        fontSize = 12.sp,
                                        color = Color(0xFF38BDF8),
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    if (staticLiveId.isNotBlank()) {
                                        Text(
                                            text = "Personal ID: $staticLiveId",
                                            fontSize = 12.sp,
                                            color = Color(0xFF10B981),
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.padding(top = 2.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))

                                // Live countdown / elapsed ticker (LIV-R02)
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
                                        if (hrs > 0) "⏱️ ${hrs}h ${mins}m ${secs}s elapsed (Continuous)" else "⏱️ ${mins}m ${secs}s elapsed (Continuous)"
                                    } else {
                                        val rem = maxOf(0L, session.expiresAt - tickerNow)
                                        if (rem <= 0L) {
                                            "Expired"
                                        } else {
                                            val hrs = rem / 3600000L
                                            val mins = (rem % 3600000L) / 60000L
                                            val secs = (rem % 60000L) / 1000L
                                            if (hrs > 0) "${hrs}h ${mins}m ${secs}s left" else "${mins}m ${secs}s left"
                                        }
                                    }
                                }

                                Text("Status / Time: $formattedRemaining", fontSize = 12.sp, color = Color(0xFFFBBF24), fontWeight = FontWeight.Medium)
                                Spacer(modifier = Modifier.height(10.dp))

                                // Link Mode Dropdown: Trip vs Personal Static
                                Text("Select Link to Share:", fontSize = 11.sp, color = Color(0xFF94A3B8))
                                var linkModeDropdownExpanded by remember { mutableStateOf(false) }
                                Box(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                                    Surface(
                                        onClick = { linkModeDropdownExpanded = true },
                                        shape = RoundedCornerShape(8.dp),
                                        color = Color(0xFF1E293B),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = if (activeShareModeStatic) "📡 Personal Link (Static)" else "🎫 Trip Link (Random)",
                                                color = Color.White,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                            Icon(Icons.Default.ArrowDropDown, contentDescription = "Select", tint = Color(0xFF94A3B8))
                                        }
                                    }
                                    DropdownMenu(
                                        expanded = linkModeDropdownExpanded,
                                        onDismissRequest = { linkModeDropdownExpanded = false },
                                        modifier = Modifier.background(Color(0xFF1E293B))
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("🎫 Trip Link (Random)", color = Color.White, fontSize = 13.sp) },
                                            onClick = {
                                                activeShareModeStatic = false
                                                linkModeDropdownExpanded = false
                                                prefs.edit().putBoolean(LiveSharingManager.KEY_PREF_LINK_MODE_STATIC, false).apply()
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("📡 Personal Link (Static)", color = Color.White, fontSize = 13.sp) },
                                            onClick = {
                                                activeShareModeStatic = true
                                                linkModeDropdownExpanded = false
                                                prefs.edit().putBoolean(LiveSharingManager.KEY_PREF_LINK_MODE_STATIC, true).apply()
                                            }
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = if (activeShareModeStatic) {
                                        "Permanent link: visitors can bookmark this link to view any live journey."
                                    } else {
                                        "Single-trip link: ephemeral link valid only for this session."
                                    },
                                    fontSize = 11.sp,
                                    color = Color(0xFF38BDF8)
                                )

                                Surface(
                                    color = Color(0xFF0F172A),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                                ) {
                                    Text(
                                        text = viewerUrl,
                                        fontSize = 11.sp,
                                        color = Color.LightGray,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.height(10.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            val shareText = if (activeShareModeStatic) {
                                                "Follow my live journey anytime on WhereAmI: $viewerUrl"
                                            } else {
                                                "Follow my live track on WhereAmI: $viewerUrl"
                                            }
                                            val sendIntent = Intent().apply {
                                                action = Intent.ACTION_SEND
                                                putExtra(Intent.EXTRA_TEXT, shareText)
                                                type = "text/plain"
                                            }
                                            context.startActivity(Intent.createChooser(sendIntent, "Share Live Track Link"))
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.Share, contentDescription = "Share", modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Share", fontSize = 12.sp)
                                    }
                                    Button(
                                        onClick = {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                            val clip = android.content.ClipData.newPlainText("WhereAmI Live Link", viewerUrl)
                                            clipboard.setPrimaryClip(clip)
                                            val label = if (activeShareModeStatic) "Personal Static Link" else "Trip Link"
                                            android.widget.Toast.makeText(context, "$label copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Copy", fontSize = 12.sp)
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                // Live Privacy: Full Trail vs Position Only
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
                                            containerColor = if (session.trailVisible) Color(0xFF0284C7) else Color(0xFF1E293B)
                                        ),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("🗺️ Full Trail", fontSize = 11.sp, fontWeight = if (session.trailVisible) FontWeight.Bold else FontWeight.Normal)
                                    }
                                    Button(
                                        onClick = {
                                            liveSharingManager.setTrailVisible(false)
                                            android.widget.Toast.makeText(context, "Visitors see current pin only", android.widget.Toast.LENGTH_SHORT).show()
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (!session.trailVisible) Color(0xFF0284C7) else Color(0xFF1E293B)
                                        ),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("📍 Position Only", fontSize = 11.sp, fontWeight = if (!session.trailVisible) FontWeight.Bold else FontWeight.Normal)
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                // Live Update Frequency Selector (LIV-R04)
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
                                                containerColor = if (isSel) Color(0xFF0284C7) else Color(0xFF1E293B)
                                            ),
                                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text("${mins}m", fontSize = 12.sp, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal)
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                // Selective & Master Pause / Resume (LIV-R05)
                                Text("Pause / Resume Controls:", fontSize = 11.sp, color = Color(0xFF94A3B8))
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    // Personal Static Link Pause/Resume
                                    Button(
                                        onClick = {
                                            if (session.isPersonalPaused) {
                                                liveSharingManager.resumePersonalLink()
                                                android.widget.Toast.makeText(context, "Personal Link resumed", android.widget.Toast.LENGTH_SHORT).show()
                                            } else {
                                                liveSharingManager.pausePersonalLink()
                                                android.widget.Toast.makeText(context, "Personal Link paused", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (session.isPersonalPaused) Color(0xFF059669) else Color(0xFF334155)
                                        ),
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            if (session.isPersonalPaused) "▶ Resume Static" else "⏸ Pause Static",
                                            fontSize = 11.sp
                                        )
                                    }

                                    // Random Trip Link Pause/Resume
                                    Button(
                                        onClick = {
                                            if (session.isRandomPaused) {
                                                liveSharingManager.resumeRandomLink()
                                                android.widget.Toast.makeText(context, "Trip Link resumed", android.widget.Toast.LENGTH_SHORT).show()
                                            } else {
                                                liveSharingManager.pauseRandomLink()
                                                android.widget.Toast.makeText(context, "Trip Link paused", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (session.isRandomPaused) Color(0xFF059669) else Color(0xFF334155)
                                        ),
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            if (session.isRandomPaused) "▶ Resume Trip" else "⏸ Pause Trip",
                                            fontSize = 11.sp
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(4.dp))
                                // Master Pause / Resume Both Links
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
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(if (session.isPaused) "▶️ Resume Entire Sharing" else "⏸️ Pause Entire Sharing", fontSize = 13.sp)
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                // Duration Adjustments — only meaningful when session has a time limit
                                if (session.expiresAt <= 0L) {
                                    // Permanent session: offer a button to switch to timed
                                    Button(
                                        onClick = {
                                            liveSharingManager.adjustSession(6.0) // sets to now+6h
                                            android.widget.Toast.makeText(context, "Switched to 6h timed duration", android.widget.Toast.LENGTH_SHORT).show()
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
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
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                                            modifier = Modifier.weight(1f)
                                        ) { Text("-1h", fontSize = 11.sp) }

                                        Button(
                                            onClick = {
                                                liveSharingManager.adjustSession(-0.5)
                                                android.widget.Toast.makeText(context, "Reduced session by -30 min", android.widget.Toast.LENGTH_SHORT).show()
                                            },
                                            shape = RoundedCornerShape(8.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                                            modifier = Modifier.weight(1f)
                                        ) { Text("-30m", fontSize = 11.sp) }

                                        Button(
                                            onClick = {
                                                liveSharingManager.adjustSession(1.0)
                                                android.widget.Toast.makeText(context, "Extended session by +1 hour", android.widget.Toast.LENGTH_SHORT).show()
                                            },
                                            shape = RoundedCornerShape(8.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                                            modifier = Modifier.weight(1f)
                                        ) { Text("+1h", fontSize = 11.sp) }

                                        Button(
                                            onClick = {
                                                liveSharingManager.adjustSession(6.0)
                                                android.widget.Toast.makeText(context, "Extended session by +6 hours", android.widget.Toast.LENGTH_SHORT).show()
                                            },
                                            shape = RoundedCornerShape(8.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                                            modifier = Modifier.weight(1f)
                                        ) { Text("+6h", fontSize = 11.sp) }
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = {
                                        liveSharingManager.syncNow()
                                        android.widget.Toast.makeText(context, "Syncing live telemetry...", android.widget.Toast.LENGTH_SHORT).show()
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.Sync, contentDescription = "Sync Now", modifier = Modifier.size(16.dp), tint = Color(0xFF38BDF8))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Sync Now (Flush Offline Queue)", color = Color(0xFF38BDF8), fontSize = 12.sp)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        Button(
                            onClick = {
                                liveSharingManager.stopSession()
                                android.widget.Toast.makeText(context, "Live sharing stopped", android.widget.Toast.LENGTH_SHORT).show()
                            },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Stop Live Sharing")
                        }
                    } else {
                        // ── Create New Session View ──
                        Text("Session Title", fontSize = 13.sp, color = Color(0xFF94A3B8))
                        OutlinedTextField(
                            value = inputTitle,
                            onValueChange = { inputTitle = it },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFF0284C7),
                                unfocusedBorderColor = Color(0xFF334155)
                            ),
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                        )

                        Spacer(modifier = Modifier.height(10.dp))

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

                        Spacer(modifier = Modifier.height(18.dp))
                        Button(
                            onClick = {
                                val serverUrl = when (selectedProvider) {
                                    LiveShareProvider.LOCAL -> "http://127.0.0.1:3003"
                                    LiveShareProvider.SYNOLOGY -> "https://whereami.janush.tech"
                                }
                                // Persist all settings so the dialog opens with same config next time
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
                    }
                }
            }
        }
    }

    if (showInstantShareDialog) {
        val lat = currentLatLng?.first
        val lng = currentLatLng?.second
        val primaryPlace = locationData.primaryPlace
        val secondaryPlace = locationData.secondaryPlace
        val addressLine = remember(primaryPlace, secondaryPlace) {
            val parts = mutableListOf<String>()
            val street = secondaryPlace?.street ?: primaryPlace?.street
            if (!street.isNullOrBlank()) {
                parts.add(street)
            }
            val road = secondaryPlace?.roadRef ?: primaryPlace?.roadRef
            if (!road.isNullOrBlank() && street != road) {
                parts.add("[$road]")
            }
            primaryPlace?.city?.takeIf { it.isNotBlank() && it != "Unknown City" }?.let { parts.add(it) }
            primaryPlace?.country?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
            if (parts.isEmpty()) "Unknown Location" else parts.joinToString(", ")
        }
        val accuracyM = remember(locationData) {
            // Check if accuracy is available via LocationManager or raw
            "High"
        }

        Dialog(
            onDismissRequest = { showInstantShareDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                modifier = Modifier
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
                            Text("📍", fontSize = 22.sp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Share Current Position",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                        IconButton(onClick = { showInstantShareDialog = false }) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.LightGray)
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    if (lat != null && lng != null) {
                        val coordsStr = String.format(Locale.US, "%.5f, %.5f", lat, lng)
                        val googleMapsUrl = "https://maps.google.com/?q=$coordsStr"
                        val fullShareText = "My current location:\n$addressLine\nCoordinates: $coordsStr\nMap: $googleMapsUrl"

                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text("Address:", fontSize = 11.sp, color = Color(0xFF94A3B8))
                                Text(
                                    text = addressLine,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("GPS Coordinates (Filtered):", fontSize = 11.sp, color = Color(0xFF94A3B8))
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
                                context.startActivity(Intent.createChooser(sendIntent, "Share Location"))
                                showInstantShareDialog = false
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

                        // Copy to Clipboard
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
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy Coords", modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Copy Coords", fontSize = 12.sp)
                            }

                            // Open in Google Maps app
                            Button(
                                onClick = {
                                    val mapIntent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lng?q=$lat,$lng($addressLine)"))
                                    try {
                                        context.startActivity(mapIntent)
                                    } catch (_: Exception) {
                                        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(googleMapsUrl))
                                        context.startActivity(browserIntent)
                                    }
                                    showInstantShareDialog = false
                                },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Map, contentDescription = "Google Maps", modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Open Map", fontSize = 12.sp)
                            }
                        }
                    } else {
                        Text(
                            text = "Waiting for valid GPS fix...",
                            color = Color(0xFFF87171),
                            fontSize = 14.sp,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
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
    activityProfile: ActivityProfile,
    primaryPlace: PlaceInfo?,
    secondaryPlace: PlaceInfo?,
    hierarchySubtitle: String,
    currentLatLng: Triple<Double, Double, Float?>?,
    onShowActiveTripRoute: () -> Unit,
    onShowLiveShare: () -> Unit,
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
                .padding(horizontal = if (isCompact) 12.dp else 16.dp, vertical = if (isCompact) 6.dp else 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
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

                val streetOrRoad = listOfNotNull(
                    primaryPlace?.street?.takeIf { it.isNotBlank() },
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
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f, fill = false)
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
                            if (activeTrip != null) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "🚩" + activeTrip.placesVisited.size,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF38BDF8),
                                    softWrap = false
                                )
                            }
                        }
                        if (liveSession?.isActive == true) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (liveSession.isPaused) Color(0x33F59E0B) else Color(0x3310B981))
                                    .clickable { onShowLiveShare() }
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    text = if (liveSession.isPaused) "⏸️PAUSED" else "📡LIVE",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (liveSession.isPaused) Color(0xFFF59E0B) else Color(0xFF10B981),
                                    softWrap = false
                                )
                            }
                        }

                        if (nearbySavedPlace != null) {
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = nearbySavedPlace.category.iconEmoji,
                                fontSize = 14.sp
                            )
                        }
                    }

                    // Compact Action Icons (Live Share, Maps, Expand) - Redundant Share removed
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (liveSession?.isActive != true) {
                            IconButton(
                                onClick = onShowLiveShare,
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ShareLocation,
                                    contentDescription = "Live Share Location",
                                    tint = Color(0xFF38BDF8),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        // Open in Google Maps
                        IconButton(
                            onClick = {
                                val lat = currentLatLng?.first
                                val lng = currentLatLng?.second
                                if (lat != null && lng != null) {
                                    openInGoogleMaps(context, lat, lng, primaryPlace?.city ?: "")
                                }
                            },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Map,
                                contentDescription = "Open in Google Maps",
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        // Direct Expand Toggle (switches to Normal mode)
                        IconButton(
                            onClick = { onSetLocalityCardStyle(LocalityCardStyle.NORMAL) },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ExpandMore,
                                contentDescription = "Expand Locality Card",
                                tint = Color(0xFFCBD5E1),
                                modifier = Modifier.size(18.dp)
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
                        Text(
                            text = "📍 $streetOrRoad",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFFBBF24), // Vivid Amber Gold
                            maxLines = 2
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

                    Spacer(modifier = Modifier.width(6.dp))

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
                            Text(
                                text = activityProfile.iconEmoji,
                                fontSize = 11.sp
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            when (activityProfile) {
                                ActivityProfile.CAR, ActivityProfile.CYCLING, ActivityProfile.MTB -> {
                                    Text(
                                        text = speedStr,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF38BDF8)
                                    )
                                }
                                ActivityProfile.WALKING, ActivityProfile.RUNNING, ActivityProfile.HIKING -> {
                                    Text(
                                        text = paceStr,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF38BDF8)
                                    )
                                }
                            }
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
                                        Text(
                                            text = "${profile.iconEmoji}  ${profile.displayName}",
                                            color = if (isSelected) Color(0xFF38BDF8) else Color.White,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            fontSize = 13.sp
                                        )
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

                // Top Utilities Row: Saved Place / Live Badge on Left, Maps/Collapse on Right (Country moved to Hierarchy line)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left side: Saved Place / Live Badge
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (nearbySavedPlace != null) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0x3310B981))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "${nearbySavedPlace.category.iconEmoji} ${nearbySavedPlace.name}",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF10B981),
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                        if (liveSession?.isActive == true) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (liveSession.isPaused) Color(0x33F59E0B) else Color(0x3310B981))
                                    .clickable { onShowLiveShare() }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = if (liveSession.isPaused) "⏸️ PAUSED" else "📡 LIVE",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (liveSession.isPaused) Color(0xFFF59E0B) else Color(0xFF10B981),
                                    softWrap = false
                                )
                            }
                        }
                    }

                    // Right Utility Icons: Live Share shortcut (if inactive), Google Maps, Collapse - Redundant Share removed
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (liveSession?.isActive != true) {
                            IconButton(
                                onClick = onShowLiveShare,
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF1E293B))
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ShareLocation,
                                    contentDescription = "Live Share Location",
                                    tint = Color(0xFF38BDF8),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

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
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF1E293B))
                        ) {
                            Icon(
                                imageVector = Icons.Default.Map,
                                contentDescription = "Open in Google Maps",
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // Direct Collapse Toggle (switches to Compact mode)
                        IconButton(
                            onClick = { onSetLocalityCardStyle(LocalityCardStyle.COMPACT) },
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF1E293B))
                        ) {
                            Icon(
                                imageVector = Icons.Default.ExpandLess,
                                contentDescription = "Collapse Locality Card",
                                tint = Color(0xFFCBD5E1),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                // Main Place Name (Largest Font, Top Priority, 100% clean horizontal width)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = activeTrip != null) {
                            onShowActiveTripRoute()
                        }
                ) {
                    Text(
                        text = primaryCity,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        lineHeight = 36.sp
                    )
                    if (activeTrip != null) {
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "🚩" + activeTrip.placesVisited.size,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF38BDF8)
                        )
                    }
                }

                if (!secondaryCity.isNullOrEmpty() && !secondaryCity.equals(primaryCity, ignoreCase = true)) {
                    Text(
                        text = "($secondaryCity)",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF94A3B8),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 1.dp)
                    )
                }

                // Street Name & Road Number
                val streetOrRoad = listOfNotNull(
                    primaryPlace?.street?.takeIf { it.isNotBlank() },
                    primaryPlace?.roadRef?.takeIf { it.isNotBlank() }
                ).joinToString(" • ")

                if (streetOrRoad.isNotEmpty()) {
                    Text(
                        text = streetOrRoad,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFFFBBF24), // Amber gold for street
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 3.dp)
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
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF38BDF8),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp)
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
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF1E293B))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Compact Dropdown Trigger Pill
                    Box {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { profileMenuExpanded = true }
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${activityProfile.iconEmoji} ${activityProfile.displayName.uppercase()}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFBBF24)
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
                                            Text(
                                                text = "${profile.iconEmoji}  ${profile.displayName}",
                                                color = if (isSelected) Color(0xFF38BDF8) else Color.White,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                fontSize = 14.sp
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

                    Spacer(modifier = Modifier.width(8.dp))

                    // Metric display: CAR, CYCLING & MTB show Speed-first; WALKING, RUNNING & HIKING show Pace-first
                    when (activityProfile) {
                        ActivityProfile.CAR -> {
                            Text(
                                text = speedStr,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF38BDF8)
                            )
                        }
                        ActivityProfile.CYCLING, ActivityProfile.MTB -> {
                            Text(
                                text = speedStr,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF38BDF8)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "($paceStr)",
                                fontSize = 13.sp,
                                color = Color(0xFF94A3B8)
                            )
                        }
                        ActivityProfile.WALKING, ActivityProfile.RUNNING, ActivityProfile.HIKING -> {
                            Text(
                                text = paceStr,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF38BDF8)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "($speedStr)",
                                fontSize = 13.sp,
                                color = Color(0xFF94A3B8)
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

            // 3. Places & Trips History Dialog Button (Prominent Sky Blue)
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
                    contentDescription = "Places & Trips History",
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

            // 6. Settings & Language Dialog Button (Amber Gold / Slate)
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
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun DestinationPlaceCard(
    destinationPoint: org.osmdroid.util.GeoPoint,
    destinationItem: SearchResultItem?,
    onClearDestination: () -> Unit,
    onNavigate: (Double, Double) -> Unit,
    onGoogleMaps: (Double, Double, String) -> Unit,
    onSavePlace: (Double, Double, SearchResultItem?) -> Unit,
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
                    Icon(
                        imageVector = Icons.Default.Place,
                        contentDescription = "Destination Pin",
                        tint = Color(0xFFEF4444),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = destinationItem?.title ?: "Searched Location",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        maxLines = 1
                    )
                }

                IconButton(
                    onClick = onClearDestination,
                    modifier = Modifier.size(26.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Exit Destination",
                        tint = Color(0xFF94A3B8),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            if (destinationItem?.subtitle?.isNotBlank() == true) {
                Text(
                    text = destinationItem.subtitle,
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp,
                    maxLines = 2,
                    modifier = Modifier.padding(top = 2.dp, start = 26.dp)
                )
            }

            // Row 1: Navigation & Map Actions
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        onNavigate(destinationPoint.latitude, destinationPoint.longitude)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Navigation, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Navigate To", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }

                Button(
                    onClick = {
                        onGoogleMaps(destinationPoint.latitude, destinationPoint.longitude, destinationItem?.title ?: "")
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Map, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Google Maps", color = Color.White, fontSize = 12.sp)
                }
            }

            // Row 2: Save Place & Exit Actions
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        onSavePlace(destinationPoint.latitude, destinationPoint.longitude, destinationItem)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.BookmarkAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Save as My Place", fontSize = 12.sp)
                }

                Button(
                    onClick = onClearDestination,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("Exit Pin", color = Color.LightGray, fontSize = 12.sp)
                }
            }
        }
    }
}
