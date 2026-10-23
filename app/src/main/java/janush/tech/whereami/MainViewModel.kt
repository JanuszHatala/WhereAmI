package janush.tech.whereami

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class HeatMapFilterState(
    val activityProfiles: Set<ActivityProfile> = emptySet(), // empty = All Modes
    val datePeriod: String = "ALL", // "ALL", "TODAY", "WEEK", "MONTH", "YEAR"
    val minVisits: Int = 1,
    val consolidateCorridors: Boolean = true,
    val includeActiveTrip: Boolean = true
) {
    val hasActiveFilter: Boolean
        get() = activityProfiles.isNotEmpty() || datePeriod != "ALL" || minVisits > 1 || !consolidateCorridors || !includeActiveTrip
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val locationManager = LocationManager.getInstance(application)
    private val tripManager = TripManager.getInstance(application)
    private val appStateManager = AppStateManager.getInstance(application)
    private val appPrefs = application.getSharedPreferences("where_am_i_ui_prefs", Context.MODE_PRIVATE)

    private val _locationData = MutableStateFlow(LocationData(null, null, null, null, false))
    val locationData: StateFlow<LocationData> = _locationData.asStateFlow()

    private val locPrefs = application.getSharedPreferences("where_am_i_prefs", Context.MODE_PRIVATE)
    private val initialLang = try {
        DisplayLanguage.valueOf(locPrefs.getString("display_language", DisplayLanguage.EN.name) ?: DisplayLanguage.EN.name)
    } catch (_: Exception) { DisplayLanguage.EN }

    private val _displayLanguage = MutableStateFlow(initialLang)
    val displayLanguage: StateFlow<DisplayLanguage> = _displayLanguage.asStateFlow()

    // Power policy and charging status
    val powerPolicy: StateFlow<BatteryPowerPolicy> = appStateManager.powerPolicy
    val isCharging: StateFlow<Boolean> = appStateManager.isCharging
    val lifecycleMode: StateFlow<AppLifecycleMode> = appStateManager.currentMode
    val isStandbySleeping: StateFlow<Boolean> = appStateManager.isStandbySleeping

    // Automatic Sleep Mode settings
    private val autoSleepManager = AutoSleepManager.getInstance(application)
    val autoSleepEnabled: StateFlow<Boolean> = autoSleepManager.autoSleepEnabled
    val autoSleepStartTime: StateFlow<String> = autoSleepManager.startTime
    val autoSleepEndTime: StateFlow<String> = autoSleepManager.endTime
    val autoSleepFollowDnd: StateFlow<Boolean> = autoSleepManager.followDnd
    val autoSleepFollowAlarm: StateFlow<Boolean> = autoSleepManager.followAlarm
    val autoSleepPauseLiveShare: StateFlow<Boolean> = autoSleepManager.pauseLiveShare

    fun setAutoSleepEnabled(enabled: Boolean) = autoSleepManager.setAutoSleepEnabled(enabled)
    fun setAutoSleepStartTime(time: String) = autoSleepManager.setStartTime(time)
    fun setAutoSleepEndTime(time: String) = autoSleepManager.setEndTime(time)
    fun setAutoSleepFollowDnd(follow: Boolean) = autoSleepManager.setFollowDnd(follow)
    fun setAutoSleepFollowAlarm(follow: Boolean) = autoSleepManager.setFollowAlarm(follow)
    fun setAutoSleepPauseLiveShare(pause: Boolean) = autoSleepManager.setPauseLiveShare(pause)

    fun enterStandbySleep() {
        appStateManager.enterStandbySleep(isManual = true)
    }

    fun wakeStandbySleep() {
        appStateManager.wakeStandbySleep(isExplicitUserAction = true)
    }

    fun toggleStandbySleep() {
        if (appStateManager.isStandbySleeping.value) {
            appStateManager.wakeStandbySleep(isExplicitUserAction = true)
        } else {
            appStateManager.enterStandbySleep(isManual = true)
        }
    }

    private val initialCoords: Triple<Double, Double, Float?>? = run {
        if (locPrefs.contains("lat") && locPrefs.contains("lng")) {
            val lat = locPrefs.getFloat("lat", 0f).toDouble()
            val lng = locPrefs.getFloat("lng", 0f).toDouble()
            if (lat != 0.0 && lng != 0.0) Triple(lat, lng, null) else null
        } else null
    }

    /** Latest GPS coordinates for the map composable (lat, lng, bearing?). */
    private val _currentLatLng = MutableStateFlow<Triple<Double, Double, Float?>?>(initialCoords)
    val currentLatLng: StateFlow<Triple<Double, Double, Float?>?> = _currentLatLng.asStateFlow()

    private val _currentLocationFix = MutableStateFlow<LocationFix?>(
        initialCoords?.let {
            val speed = if (locPrefs.contains("speed")) locPrefs.getFloat("speed", 0f) else 0f
            LocationFix(it.first, it.second, null, speed, System.currentTimeMillis())
        }
    )
    val currentLocationFix: StateFlow<LocationFix?> = _currentLocationFix.asStateFlow()

    // Screen On Preference
    private val _keepScreenOn = MutableStateFlow(appPrefs.getBoolean("pref_keep_screen_on", false))
    val keepScreenOn: StateFlow<Boolean> = _keepScreenOn.asStateFlow()

    // Activity Profile (Car, Walking, Cycling)
    val activityProfile: StateFlow<ActivityProfile> = tripManager.activityProfile

    // Trips state
    val activeTrip: StateFlow<TripRecord?> = tripManager.activeTrip
    val tripMode: StateFlow<TripMode> = tripManager.tripMode
    val autoStopMinutes: StateFlow<Int> = tripManager.autoStopMinutes

    private val _savedTrips = MutableStateFlow<List<TripRecord>>(emptyList())
    val savedTrips: StateFlow<List<TripRecord>> = _savedTrips.asStateFlow()

    private val _fitTrackTrigger = MutableStateFlow(0L)
    val fitTrackTrigger: StateFlow<Long> = _fitTrackTrigger.asStateFlow()

    private val _fitPlacesTrigger = MutableStateFlow(0L)
    val fitPlacesTrigger: StateFlow<Long> = _fitPlacesTrigger.asStateFlow()

    // Locality Card Style (Normal vs Compact)
    private val _localityCardStyle = MutableStateFlow(
        try {
            LocalityCardStyle.valueOf(appPrefs.getString("pref_locality_card_style", LocalityCardStyle.NORMAL.name) ?: LocalityCardStyle.NORMAL.name)
        } catch (_: Exception) {
            LocalityCardStyle.NORMAL
        }
    )
    val localityCardStyle: StateFlow<LocalityCardStyle> = _localityCardStyle.asStateFlow()

    // Compact Street Names Preference (abbreviates honorifics and first names)
    private val _compactStreetNames = MutableStateFlow(appPrefs.getBoolean("compact_street_names", false))
    val compactStreetNames: StateFlow<Boolean> = _compactStreetNames.asStateFlow()


    // Destination Pin & Details (from search)
    private val _destinationItem = MutableStateFlow<SearchResultItem?>(null)
    val destinationItem: StateFlow<SearchResultItem?> = _destinationItem.asStateFlow()

    private val _destinationPoint = MutableStateFlow<org.osmdroid.util.GeoPoint?>(null)
    val destinationPoint: StateFlow<org.osmdroid.util.GeoPoint?> = _destinationPoint.asStateFlow()

    private val _destinationAltitude = MutableStateFlow<Double?>(null)
    val destinationAltitude: StateFlow<Double?> = _destinationAltitude.asStateFlow()

    private val _destinationAltitudeLoading = MutableStateFlow(false)
    val destinationAltitudeLoading: StateFlow<Boolean> = _destinationAltitudeLoading.asStateFlow()

    private var destinationElevationJob: Job? = null

    // Altitude Source Mode (DEM vs GPS Direct)
    private val _altitudeSourceMode = MutableStateFlow(
        MapProfileConfigHelper.getAltitudeSourceMode(application)
    )
    val altitudeSourceMode: StateFlow<AltitudeSourceMode> = _altitudeSourceMode.asStateFlow()

    private val _currentDemAltitude = MutableStateFlow<Double?>(null)
    val currentDemAltitude: StateFlow<Double?> = _currentDemAltitude.asStateFlow()

    private val _isAltitudeFallback = MutableStateFlow(false)
    val isAltitudeFallback: StateFlow<Boolean> = _isAltitudeFallback.asStateFlow()

    private val _displayAltitude = MutableStateFlow<Double?>(null)
    val displayAltitude: StateFlow<Double?> = _displayAltitude.asStateFlow()

    private var demLookupJob: Job? = null
    private val demHold = DemAltitudeHold()
    private var lastDemLookupLat: Double = 0.0
    private var lastDemLookupLng: Double = 0.0

    private val _pendingDemTripPrompt = MutableStateFlow<TripRecord?>(null)
    val pendingDemTripPrompt: StateFlow<TripRecord?> = _pendingDemTripPrompt.asStateFlow()
    private val promptedDemTripIds = mutableSetOf<Long>()

    fun dismissDemTripPrompt() {
        val tripId = _pendingDemTripPrompt.value?.id
        if (tripId != null) {
            promptedDemTripIds.add(tripId)
        }
        _pendingDemTripPrompt.value = null
    }

    fun confirmDemTripSupplement(tripId: Long) {
        promptedDemTripIds.add(tripId)
        _pendingDemTripPrompt.value = null
        correctTripElevation(tripId, OverrideMode.FILL_MISSING)
    }

    fun setAltitudeSourceMode(mode: AltitudeSourceMode) {
        _altitudeSourceMode.value = mode
        MapProfileConfigHelper.setAltitudeSourceMode(getApplication(), mode)
        val cur = _currentLatLng.value
        val speedKmh = (_currentLocationFix.value?.speedMs ?: 0f) * 3.6f
        if (cur != null) {
            updatePositionAltitude(cur.first, cur.second, _locationData.value.altitude, speedKmh)
        }
    }

    private fun updatePositionAltitude(lat: Double, lng: Double, gpsAlt: Double?, speedKmh: Float = 0f) {
        val mode = _altitudeSourceMode.value
        if (mode == AltitudeSourceMode.GPS) {
            _isAltitudeFallback.value = true
            _displayAltitude.value = if (speedKmh < 15f) gpsAlt else null
            return
        }

        // Mode is DEM:
        // Speed gating: at speed >= 15 km/h, clear display altitude and cancel pending DEM jobs
        if (speedKmh >= 15f) {
            demLookupJob?.cancel()
            _isAltitudeFallback.value = true
            _displayAltitude.value = null
            return
        }

        // 1. Hold the last valid DEM value near where it was resolved: a pending or failed lookup for the
        //    next grid cell must never blank the badge (visible flicker while walking).
        val held = demHold.held(lat, lng)
        if (held != null) {
            _isAltitudeFallback.value = false
            _displayAltitude.value = held
        } else {
            _isAltitudeFallback.value = (_displayAltitude.value == null)
        }

        // 2. Close to the last lookup (pending or resolved): nothing new to ask for
        val nearLastLookup = DemAltitudeHold.distanceM(lastDemLookupLat, lastDemLookupLng, lat, lng) < 15.0
        if (nearLastLookup && (held != null || demLookupJob?.isActive == true)) return

        // 3. Resolve off the main thread: SQLite cache first, then the network (debounced)
        lastDemLookupLat = lat
        lastDemLookupLng = lng
        demLookupJob?.cancel()
        demLookupJob = viewModelScope.launch(Dispatchers.IO) {
            val cached = dbHelper.getCachedElevation(lat, lng)?.takeIf { it > 0.0 }
            val dem = cached ?: run {
                kotlinx.coroutines.delay(250L) // Debounce rapid GPS bursts
                ElevationRepository.getElevation(lat, lng, dbHelper)
            }
            withContext(Dispatchers.Main) {
                if (_altitudeSourceMode.value != AltitudeSourceMode.DEM) return@withContext
                val shown = demHold.accept(lat, lng, dem)
                if (dem != null && dem > 0.0) _currentDemAltitude.value = dem
                _isAltitudeFallback.value = shown == null
                _displayAltitude.value = shown
            }
        }
    }

    // Saved Places State
    private val dbHelper = TripDatabaseHelper(application)
    private val _savedPlaces = MutableStateFlow<List<SavedPlace>>(emptyList())
    val savedPlaces: StateFlow<List<SavedPlace>> = _savedPlaces.asStateFlow()

    // Orientation Mode (North, East, South, West, Course-Up)
    private val _orientationMode = MutableStateFlow(
        try {
            MapOrientationMode.valueOf(appPrefs.getString("pref_map_orientation_mode", MapOrientationMode.NORTH.name) ?: MapOrientationMode.NORTH.name)
        } catch (_: Exception) {
            MapOrientationMode.NORTH
        }
    )
    val orientationMode: StateFlow<MapOrientationMode> = _orientationMode.asStateFlow()

    // Road / Path Traversal Heat Map Layer
    private val _showHeatMap = MutableStateFlow(appPrefs.getBoolean("pref_show_heat_map", false))
    val showHeatMap: StateFlow<Boolean> = _showHeatMap.asStateFlow()

    // Multi-Trip Route Selection (Phase 2)
    private val _selectedTripIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedTripIds: StateFlow<Set<Long>> = _selectedTripIds.asStateFlow()

    enum class AppDialogTarget {
        NONE,
        CACHE_MANAGER,
        LIVE_SHARING,
        ELEVATION_SETTINGS
    }

    private val _requestedDialogTarget = MutableStateFlow(AppDialogTarget.NONE)
    val requestedDialogTarget: StateFlow<AppDialogTarget> = _requestedDialogTarget.asStateFlow()

    private val _showCacheManagerDialog = MutableStateFlow(false)
    val showCacheManagerDialog: StateFlow<Boolean> = _showCacheManagerDialog.asStateFlow()

    fun openCacheManager() {
        dismissAllDialogs()
        _requestedDialogTarget.value = AppDialogTarget.CACHE_MANAGER
        _showCacheManagerDialog.value = true
    }

    fun openElevationSettings() {
        dismissAllDialogs()
        _requestedDialogTarget.value = AppDialogTarget.ELEVATION_SETTINGS
    }

    fun dismissAllDialogs() {
        _showCacheManagerDialog.value = false
    }

    fun dismissCacheManager() {
        _showCacheManagerDialog.value = false
    }

    fun openLiveSharing() {
        dismissAllDialogs()
        _requestedDialogTarget.value = AppDialogTarget.LIVE_SHARING
    }

    fun requestDialogTarget(target: AppDialogTarget) {
        _requestedDialogTarget.value = target
        if (target == AppDialogTarget.CACHE_MANAGER) {
            _showCacheManagerDialog.value = true
        }
    }

    fun consumeDialogTarget() {
        _requestedDialogTarget.value = AppDialogTarget.NONE
    }

    // Administrative Borders Layer (Phase 2)
    private val _showBorders = MutableStateFlow(appPrefs.getBoolean("pref_show_borders", false))
    val showBorders: StateFlow<Boolean> = _showBorders.asStateFlow()

    private val _boundaryPoints = MutableStateFlow<List<org.osmdroid.util.GeoPoint>?>(null)
    val boundaryPoints: StateFlow<List<org.osmdroid.util.GeoPoint>?> = _boundaryPoints.asStateFlow()

    // Pinned Locality Borders Layer (Point 8)
    private val _pinnedBoundaryPoints = MutableStateFlow<List<org.osmdroid.util.GeoPoint>?>(null)
    val pinnedBoundaryPoints: StateFlow<List<org.osmdroid.util.GeoPoint>?> = _pinnedBoundaryPoints.asStateFlow()

    // Search Results State
    private val _searchResults = MutableStateFlow<List<SearchResultItem>>(emptyList())
    val searchResults: StateFlow<List<SearchResultItem>> = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private var trackingJob: Job? = null
    private var rawTrackingJob: Job? = null

    init {
        startTracking()
        loadSavedTrips()
        loadSavedPlaces()

        // Automatically reload trips when a trip completes (manual or auto-stop) and keep last recorded trip selected
        viewModelScope.launch {
            var lastActiveTripId: Long? = null
            tripManager.activeTrip.collect { active ->
                if (active != null) {
                    if (lastActiveTripId == null) {
                        // Invariant: When a new trip starts recording, clear any previously selected past trips
                        _selectedTripIds.value = emptySet()
                    }
                    lastActiveTripId = active.id
                } else if (lastActiveTripId != null) {
                    val finishedId = lastActiveTripId
                    lastActiveTripId = null
                    val trips = kotlinx.coroutines.withContext(Dispatchers.IO) { tripManager.getAllTrips() }
                    _savedTrips.value = trips
                    if (finishedId != null && trips.any { t -> t.id == finishedId }) {
                        _selectedTripIds.value = setOf(finishedId)
                        _fitTrackTrigger.value = System.currentTimeMillis()

                        val autoCorrecting = ElevationCorrectionManager.progress.value.let { it.isRunning && it.singleTripId == finishedId }
                        if (!autoCorrecting) promptDemIfNeeded(finishedId)
                    }
                }
            }
        }

        // Saved trip's automatic DEM pass (TripManager.stopTrip): show the corrected profile, and offer the
        // correction only if GPS altitudes remain (offline, provider failure).
        viewModelScope.launch {
            var wasRunningFor: Long? = null
            ElevationCorrectionManager.progress.collect { p ->
                if (p.isRunning) {
                    wasRunningFor = p.singleTripId
                } else if (wasRunningFor != null && p.singleTripId == wasRunningFor) {
                    val tripId = wasRunningFor!!
                    wasRunningFor = null
                    _savedTrips.value = kotlinx.coroutines.withContext(Dispatchers.IO) { tripManager.getAllTrips() }
                    promptDemIfNeeded(tripId)
                } else {
                    wasRunningFor = null
                }
            }
        }
    }

    private suspend fun promptDemIfNeeded(tripId: Long) {
        if (_altitudeSourceMode.value != AltitudeSourceMode.DEM || promptedDemTripIds.contains(tripId)) return
        val needsDem = kotlinx.coroutines.withContext(Dispatchers.IO) { dbHelper.tripHasGpsAltitudePoints(tripId) }
        if (needsDem) {
            promptedDemTripIds.add(tripId)
            _pendingDemTripPrompt.value = _savedTrips.value.firstOrNull { it.id == tripId }
        }
    }

    /** Points of a saved trip whose altitude is not from the DEM (GPS or missing). */
    suspend fun countNonDemAltitudePoints(tripId: Long): Int = kotlinx.coroutines.withContext(Dispatchers.IO) {
        dbHelper.getTrackPointRows(tripId).count { it.altitudeM == null || (it.altSource ?: "GPS") != "DEM" }
    }

    fun setActivityProfile(profile: ActivityProfile) {
        tripManager.setActivityProfile(profile)
        val config = MapProfileConfigHelper.getConfig(getApplication(), profile)
        _orientationMode.value = config.orientationMode
    }

    private fun resolveDestinationAltitude(point: org.osmdroid.util.GeoPoint?) {
        destinationElevationJob?.cancel()
        if (point == null) {
            _destinationAltitude.value = null
            _destinationAltitudeLoading.value = false
            return
        }
        _destinationAltitude.value = null
        _destinationAltitudeLoading.value = true
        destinationElevationJob = viewModelScope.launch(Dispatchers.IO) {
            val dem = ElevationRepository.getElevation(point.latitude, point.longitude, dbHelper)
            val effective = dem ?: _locationData.value.altitude?.takeIf { it > 0.0 }
            if (_destinationPoint.value == point) {
                _destinationAltitude.value = effective
                _destinationAltitudeLoading.value = false
            }
        }
    }

    fun setDestination(item: SearchResultItem?) {
        _destinationItem.value = item
        _destinationPoint.value = item?.geoPoint
        _pinnedBoundaryPoints.value = null
        resolveDestinationAltitude(item?.geoPoint)
        if (item != null) {
            rememberRecentSearch(item)
        }
    }

    fun setDestinationPoint(point: org.osmdroid.util.GeoPoint?) {
        _destinationPoint.value = point
        _pinnedBoundaryPoints.value = null
        if (point == null) {
            _destinationItem.value = null
        }
        resolveDestinationAltitude(point)
    }

    fun selectMapPoint(point: org.osmdroid.util.GeoPoint) {
        _destinationPoint.value = point
        _pinnedBoundaryPoints.value = null
        resolveDestinationAltitude(point)
        _destinationItem.value = SearchResultItem(
            title = "Resolving Address...",
            subtitle = String.format(java.util.Locale.US, "%.5f, %.5f", point.latitude, point.longitude),
            geoPoint = point
        )
        viewModelScope.launch {
            val resolved = SearchHelper.reverseGeocode(getApplication(), point)
            // Ensure we only update if the destination hasn't changed in the meantime
            if (_destinationPoint.value == point) {
                _destinationItem.value = resolved
            }
        }
    }

    fun togglePinnedBorders(
        locality: String?,
        countryCode: String? = "pl",
        fallbackMunicipality: String? = null,
        geoPoint: org.osmdroid.util.GeoPoint? = null
    ) {
        if (_pinnedBoundaryPoints.value != null) {
            _pinnedBoundaryPoints.value = null
        } else {
            if (locality.isNullOrBlank() && fallbackMunicipality.isNullOrBlank() && geoPoint == null) return
            viewModelScope.launch {
                val poly = BoundaryHelper.getLocalityBoundary(
                    context = getApplication(),
                    cityName = locality,
                    countryCode = countryCode ?: "pl",
                    fallbackMunicipality = fallbackMunicipality,
                    geoPoint = geoPoint,
                    forPin = true
                )
                _pinnedBoundaryPoints.value = poly
            }
        }
    }

    fun clearPinnedBorders() {
        _pinnedBoundaryPoints.value = null
    }

    fun loadSavedPlaces() {
        viewModelScope.launch(Dispatchers.IO) {
            _savedPlaces.value = dbHelper.getAllSavedPlaces()
        }
    }

    fun savePlace(
        name: String,
        category: PlaceCategory,
        geoPoint: org.osmdroid.util.GeoPoint,
        locality: String,
        street: String,
        colorHex: String = "",
        altitude: Double? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val finalColor = if (colorHex.isBlank()) PRESET_PIN_COLORS.filter { it.hex.isNotEmpty() }.random().hex else colorHex
            val place = SavedPlace(
                name = name,
                category = category,
                latitude = geoPoint.latitude,
                longitude = geoPoint.longitude,
                locality = locality,
                street = street,
                colorHex = finalColor,
                altitude = altitude
            )
            dbHelper.insertSavedPlace(place)
            loadSavedPlaces()
        }
    }

    fun readAndSavePlaceAltitude(place: SavedPlace, onUpdated: ((SavedPlace) -> Unit)? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val dem = ElevationRepository.getElevation(place.latitude, place.longitude, dbHelper)
            val effectiveAlt = dem ?: _locationData.value.altitude?.takeIf { it > 0.0 }
            val src = if (dem != null) "DEM" else if (effectiveAlt != null) "GPS" else null
            if (effectiveAlt != null && effectiveAlt > 0.0) {
                val updated = place.copy(altitude = effectiveAlt, altitudeSource = src)
                dbHelper.updateSavedPlace(updated)
                loadSavedPlaces()
                withContext(Dispatchers.Main) {
                    onUpdated?.invoke(updated)
                }
            }
        }
    }

    suspend fun fetchDemAltitudeForLocation(lat: Double, lng: Double): Double? = withContext(Dispatchers.IO) {
        ElevationRepository.getElevation(lat, lng, dbHelper)
    }

    fun deleteSavedPlace(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            dbHelper.deleteSavedPlace(id)
            loadSavedPlaces()
        }
    }

    fun updateSavedPlace(place: SavedPlace) {
        viewModelScope.launch(Dispatchers.IO) {
            dbHelper.updateSavedPlace(place)
            loadSavedPlaces()
        }
    }

    private var searchJob: Job? = null
    private val recentSearchPrefsKey = "recent_place_searches_v1"
    private val maxRecentSearches = 12

    fun search(query: String) {
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.length < 2) {
            _searchResults.value = emptyList()
            _isSearching.value = false
            return
        }
        searchJob = viewModelScope.launch {
            _isSearching.value = true
            delay(300)
            if (!isActive) return@launch
            val currentCity = _locationData.value.primaryPlace?.city
            val countryCode = _locationData.value.primaryPlace?.countryCode
                ?.takeIf { it.isNotBlank() }?.lowercase()
            val coords = _currentLatLng.value
            val results = SearchHelper.searchLocation(
                context = getApplication(),
                query = trimmed,
                currentCity = currentCity,
                originLat = coords?.first,
                originLon = coords?.second,
                countryCode = countryCode,
                savedPlaces = _savedPlaces.value,
                recentPlaces = loadRecentSearches()
            )
            if (!isActive) return@launch
            _searchResults.value = results
            _isSearching.value = false
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        searchJob = null
        _searchResults.value = emptyList()
        _isSearching.value = false
    }

    private fun rememberRecentSearch(item: SearchResultItem) {
        try {
            val existing = loadRecentSearches().toMutableList()
            existing.removeAll {
                String.format(java.util.Locale.US, "%.5f,%.5f", it.geoPoint.latitude, it.geoPoint.longitude) ==
                    String.format(java.util.Locale.US, "%.5f,%.5f", item.geoPoint.latitude, item.geoPoint.longitude)
            }
            existing.add(0, item.copy(source = item.source ?: "recent", placeType = item.placeType ?: "recent"))
            val trimmed = existing.take(maxRecentSearches)
            val arr = JSONArray()
            for (r in trimmed) {
                arr.put(
                    JSONObject()
                        .put("title", r.title)
                        .put("subtitle", r.subtitle)
                        .put("lat", r.geoPoint.latitude)
                        .put("lon", r.geoPoint.longitude)
                        .put("locality", r.localityName ?: "")
                        .put("countryCode", r.countryCode ?: "")
                        .put("placeType", r.placeType ?: "recent")
                )
            }
            appPrefs.edit().putString(recentSearchPrefsKey, arr.toString()).apply()
        } catch (_: Exception) {}
    }

    private fun loadRecentSearches(): List<SearchResultItem> {
        return try {
            val raw = appPrefs.getString(recentSearchPrefsKey, null) ?: return emptyList()
            val arr = JSONArray(raw)
            val out = mutableListOf<SearchResultItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val title = obj.optString("title", "").takeIf { it.isNotBlank() } ?: continue
                val lat = obj.optDouble("lat", Double.NaN)
                val lon = obj.optDouble("lon", Double.NaN)
                if (lat.isNaN() || lon.isNaN()) continue
                out.add(
                    SearchResultItem(
                        title = title,
                        subtitle = obj.optString("subtitle", ""),
                        geoPoint = org.osmdroid.util.GeoPoint(lat, lon),
                        localityName = obj.optString("locality", "").takeIf { it.isNotBlank() },
                        countryCode = obj.optString("countryCode", "").takeIf { it.isNotBlank() },
                        placeType = obj.optString("placeType", "recent"),
                        source = "recent"
                    )
                )
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun toggleKeepScreenOn() {
        val newState = !_keepScreenOn.value
        _keepScreenOn.value = newState
        appPrefs.edit().putBoolean("pref_keep_screen_on", newState).apply()
    }

    fun setTripMode(mode: TripMode) {
        tripManager.setTripMode(mode)
    }

    fun setLocalityCardStyle(style: LocalityCardStyle) {
        _localityCardStyle.value = style
        appPrefs.edit().putString("pref_locality_card_style", style.name).apply()
    }

    fun toggleCompactStreetNames() {
        val newVal = !_compactStreetNames.value
        _compactStreetNames.value = newVal
        appPrefs.edit().putBoolean("compact_street_names", newVal).apply()
    }


    fun setAutoStopMinutes(minutes: Int) {
        tripManager.setAutoStopMinutes(minutes)
    }

    fun getAutoStartSecondsForProfile(profile: ActivityProfile): Int {
        return tripManager.getAutoStartSecondsForProfile(profile)
    }

    fun setAutoStartSecondsForProfile(profile: ActivityProfile, seconds: Int) {
        tripManager.setAutoStartSecondsForProfile(profile, seconds)
    }

    fun getAutoStartSpeedForProfile(profile: ActivityProfile): Float {
        return tripManager.getAutoStartSpeedForProfile(profile)
    }

    fun setAutoStartSpeedForProfile(profile: ActivityProfile, speedKmh: Float) {
        tripManager.setAutoStartSpeedForProfile(profile, speedKmh)
    }

    fun getAutoStopMinutesForProfile(profile: ActivityProfile): Int {
        return tripManager.getAutoStopMinutesForProfile(profile)
    }

    fun setAutoStopMinutesForProfile(profile: ActivityProfile, minutes: Int) {
        tripManager.setAutoStopMinutesForProfile(profile, minutes)
    }

    fun startManualTrip() {
        tripManager.startManualTrip()
    }

    fun stopManualTrip() {
        tripManager.stopManualTrip()
        loadSavedTrips()
    }

    fun triggerFitTrack() {
        _fitTrackTrigger.value = System.currentTimeMillis()
    }

    fun triggerFitPlaces() {
        _fitPlacesTrigger.value = System.currentTimeMillis()
    }

    fun loadSavedTrips() {
        viewModelScope.launch(Dispatchers.IO) {
            _savedTrips.value = tripManager.getAllTrips()
        }
    }

    fun deleteTrip(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            tripManager.deleteTrip(id)
            _selectedTripIds.value = _selectedTripIds.value - id
            _savedTrips.value = tripManager.getAllTrips()
        }
    }

    fun renameTrip(id: Long, newTitle: String) {
        viewModelScope.launch(Dispatchers.IO) {
            tripManager.renameTrip(id, newTitle)
            _savedTrips.value = tripManager.getAllTrips()
        }
    }

    fun splitTripAtPause(tripId: Long, pauseIndex: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            tripManager.splitTripAtPause(tripId, pauseIndex)
            _selectedTripIds.value = _selectedTripIds.value - tripId
            _savedTrips.value = tripManager.getAllTrips()
        }
    }

    fun deleteTripPause(tripId: Long, pauseIndex: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            tripManager.deleteTripPause(tripId, pauseIndex)
            _savedTrips.value = tripManager.getAllTrips()
        }
    }

    fun mergeTripPauses(tripId: Long, pauseIndex: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            tripManager.mergeTripPauses(tripId, pauseIndex)
            _savedTrips.value = tripManager.getAllTrips()
        }
    }

    fun setOrientationMode(mode: MapOrientationMode) {
        _orientationMode.value = mode
        val currentProfile = tripManager.activityProfile.value
        MapProfileConfigHelper.setOrientationMode(getApplication(), currentProfile, mode)
        appPrefs.edit().putString("pref_map_orientation_mode", mode.name).apply()
        TelemetryLogger.log("SETTINGS", "MapOrientationMode changed to ${mode.name} for ${currentProfile.name}")
    }

    fun resetMapProfileDefaults(profile: ActivityProfile) {
        MapProfileConfigHelper.resetProfileDefaults(getApplication(), profile)
        val config = MapProfileConfigHelper.getConfig(getApplication(), profile)
        if (tripManager.activityProfile.value == profile) {
            _orientationMode.value = config.orientationMode
        }
        TelemetryLogger.log("SETTINGS", "Reset map defaults for profile: ${profile.name}")
    }

    fun resetAllMapProfileDefaults() {
        MapProfileConfigHelper.resetAllProfileDefaults(getApplication())
        val currentProfile = tripManager.activityProfile.value
        val config = MapProfileConfigHelper.getConfig(getApplication(), currentProfile)
        _orientationMode.value = config.orientationMode
        TelemetryLogger.log("SETTINGS", "Reset map defaults for all profiles")
    }

    fun toggleShowHeatMap() {
        val newState = !_showHeatMap.value
        _showHeatMap.value = newState
        appPrefs.edit().putBoolean("pref_show_heat_map", newState).apply()
        TelemetryLogger.log("SETTINGS", "ShowHeatMap changed to $newState")
    }

    private val _heatMapFilterState = MutableStateFlow(
        HeatMapFilterState(
            activityProfiles = appPrefs.getStringSet("pref_heatmap_activities", null)?.mapNotNull {
                try { ActivityProfile.valueOf(it) } catch (_: Exception) { null }
            }?.toSet() ?: appPrefs.getString("pref_heatmap_activity", null)?.let {
                try { setOf(ActivityProfile.valueOf(it)) } catch (_: Exception) { emptySet() }
            } ?: emptySet(),
            datePeriod = appPrefs.getString("pref_heatmap_date", "ALL") ?: "ALL",
            minVisits = appPrefs.getInt("pref_heatmap_min_visits", 1),
            consolidateCorridors = appPrefs.getBoolean("pref_heatmap_consolidate", true),
            includeActiveTrip = appPrefs.getBoolean("pref_heatmap_include_active", true)
        )
    )
    val heatMapFilterState: StateFlow<HeatMapFilterState> = _heatMapFilterState.asStateFlow()

    fun updateHeatMapFilter(state: HeatMapFilterState) {
        _heatMapFilterState.value = state
        appPrefs.edit().apply {
            putStringSet("pref_heatmap_activities", state.activityProfiles.map { it.name }.toSet())
            remove("pref_heatmap_activity")
            putString("pref_heatmap_date", state.datePeriod)
            putInt("pref_heatmap_min_visits", state.minVisits)
            putBoolean("pref_heatmap_consolidate", state.consolidateCorridors)
            putBoolean("pref_heatmap_include_active", state.includeActiveTrip)
            apply()
        }
    }

    fun resetHeatMapFilter() {
        updateHeatMapFilter(HeatMapFilterState())
    }

    fun setSelectedTripIds(ids: Set<Long>) {
        _selectedTripIds.value = ids
    }

    fun mergeSelectedTrips(customTitle: String? = null, targetProfile: ActivityProfile? = null) {
        val ids = _selectedTripIds.value.toList()
        if (ids.size < 2) return
        viewModelScope.launch(Dispatchers.IO) {
            val newId = tripManager.mergeTrips(ids, customTitle, targetProfile)
            _selectedTripIds.value = setOf(newId)
            _savedTrips.value = tripManager.getAllTrips()
            TelemetryLogger.log("TRIP", "Merged ${ids.size} trips into #$newId (title='$customTitle', profile=$targetProfile)")
        }
    }

    fun toggleTripSelection(id: Long) {
        val current = _selectedTripIds.value.toMutableSet()
        if (current.contains(id)) {
            current.remove(id)
        } else {
            current.add(id)
        }
        _selectedTripIds.value = current
    }

    fun selectAllTrips() {
        _selectedTripIds.value = _savedTrips.value.map { it.id }.toSet()
    }

    fun clearTripSelection() {
        _selectedTripIds.value = emptySet()
    }

    fun toggleShowBorders() {
        val newState = !_showBorders.value
        _showBorders.value = newState
        appPrefs.edit().putBoolean("pref_show_borders", newState).apply()
        if (newState) {
            fetchCurrentBoundary()
        } else {
            _boundaryPoints.value = null
        }
    }

    private fun fetchCurrentBoundary() {
        val place = _locationData.value.primaryPlace ?: return
        if (place.city == "Unknown City" || place.city.isBlank()) return

        val curLat = _currentLatLng.value?.first
        val curLng = _currentLatLng.value?.second
        val curGeoPoint = if (curLat != null && curLng != null) org.osmdroid.util.GeoPoint(curLat, curLng) else null

        viewModelScope.launch {
            val poly = BoundaryHelper.getLocalityBoundary(
                context = getApplication(),
                cityName = place.city,
                countryCode = place.countryCode,
                fallbackMunicipality = place.gmina,
                geoPoint = curGeoPoint
            )
            _boundaryPoints.value = poly
        }
    }

    fun setPowerPolicy(policy: BatteryPowerPolicy) {
        appStateManager.setPowerPolicy(policy)
    }

    fun updateTripActivityProfile(tripId: Long, profile: ActivityProfile) {
        viewModelScope.launch(Dispatchers.IO) {
            dbHelper.updateTripActivityProfile(tripId, profile)
            _savedTrips.value = tripManager.getAllTrips()
        }
    }

    fun setDisplayLanguage(language: DisplayLanguage) {
        locPrefs.edit().putString("display_language", language.name).apply()
        _displayLanguage.value = language

        val lat = if (locPrefs.contains("lat")) locPrefs.getFloat("lat", 0f).toDouble() else null
        val lng = if (locPrefs.contains("lng")) locPrefs.getFloat("lng", 0f).toDouble() else null
        val speed = if (locPrefs.contains("speed")) locPrefs.getFloat("speed", 0f) else null
        if (lat != null && lng != null) {
            viewModelScope.launch(Dispatchers.IO) {
                val resolved = locationManager.resolveLocationData(lat, lng, speed, language)
                _locationData.value = resolved
            }
        }

        startTracking()
    }

    fun startTracking() {
        trackingJob?.cancel()
        rawTrackingJob?.cancel()
        trackingJob = viewModelScope.launch {
            locationManager.getLocationUpdates(_displayLanguage.value).collect { data ->
                val previousCity = _locationData.value.primaryPlace?.city
                _locationData.value = data

                val cur = _currentLatLng.value
                val speedKmh = (_currentLocationFix.value?.speedMs ?: 0f) * 3.6f
                if (cur != null) {
                    updatePositionAltitude(cur.first, cur.second, data.altitude, speedKmh)
                }

                if (_showBorders.value) {
                    val newCity = data.primaryPlace?.city
                    if (newCity != null && newCity != previousCity && newCity != "Unknown City") {
                        fetchCurrentBoundary()
                    }
                }
            }
        }

        rawTrackingJob = viewModelScope.launch {
            locationManager.getLocationRaw().collect { fix ->
                _currentLatLng.value = Triple(fix.lat, fix.lng, fix.bearing)
                _currentLocationFix.value = fix
                val speedKmh = fix.speedMs * 3.6f
                updatePositionAltitude(fix.lat, fix.lng, _locationData.value.altitude, speedKmh)
            }
        }
    }

    private var oneShotLocationJob: Job? = null

    fun stopTracking() {
        trackingJob?.cancel()
        trackingJob = null
        rawTrackingJob?.cancel()
        rawTrackingJob = null
        oneShotLocationJob?.cancel()
        oneShotLocationJob = null
    }

    fun requestOneShotLocation() {
        val lastSnap = locationManager.lastLocationSnapshot
        if (lastSnap != null && lastSnap.lat != 0.0 && lastSnap.lng != 0.0 &&
            (System.currentTimeMillis() - lastSnap.timestamp) < 15_000L
        ) {
            return
        }
        oneShotLocationJob?.cancel()
        oneShotLocationJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                locationManager.getCurrentLocationSingle(_displayLanguage.value)
            } catch (_: Exception) {}
        }
    }

    fun resetAllSettingsToDefaults() {
        appPrefs.edit().clear().apply()
        locPrefs.edit().remove("display_language").apply()
        val mapPrefs = getApplication<Application>().getSharedPreferences("where_am_i_map_prefs", Context.MODE_PRIVATE)
        mapPrefs.edit().clear().apply()

        _keepScreenOn.value = false
        _localityCardStyle.value = LocalityCardStyle.NORMAL
        _orientationMode.value = MapOrientationMode.NORTH
        _showHeatMap.value = false
        _showBorders.value = false
        _boundaryPoints.value = null
        _displayLanguage.value = DisplayLanguage.EN

        tripManager.setActivityProfile(ActivityProfile.CAR)
        tripManager.setAutoStopMinutes(5)
        tripManager.setTripMode(TripMode.AUTO)
        appStateManager.setPowerPolicy(BatteryPowerPolicy.SMART_AUTO)

        TelemetryLogger.log("SETTINGS", "All settings reset to canonical defaults")
    }

    val isTripPaused: StateFlow<Boolean> = tripManager.isPaused

    fun pauseTrip() {
        tripManager.pauseTrip()
    }

    fun resumeTrip() {
        tripManager.resumeTrip()
    }

    fun getTripModeForProfile(profile: ActivityProfile): TripMode {
        return tripManager.getTripModeForProfile(profile)
    }

    fun setTripModeForProfile(profile: ActivityProfile, mode: TripMode) {
        tripManager.setTripModeForProfile(profile, mode)
    }

    val autoResumeOnMotion: StateFlow<Boolean> = tripManager.autoResumeOnMotion

    fun setAutoResumeOnMotion(enabled: Boolean) {
        tripManager.setAutoResumeOnMotion(enabled)
    }

    fun reanalyzeTripPlaces(onResult: (TripManager.MaintenanceResult) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = tripManager.reanalyzeAllTripPlaces()
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                onResult(result)
            }
        }
    }

    val elevationCorrectionProgress: StateFlow<ElevationCorrectionProgress> = ElevationCorrectionManager.progress

    fun correctTripElevation(tripId: Long, mode: OverrideMode) {
        ElevationCorrectionManager.startSingleTrip(getApplication(), tripId, mode) {
            viewModelScope.launch(Dispatchers.IO) {
                _savedTrips.value = tripManager.getAllTrips()
            }
        }
    }

    fun correctAllTripsElevation(mode: OverrideMode) {
        ElevationCorrectionManager.startAll(
            context = getApplication(),
            mode = mode,
            onPlacesCompleted = {
                viewModelScope.launch(Dispatchers.IO) {
                    loadSavedPlaces()
                }
            },
            onAllCompleted = {
                viewModelScope.launch(Dispatchers.IO) {
                    _savedTrips.value = tripManager.getAllTrips()
                    loadSavedPlaces()
                }
            }
        )
    }

    fun pauseElevationCorrection() {
        ElevationCorrectionManager.pause()
    }

    fun resumeElevationCorrection() {
        ElevationCorrectionManager.resume()
    }

    fun stopElevationCorrection() {
        ElevationCorrectionManager.stop()
    }
}




