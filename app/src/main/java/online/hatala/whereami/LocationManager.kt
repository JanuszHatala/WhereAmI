package online.hatala.whereami

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.location.Address
import android.location.Geocoder
import android.location.Location as AndroidLocation
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import com.google.android.gms.location.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import online.hatala.whereami.tracking.SmoothedPoint
import online.hatala.whereami.tracking.TrackDiagnosticsRecorder
import online.hatala.whereami.tracking.TrackState
import online.hatala.whereami.tracking.TrackingBridge
import online.hatala.whereami.tracking.TrackingEngine
import online.hatala.whereami.tracking.TrackingProfile
import online.hatala.whereami.tracking.TrackingRuntime
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.*
import kotlin.coroutines.resume

enum class DisplayLanguage {
    EN,
    PL,
    NATIVE
}

data class PlaceInfo(
    val city: String,          // Miejscowość / Town / Village / City
    val street: String? = null, // Ulica / Street / Thoroughfare
    val roadRef: String? = null,// Road Number / Ref (e.g. DK91, A1, E75)
    val gmina: String? = null, // Gmina / Municipality / Commune
    val powiat: String? = null,// Powiat / County / District
    val voivodeship: String,   // Województwo / State / Province
    val country: String,       // Kraj / Country
    val countryCode: String = "", // ISO 2-letter country code
    val highwayType: String? = null // OSM highway tag (motorway, trunk, primary, etc.)
) {
    fun isValid(): Boolean = city.isNotBlank() && city != "Unknown City" && city != "--"
}

data class MultiLanguagePlaceInfo(
    val en: PlaceInfo,
    val pl: PlaceInfo,
    val native: PlaceInfo
)

data class LocationData(
    val primaryPlace: PlaceInfo?,
    val secondaryPlace: PlaceInfo?,
    val speedMs: Float?, // Speed in meters per second (smoothed, non-null during active tracking)
    val error: String? = null,
    val isLoading: Boolean = false,
    val altitude: Double? = null,
    val accuracy: Float? = null,
    val outdoorContext: OutdoorContext? = null
)

/** Unified location snapshot broadcast to all consumers (Single Source of Truth). */
data class MultiLocationSnapshot(
    val multiPlace: MultiLanguagePlaceInfo?,
    val speedMs: Float,
    val speedKmh: Float,
    val lat: Double,
    val lng: Double,
    val altitude: Double?,
    val bearing: Float?,
    val accuracy: Float?,
    val timestamp: Long,
    val error: String? = null,
    val isLoading: Boolean = false,
    val fixSeqId: Long = 0L,
    /** Filtered kinematic state (position, velocity, turn rate, uncertainty) from the tracking engine. */
    val track: TrackState? = null,
    val outdoorContext: OutdoorContext? = null
) {
    fun toLocationData(displayLanguage: DisplayLanguage): LocationData {
        val place = when (displayLanguage) {
            DisplayLanguage.PL -> multiPlace?.pl
            DisplayLanguage.EN -> multiPlace?.en
            DisplayLanguage.NATIVE -> multiPlace?.native
        }
        val secPlace = when (displayLanguage) {
            DisplayLanguage.PL -> multiPlace?.en
            DisplayLanguage.EN -> multiPlace?.pl
            DisplayLanguage.NATIVE -> multiPlace?.en
        }
        return LocationData(
            primaryPlace = place,
            secondaryPlace = secPlace,
            speedMs = speedMs,
            altitude = altitude,
            accuracy = accuracy,
            error = error,
            isLoading = isLoading,
            outdoorContext = outdoorContext
        )
    }
}

/** Lightweight position & kinematics snapshot for smooth map tracking. */
data class LocationFix(
    val lat: Double,
    val lng: Double,
    val bearing: Float?,
    val speedMs: Float,
    val timestamp: Long,
    val accuracy: Float? = null,
    val isAnchored: Boolean = false,
    val fixSeqId: Long = 0L,
    /** Filtered kinematic state driving the render pipeline; null only for the cached startup position. */
    val track: TrackState? = null
)

/** Small data class for OSM Nominatim reverse-geocode results. */
private data class OsmPlaceResult(
    val city: String?,         // city / town / village / hamlet
    val street: String?,       // road / street / pedestrian
    val roadRef: String?,      // ref / road number
    val municipality: String?, // commune / gmina
    val county: String?,       // district / powiat
    val state: String?,
    val country: String?,
    val countryCode: String?,
    val houseNumber: String? = null, // Specific house number if returned by Nominatim
    val highwayType: String? = null  // OSM highway classification (motorway, trunk, primary, etc.)
)

