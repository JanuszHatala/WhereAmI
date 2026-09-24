package janush.tech.whereami

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HeatMapFilterState(
    val activityProfile: ActivityProfile? = null,
    val datePeriod: String = "ALL", // "ALL", "TODAY", "WEEK", "MONTH", "YEAR"
    val minVisits: Int = 1,
    val consolidateCorridors: Boolean = true,
    val includeActiveTrip: Boolean = true
) {
    val hasActiveFilter: Boolean
        get() = activityProfile != null || datePeriod != "ALL" || minVisits > 1 || !consolidateCorridors || !includeActiveTrip
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

    /** Latest GPS coordinates for the map composable (lat, lng, bearing?). */
    private val _currentLatLng = MutableStateFlow<Triple<Double, Double, Float?>?>(null)
    val currentLatLng: StateFlow<Triple<Double, Double, Float?>?> = _currentLatLng.asStateFlow()

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

    // Destination Pin & Details (from search)
    private val _destinationItem = MutableStateFlow<SearchResultItem?>(null)
    val destinationItem: StateFlow<SearchResultItem?> = _destinationItem.asStateFlow()

    private val _destinationPoint = MutableStateFlow<org.osmdroid.util.GeoPoint?>(null)
    val destinationPoint: StateFlow<org.osmdroid.util.GeoPoint?> = _destinationPoint.asStateFlow()

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

    // Manage Storage / Cache Pre-fetch & Navigation Dialog Target State
    enum class AppDialogTarget {
        NONE,
        CACHE_MANAGER,
        LIVE_SHARING
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

    init {
        startTracking()
        loadSavedTrips()
        loadSavedPlaces()

        // Automatically reload trips when a trip completes (manual or auto-stop)
        viewModelScope.launch {
            var hadActive = false
            tripManager.activeTrip.collect { active ->
                if (active != null) {
                    hadActive = true
                } else if (hadActive) {
                    hadActive = false
                    loadSavedTrips()
                }
            }
        }
    }

    fun setActivityProfile(profile: ActivityProfile) {
        tripManager.setActivityProfile(profile)
    }

    fun setDestination(item: SearchResultItem?) {
        _destinationItem.value = item
        _destinationPoint.value = item?.geoPoint
        _pinnedBoundaryPoints.value = null
    }

    fun setDestinationPoint(point: org.osmdroid.util.GeoPoint?) {
        _destinationPoint.value = point
        _pinnedBoundaryPoints.value = null
        if (point == null) {
            _destinationItem.value = null
        }
    }

    fun selectMapPoint(point: org.osmdroid.util.GeoPoint) {
        _destinationPoint.value = point
        _pinnedBoundaryPoints.value = null
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
                    geoPoint = geoPoint
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
        colorHex: String = ""
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
                colorHex = finalColor
            )
            dbHelper.insertSavedPlace(place)
            loadSavedPlaces()
        }
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

    fun search(query: String) {
        if (query.trim().length < 2) {
            _searchResults.value = emptyList()
            return
        }
        viewModelScope.launch {
            _isSearching.value = true
            val currentCity = _locationData.value.primaryPlace?.city
            _searchResults.value = SearchHelper.searchLocation(getApplication(), query, currentCity)
            _isSearching.value = false
        }
    }

    fun clearSearch() {
        _searchResults.value = emptyList()
        _isSearching.value = false
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

    fun setAutoStopMinutes(minutes: Int) {
        tripManager.setAutoStopMinutes(minutes)
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
        appPrefs.edit().putString("pref_map_orientation_mode", mode.name).apply()
        TelemetryLogger.log("SETTINGS", "MapOrientationMode changed to ${mode.name}")
    }

    fun toggleShowHeatMap() {
        val newState = !_showHeatMap.value
        _showHeatMap.value = newState
        appPrefs.edit().putBoolean("pref_show_heat_map", newState).apply()
        TelemetryLogger.log("SETTINGS", "ShowHeatMap changed to $newState")
    }

    private val _heatMapFilterState = MutableStateFlow(
        HeatMapFilterState(
            activityProfile = appPrefs.getString("pref_heatmap_activity", null)?.let {
                try { ActivityProfile.valueOf(it) } catch (_: Exception) { null }
            },
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
            putString("pref_heatmap_activity", state.activityProfile?.name)
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

    fun mergeSelectedTrips() {
        val ids = _selectedTripIds.value.toList()
        if (ids.size < 2) return
        viewModelScope.launch(Dispatchers.IO) {
            val newId = tripManager.mergeTrips(ids)
            _selectedTripIds.value = setOf(newId)
            _savedTrips.value = tripManager.getAllTrips()
            TelemetryLogger.log("TRIP", "Merged ${ids.size} trips into #$newId")
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
        trackingJob = viewModelScope.launch {
            locationManager.getLocationUpdates(_displayLanguage.value).collect { data ->
                val previousCity = _locationData.value.primaryPlace?.city
                _locationData.value = data

                if (_showBorders.value) {
                    val newCity = data.primaryPlace?.city
                    if (newCity != null && newCity != previousCity && newCity != "Unknown City") {
                        fetchCurrentBoundary()
                    }
                }
            }
        }

        viewModelScope.launch {
            locationManager.getLocationRaw().collect { (lat, lng, bearing) ->
                _currentLatLng.value = Triple(lat, lng, bearing)
            }
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
}