class LocationManager private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: LocationManager? = null

        fun getInstance(context: Context): LocationManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LocationManager(context.applicationContext).also {
                    StorageMigrationHelper.migratePreferencesIfNeeded(it.context)
                    INSTANCE = it
                }
            }
        }

        @Volatile
        private var currentIntervalMs: Long = 1500L
        @Volatile
        private var currentMinIntervalMs: Long = 1000L
        @Volatile
        private var currentMaxUpdateDelayMs: Long = 0L
        @Volatile
        var isGpsStopped: Boolean = false

        private const val PREFS_WRITE_INTERVAL_MS = 30_000L
        private const val PREFS_WRITE_DISTANCE_M = 200f

        private val LOCALITY_SUFFIX_REGEX = Regex("""^(\p{L}[\p{L}\s.-]+?)(?:-\d+|\s+\d+|\d{2,})$""")

        private val activeCallbacks = Collections.synchronizedSet(mutableSetOf<LocationCallback>())

        /**
         * Set of Polish cities with county rights (miasta na prawach powiatu).
         * These localities constitute an autonomous urban county with no rural gmina.
         */
        /**
         * Generic structural autonomous county city check (e.g. city with county status / independent city):
         * Matches whenever the county designation is equal to the city name or starts with "m. <city>"
         * or "miasto <city>", or when gmina equals city and county is omitted.
         * Works generically in Poland and internationally without hardcoded city lists.
         */
        fun isAutonomousCountyCity(city: String?, gmina: String?, powiat: String?): Boolean {
            if (city.isNullOrBlank()) return false
            val cleanCity = city.trim()
            val cleanP = powiat?.replace("Powiat ", "", ignoreCase = true)
                ?.replace("powiat ", "", ignoreCase = true)
                ?.replace("m. st. ", "", ignoreCase = true)
                ?.replace("m.st. ", "", ignoreCase = true)
                ?.replace("m. ", "", ignoreCase = true)
                ?.replace("miasto ", "", ignoreCase = true)?.trim()
            val cleanG = gmina?.replace("Gmina ", "", ignoreCase = true)
                ?.replace("gmina ", "", ignoreCase = true)?.trim()
            if (!cleanP.isNullOrBlank() && cleanP.equals(cleanCity, ignoreCase = true)) return true
            if (cleanP.isNullOrBlank() && !cleanG.isNullOrBlank() && cleanG.equals(cleanCity, ignoreCase = true)) return true
            return false
        }

        /**
         * Country-aware hierarchy formatter:
         * - In Poland: gm. X • pow. Y • woj. Z (with smart deduplication)
         * - Outside Poland: Municipality • County • State (without Polish abbreviations)
         */
        /**
         * Canonicalizes Polish voivodeship names, mapping English descriptions from OpenStreetMap
         * or Android Platform Geocoder (e.g. "Lesser Poland Voivodeship", "Silesian Voivodeship")
         * into standardized Polish lowercase designations ("małopolskie", "śląskie").
         */
        fun canonicalizePolishVoivodeship(raw: String): String {
            val clean = raw.trim()
            val lower = clean.lowercase(Locale.ROOT)
            return when {
                lower.contains("lesser poland") || lower == "malopolskie" || lower == "małopolskie" -> "małopolskie"
                lower.contains("silesian") && !lower.contains("lower") || lower == "slaskie" || lower == "śląskie" -> "śląskie"
                lower.contains("lower silesian") || lower == "dolnoslaskie" || lower == "dolnośląskie" -> "dolnośląskie"
                lower.contains("greater poland") || lower == "wielkopolskie" -> "wielkopolskie"
                lower.contains("masovian") || lower == "mazowieckie" -> "mazowieckie"
                lower.contains("lodz") || lower.contains("łódź") || lower == "lodzkie" || lower == "łódzkie" -> "łódzkie"
                lower.contains("subcarpathian") || lower == "podkarpackie" -> "podkarpackie"
                lower.contains("pomeranian") && !lower.contains("west") && !lower.contains("kuyavian") || lower == "pomorskie" -> "pomorskie"
                lower.contains("west pomeranian") || lower == "zachodniopomorskie" -> "zachodniopomorskie"
                lower.contains("kuyavian") || lower == "kujawsko-pomorskie" -> "kujawsko-pomorskie"
                lower.contains("lublin") || lower == "lubelskie" -> "lubelskie"
                lower.contains("lubusz") || lower == "lubuskie" -> "lubuskie"
                lower.contains("opole") || lower == "opolskie" -> "opolskie"
                lower.contains("podlaskie") -> "podlaskie"
                lower.contains("holy cross") || lower == "swietokrzyskie" || lower == "świętokrzyskie" -> "świętokrzyskie"
                lower.contains("warmian") || lower == "warminsko-mazurskie" || lower == "warmińsko-mazurskie" -> "warmińsko-mazurskie"
                else -> clean.replace("Voivodeship", "", ignoreCase = true)
                    .replace("województwo ", "", ignoreCase = true)
                    .replace("województwo", "", ignoreCase = true)
                    .trim()
            }
        }

        fun formatHierarchy(place: PlaceInfo?): String {
            if (place == null) return ""
            val countryCode = place.countryCode.uppercase()
            val isPoland = countryCode == "PL" ||
                    place.country.equals("Polska", ignoreCase = true) ||
                    place.country.equals("Poland", ignoreCase = true)

            val city = place.city.trim()
            val rawGmina = place.gmina?.trim()
            val rawPowiat = place.powiat?.trim()
            val cleanGmina = if (isPoland) {
                if (rawGmina != null && (rawGmina.startsWith("gmina ", ignoreCase = true) || rawGmina.startsWith("gm. ", ignoreCase = true))) {
                    rawGmina.replace("gmina ", "", ignoreCase = true).replace("gm. ", "", ignoreCase = true).trim()
                } else null
            } else {
                rawGmina?.replace("Gmina ", "", ignoreCase = true)
                    ?.replace("gmina ", "", ignoreCase = true)?.trim()
            }
            val cleanPowiat = rawPowiat?.replace("Powiat ", "", ignoreCase = true)
                ?.replace("powiat ", "", ignoreCase = true)?.trim()
            val voivodeship = if (isPoland) {
                canonicalizePolishVoivodeship(place.voivodeship)
            } else {
                place.voivodeship
                    .replace("województwo ", "", ignoreCase = true)
                    .replace("województwo", "", ignoreCase = true)
                    .trim()
            }

            val isCountyCity = isAutonomousCountyCity(city, cleanGmina, cleanPowiat)

            if (isPoland) {
                val parts = mutableListOf<String>()
                // Cities with county rights (miasta na prawach powiatu) are self-governing counties:
                // They have NO separate rural gmina. Suppress "gm." and "pow." entirely.
                if (!isCountyCity) {
                    // Clean deduplication: omit gm. X if city is X
                    val isInvalidGmina = cleanGmina.isNullOrEmpty() ||
                        cleanGmina.contains("powiat", ignoreCase = true) ||
                        cleanGmina.contains("województwo", ignoreCase = true)
                    if (!isInvalidGmina && !cleanGmina!!.equals(city, ignoreCase = true)) {
                        parts.add("gm. $cleanGmina")
                    }
                    // Omit pow. Y if city or gmina is Y
                    if (!cleanPowiat.isNullOrEmpty() && !cleanPowiat.equals(city, ignoreCase = true) && !cleanPowiat.equals(cleanGmina, ignoreCase = true)) {
                        parts.add("pow. $cleanPowiat")
                    }
                }
                if (voivodeship.isNotEmpty() && !voivodeship.equals("Unknown Region", ignoreCase = true)) {
                    parts.add("woj. $voivodeship")
                }
                return parts.joinToString(" • ")
            } else {
                // International formatting: clean generic prefixes (e.g. "District of ", "Okres ", "Region of ", "Kraj ") to maintain stable presentation
                val cleanInternationalPowiat = cleanPowiat
                    ?.replace("District of ", "", ignoreCase = true)
                    ?.replace("Okres ", "", ignoreCase = true)
                    ?.trim()
                val cleanInternationalVoivodeship = voivodeship
                    .replace("Region of ", "", ignoreCase = true)
                    .replace("Kraj ", "", ignoreCase = true)
                    .replace("kraj", "", ignoreCase = true)
                    .trim()

                val parts = mutableListOf<String>()
                if (!cleanGmina.isNullOrEmpty() && !cleanGmina.equals(city, ignoreCase = true)) {
                    parts.add(cleanGmina)
                }
                if (!cleanInternationalPowiat.isNullOrEmpty() && !cleanInternationalPowiat.equals(city, ignoreCase = true) && !cleanInternationalPowiat.equals(cleanGmina, ignoreCase = true)) {
                    parts.add(cleanInternationalPowiat)
                }
                if (cleanInternationalVoivodeship.isNotEmpty() && !cleanInternationalVoivodeship.equals("Unknown Region", ignoreCase = true)) {
                    parts.add(cleanInternationalVoivodeship)
                }
                return parts.joinToString(" • ")
            }
        }
    }

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)
    private val requestSequencer = BatchedRequestSequencer()
    private val ioScope = CoroutineScope(Dispatchers.IO)
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var lastFixRealtimeMs: Long = 0L
    @Volatile
    private var lastSubscriptionRealtimeMs: Long = 0L
    @Volatile
    private var lastOutdoorContext: OutdoorContext? = null

    init {
        registerSystemReceivers()
        startGpsWatchdog()
    }

    // ── Tracking Engine v2 (CTRV EKF + fixed-lag smoother) ─────────────────────
    private val trackingLock = Any()
    private val diagnosticsRecorder = TrackDiagnosticsRecorder(File(context.filesDir, "diagnostics"))
    private val trackingEngine = TrackingEngine(TrackingProfile.CAR, diagnostics = diagnosticsRecorder)
    private var lastIngestedElapsedMs = 0L
    private var diagnosticsTripId: Long? = null
    private val diagnosticsEnabled: Boolean by lazy {
        (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0 ||
            context.getSharedPreferences("where_am_i_prefs", Context.MODE_PRIVATE).getBoolean("pref_track_diagnostics", false)
    }

    /** Emit smoothed points still inside the lag window (trip stop). The engine keeps running. */
    fun flushTracking(): List<SmoothedPoint> = synchronized(trackingLock) { trackingEngine.flush() }

    /** Forget filter state (e.g. after a long GNSS shutdown) so stale anchors cannot reject new fixes. */
    fun resetTracking() {
        synchronized(trackingLock) {
            trackingEngine.reset()
            lastIngestedElapsedMs = 0L
        }
    }

    private fun syncDiagnosticsSession(tripId: Long?) {
        if (!diagnosticsEnabled || tripId == diagnosticsTripId) return
        if (diagnosticsRecorder.isActive) diagnosticsRecorder.close()
        if (tripId != null) diagnosticsRecorder.startSession("trip$tripId")
        diagnosticsTripId = tripId
    }

    // Last-fix persistence is throttled: the old code wrote SharedPreferences on every fix.
    private var lastPrefsWriteMs = 0L
    private var lastPrefsLat = 0.0
    private var lastPrefsLng = 0.0

    private data class PendingGeocodeParams(
        val lat: Double,
        val lng: Double,
        val speedKmh: Float,
        val speedMs: Float,
        val bearing: Float?,
        val accuracy: Float?,
        val timestamp: Long
    )
    private var pendingGeocodeParams: PendingGeocodeParams? = null
    private var inFlightGeocodeJob: Job? = null
    private val geocodeFlightLock = Any()
    private var lastOutdoorResolvedLat: Double = 0.0
    private var lastOutdoorResolvedLng: Double = 0.0
    private var lastOutdoorResolvedTimeMs: Long = 0L
    private var outdoorWorkerJob: Job? = null
    private val outdoorLock = Any()
    private var lastScreenOffGeocodeMs = 0L
    private var lastScreenOffGeocodedPoint: Pair<Double, Double>? = null

    private fun persistLastFix(force: Boolean) {
        val snap = lastLocationSnapshot ?: return
        if (snap.lat == 0.0 && snap.lng == 0.0) return
        val now = android.os.SystemClock.elapsedRealtime()
        val moved = FloatArray(1)
        AndroidLocation.distanceBetween(lastPrefsLat, lastPrefsLng, snap.lat, snap.lng, moved)
        if (!force && now - lastPrefsWriteMs < PREFS_WRITE_INTERVAL_MS && moved[0] < PREFS_WRITE_DISTANCE_M) return
        lastPrefsWriteMs = now
        lastPrefsLat = snap.lat
        lastPrefsLng = snap.lng
        context.getSharedPreferences("where_am_i_prefs", Context.MODE_PRIVATE).edit()
            .putFloat("lat", snap.lat.toFloat())
            .putFloat("lng", snap.lng.toFloat())
            .putFloat("speed", snap.speedMs)
            .putFloat("accuracy", snap.accuracy ?: 0f)
            .apply()
    }

    private fun buildRequest(intervalMs: Long, minIntervalMs: Long, maxUpdateDelayMs: Long): LocationRequest {
        val b = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(minIntervalMs)
            .setGranularity(Granularity.GRANULARITY_FINE)
            .setWaitForAccurateLocation(false)
        // Batched delivery (screen off): the system wakes the CPU once per batch instead of per fix.
        // Every fix in the batch is processed in order, so no geometry is lost.
        if (maxUpdateDelayMs >= intervalMs) b.setMaxUpdateDelayMillis(maxUpdateDelayMs)
        return b.build()
    }

    /**
     * @param maxUpdateDelayMs 0 = deliver immediately; otherwise fixes may be batched for up to this long
     *        (only used while the screen is off and a trip is recording).
     */
    @SuppressLint("MissingPermission")
    fun updateSamplingInterval(intervalMs: Long, minIntervalMs: Long, maxUpdateDelayMs: Long = 0L) {
        // Dedupe identical re-issues: re-requesting restarts GNSS bookkeeping and wakes the CPU for nothing.
        if (!isGpsStopped && intervalMs == currentIntervalMs && minIntervalMs == currentMinIntervalMs &&
            maxUpdateDelayMs == currentMaxUpdateDelayMs && synchronized(activeCallbacks) { activeCallbacks.isNotEmpty() }
        ) return
        val wasBatched = isBatchedRequestActive()
        currentIntervalMs = intervalMs
        currentMinIntervalMs = minIntervalMs
        currentMaxUpdateDelayMs = maxUpdateDelayMs
        isGpsStopped = false
        lastSubscriptionRealtimeMs = SystemClock.elapsedRealtime()
        try {
            StationaryDetector.getInstance(context).startListening()
        } catch (_: Exception) {}
        val req = buildRequest(intervalMs, minIntervalMs, maxUpdateDelayMs)
        requestSequencer.run(wasBatched, ::flushBatchedFixes) {
            synchronized(activeCallbacks) {
                for (cb in activeCallbacks) {
                    try {
                        fusedLocationClient.requestLocationUpdates(req, cb, Looper.getMainLooper())
                    } catch (_: Exception) {}
                }
            }
        }
    }

    fun resubscribeGpsIfNeeded() {
        if (isGpsStopped) return
        val req = buildRequest(currentIntervalMs, currentMinIntervalMs, currentMaxUpdateDelayMs)
        lastSubscriptionRealtimeMs = SystemClock.elapsedRealtime()
        requestSequencer.run(isBatchedRequestActive(), ::flushBatchedFixes) {
            synchronized(activeCallbacks) {
                if (activeCallbacks.isEmpty()) return@run
                for (cb in activeCallbacks) {
                    try {
                        fusedLocationClient.requestLocationUpdates(req, cb, Looper.getMainLooper())
                    } catch (e: Exception) {
                        TelemetryLogger.log("LOCATION", "Failed to refresh location callback: ${e.message}")
                    }
                }
            }
        }
    }

    private fun registerSystemReceivers() {
        val filter = IntentFilter(android.location.LocationManager.PROVIDERS_CHANGED_ACTION)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action == android.location.LocationManager.PROVIDERS_CHANGED_ACTION) {
                    val lm = context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
                    val isGpsEnabled = lm?.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) == true
                    TelemetryLogger.log("LOCATION", "Location provider changed: GPS_PROVIDER enabled=$isGpsEnabled (isGpsStopped=$isGpsStopped)")
                    if (isGpsEnabled && !isGpsStopped) {
                        resubscribeGpsIfNeeded()
                    }
                }
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
        } catch (e: Exception) {
            TelemetryLogger.log("ERROR", "Failed to register PROVIDERS_CHANGED receiver: ${e.message}")
        }
    }

    private fun startGpsWatchdog() {
        managerScope.launch {
            while (true) {
                kotlinx.coroutines.delay(10_000L)
                if (isGpsStopped) continue
                val hasSubscribers = synchronized(activeCallbacks) { activeCallbacks.isNotEmpty() }
                if (!hasSubscribers) continue

                val appStateMgr = AppStateManager.getInstance(context)
                val mode = appStateMgr.currentMode.value
                val needsContinuousGps = mode == AppLifecycleMode.FOREGROUND_VIEW ||
                        mode == AppLifecycleMode.ANDROID_AUTO ||
                        mode == AppLifecycleMode.TRIP_RECORDING
                if (!needsContinuousGps) continue

                val now = SystemClock.elapsedRealtime()
                val baseline = maxOf(lastFixRealtimeMs, lastSubscriptionRealtimeMs)
                if (baseline > 0L && (now - baseline) > 25_000L) {
                    TelemetryLogger.log(
                        "LOCATION",
                        "GPS stall detected: no fix for ${(now - baseline) / 1000}s in mode $mode. Triggering soft re-subscription."
                    )
                    resubscribeGpsIfNeeded()
                }
            }
        }
    }

    fun stopLocationUpdates(preserveStationaryState: Boolean = false) {
        val wasBatched = isBatchedRequestActive()
        isGpsStopped = true
        lastValidSpeedMs = 0f
        lastValidRawSpeedTimestamp = 0L
        persistLastFix(force = true)
        try {
            StationaryDetector.getInstance(context).stopListening(preserveStationaryState)
        } catch (_: Exception) {}
        requestSequencer.run(wasBatched, ::flushBatchedFixes) {
            synchronized(activeCallbacks) {
                for (cb in activeCallbacks) {
                    try {
                        fusedLocationClient.removeLocationUpdates(cb)
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private fun isBatchedRequestActive(): Boolean =
        !isGpsStopped && currentMaxUpdateDelayMs > 0L && synchronized(activeCallbacks) { activeCallbacks.isNotEmpty() }

    /** Delivers fixes Fused is holding in a batch to the registered callbacks, then calls [onDone]. */
    @SuppressLint("MissingPermission")
    private fun flushBatchedFixes(onDone: () -> Unit) {
        val completed = java.util.concurrent.atomic.AtomicBoolean(false)
        val completeOnce: () -> Unit = {
            if (completed.compareAndSet(false, true)) {
                onDone()
            }
        }
        val timeoutRunnable = Runnable { completeOnce() }
        mainHandler.postDelayed(timeoutRunnable, 500L)
        try {
            fusedLocationClient.flushLocations().addOnCompleteListener {
                mainHandler.removeCallbacks(timeoutRunnable)
                completeOnce()
            }
        } catch (_: Exception) {
            mainHandler.removeCallbacks(timeoutRunnable)
            completeOnce()
        }
    }

    var isGpsStopped: Boolean
        get() = LocationManager.isGpsStopped
        set(value) { LocationManager.isGpsStopped = value }

    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocationBounded(
        timeoutMs: Long = 15_000L,
        priority: Int = Priority.PRIORITY_HIGH_ACCURACY
    ): AndroidLocation? = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { cont ->
            val cts = com.google.android.gms.tasks.CancellationTokenSource()
            cont.invokeOnCancellation {
                cts.cancel()
            }
            try {
                fusedLocationClient.getCurrentLocation(priority, cts.token)
                    .addOnSuccessListener { loc ->
                        if (cont.isActive) cont.resume(loc)
                    }
                    .addOnFailureListener {
                        if (cont.isActive) cont.resume(null)
                    }
            } catch (_: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    // ── Adaptive Velocity Kalman Filter (Industry Standard Speedometer) ─────
    private var kalmanSpeed: Float? = null
    private var kalmanVariance = 0.25f
    private val BASE_PROCESS_NOISE = 0.08f
    private val ADAPTIVE_FACTOR = 0.35f
    private val DEFAULT_MEAS_NOISE = 0.25f // typical GNSS Doppler speed variance (sigma ~ 0.5 m/s)
    private val STATIONARY_THRESHOLD = 0.5f // m/s (~1.8 km/h) — typical indoor GPS Doppler noise floor
    private val ZERO_SNAP_THRESHOLD = 0.4f // m/s (~1.44 km/h) — instant snap to 0 km/h

    @Volatile
    private var lastValidSpeedMs: Float = 0f
    @Volatile
    private var lastValidRawSpeedTimestamp: Long = 0L
    @Volatile
    private var snapshotChannelSend: ((MultiLocationSnapshot) -> Unit)? = null

    fun getLastValidSpeedMs(): Float = lastValidSpeedMs

    // Displacement tracking for zero-motion confirmation
    private var lastFixLat: Double = 0.0
    private var lastFixLng: Double = 0.0
    private var lastFixTimestamp: Long = 0L

    // Cumulative displacement tracking to unblock slow-speed driving (5-10 km/h) from rest
    private var cumulativeAnchorLat: Double = 0.0
    private var cumulativeAnchorLng: Double = 0.0
    private var cumulativeAnchorTime: Long = 0L

    // MAP-R03: Stationary Bearing Freeze (retains driving heading when stopped)
    @Volatile
    private var lastValidBearing: Float? = null

    private fun hybridSpeedUpdate(
        rawSpeed: Float?,
        gpsAccuracyMps: Float?,
        lat: Double = 0.0,
        lng: Double = 0.0,
        timestamp: Long = 0L
    ): Float {
        // Calculate physical displacement delta
        var isStationaryDisplacement = false
        var distMoved = 0f
        if (lat != 0.0 && lng != 0.0 && lastFixLat != 0.0 && lastFixLng != 0.0 && timestamp > 0L && lastFixTimestamp > 0L) {
            val dist = FloatArray(1)
            android.location.Location.distanceBetween(lastFixLat, lastFixLng, lat, lng, dist)
            distMoved = dist[0]
            val dtSec = (timestamp - lastFixTimestamp) / 1000f
            if (dtSec in 0.5f..45f) {
                val displacementSpeed = distMoved / dtSec
                // If device hasn't displaced more than 3.0m and displacement speed is under 0.5 m/s, it's stationary jitter
                if (distMoved < 3.0f && displacementSpeed < 0.5f) {
                    isStationaryDisplacement = true
                }
            }
        }
        if (lat != 0.0 && lng != 0.0 && timestamp > 0L) {
            lastFixLat = lat
            lastFixLng = lng
            lastFixTimestamp = timestamp
        }

        // Cumulative displacement tracking: prevents getting trapped at 0.0 km/h during slow starts (5-10 km/h)
        var cumulativeDistMoved = 0f
        if (lat != 0.0 && lng != 0.0 && timestamp > 0L) {
            if (cumulativeAnchorLat == 0.0 || cumulativeAnchorLng == 0.0 || (timestamp - cumulativeAnchorTime) > 20_000L) {
                cumulativeAnchorLat = lat
                cumulativeAnchorLng = lng
                cumulativeAnchorTime = timestamp
            } else {
                val cumDist = FloatArray(1)
                android.location.Location.distanceBetween(cumulativeAnchorLat, cumulativeAnchorLng, lat, lng, cumDist)
                cumulativeDistMoved = cumDist[0]
            }
        }

        // Accelerometer-based physical motion check:
        // Strictly for indoor desk / resting clamp.
        // Clamps speed to 0.0 m/s when accelerometer variance confirms device is at rest
        // and displacement is within GPS multipath jitter bounds (single fix <= 4.0m and cumulative <= 8.0m).
        // This eliminates false indoor speeds (1.2 - 2.5 m/s) sitting on a desk or couch.
        // Smooth highway cruising is not clamped because vehicle travel produces continuous displacement (> 4.0m).
        val isPhysicallyStationary = try {
            StationaryDetector.getInstance(context).isPhysicallyStationary.value
        } catch (_: Exception) { false }

        if (isPhysicallyStationary && distMoved <= 4.0f && cumulativeDistMoved <= 8.0f) {
            kalmanSpeed = 0f
            lastValidSpeedMs = 0f
            return 0f
        }

        // If rawSpeed is null (GPS chip Doppler cold start), use cumulative displacement speed if available
        val effectiveRawSpeed = if (rawSpeed == null && cumulativeDistMoved > 8.0f && timestamp > cumulativeAnchorTime) {
            val cumDtSec = (timestamp - cumulativeAnchorTime) / 1000f
            if (cumDtSec > 0.5f) cumulativeDistMoved / cumDtSec else lastValidSpeedMs
        } else {
            rawSpeed
        }

        if (effectiveRawSpeed == null) {
            val staleSpeed = timestamp > 0L && lastValidRawSpeedTimestamp > 0L && (timestamp - lastValidRawSpeedTimestamp) > 6_000L
            if (staleSpeed || isStationaryDisplacement || isPhysicallyStationary) {
                kalmanSpeed = 0f
                lastValidSpeedMs = 0f
                return 0f
            }
            // Keep last valid speed briefly (< 6s) rather than dropping to 0 on a single fix without Doppler speed
            return lastValidSpeedMs
        }
        if (timestamp > 0L) {
            lastValidRawSpeedTimestamp = timestamp
        }

        // Check if raw speed falls within stationary noise floor:
        // 1) Below physical locomotion threshold (< 0.5 m/s or ~1.8 km/h)
        // 2) Or raw speed is smaller than the GPS speed uncertainty margin (noise floor)
        // 3) Or physical position displacement over the interval confirms zero motion
        val speedCandidate = effectiveRawSpeed
        val isStationaryNoise = speedCandidate < STATIONARY_THRESHOLD ||
                (gpsAccuracyMps != null && gpsAccuracyMps > 0.8f && speedCandidate <= gpsAccuracyMps && speedCandidate < 1.0f) ||
                (isStationaryDisplacement && speedCandidate < 0.8f)

        // Clamp stationary noise to true 0
        val cleanRaw = if (isStationaryNoise) 0f else speedCandidate

        // Instant Zero-Snap (Anti-Creep): when stopped or below snap threshold, snap directly to 0
        if (cleanRaw < ZERO_SNAP_THRESHOLD) {
            kalmanSpeed = 0f
            lastValidSpeedMs = 0f
            return 0f
        }

        val measNoise = when {
            gpsAccuracyMps != null && gpsAccuracyMps > 0f -> (gpsAccuracyMps * gpsAccuracyMps).coerceIn(0.04f, 1.5f)
            else -> DEFAULT_MEAS_NOISE
        }

        if (kalmanSpeed == null || kalmanSpeed == 0f) {
            kalmanSpeed = cleanRaw
            kalmanVariance = measNoise
            lastValidSpeedMs = cleanRaw
            return cleanRaw
        }

        // Adaptive 1D Velocity Kalman Filter step:
        // Accelerating or braking: innovation is high -> expand process noise -> gain -> 1 (zero lag)
        // Steady cruising: innovation is low -> process noise contracts -> gain is small (rock-solid filtering)
        val innovation = cleanRaw - kalmanSpeed!!
        val adaptiveProcessNoise = BASE_PROCESS_NOISE + ADAPTIVE_FACTOR * innovation * innovation
        val predictedVariance = kalmanVariance + adaptiveProcessNoise
        val gain = predictedVariance / (predictedVariance + measNoise)
        kalmanSpeed = kalmanSpeed!! + gain * innovation
        kalmanVariance = (1f - gain) * predictedVariance

        val kSpeed = if (kalmanSpeed!! < ZERO_SNAP_THRESHOLD) 0f else kalmanSpeed!!
        lastValidSpeedMs = kSpeed
        return kSpeed
    }

    // ── Border Debounce / Hysteresis Engine ─────────────────────────────────────
    private val geocodeLock = Any()
    @Volatile
    private var latestEnrichedTimestamp: Long = 0L

    private var committedPlace: MultiLanguagePlaceInfo? = null
    private var candidatePlace: MultiLanguagePlaceInfo? = null
    private var candidateCount: Int = 0
    private var candidateFirstSeenTime: Long = 0L
    private var lastCommittedLat: Double = 0.0
    private var lastCommittedLng: Double = 0.0

    private fun applyBorderHysteresis(
        lat: Double,
        lng: Double,
        rawMultiData: MultiLanguagePlaceInfo
    ): MultiLanguagePlaceInfo {
        val current = committedPlace
        val now = System.currentTimeMillis()
        if (current == null) {
            committedPlace = rawMultiData
            lastCommittedLat = lat
            lastCommittedLng = lng
            TelemetryLogger.logHysteresis("INITIAL_COMMIT", null, rawMultiData.pl.city, "lat=$lat, lng=$lng")
            return rawMultiData
        }

        val rawCity = rawMultiData.pl.city
        val currentCity = current.pl.city

        if (rawCity.equals(currentCity, ignoreCase = true)) {
            // Same locality: update committed details (subtitles etc.) and reset candidate
            committedPlace = rawMultiData
            candidatePlace = null
            candidateCount = 0
            candidateFirstSeenTime = 0L
            return rawMultiData
        }

        // Different locality observed! Check distance from last committed switch
        val distMoved = distanceBetween(lat, lng, lastCommittedLat, lastCommittedLng)
        val speedKmh = lastValidSpeedMs * 3.6f

        // When driving (> 25 km/h), require at least 200m inside the new territory OR sustained for 4+ seconds
        val minDistanceRequired = if (speedKmh > 25f) 200f else 150f
        val minDurationRequiredMs = if (speedKmh > 25f) 4_000L else 3_000L

        if (candidatePlace != null && candidatePlace!!.pl.city.equals(rawCity, ignoreCase = true)) {
            candidateCount++
        } else {
            candidatePlace = rawMultiData
            candidateCount = 1
            candidateFirstSeenTime = now
        }

        val candidateDurationMs = now - candidateFirstSeenTime
        val isDistanceMet = distMoved >= minDistanceRequired && candidateCount >= 3
        val isDurationMet = candidateDurationMs >= minDurationRequiredMs && candidateCount >= 4

        if (isDistanceMet || isDurationMet) {
            TelemetryLogger.logHysteresis("COMMIT_SWITCH", currentCity, rawCity, "distMoved=${distMoved.toInt()}m, candidateCount=$candidateCount, duration=${candidateDurationMs/1000}s")
            committedPlace = candidatePlace
            lastCommittedLat = lat
            lastCommittedLng = lng
            candidatePlace = null
            candidateCount = 0
            candidateFirstSeenTime = 0L
            return committedPlace!!
        }

        TelemetryLogger.logHysteresis("SUPPRESS_OSCILLATION", currentCity, rawCity, "distMoved=${distMoved.toInt()}m (need $minDistanceRequired m), count=$candidateCount (need 3), dur=${candidateDurationMs/1000}s (need ${minDurationRequiredMs/1000}s)")
        return current
    }

    // ── Street Debounce / Hysteresis Engine ─────────────────────────────────────
    private var committedStreetPl: String? = null
    private var committedStreetBase: String? = null
    private var candidateStreetPl: String? = null
    private var candidateStreetBase: String? = null
    private var candidateStreetCount: Int = 0
    private var candidateStreetFirstSeenTime: Long = 0L
    private var lastStreetSeenTimestamp: Long = 0L
    private var lastCommittedStreetLocality: String? = null
    private var lastSustainedBearing: Float? = null
    private var lastTurnTimestamp: Long = 0L

    private fun applyStreetHysteresis(
        speedMs: Float,
        multiData: MultiLanguagePlaceInfo,
        bearing: Float? = null
    ): MultiLanguagePlaceInfo {
        val rawStreetPl = multiData.pl.street
        val rawBase = RoadNameNormalizer.extractBaseStreet(rawStreetPl)
        val now = System.currentTimeMillis()
        val speedKmh = speedMs * 3.6f

        // Kinematic turn tracking: if vehicle turns (heading change >= 25 deg at speed >= 1 km/h),
        // record turn timestamp so we promptly switch to the new street.
        if (bearing != null && speedKmh >= 1f) {
            val prev = lastSustainedBearing
            if (prev != null) {
                val delta = kotlin.math.abs(((bearing - prev + 540) % 360) - 180)
                if (delta >= 25f) {
                    lastTurnTimestamp = now
                    TelemetryLogger.log("STREET", "Kinematic turn detected: Δbearing=${delta.toInt()}°, heading=$prev -> $bearing")
                    lastSustainedBearing = bearing
                }
            } else {
                lastSustainedBearing = bearing
            }
        }

        // GEO-02: 15-second decay grace period when reverse geocoding temporarily returns no street
        if (rawStreetPl.isNullOrBlank()) {
            if (committedStreetPl != null && (now - lastStreetSeenTimestamp) < 15_000L) {
                return multiData.copy(
                    en = multiData.en.copy(street = committedStreetPl),
                    pl = multiData.pl.copy(street = committedStreetPl),
                    native = multiData.native.copy(street = committedStreetPl)
                )
            }
            committedStreetPl = null
            committedStreetBase = null
            return multiData
        }

        lastStreetSeenTimestamp = now

        // Initial commit
        if (committedStreetPl == null || committedStreetBase == null) {
            committedStreetPl = rawStreetPl
            committedStreetBase = rawBase
            lastCommittedStreetLocality = multiData.pl.city
            candidateStreetPl = null
            candidateStreetBase = null
            candidateStreetCount = 0
            return multiData
        }

        // Same base road! Update house number or minor variation immediately while preserving confirmed highway codes
        if (rawBase.equals(committedStreetBase, ignoreCase = true)) {
            val existingHighwayCode = RoadNameNormalizer.extractHighwayCode(committedStreetPl) ?: committedPlace?.pl?.roadRef
            val incomingHighwayCode = multiData.pl.roadRef ?: RoadNameNormalizer.extractHighwayCode(rawStreetPl)
            val effectiveRef = incomingHighwayCode ?: existingHighwayCode
            val isMajor = RoadNameNormalizer.isMajorRoad(rawBase, effectiveRef)

            val updatedStreetPl = if (isMajor && effectiveRef != null) {
                RoadNameNormalizer.formatStreetDisplay(rawBase, effectiveRef, multiData.pl.countryCode) ?: rawStreetPl
            } else {
                rawStreetPl
            }

            committedStreetPl = updatedStreetPl
            if (!multiData.pl.city.isNullOrBlank() && !multiData.pl.city.equals("Unknown City", ignoreCase = true)) {
                lastCommittedStreetLocality = multiData.pl.city
            }
            candidateStreetPl = null
            candidateStreetBase = null
            candidateStreetCount = 0
            val effectiveRefPl = if (isMajor) effectiveRef else multiData.pl.roadRef
            return multiData.copy(
                en = multiData.en.copy(street = updatedStreetPl, roadRef = effectiveRefPl),
                pl = multiData.pl.copy(street = updatedStreetPl, roadRef = effectiveRefPl),
                native = multiData.native.copy(street = updatedStreetPl, roadRef = effectiveRefPl)
            )
        }

        // Different road detected!
        val isCommittedMajor = RoadNameNormalizer.isMajorRoad(committedStreetBase, committedPlace?.pl?.roadRef) ||
            RoadNameNormalizer.isMajorRoad(committedStreetPl, RoadNameNormalizer.extractHighwayCode(committedStreetPl))
        val isCandidateMajor = RoadNameNormalizer.isMajorRoad(rawBase, multiData.pl.roadRef) ||
            RoadNameNormalizer.isMajorRoad(rawStreetPl, RoadNameNormalizer.extractHighwayCode(rawStreetPl))

        // Track candidate observations
        if (candidateStreetBase != null && candidateStreetBase.equals(rawBase, ignoreCase = true)) {
            candidateStreetCount++
        } else {
            candidateStreetPl = rawStreetPl
            candidateStreetBase = rawBase
            candidateStreetCount = 1
            candidateStreetFirstSeenTime = now
        }

        val isLocalityTransition = lastCommittedStreetLocality != null &&
                !multiData.pl.city.isNullOrBlank() &&
                !multiData.pl.city.equals("Unknown City", ignoreCase = true) &&
                !multiData.pl.city.equals(lastCommittedStreetLocality, ignoreCase = true)

        val isRoundaboutExit = (committedStreetBase?.contains("Rondo", ignoreCase = true) == true ||
                committedStreetBase?.contains("Roundabout", ignoreCase = true) == true) &&
                !rawBase.contains("Rondo", ignoreCase = true) &&
                !rawBase.contains("Roundabout", ignoreCase = true)

        // When crossing into a confirmed new locality, adopt the new locality's street immediately on the 1st fix,
        // UNLESS we are currently on a confirmed major highway corridor and the candidate is a non-major side street
        // (preserves highway corridor continuity e.g. DK52 across village borders).
        val allowImmediateLocalityTransition = isLocalityTransition && !(isCommittedMajor && !isCandidateMajor)
        if (allowImmediateLocalityTransition && !rawStreetPl.isNullOrBlank()) {
            TelemetryLogger.log("STREET", "Locality transition to '${multiData.pl.city}': adopting candidate '$rawStreetPl' immediately on 1st fix")
            committedStreetPl = rawStreetPl
            committedStreetBase = rawBase
            lastCommittedStreetLocality = multiData.pl.city
            candidateStreetPl = null
            candidateStreetBase = null
            candidateStreetCount = 0
            return multiData.copy(
                en = multiData.en.copy(street = committedStreetPl),
                pl = multiData.pl.copy(street = committedStreetPl),
                native = multiData.native.copy(street = committedStreetPl)
            )
        }

        // When leaving a roundabout onto an exit street, adopt immediately on the 1st fix (zero delay)
        if (isRoundaboutExit && !rawStreetPl.isNullOrBlank()) {
            TelemetryLogger.log("STREET", "Roundabout exit from '$committedStreetPl' to '$rawStreetPl': adopting immediately on 1st fix")
            committedStreetPl = rawStreetPl
            committedStreetBase = rawBase
            lastCommittedStreetLocality = multiData.pl.city
            candidateStreetPl = null
            candidateStreetBase = null
            candidateStreetCount = 0
            return multiData.copy(
                en = multiData.en.copy(street = committedStreetPl),
                pl = multiData.pl.copy(street = committedStreetPl),
                native = multiData.native.copy(street = committedStreetPl)
            )
        }

        val isRecentTurn = (now - lastTurnTimestamp) < 16_000L

        // When turning onto a major corridor (e.g. from local street onto DK52 / Żywiecka),
        // adopt candidate immediately on the 1st fix (zero delay) to prevent showing the old street
        if (isRecentTurn && isCandidateMajor && !isCommittedMajor && !rawStreetPl.isNullOrBlank()) {
            TelemetryLogger.log("STREET", "Turn onto major corridor from '$committedStreetPl' to '$rawStreetPl': adopting immediately on 1st fix")
            committedStreetPl = rawStreetPl
            committedStreetBase = rawBase
            lastCommittedStreetLocality = multiData.pl.city
            candidateStreetPl = null
            candidateStreetBase = null
            candidateStreetCount = 0
            return multiData.copy(
                en = multiData.en.copy(street = committedStreetPl),
                pl = multiData.pl.copy(street = committedStreetPl),
                native = multiData.native.copy(street = committedStreetPl)
            )
        }

        // High-Speed Corridor Shield:
        // While driving at high speed (> 30 km/h) along a confirmed corridor without having made a physical turn:
        // Strictly reject building parcel addresses with house numbers (e.g. "Braterska 144", "Lipowa 6").
        val hasCandidateHouseNumber = rawStreetPl.any { it.isDigit() }
        if (speedKmh > 30f && isCommittedMajor && hasCandidateHouseNumber && !isRecentTurn) {
            TelemetryLogger.log("STREET", "Shielding major corridor '$committedStreetPl' from house-numbered side street '$rawStreetPl' at ${speedKmh.toInt()} km/h")
            val committedRef = RoadNameNormalizer.extractHighwayCode(committedStreetPl)
            return multiData.copy(
                en = multiData.en.copy(street = committedStreetPl, roadRef = committedRef),
                pl = multiData.pl.copy(street = committedStreetPl, roadRef = committedRef),
                native = multiData.native.copy(street = committedStreetPl, roadRef = committedRef)
            )
        }

        // Compute required confirmations based on speed and road hierarchy
        val rawRequiredCount: Int
        val rawRequiredDuration: Long

        when {
            speedKmh < 1.2f -> {
                // Stationary (traffic light / stop sign / resting):
                // Strictly resist changing street name unless confirmed over extended duration
                rawRequiredCount = if (isCommittedMajor && !isCandidateMajor) 6 else 4
                rawRequiredDuration = if (isCommittedMajor && !isCandidateMajor) 15_000L else 8_000L
            }
            speedKmh > 35f -> {
                // High speed driving (viaduct / bridge / corridor inertia):
                // Never abandon DK/DW/A/S or major thoroughfares for a parallel or side street unless sustained.
                // Non-major → non-major also needs inertia: trip 249 Kozy showed "Przecznia" (perpendicular)
                // while driving Krakowska because the old 2 fixes / 2 s gate flipped on a brief OSRM snap.
                rawRequiredCount = when {
                    isCommittedMajor && !isCandidateMajor -> 8
                    !isCommittedMajor && isCandidateMajor -> 2 // Snap onto highway corridor quickly
                    else -> 5
                }
                rawRequiredDuration = when {
                    isCommittedMajor && !isCandidateMajor -> 12_000L
                    !isCommittedMajor && isCandidateMajor -> 2_000L
                    else -> 7_000L
                }
            }
            speedKmh > 18f -> {
                // Moderate city driving / cycling (18 - 35 km/h, road intersections / corridor preservation):
                // Without a verified physical turn, leaving a major road requires corridor inertia (6 fixes / 8s)
                rawRequiredCount = when {
                    isCommittedMajor && !isCandidateMajor -> if (isRecentTurn) 4 else 6
                    !isCommittedMajor && isCandidateMajor -> 2
                    else -> if (isRecentTurn) 2 else 4
                }
                rawRequiredDuration = when {
                    isCommittedMajor && !isCandidateMajor -> if (isRecentTurn) 4_000L else 8_000L
                    !isCommittedMajor && isCandidateMajor -> 2_000L
                    else -> if (isRecentTurn) 2_000L else 5_000L
                }
            }
            else -> {
                // Slow driving / cycling / walking (1.2 - 18 km/h):
                // Protect major roads (DK*, DW*, etc.) and main thoroughfares against side-street hopping
                rawRequiredCount = if (isCommittedMajor && !isCandidateMajor) 5 else 3
                rawRequiredDuration = if (isCommittedMajor && !isCandidateMajor) 6_000L else 3_000L
            }
        }

        // When crossing into a confirmed new locality, after making a physical turn,
        // or transitioning between village estates (osiedla/przysiółki),
        // relax threshold to adopt the new name promptly (2 confirmations / 2 seconds).
        // BUT NEVER relax leaving a major highway corridor for a non-major residential street
        // (prevents roundabout entry curves and highway bends from hopping to side streets like "Lipowa 6").
        val isEstateTransition = (committedStreetBase?.startsWith("os. ") == true) || rawBase.startsWith("os. ")
        val canRelax = (isLocalityTransition || isRecentTurn || isEstateTransition) &&
            !(isCommittedMajor && !isCandidateMajor)
        val requiredCount = when {
            canRelax -> minOf(rawRequiredCount, 2)
            else -> rawRequiredCount
        }
        val requiredDuration = when {
            canRelax -> minOf(rawRequiredDuration, 2_000L)
            else -> rawRequiredDuration
        }
        val candidateDuration = now - candidateStreetFirstSeenTime

        // Must satisfy BOTH count AND duration to commit a street switch!
        if (candidateStreetCount >= requiredCount && candidateDuration >= requiredDuration) {
            TelemetryLogger.log("STREET", "Switch committed at ${speedKmh.toInt()} km/h (turn=$isRecentTurn): '$committedStreetPl' -> '$rawStreetPl'")
            committedStreetPl = candidateStreetPl
            committedStreetBase = candidateStreetBase
            lastCommittedStreetLocality = multiData.pl.city
            candidateStreetPl = null
            candidateStreetBase = null
            candidateStreetCount = 0
            val committedRef = RoadNameNormalizer.extractHighwayCode(committedStreetPl)
            return multiData.copy(
                en = multiData.en.copy(street = committedStreetPl, roadRef = committedRef),
                pl = multiData.pl.copy(street = committedStreetPl, roadRef = committedRef),
                native = multiData.native.copy(street = committedStreetPl, roadRef = committedRef)
            )
        } else {
            // If crossing into a new locality and candidate street is pending confirmation,
            // prioritize candidate street or roadRef to never leave the road display blank!
            if (isLocalityTransition) {
                val fallbackStreet = if (!multiData.pl.roadRef.isNullOrBlank()) {
                    RoadNameNormalizer.normalize(null, multiData.pl.roadRef, null)
                } else {
                    rawStreetPl
                }
                return multiData.copy(
                    en = multiData.en.copy(street = fallbackStreet),
                    pl = multiData.pl.copy(street = fallbackStreet),
                    native = multiData.native.copy(street = fallbackStreet)
                )
            }
            // Keep current committed street (filter out momentary cross-street and side-street jitter)
            val committedRef = RoadNameNormalizer.extractHighwayCode(committedStreetPl)
            return multiData.copy(
                en = multiData.en.copy(street = committedStreetPl, roadRef = committedRef),
                pl = multiData.pl.copy(street = committedStreetPl, roadRef = committedRef),
                native = multiData.native.copy(street = committedStreetPl, roadRef = committedRef)
            )
        }
    }

    // ── Single Source of Truth (SSOT) Master Location Pipeline ────────────────
    @Volatile
    private var fixSeqCounter: Long = 0L

    @Volatile
    var lastLocationSnapshot: MultiLocationSnapshot? = null
        private set

    private val masterLocationFlow: SharedFlow<MultiLocationSnapshot> by lazy {
        callbackFlow {
            val prefs = context.getSharedPreferences("where_am_i_prefs", Context.MODE_PRIVATE)
            val initialSpeed = if (prefs.contains("speed")) prefs.getFloat("speed", 0f) else 0f
            val initialCoords = if (prefs.contains("lat") && prefs.contains("lng")) {
                Pair(prefs.getFloat("lat", 0f).toDouble(), prefs.getFloat("lng", 0f).toDouble())
            } else null

            if (initialCoords != null) {
                val cachedMulti = resolveMultiLanguageData(initialCoords.first, initialCoords.second, allowNetwork = false)
                committedPlace = cachedMulti
                val initialSnap = MultiLocationSnapshot(
                    multiPlace = cachedMulti,
                    speedMs = initialSpeed,
                    speedKmh = initialSpeed * 3.6f,
                    lat = initialCoords.first,
                    lng = initialCoords.second,
                    altitude = null,
                    bearing = null,
                    accuracy = null,
                    timestamp = System.currentTimeMillis()
                )
                lastLocationSnapshot = initialSnap
                trySend(initialSnap)
            } else {
                val emptySnap = MultiLocationSnapshot(
                    multiPlace = null,
                    speedMs = initialSpeed,
                    speedKmh = initialSpeed * 3.6f,
                    lat = 0.0,
                    lng = 0.0,
                    altitude = null,
                    bearing = null,
                    accuracy = null,
                    timestamp = System.currentTimeMillis(),
                    isLoading = true
                )
                trySend(emptySnap)
            }

            snapshotChannelSend = { trySend(it) }
            val locationRequest = buildRequest(currentIntervalMs, currentMinIntervalMs, currentMaxUpdateDelayMs)

            fun handleLocation(location: AndroidLocation, emitUiAndGeocode: Boolean = true): Boolean {
                val tripManager = TripManager.getInstance(context)
                val activeTrip = tripManager.activeTrip.value
                val currentProfile = activeTrip?.activityProfile ?: tripManager.activityProfile.value

                val rawFix = TrackingBridge.toRawFix(location)
                lastFixRealtimeMs = SystemClock.elapsedRealtime()
                val isPhysicallyStationary = try {
                    StationaryDetector.getInstance(context).isPhysicallyStationary.value
                } catch (_: Exception) { false }

                // Tracking Engine v2: gate + CTRV EKF + ZUPT + fixed-lag smoother replace the former
                // GpsFilterEngine -> bearing EMA -> PositionStabilizer chain.
                val result = synchronized(trackingLock) {
                    syncDiagnosticsSession(activeTrip?.id)
                    val wanted = TrackingBridge.profileFor(currentProfile)
                    if (trackingEngine.profile.name != wanted.name) {
                        trackingEngine.reset()
                        trackingEngine.profile = wanted
                    }
                    val gyroRate = if (lastIngestedElapsedMs > 0L) {
                        TrackingRuntime.gyro.meanRate(lastIngestedElapsedMs, rawFix.tElapsedMs)
                    } else null
                    diagnosticsRecorder.gyroRateForNextFix = gyroRate
                    diagnosticsRecorder.compassForNextFix = TrackingRuntime.compass.headingAt(rawFix.tElapsedMs, 1_000L)
                    lastIngestedElapsedMs = rawFix.tElapsedMs
                    trackingEngine.ingest(rawFix, gyroRate, isPhysicallyStationary)
                }
                val state = result.state
                if (!result.accepted || state == null) {
                    val staleWhileRejected = lastValidSpeedMs > 0f && (
                        isPhysicallyStationary ||
                            tripManager.isPaused.value ||
                            (lastFixTimestamp > 0L && (rawFix.tUtcMs - lastFixTimestamp) > 6_000L)
                        )
                    if (staleWhileRejected) {
                        kalmanSpeed = 0f
                        lastValidSpeedMs = 0f
                        val prevSnap = lastLocationSnapshot
                        if (prevSnap != null && prevSnap.speedKmh > 0f) {
                            val zeroed = prevSnap.copy(speedMs = 0f, speedKmh = 0f)
                            lastLocationSnapshot = zeroed
                            trySend(zeroed)
                        }
                    }
                    return false
                }
                TrackingRuntime.states.add(state)

                val lat = state.lat
                val lng = state.lng
                val fixTimestamp = rawFix.tUtcMs

                val rawSpeed = if (location.hasSpeed()) location.speed else null
                val accuracy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasSpeedAccuracy())
                    location.speedAccuracyMetersPerSecond else null
                val speed = hybridSpeedUpdate(
                    rawSpeed = rawSpeed,
                    gpsAccuracyMps = accuracy,
                    lat = lat,
                    lng = lng,
                    timestamp = fixTimestamp
                )
                val speedKmh = speed * 3.6f
                val alt = if (location.hasAltitude()) location.altitude else null
                val fixAccuracy = if (location.hasAccuracy()) location.accuracy else null

                // Heading comes from the engine: course/gyro fused, held while stopped or unobserved.
                val currentBearing: Float? = if (state.headingConfident) {
                    state.headingDeg.toFloat().also { lastValidBearing = it }
                } else {
                    lastValidBearing
                }

                // 1. Immediate TripManager kinematic notification (live state machine) and recorded geometry
                //    (fixed-lag smoothed points).
                tripManager.onLocationUpdate(
                    lat = lat,
                    lng = lng,
                    speedMs = speed,
                    currentPlace = committedPlace?.let { it.pl.takeIf { p -> p.isValid() } ?: it.en },
                    accuracy = fixAccuracy,
                    altitude = alt,
                    rawLat = rawFix.lat,
                    rawLng = rawFix.lng,
                    rawAlt = rawFix.altitudeM
                )
                tripManager.onSmoothedPoints(result.finalized)

                if (!emitUiAndGeocode) return true

                // 2. Immediate LiveSharing telemetry notification
                val immediatePlaceName = committedPlace?.let {
                    val p = it.pl.takeIf { pl -> pl.isValid() } ?: it.en
                    if (!p.street.isNullOrBlank()) "${p.city}, ${p.street}" else p.city
                }
                LiveSharingManager.getInstance(context).onLocationUpdate(
                    lat = lat,
                    lng = lng,
                    speedKmh = speedKmh,
                    altitude = alt,
                    bearing = currentBearing,
                    placeName = immediatePlaceName,
                    trekkingBadge = null,
                    accuracy = fixAccuracy
                )

                // 3. Immediate UI emission with latest kinematics & cached locality (anti-lag)
                val seqId = ++fixSeqCounter
                val fastSnapshot = MultiLocationSnapshot(
                    multiPlace = committedPlace,
                    speedMs = speed,
                    speedKmh = speedKmh,
                    lat = lat,
                    lng = lng,
                    altitude = alt,
                    bearing = currentBearing,
                    accuracy = fixAccuracy,
                    timestamp = fixTimestamp,
                    fixSeqId = seqId,
                    track = state,
                    outdoorContext = lastOutdoorContext
                )
                lastLocationSnapshot = fastSnapshot
                trySend(fastSnapshot)
                persistLastFix(force = false)

                // 4. Asynchronous geocoding enrichment on IO pool (single-flight latest-value conflation)
                val capturedBearing = currentBearing
                synchronized(geocodeFlightLock) {
                    pendingGeocodeParams = PendingGeocodeParams(
                        lat = lat,
                        lng = lng,
                        speedKmh = speedKmh,
                        speedMs = speed,
                        bearing = capturedBearing,
                        accuracy = fixAccuracy,
                        timestamp = fixTimestamp
                    )
                    if (inFlightGeocodeJob?.isActive != true) {
                        launchNextGeocodeFlight()
                    }
                }
                scheduleOutdoorContextEnrichment(lat, lng)
                return true
            }

            val locationCallback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    // Process every delivered fix in time order: when the screen is off in Battery Saver, Fused
                    // may batch fixes and deliver several at once; only the last fix triggers UI/HTTP geocoding.
                    val fixes = result.locations
                    if (fixes.size > 1) {
                        val sorted = fixes.sortedBy { it.elapsedRealtimeNanos }
                        for (i in 0 until sorted.size - 1) {
                            handleLocation(sorted[i], emitUiAndGeocode = false)
                        }
                        handleLocation(sorted.last(), emitUiAndGeocode = true)
                    } else {
                        fixes.firstOrNull()?.let { handleLocation(it, emitUiAndGeocode = true) }
                    }
                }
            }

            synchronized(activeCallbacks) {
                activeCallbacks.add(locationCallback)
            }
            if (!isGpsStopped) {
                fusedLocationClient.requestLocationUpdates(
                    locationRequest,
                    locationCallback,
                    Looper.getMainLooper()
                ).addOnFailureListener { e ->
                    trySend(
                        MultiLocationSnapshot(
                            multiPlace = committedPlace,
                            speedMs = lastValidSpeedMs,
                            speedKmh = lastValidSpeedMs * 3.6f,
                            lat = 0.0,
                            lng = 0.0,
                            altitude = null,
                            bearing = null,
                            accuracy = null,
                            timestamp = System.currentTimeMillis(),
                            error = e.message ?: "Failed to get location"
                        )
                    )
                }
            }

            awaitClose {
                snapshotChannelSend = null
                synchronized(activeCallbacks) {
                    activeCallbacks.remove(locationCallback)
                }
                fusedLocationClient.removeLocationUpdates(locationCallback)
            }
        }.shareIn(
            scope = managerScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5000),
            replay = 1
        )
    }

    private fun launchNextGeocodeFlight() {
        inFlightGeocodeJob = ioScope.launch {
            while (isActive) {
                val params = synchronized(geocodeFlightLock) {
                    val p = pendingGeocodeParams
                    pendingGeocodeParams = null
                    p
                } ?: break

                val rawMultiData = resolveMultiLanguageData(
                    lat = params.lat,
                    lng = params.lng,
                    bearing = params.bearing,
                    speedKmh = params.speedKmh,
                    accuracyMeters = params.accuracy,
                    isCancelled = { !isActive }
                )

                val enrichedSnapshot = synchronized(geocodeLock) {
                    if (params.timestamp < latestEnrichedTimestamp) {
                        TelemetryLogger.log("GEOCODE", "Discarding out-of-order geocode completion for timestamp ${params.timestamp} (already at $latestEnrichedTimestamp)")
                        return@synchronized null
                    }
                    latestEnrichedTimestamp = params.timestamp

                    val borderStabilized = applyBorderHysteresis(params.lat, params.lng, rawMultiData)
                    val stabilizedMultiData = applyStreetHysteresis(params.speedMs, borderStabilized, params.bearing)

                    committedPlace = stabilizedMultiData
                    TripManager.getInstance(context).onLocalityEnriched(stabilizedMultiData.pl, lastOutdoorContext)

                    val currentSnap = lastLocationSnapshot
                    val snap = MultiLocationSnapshot(
                        multiPlace = stabilizedMultiData,
                        speedMs = currentSnap?.speedMs ?: params.speedMs,
                        speedKmh = currentSnap?.speedKmh ?: params.speedKmh,
                        lat = currentSnap?.lat ?: params.lat,
                        lng = currentSnap?.lng ?: params.lng,
                        altitude = currentSnap?.altitude,
                        bearing = currentSnap?.bearing ?: params.bearing,
                        accuracy = currentSnap?.accuracy ?: params.accuracy,
                        timestamp = currentSnap?.timestamp ?: params.timestamp,
                        fixSeqId = currentSnap?.fixSeqId ?: 0L,
                        track = currentSnap?.track,
                        outdoorContext = lastOutdoorContext
                    )
                    lastLocationSnapshot = snap
                    snap
                }
                if (enrichedSnapshot != null) {
                    snapshotChannelSend?.invoke(enrichedSnapshot)
                }
            }
        }
    }

    private fun scheduleOutdoorContextEnrichment(lat: Double, lng: Double) {
        val now = SystemClock.elapsedRealtime()
        val distMoved = FloatArray(1)
        AndroidLocation.distanceBetween(lastOutdoorResolvedLat, lastOutdoorResolvedLng, lat, lng, distMoved)
        if (lastOutdoorResolvedTimeMs != 0L && distMoved[0] < 250f && (now - lastOutdoorResolvedTimeMs < 30_000L)) {
            return
        }
        synchronized(outdoorLock) {
            if (outdoorWorkerJob?.isActive == true) return
            outdoorWorkerJob = ioScope.launch {
                try {
                    val isScreenOffNow = run {
                        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                        val isAuto = AppStateManager.getInstance(context).currentMode.value == AppLifecycleMode.ANDROID_AUTO
                        pm?.isInteractive == false && !isAuto
                    }
                    val outdoorCtx = OutdoorContextResolver.resolveOutdoorContext(
                        lat = lat,
                        lng = lng,
                        context = context,
                        allowNetwork = !isScreenOffNow
                    )
                    if (outdoorCtx != null) {
                        lastOutdoorContext = outdoorCtx
                        lastOutdoorResolvedLat = lat
                        lastOutdoorResolvedLng = lng
                        lastOutdoorResolvedTimeMs = SystemClock.elapsedRealtime()
                        lastLocationSnapshot?.let { snap ->
                            val updatedSnap = snap.copy(outdoorContext = outdoorCtx)
                            lastLocationSnapshot = updatedSnap
                            snapshotChannelSend?.invoke(updatedSnap)
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Continuous location flow mapped to the requested display language.
     * Consumes the single shared master location pipeline (SSOT).
     */
    fun getLocationUpdates(displayLanguage: DisplayLanguage): Flow<LocationData> {
        return masterLocationFlow.map { it.toLocationData(displayLanguage) }
    }

    /**
     * Lightweight flow for map composable (LocationFix).
     * Decoupled from asynchronous geocoding enrichment: only emits upon real GPS fix arrivals.
     * Distinct by coordinate and timestamp so that locality geocoding enrichments do NOT
     * re-trigger map marker repositioning or camera animation jerks.
     */
    fun getLocationRaw(): Flow<LocationFix> {
        return masterLocationFlow
            .distinctUntilChanged { old, new ->
                if (old.fixSeqId > 0L && new.fixSeqId > 0L) {
                    old.fixSeqId == new.fixSeqId
                } else {
                    old.lat == new.lat && old.lng == new.lng && old.timestamp == new.timestamp
                }
            }
            .map {
                // Stateless projection: stabilisation, anchoring and heading now live in TrackingEngine.
                LocationFix(
                    lat = it.lat,
                    lng = it.lng,
                    bearing = it.bearing,
                    speedMs = it.speedMs,
                    timestamp = it.timestamp,
                    accuracy = it.accuracy,
                    isAnchored = it.track?.isStationary == true,
                    fixSeqId = it.fixSeqId,
                    track = it.track
                )
            }
    }

    // ── One-shot location ────────────────────────────────
    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocationSingle(displayLanguage: DisplayLanguage): LocationData =
        suspendCancellableCoroutine { cont ->
            val cts = com.google.android.gms.tasks.CancellationTokenSource()
            cont.invokeOnCancellation {
                cts.cancel()
            }
            try {
                fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                    .addOnSuccessListener { location ->
                        if (location != null) {
                            val rawSpeed = if (location.hasSpeed()) location.speed else null
                            val accuracy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasSpeedAccuracy())
                                location.speedAccuracyMetersPerSecond else null
                            val speed = hybridSpeedUpdate(
                                rawSpeed = rawSpeed,
                                gpsAccuracyMps = accuracy,
                                lat = location.latitude,
                                lng = location.longitude,
                                timestamp = location.time.takeIf { it > 0L } ?: System.currentTimeMillis()
                            )

                            val prefs = context.getSharedPreferences("where_am_i_prefs", Context.MODE_PRIVATE)
                            prefs.edit()
                                .putFloat("lat", location.latitude.toFloat())
                                .putFloat("lng", location.longitude.toFloat())
                                .putFloat("speed", speed)
                                .apply()

                            val rawAccuracy = if (location.hasAccuracy()) location.accuracy else null
                            ioScope.launch {
                                val multiData = resolveMultiLanguageData(
                                    lat = location.latitude,
                                    lng = location.longitude,
                                    speedKmh = speed * 3.6f,
                                    accuracyMeters = rawAccuracy
                                )
                                val stabilized = applyBorderHysteresis(location.latitude, location.longitude, multiData)
                                cont.resume(buildLocationData(stabilized, speed, displayLanguage))
                            }
                        } else {
                            resumeFromCache(cont, displayLanguage)
                        }
                    }.addOnFailureListener {
                        resumeFromCache(cont, displayLanguage)
                    }
            } catch (e: Exception) {
                resumeFromCache(cont, displayLanguage, e.message)
            }
        }

    private fun resumeFromCache(
        cont: kotlin.coroutines.Continuation<LocationData>,
        displayLanguage: DisplayLanguage,
        errorMsg: String? = null
    ) {
        val prefs = context.getSharedPreferences("where_am_i_prefs", Context.MODE_PRIVATE)
        val lat = if (prefs.contains("lat")) prefs.getFloat("lat", 0f).toDouble() else null
        val lng = if (prefs.contains("lng")) prefs.getFloat("lng", 0f).toDouble() else null
        val lastSpeed = if (prefs.contains("speed")) prefs.getFloat("speed", 0f) else lastValidSpeedMs
        if (lat != null && lng != null) {
            cont.resume(resolveLocationData(lat, lng, lastSpeed, displayLanguage))
        } else {
            cont.resume(LocationData(null, null, lastSpeed, errorMsg ?: "Location unavailable", false))
        }
    }

    // GEO-07: Spatial grid LRU cache (~100m cell resolution, 30 min TTL, up to 300 locations)
    private data class CachedMultiPlace(
        val timestamp: Long,
        val lat: Double,
        val lng: Double,
        val data: MultiLanguagePlaceInfo
    )
    private val spatialPlaceCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, CachedMultiPlace>(200, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedMultiPlace>?): Boolean {
                return size > 300
            }
        }
    )

    // In-memory cache for Nominatim reverse-geocode responses (150 entries, 30 min TTL, ~100m grid)
    private data class CachedOsmResult(
        val timestamp: Long,
        val result: OsmPlaceResult
    )
    private val osmResponseCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, CachedOsmResult>(100, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedOsmResult>?): Boolean {
                return size > 150
            }
        }
    )
    @Volatile
    private var lastOsmRequestTime = 0L
    @Volatile
    private var osmRateLimitCooldownUntil = 0L

    fun isOsmRateLimited(): Boolean = System.currentTimeMillis() < osmRateLimitCooldownUntil

    private fun sanitizeMultiDataHouseNumbers(
        data: MultiLanguagePlaceInfo,
        speedKmh: Float?,
        accuracyMeters: Float?
    ): MultiLanguagePlaceInfo {
        val isPhysStationary = try {
            StationaryDetector.getInstance(context).isPhysicallyStationary.value
        } catch (_: Exception) { false }
        val isMajor = RoadNameNormalizer.isMajorRoad(data.pl.street) || !data.pl.roadRef.isNullOrBlank()
        val hasNamedLocalStreet = !RoadNameNormalizer.isHighwayCodeOnly(data.pl.street)
        val allowHouse = RoadNameNormalizer.shouldShowHouseNumber(
            speedKmh, accuracyMeters, isMajor, isPhysStationary, hasNamedLocalStreet
        )
        if (allowHouse) return data

        // If physically stationary, hold previously confirmed house number on the same street
        // rather than blanking it out due to indoor GPS accuracy degradation
        val currentStreet = committedPlace?.pl?.street
        if (isPhysStationary && !currentStreet.isNullOrBlank() && !isMajor && hasNamedLocalStreet) {
            val baseCurrent = RoadNameNormalizer.extractBaseStreet(currentStreet)
            val baseData = RoadNameNormalizer.extractBaseStreet(data.pl.street)
            if (baseCurrent.equals(baseData, ignoreCase = true) && RoadNameNormalizer.extractHouseNumber(currentStreet) != null) {
                return data.copy(
                    en = data.en.copy(street = currentStreet),
                    pl = data.pl.copy(street = currentStreet),
                    native = data.native.copy(street = currentStreet)
                )
            }
        }
        return data.copy(
            en = data.en.copy(street = RoadNameNormalizer.sanitizeHouseNumber(data.en.street, false)),
            pl = data.pl.copy(street = RoadNameNormalizer.sanitizeHouseNumber(data.pl.street, false)),
            native = data.native.copy(street = RoadNameNormalizer.sanitizeHouseNumber(data.native.street, false))
        )
    }

    // ── Place resolution ───────────────────────────────────────────────────────

    private val pendingRevalidations = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun isNetworkConnected(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            val network = cm?.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            false
        }
    }

    private fun scheduleBackgroundRevalidation(lat: Double, lng: Double, bearing: Float?, speedKmh: Float?, accuracyMeters: Float?) {
        val key = SpatialCacheHelper.toGridKey(lat, lng)
        if (!pendingRevalidations.add(key)) return
        ioScope.launch {
            try {
                val refreshed = resolveMultiLanguageData(lat, lng, bearing, speedKmh, accuracyMeters, forceCache = true, allowNetwork = true, forceRefresh = true)
                val currentSnap = lastLocationSnapshot
                if (currentSnap != null && currentSnap.lat != 0.0 && distanceBetween(lat, lng, currentSnap.lat, currentSnap.lng) < 40f) {
                    val sanitized = sanitizeMultiDataHouseNumbers(refreshed, currentSnap.speedKmh, currentSnap.accuracy)
                    val newStreet = sanitized.pl.street
                    val oldStreet = committedPlace?.pl?.street
                    if (!newStreet.isNullOrBlank() && newStreet != oldStreet &&
                        RoadNameNormalizer.extractBaseStreet(newStreet).equals(RoadNameNormalizer.extractBaseStreet(oldStreet), ignoreCase = true)
                    ) {
                        val updatedSnap = synchronized(geocodeLock) {
                            committedPlace = sanitized
                            committedStreetPl = newStreet
                            val snap = currentSnap.copy(multiPlace = sanitized)
                            lastLocationSnapshot = snap
                            snap
                        }
                        snapshotChannelSend?.invoke(updatedSnap)
                    }
                }
            } finally {
                pendingRevalidations.remove(key)
            }
        }
    }

    fun resolveMultiLanguageData(
        lat: Double,
        lng: Double,
        bearing: Float? = null,
        speedKmh: Float? = null,
        accuracyMeters: Float? = null,
        forceCache: Boolean = false,
        allowNetwork: Boolean = true,
        forceRefresh: Boolean = false,
        isCancelled: () -> Boolean = { false }
    ): MultiLanguagePlaceInfo {
        val now = System.currentTimeMillis()
        val gridKey = "${String.format(Locale.ROOT, "%.4f", lat)}_${String.format(Locale.ROOT, "%.4f", lng)}"
        val isDrivingFast = speedKmh != null && speedKmh > 15f && bearing != null

        if (!forceRefresh) {
            val cached = spatialPlaceCache[gridKey]
            if (cached != null && (now - cached.timestamp) < 30 * 60 * 1000L) {
                val isStationaryOrLowSpeed = (speedKmh == null || speedKmh < 15f) && (accuracyMeters == null || accuracyMeters <= 35f)
                val cachedHasBareStreet = cached.data.pl.street != null &&
                    !RoadNameNormalizer.isHighwayCodeOnly(cached.data.pl.street) &&
                    !RoadNameNormalizer.isMajorRoad(cached.data.pl.street, cached.data.pl.roadRef) &&
                    RoadNameNormalizer.extractHouseNumber(cached.data.pl.street) == null
                if (isStationaryOrLowSpeed && cachedHasBareStreet && isNetworkConnected() && allowNetwork) {
                    scheduleBackgroundRevalidation(lat, lng, bearing, speedKmh, accuracyMeters)
                }
                return sanitizeMultiDataHouseNumbers(cached.data, speedKmh, accuracyMeters)
            }

            // Check persistent SQLite spatial cache with 3-tier staleness evaluation
            try {
                val timedCached = SpatialCacheHelper.getInstance(context).getWithTimestamp(lat, lng)
                if (timedCached != null) {
                    val diskCached = timedCached.info
                    val cacheAgeMs = now - timedCached.timestamp
                    val committed = committedPlace?.pl
                    val isCommittedMajor = RoadNameNormalizer.isMajorRoad(committed?.street, committed?.roadRef)
                    // Only true residential addresses (house number) are side-streets. A plain local name
                    // like "Krakowska" on a DK52 cell is the corridor itself — coalesce, do not shield.
                    val isCachedSideStreet = RoadNameNormalizer.isResidentialSideStreet(diskCached.pl.street)

                    // Bare DK52 committed + cached "Krakowska" → show Krakowska (DK52) immediately.
                    val bareUpgrade = if (committed != null) {
                        RoadNameNormalizer.coalesceBareCodeWithCachedName(
                            committed.street, committed.roadRef,
                            diskCached.pl.street, diskCached.pl.roadRef
                        )
                    } else null
                    if (bareUpgrade != null) {
                        val (localName, roadRef) = bareUpgrade
                        val display = RoadNameNormalizer.formatStreetDisplay(localName, roadRef, committed?.countryCode)
                        TelemetryLogger.log(
                            "STREET",
                            "Upgrading bare corridor '${committed?.street}' with cached local name '$localName' → '$display'"
                        )
                        val upgraded = diskCached.copy(
                            en = diskCached.en.copy(street = display, roadRef = roadRef),
                            pl = diskCached.pl.copy(street = display, roadRef = roadRef),
                            native = diskCached.native.copy(street = display, roadRef = roadRef)
                        )
                        spatialPlaceCache[gridKey] = CachedMultiPlace(now, lat, lng, upgraded)
                        try {
                            SpatialCacheHelper.getInstance(context).put(lat, lng, upgraded)
                        } catch (_: Exception) {}
                        return sanitizeMultiDataHouseNumbers(upgraded, speedKmh, accuracyMeters)
                    }

                    if (isDrivingFast && speedKmh != null && speedKmh > 20f && isCommittedMajor && isCachedSideStreet) {
                        TelemetryLogger.log("STREET", "Shielding major corridor '${committed?.street}' from cached side-street '${diskCached.pl.street}' at ${speedKmh.toInt()} km/h")
                        if (committedPlace != null) {
                            val sameLocality = diskCached.pl.city.equals(committedPlace!!.pl.city, ignoreCase = true)
                            val fallbackStreet = if (sameLocality) {
                                committedPlace!!.pl.street
                            } else {
                                // Crossing into a new locality: never leak previous locality's street name across border!
                                committedPlace!!.pl.roadRef?.let { RoadNameNormalizer.normalize(null, it) }
                                    ?: diskCached.pl.street
                            }
                            val preserved = diskCached.copy(
                                en = diskCached.en.copy(street = fallbackStreet, roadRef = committedPlace!!.en.roadRef),
                                pl = diskCached.pl.copy(street = fallbackStreet, roadRef = committedPlace!!.pl.roadRef),
                                native = diskCached.native.copy(street = fallbackStreet, roadRef = committedPlace!!.native.roadRef)
                            )
                            return sanitizeMultiDataHouseNumbers(preserved, speedKmh, accuracyMeters)
                        }
                    } else {
                        // Bare highway-only cache cells or missing house numbers during stationary dwell require revalidation
                        val bareHighwayCache = RoadNameNormalizer.isHighwayCodeOnly(diskCached.pl.street)
                        val isStationaryOrLowSpeed = (speedKmh == null || speedKmh < 15f) && (accuracyMeters == null || accuracyMeters <= 35f)
                        val cachedHasBareStreet = diskCached.pl.street != null &&
                            !RoadNameNormalizer.isHighwayCodeOnly(diskCached.pl.street) &&
                            !RoadNameNormalizer.isMajorRoad(diskCached.pl.street, diskCached.pl.roadRef) &&
                            RoadNameNormalizer.extractHouseNumber(diskCached.pl.street) == null
                        if ((bareHighwayCache || (isStationaryOrLowSpeed && cachedHasBareStreet)) && isNetworkConnected() && allowNetwork) {
                            scheduleBackgroundRevalidation(lat, lng, bearing, speedKmh, accuracyMeters)
                        }

                        spatialPlaceCache[gridKey] = CachedMultiPlace(now, lat, lng, diskCached)
                        val result = sanitizeMultiDataHouseNumbers(diskCached, speedKmh, accuracyMeters)

                        // 3-Tier Staleness Gates
                        val isOnline = isNetworkConnected()
                        if (cacheAgeMs > 365L * 24 * 3600 * 1000L) { // > 1 Year (Obsolete)
                            if (isOnline && allowNetwork) {
                                // SWR: Serve immediately (0ms UI latency), revalidate in background
                                scheduleBackgroundRevalidation(lat, lng, bearing, speedKmh, accuracyMeters)
                            } else {
                                // Offline fallback: keep cache, mark for post-trip routine update
                                CacheManager.getInstance(context).enqueuePendingRefresh(lat, lng)
                            }
                        } else if (cacheAgeMs > 60L * 24 * 3600 * 1000L) { // 60 - 365 Days (Aging)
                            // Enqueue for post-trip routine on Wi-Fi + Charger
                            CacheManager.getInstance(context).enqueuePendingRefresh(lat, lng)
                        }

                        return result
                    }
                }
            } catch (_: Exception) {}
        }

        val prefs = context.getSharedPreferences("where_am_i_prefs", Context.MODE_PRIVATE)

        val isScreenOff = run {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val isAuto = AppStateManager.getInstance(context).currentMode.value == AppLifecycleMode.ANDROID_AUTO
            pm?.isInteractive == false && !isAuto
        }

        var effectiveAllowNetwork = allowNetwork
        if (isScreenOff && allowNetwork && !forceCache) {
            val now = android.os.SystemClock.elapsedRealtime()
            val distFromLast = lastScreenOffGeocodedPoint?.let {
                val res = FloatArray(1)
                AndroidLocation.distanceBetween(lat, lng, it.first, it.second, res)
                res[0]
            } ?: Float.MAX_VALUE
            if (now - lastScreenOffGeocodeMs < 60_000L && distFromLast < 250f) {
                effectiveAllowNetwork = false
            } else {
                lastScreenOffGeocodeMs = now
                lastScreenOffGeocodedPoint = Pair(lat, lng)
            }
        }

        // Strict Main Thread and allowNetwork Guard:
        // NEVER perform blocking network Geocoder, Nominatim HTTP, OSRM HTTP, or Thread.sleep on the Android Main (UI) thread!
        val isMainThread = try { android.os.Looper.myLooper() == android.os.Looper.getMainLooper() } catch (_: Exception) { false }
        if (!effectiveAllowNetwork || isMainThread) {
            val fallback = committedPlace ?: getLastGoodMultiPlace(prefs)
            if (isMainThread && effectiveAllowNetwork) {
                // Trigger asynchronous background geocoding so cache gets populated without blocking main thread Looper
                ioScope.launch {
                    resolveMultiLanguageData(lat, lng, bearing, speedKmh, accuracyMeters, forceCache, allowNetwork = true)
                }
            }
            return fallback
        }

        if (isCancelled()) {
            return committedPlace ?: getLastGoodMultiPlace(prefs)
        }

        val defaultLocale = Locale.getDefault()
        val addressMap = mutableMapOf<String, Address?>()
        fun getCachedAddress(loc: Locale): Address? {
            val key = "${loc.language}_${loc.country}"
            return addressMap.getOrPut(key) { geocode(lat, lng, loc) }
        }

        val baseAddress = getCachedAddress(defaultLocale)
        if (isCancelled()) {
            return committedPlace ?: getLastGoodMultiPlace(prefs)
        }

        val countryCode = baseAddress?.countryCode?.uppercase() ?: ""
        val nativeLocale = if (countryCode.isNotEmpty()) {
            val langForCountry = Locale.getAvailableLocales()
                .firstOrNull { it.country.equals(countryCode, ignoreCase = true) }?.language ?: defaultLocale.language
            Locale(langForCountry, countryCode)
        } else defaultLocale

        val enAddress = getCachedAddress(Locale.US) ?: baseAddress
        val plAddress = getCachedAddress(Locale("pl", "PL")) ?: baseAddress
        val nativeAddress = getCachedAddress(nativeLocale) ?: baseAddress

        if (isCancelled()) {
            return committedPlace ?: getLastGoodMultiPlace(prefs)
        }

        // Fetch shared OSM enrichment once to avoid rapid-fire HTTP 429 rate limits
        val sharedOsm = geocodeWithOsm(lat, lng, if (countryCode == "PL") "pl" else defaultLocale.language, speedKmh)
        val effectiveBearing = bearing ?: lastValidBearing

        if (isCancelled()) {
            return committedPlace ?: getLastGoodMultiPlace(prefs)
        }

        // OSRM /nearest: street name for the locality card AND snapped coordinates for display-only road pull.
        // Screen-off bypass: Skip OSRM network requests when screen is off to eliminate cellular modem drain.
        val nearest = if (isScreenOff) null else OsmMapMatcher.getNearest(lat, lng, effectiveBearing)
        if (nearest != null) {
            online.hatala.whereami.tracking.TrackingRuntime.displaySnap.observeNearest(
                rawLat = lat,
                rawLng = lng,
                snapLat = nearest.lat,
                snapLng = nearest.lng,
                distanceM = nearest.distanceM,
                dtSec = 1.1
            )
        }
        val rawOsrmStreet = nearest?.name
        // Use intrinsic OSRM street for cell coordinates directly.
        // Never shield OSRM before geocoding or spatial caching: corridor shielding belongs strictly in applyStreetHysteresis.
        val osrmStreet = rawOsrmStreet

        // If OSRM snaps within 12m to a canonical highway corridor (e.g. DW948, DK52), infer major highway type for coalescing
        val candidateHighwayType = sharedOsm?.highwayType ?: if (nearest != null && nearest.distanceM <= 12.0 && RoadNameNormalizer.isHighwayCodeOnly(nearest.name)) "primary" else null

        val enPlace   = resolvePlace(lat, lng, "en",     enAddress,     "en",     countryCode, prefs, sharedOsm, osrmStreet, speedKmh, accuracyMeters, candidateHighwayType = candidateHighwayType)
        val plPlace   = resolvePlace(lat, lng, "pl",     plAddress,     "pl",     countryCode, prefs, sharedOsm, osrmStreet, speedKmh, accuracyMeters, candidateHighwayType = candidateHighwayType)
        val nativePlace = resolvePlace(lat, lng, "native", nativeAddress, nativeLocale.language, countryCode, prefs, sharedOsm, osrmStreet, speedKmh, accuracyMeters, candidateHighwayType = candidateHighwayType)

        // If online geocoding failed or returned unknown (e.g. offline tunnel/cell cutout), fall back to persistent cache of any age
        if (enPlace.city == "Unknown City" && plPlace.city == "Unknown City") {
            try {
                val fallbackDisk = SpatialCacheHelper.getInstance(context).get(lat, lng, maxAgeMs = null)
                if (fallbackDisk != null) {
                    spatialPlaceCache[gridKey] = CachedMultiPlace(now, lat, lng, fallbackDisk)
                    return sanitizeMultiDataHouseNumbers(fallbackDisk, speedKmh, accuracyMeters)
                }
            } catch (_: Exception) {}
        }

        if (enPlace.city != "Unknown City")     saveLastGood(prefs, "en",     lat, lng, enPlace)
        if (plPlace.city != "Unknown City")     saveLastGood(prefs, "pl",     lat, lng, plPlace)
        if (nativePlace.city != "Unknown City") saveLastGood(prefs, "native", lat, lng, nativePlace)

        val result = MultiLanguagePlaceInfo(en = enPlace, pl = plPlace, native = nativePlace)
        val lastGoodPl = loadLastGood(prefs, "pl")
        val isFallbackToLastGood = lastGoodPl != null && plPlace.street == lastGoodPl.street && plPlace.city == lastGoodPl.city && enAddress == null && plAddress == null && sharedOsm == null
        val isFirstClass = sharedOsm != null
        if ((enPlace.city != "Unknown City" || plPlace.city != "Unknown City" || forceCache) && !isFallbackToLastGood) {
            spatialPlaceCache[gridKey] = CachedMultiPlace(now, lat, lng, result)
            if (isFirstClass || forceCache) {
                try {
                    SpatialCacheHelper.getInstance(context).put(lat, lng, result)
                } catch (_: Exception) {}
            } else {
                // Enqueue degraded/provisional point for post-trip refresh when stable network is available
                try {
                    CacheManager.getInstance(context).enqueuePendingRefresh(lat, lng)
                } catch (_: Exception) {}
            }
        }
        return sanitizeMultiDataHouseNumbers(result, speedKmh, accuracyMeters)
    }

    private fun resolvePlace(
        lat: Double, lng: Double,
        cacheKey: String,
        address: Address?,
        osmLang: String,
        countryCode: String,
        prefs: SharedPreferences,
        sharedOsm: OsmPlaceResult? = null,
        osrmStreet: String? = null,
        speedKmh: Float? = null,
        accuracyMeters: Float? = null,
        candidateHighwayType: String? = null,
        allowOsmFallback: Boolean = false
    ): PlaceInfo {
        val lastGood = loadLastGood(prefs, cacheKey)
        val lastGoodLat = prefs.getFloat("last_good_lat", Float.MIN_VALUE).toDouble()
        val lastGoodLng = prefs.getFloat("last_good_lng", Float.MIN_VALUE).toDouble()
        val distToLastGood = if (lastGood != null && lastGoodLat != Float.MIN_VALUE.toDouble()) {
            distanceBetween(lat, lng, lastGoodLat, lastGoodLng)
        } else Float.MAX_VALUE

        val osm = sharedOsm ?: if (allowOsmFallback) geocodeWithOsm(lat, lng, osmLang, speedKmh) else null
        val trajectoryStreet = osrmStreet?.takeIf { it.isNotBlank() }
        val isHighwayTrajectory = trajectoryStreet != null && RoadNameNormalizer.isHighwayCodeOnly(trajectoryStreet)
        val osmMatchesTrajectory = trajectoryStreet == null || isHighwayTrajectory || RoadNameNormalizer.streetsMatch(osm?.street, trajectoryStreet)
        val thoroughfareMatchesTrajectory = trajectoryStreet == null || isHighwayTrajectory || RoadNameNormalizer.streetsMatch(address?.thoroughfare, trajectoryStreet)

        // Tier 1 - Geocoder returned locality. Enrich with OSM canonical road ref (DK52) & administrative gmina/powiat
        if (address?.locality != null) {
            val basePlace = address.toPlaceInfo(countryCode)
            // OSRM may return only DW948 while OSM/Geocoder have Krakowska on the same corridor.
            val (coalescedStreet, coalescedRef) = RoadNameNormalizer.coalesceOsrmAndOsmStreet(
                osrmStreet = osrmStreet,
                osmStreet = osm?.street,
                osmRoadRef = osm?.roadRef,
                geocoderStreet = address.thoroughfare,
                candidateHighwayType = osm?.highwayType ?: candidateHighwayType
            )
            val canonicalStreet = when {
                // 1. OSRM + OSM/Geocoder coalesced street/ref (prefers local name over bare highway code)
                !coalescedStreet.isNullOrBlank() || !coalescedRef.isNullOrBlank() -> {
                    val validHouse = if (osmMatchesTrajectory) (osm?.houseNumber ?: address.subThoroughfare) else null
                    val cacheHouse = validHouse != null && osmMatchesTrajectory &&
                        !RoadNameNormalizer.isMajorRoad(coalescedStreet, coalescedRef) &&
                        !RoadNameNormalizer.isHighwayCodeOnly(coalescedStreet)
                    RoadNameNormalizer.normalize(
                        coalescedStreet,
                        coalescedRef,
                        validHouse,
                        includeHouseNumber = cacheHouse,
                        countryCode = countryCode
                    )
                }
                // 2. Road ref enrichment if thoroughfare provided (no OSRM/OSM street)
                osm != null && !osm.roadRef.isNullOrBlank() -> {
                    val targetStreet = if (trajectoryStreet != null && !thoroughfareMatchesTrajectory) trajectoryStreet else (address.thoroughfare ?: osm.street)
                    val validHouse = if (thoroughfareMatchesTrajectory) address.subThoroughfare else null
                    val cacheHouse = validHouse != null && thoroughfareMatchesTrajectory && !RoadNameNormalizer.isMajorRoad(targetStreet, osm.roadRef)
                    RoadNameNormalizer.normalize(targetStreet, osm.roadRef, validHouse, includeHouseNumber = cacheHouse, countryCode = countryCode)
                }
                // 3. Fallback to Android native Geocoder thoroughfare
                else -> {
                    val targetStreet = if (trajectoryStreet != null && !thoroughfareMatchesTrajectory) trajectoryStreet else address.thoroughfare
                    val validHouse = if (thoroughfareMatchesTrajectory) address.subThoroughfare else null
                    val cacheHouse = validHouse != null && thoroughfareMatchesTrajectory && !RoadNameNormalizer.isMajorRoad(targetStreet, null)
                    RoadNameNormalizer.normalize(targetStreet, null, validHouse, includeHouseNumber = cacheHouse, countryCode = countryCode)
                }
            }

            // Locality accuracy: if OSM has a resolved village/town within a gmina (e.g. Kozy in gmina Kozy),
            // prefer it over Google Geocoder's postal city fallback (e.g. Bielsko-Biała).
            // When OSM is temporarily unavailable (cooldown/timeout) and lastGood was a rural locality with a gmina
            // within 1.5 km, do not let Android Geocoder's postal-town county-city fallback flip the locality.
            val effectiveCity = if (!osm?.city.isNullOrBlank() && osm.city != "Unknown City") {
                osm.city
            } else if (osm == null && lastGood != null && distToLastGood < 1500f &&
                !lastGood.gmina.isNullOrBlank() &&
                isAutonomousCountyCity(basePlace.city, basePlace.gmina, basePlace.powiat) &&
                !lastGood.city.equals(basePlace.city, ignoreCase = true)
            ) {
                lastGood.city
            } else if (basePlace.city.isNotBlank() && basePlace.city != "Unknown City") {
                sanitizeGeocoderLocality(basePlace.city, basePlace.gmina, lat, lng, lastGood)
            } else {
                osm?.municipality?.removePrefix("gmina ")?.trim()
                    ?: osm?.county
                    ?: basePlace.gmina?.removePrefix("gmina ")?.trim()
                    ?: basePlace.city
            }

            // Administrative hierarchy resolution with decay protection (never lose gmina due to transient geocoder glitch)
            val effectivePowiat = osm?.county ?: basePlace.powiat ?: (if (distToLastGood < 3000f) lastGood?.powiat else null)
            val localityKey = effectiveCity.lowercase(Locale.ROOT)
            val candidateGminaRaw = osm?.municipality ?: basePlace.gmina
            val isCountyCity = isAutonomousCountyCity(effectiveCity, candidateGminaRaw, effectivePowiat)
            val candidateGmina = if (isCountyCity) null else candidateGminaRaw
            val effectiveGmina = if (isCountyCity) {
                null
            } else if (!candidateGmina.isNullOrBlank()) {
                prefs.edit().putString("loc_gmina_$localityKey", candidateGmina).apply()
                candidateGmina
            } else {
                prefs.getString("loc_gmina_$localityKey", null)
                    ?: if (lastGood?.city.equals(effectiveCity, ignoreCase = true)) lastGood?.gmina else null
            }
            val rawVoivodeship = osm?.state ?: (if (distToLastGood < 5000f) lastGood?.voivodeship else null) ?: basePlace.voivodeship
            val effectiveVoivodeship = if (basePlace.countryCode == "PL" || basePlace.country.equals("Polska", ignoreCase = true) || basePlace.country.equals("Poland", ignoreCase = true)) {
                canonicalizePolishVoivodeship(rawVoivodeship)
            } else rawVoivodeship

            val resolvedCountry = when (cacheKey) {
                "pl" -> if (basePlace.countryCode == "PL" || basePlace.country.equals("Poland", ignoreCase = true)) "Polska" else (osm?.country ?: basePlace.country)
                "en" -> if (basePlace.countryCode == "PL") "Poland" else (osm?.country ?: basePlace.country)
                else -> osm?.country ?: basePlace.country
            }

            return basePlace.copy(
                city = effectiveCity,
                street = canonicalStreet,
                roadRef = coalescedRef
                    ?: osm?.roadRef
                    ?: RoadNameNormalizer.extractHighwayCode(canonicalStreet)
                    ?: basePlace.roadRef,
                gmina = effectiveGmina,
                powiat = effectivePowiat,
                voivodeship = effectiveVoivodeship,
                country = resolvedCountry,
                highwayType = osm?.highwayType
            )
        }

        // Tier 2a – OpenStreetMap Nominatim
        if (osm != null) {
            val city = osm.city ?: osm.municipality ?: osm.county
            if (city != null) {
                val localityKey = city.lowercase(Locale.ROOT)
                val isCountyCity = isAutonomousCountyCity(city, osm.municipality, osm.county)
                val effectiveGmina = if (isCountyCity) {
                    null
                } else if (!osm.municipality.isNullOrBlank()) {
                    prefs.edit().putString("loc_gmina_$localityKey", osm.municipality).apply()
                    osm.municipality
                } else {
                    prefs.getString("loc_gmina_$localityKey", null)
                        ?: if (lastGood?.city.equals(city, ignoreCase = true)) lastGood?.gmina else null
                }

                val rawCountry = osm.country ?: address?.countryName ?: lastGood?.country ?: "Unknown Country"
                val cc = osm.countryCode?.uppercase() ?: countryCode
                val osmNorm = osm.street?.let {
                    val isMaj = RoadNameNormalizer.isMajorRoad(it) || !osm.roadRef.isNullOrBlank()
                    val cacheH = !isMaj && !RoadNameNormalizer.isHighwayCodeOnly(it) && !osm.houseNumber.isNullOrBlank()
                    RoadNameNormalizer.normalize(it, osm.roadRef, osm.houseNumber, includeHouseNumber = cacheH, countryCode = cc)
                }
                val addrNorm = address?.thoroughfare?.let {
                    val isMaj = RoadNameNormalizer.isMajorRoad(it)
                    val cacheH = !isMaj && !RoadNameNormalizer.isHighwayCodeOnly(it) && !address.subThoroughfare.isNullOrBlank()
                    RoadNameNormalizer.normalize(it, null, address.subThoroughfare, includeHouseNumber = cacheH, countryCode = cc)
                }
                val resolvedCountry = when (cacheKey) {
                    "pl" -> if (cc == "PL" || rawCountry.equals("Poland", ignoreCase = true)) "Polska" else rawCountry
                    "en" -> if (cc == "PL") "Poland" else rawCountry
                    else -> rawCountry
                }
                val rawV = osm.state ?: address?.adminArea ?: lastGood?.voivodeship ?: "Unknown Region"
                val effectiveVoivodeship = if (cc == "PL" || resolvedCountry.equals("Polska", ignoreCase = true) || resolvedCountry.equals("Poland", ignoreCase = true)) {
                    canonicalizePolishVoivodeship(rawV)
                } else rawV

                return PlaceInfo(
                    city = city,
                    street = osmNorm ?: addrNorm,
                    roadRef = osm.roadRef,
                    gmina = effectiveGmina,
                    powiat = osm.county ?: (if (distToLastGood < 3000f) lastGood?.powiat else null),
                    voivodeship = effectiveVoivodeship,
                    country = resolvedCountry,
                    countryCode = cc,
                    highwayType = osm.highwayType
                )
            }
        }

        // Tier 2b – Close to last known good (memory fallback when both Geocoder and OSM lack locality)
        if (lastGood != null && distToLastGood < 45f) {
            return lastGood
        }

        // Tier 3 – Last known good
        if (lastGood != null) return lastGood

        return address?.toPlaceInfo(countryCode) ?: PlaceInfo("Unknown City", null, null, null, null, "Unknown Region", "Unknown Country", countryCode)
    }

    // ── OSM Nominatim ─────────────────────────────────────────────────────────

    private fun geocodeWithOsm(lat: Double, lng: Double, language: String, speedKmh: Float? = null): OsmPlaceResult? {
        val now = System.currentTimeMillis()
        val prefs = context.getSharedPreferences("where_am_i_trip_prefs", Context.MODE_PRIVATE)
        val profile = prefs.getString("pref_activity_profile", "CAR") ?: "CAR"
        val isLowSpeedOrStationary = speedKmh == null || speedKmh < 15f
        val zoom = if (profile == "CAR" && !isLowSpeedOrStationary) 17 else 18

        // Quantize coordinates to 4 decimal places (~11m grid cell, matching spatial SQLite cache)
        // Include zoom level so high-speed road-only caching (zoom 17) does not suppress stationary house numbers (zoom 18)
        val osmKey = "${String.format(Locale.ROOT, "%.4f", lat)}_${String.format(Locale.ROOT, "%.4f", lng)}_z${zoom}_$language"
        val cachedOsm = osmResponseCache[osmKey]
        if (cachedOsm != null && (now - cachedOsm.timestamp) < 30 * 60 * 1000L) {
            return cachedOsm.result
        }

        if (now < osmRateLimitCooldownUntil) {
            return null
        }

        return try {
            val elapsed = now - lastOsmRequestTime
            if (elapsed < 1000L) {
                Thread.sleep(1000L - elapsed)
            }
            lastOsmRequestTime = System.currentTimeMillis()

            val urlStr = "https://nominatim.openstreetmap.org/reverse" +
                    "?format=json&lat=$lat&lon=$lng" +
                    "&accept-language=$language&zoom=$zoom&addressdetails=1&namedetails=1&extratags=1" +
                    (if (zoom >= 18) "&polygon_geojson=1" else "")
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "WhereAmIPersonalApp/1.1 (android)")
            conn.connectTimeout = 6000
            conn.readTimeout = 6000

            if (conn.responseCode == 429) {
                osmRateLimitCooldownUntil = System.currentTimeMillis() + 30_000L
                TelemetryLogger.log("OSM", "Nominatim reverse geocode HTTP 429; 30s cooldown active")
                return null
            }

            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().readText()
                val root = JSONObject(body)
                val addrObj = root.optJSONObject("address") ?: return null

                fun str(key: String) = addrObj.optString(key).takeIf { it.isNotEmpty() }

                val rawRoad = str("road") ?: str("street") ?: str("pedestrian") ?: str("footway")
                var houseNum = str("house_number")

                // Building polygon containment validation for house numbers at low speeds / rest
                if (houseNum != null && zoom >= 18) {
                    val geojson = root.optJSONObject("geojson")
                    if (geojson != null && geojson.optString("type") == "Polygon") {
                        val coords = geojson.optJSONArray("coordinates")?.optJSONArray(0)
                        if (coords != null && coords.length() >= 3) {
                            val polyPoints = mutableListOf<org.osmdroid.util.GeoPoint>()
                            for (cIdx in 0 until coords.length()) {
                                val pt = coords.optJSONArray(cIdx)
                                if (pt != null && pt.length() >= 2) {
                                    polyPoints.add(org.osmdroid.util.GeoPoint(pt.getDouble(1), pt.getDouble(0)))
                                }
                            }
                            val isInside = BoundaryHelper.polygonContains(polyPoints, lat, lng)
                            if (!isInside) {
                                val objLat = root.optDouble("lat", lat)
                                val objLon = root.optDouble("lon", lng)
                                val dist = FloatArray(1)
                                android.location.Location.distanceBetween(lat, lng, objLat, objLon, dist)
                                if (dist[0] > 25f) {
                                    houseNum = null
                                }
                            }
                        }
                    }
                }

                val rawRef = str("ref")
                    ?: RoadNameNormalizer.wayRef(rawRoad, root.optJSONObject("namedetails")?.let { nd ->
                        nd.optString("name").takeIf { it.isNotEmpty() } to nd.optString("ref").takeIf { it.isNotEmpty() }
                    })
                val osmCountryCode = str("country_code")?.uppercase()
                val rawHighwayType = if (root.optString("class") == "highway") {
                    root.optString("type").takeIf { it.isNotEmpty() }
                } else {
                    root.optJSONObject("extratags")?.optString("highway")?.takeIf { it.isNotEmpty() }
                        ?: root.optString("type").takeIf { it.isNotEmpty() }
                }

                // GEO-03: Normalize road name (DK52, DW946, A4, S7) and strip house numbers from major highways
                val normalizedStreet = RoadNameNormalizer.normalize(rawRoad, rawRef, houseNum, includeHouseNumber = true, countryCode = osmCountryCode)

                val isPoland = osmCountryCode == "PL"
                val rawMunicipality = if (isPoland) {
                    val cand = str("municipality") ?: str("commune") ?: str("gmina")
                    cand?.takeIf { it.startsWith("gmina", ignoreCase = true) }
                } else {
                    str("municipality")
                        ?: str("commune")
                        ?: str("gmina")
                        ?: str("district")
                        ?: str("city_district")
                        ?: str("subdistrict")
                        ?: str("local_administrative_area")
                }

                val isPolishGmina = rawMunicipality != null && rawMunicipality.startsWith("gmina ", ignoreCase = true)
                val gminaName = if (isPolishGmina) rawMunicipality.removePrefix("gmina ").trim() else null
                val villageOrTown = str("village") ?: str("town")

                val resolvedCity = if (isPolishGmina && !villageOrTown.isNullOrBlank()) {
                    villageOrTown
                } else if (isPolishGmina && gminaName != null) {
                    gminaName
                } else {
                    str("city") ?: str("town") ?: str("village") ?: str("suburb") ?: str("hamlet")
                        ?: gminaName ?: rawMunicipality?.removePrefix("gmina ")?.trim() ?: str("county")
                }

                val osmResult = OsmPlaceResult(
                    city = resolvedCity,
                    street = normalizedStreet,
                    roadRef = rawRef,
                    municipality = rawMunicipality,
                    county = str("county"),
                    state = str("state"),
                    country = str("country"),
                    countryCode = str("country_code"),
                    houseNumber = houseNum,
                    highwayType = rawHighwayType
                )
                osmResponseCache[osmKey] = CachedOsmResult(System.currentTimeMillis(), osmResult)
                osmResult
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun saveLastGood(prefs: SharedPreferences, key: String, lat: Double, lng: Double, place: PlaceInfo) {
        prefs.edit()
            .putString("last_good_city_$key", place.city)
            .putString("last_good_street_$key", place.street ?: "")
            .putString("last_good_roadref_$key", place.roadRef ?: "")
            .putString("last_good_gmina_$key", place.gmina ?: "")
            .putString("last_good_powiat_$key", place.powiat ?: "")
            .putString("last_good_state_$key", place.voivodeship)
            .putString("last_good_country_$key", place.country)
            .putString("last_good_cc_$key", place.countryCode)
            .putFloat("last_good_lat", lat.toFloat())
            .putFloat("last_good_lng", lng.toFloat())
            .commit()
    }

    private fun loadLastGood(prefs: SharedPreferences, key: String): PlaceInfo? {
        val city = prefs.getString("last_good_city_$key", null) ?: return null
        val street = prefs.getString("last_good_street_$key", null).takeIf { !it.isNullOrEmpty() }
        val roadRef = prefs.getString("last_good_roadref_$key", null).takeIf { !it.isNullOrEmpty() }
        val gmina = prefs.getString("last_good_gmina_$key", null).takeIf { !it.isNullOrEmpty() }
        val powiat = prefs.getString("last_good_powiat_$key", null).takeIf { !it.isNullOrEmpty() }
        val state = prefs.getString("last_good_state_$key", null) ?: return null
        val rawCountry = prefs.getString("last_good_country_$key", null) ?: return null
        val cc = prefs.getString("last_good_cc_$key", "") ?: ""
        val canonicalCountry = if (cc.uppercase() == "PL" || rawCountry.equals("Poland", ignoreCase = true)) "Polska" else rawCountry
        return PlaceInfo(city, street, roadRef, gmina, powiat, state, canonicalCountry, cc)
    }

    private fun getLastGoodMultiPlace(prefs: SharedPreferences): MultiLanguagePlaceInfo {
        val pl = loadLastGood(prefs, "pl")
        val en = loadLastGood(prefs, "en")
        val native = loadLastGood(prefs, "native")
        val fallback = PlaceInfo("Unknown City", null, null, null, null, "Unknown Region", "Unknown Country", "")
        return MultiLanguagePlaceInfo(
            en = en ?: pl ?: native ?: fallback,
            pl = pl ?: en ?: native ?: fallback,
            native = native ?: pl ?: en ?: fallback
        )
    }

    private fun distanceBetween(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
        val results = FloatArray(1)
        AndroidLocation.distanceBetween(lat1, lng1, lat2, lng2, results)
        return results[0]
    }

    fun resolveLocationData(lat: Double, lng: Double, speedMs: Float?, displayLanguage: DisplayLanguage): LocationData {
        val multiData = resolveMultiLanguageData(lat, lng)
        val outdoorCtx = try {
            OutdoorContextResolver.resolveOffline(lat, lng, context)
        } catch (_: Exception) { null }
        return buildLocationData(multiData, speedMs ?: lastValidSpeedMs, displayLanguage, outdoorCtx ?: lastOutdoorContext)
    }

    private fun buildLocationData(
        multiData: MultiLanguagePlaceInfo,
        speedMs: Float?,
        displayLanguage: DisplayLanguage,
        outdoorContext: OutdoorContext? = lastOutdoorContext
    ): LocationData {
        val (primaryPlace, secondaryPlace) = when (displayLanguage) {
            DisplayLanguage.EN     -> multiData.en     to multiData.native
            DisplayLanguage.PL     -> multiData.pl     to multiData.native
            DisplayLanguage.NATIVE -> multiData.native to multiData.en
        }

        val filteredSecondary = if (
            secondaryPlace.city.equals(primaryPlace.city, ignoreCase = true) &&
            secondaryPlace.voivodeship.equals(primaryPlace.voivodeship, ignoreCase = true)
        ) null else secondaryPlace

        return LocationData(
            primaryPlace = primaryPlace,
            secondaryPlace = filteredSecondary,
            speedMs = speedMs ?: lastValidSpeedMs,
            error = null,
            isLoading = false,
            outdoorContext = outdoorContext
        )
    }

    fun sanitizeGeocoderLocality(
        rawCity: String?,
        gmina: String?,
        lat: Double,
        lng: Double,
        lastGood: PlaceInfo?
    ): String {
        if (rawCity.isNullOrBlank() || rawCity == "--") return rawCity ?: ""
        var city = rawCity.trim()

        // 1. Strip trailing statistical district hyphens and conscription numbers
        LOCALITY_SUFFIX_REGEX.matchEntire(city)?.let { match ->
            val stripped = match.groupValues[1].trim()
            if (stripped.length >= 2) city = stripped
        }
        if (city.length < 2) return ""

        // 2. Resolve abbreviated or ASCII dot tokens (e.g. "Międzybrodzie Kob.") using nearby verified OSM locality
        if (city.contains(".")) {
            if (lastGood != null && !lastGood.city.contains(".") && !lastGood.city.isNullOrBlank()) {
                val prefix = city.substringBefore(".").trim().lowercase(Locale.ROOT)
                if (lastGood.city.lowercase(Locale.ROOT).startsWith(prefix)) {
                    return lastGood.city
                }
            }
            try {
                val verifiedNearby = SpatialCacheHelper.getInstance(context).findNearestVerifiedLocality(lat, lng, 2500.0, gmina)
                if (verifiedNearby != null) {
                    val prefix = city.substringBefore(".").trim().lowercase(Locale.ROOT)
                    if (verifiedNearby.lowercase(Locale.ROOT).startsWith(prefix)) {
                        return verifiedNearby
                    }
                }
            } catch (_: Exception) {}
        }
        return city
    }

    private fun geocode(lat: Double, lng: Double, locale: Locale): Address? {
        val geocoder = Geocoder(context, locale)
        return try {
            @Suppress("DEPRECATION")
            val addresses = geocoder.getFromLocation(lat, lng, 1)
            if (!addresses.isNullOrEmpty()) addresses[0] else Address(locale)
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun Address.toPlaceInfo(countryCode: String): PlaceInfo {
        val cityName = locality ?: subLocality ?: subAdminArea ?: "--"
        val thoroughfare = this.thoroughfare
        val houseNum = this.subThoroughfare
        val cc = this.countryCode?.uppercase() ?: countryCode
        val streetName = RoadNameNormalizer.normalize(thoroughfare, houseNumber = houseNum, includeHouseNumber = true, countryCode = cc)
        val rawCountry = this.countryName ?: "Unknown Country"
        val resolvedCountry = if (cc == "PL" || rawCountry.equals("Poland", ignoreCase = true)) "Polska" else rawCountry
        val isPoland = cc == "PL" || rawCountry.equals("Poland", ignoreCase = true) || rawCountry.equals("Polska", ignoreCase = true)
        val gminaName = if (isPoland) {
            subLocality?.takeIf { it.startsWith("gmina", ignoreCase = true) }
        } else {
            subLocality
        }
        val powiatName = subAdminArea
        val stateName = adminArea ?: "Unknown Region"
        return PlaceInfo(cityName, streetName, null, gminaName, powiatName, stateName, resolvedCountry, cc)
    }
}



