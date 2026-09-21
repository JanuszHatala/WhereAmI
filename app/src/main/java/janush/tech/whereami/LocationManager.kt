package janush.tech.whereami

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.location.Address
import android.location.Geocoder
import android.location.Location as AndroidLocation
import android.os.Build
import android.os.Looper
import com.google.android.gms.location.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
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
    val countryCode: String = "" // ISO 2-letter country code
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
    val isLoading: Boolean = false
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
    val isLoading: Boolean = false
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
            error = error,
            isLoading = isLoading
        )
    }
}

/** Small data class for OSM Nominatim reverse-geocode results. */
private data class OsmPlaceResult(
    val city: String?,         // city / town / village / hamlet
    val street: String?,       // road / street / pedestrian
    val roadRef: String?,      // ref / road number
    val municipality: String?, // commune / gmina
    val county: String?,       // district / powiat
    val state: String?,
    val country: String?,
    val countryCode: String?
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
        private var isGpsStopped: Boolean = false

        private val activeCallbacks = Collections.synchronizedSet(mutableSetOf<LocationCallback>())

        /**
         * Set of Polish cities with county rights (miasta na prawach powiatu).
         * These localities constitute an autonomous urban county with no rural gmina.
         */
        val POLISH_COUNTY_CITIES = setOf(
            "bielsko-biała", "białystok", "bydgoszcz", "bytom", "chełm", "chorzów",
            "częstochowa", "dąbrowa górnicza", "elbląg", "gdańsk", "gdynia", "gliwice",
            "głogów", "gniezno", "gorzów wielkopolski", "grudziądz", "inowrocław",
            "jastrzębie-zdrój", "jaworzno", "jelenia góra", "kalisz", "katowice",
            "kędzierzyn-koźle", "kielce", "konin", "koszalin", "kraków", "krosno",
            "legnica", "leszno", "lubin", "lublin", "łomża", "łódź", "mysłowice",
            "nowy sącz", "olsztyn", "opole", "ostrołęka", "ostrowiec świętokrzyski",
            "pabianice", "piekary śląskie", "piotrków trybunalski", "płock", "poznań",
            "przemyśl", "radom", "ruda śląska", "rybnik", "rzeszów", "siedlce",
            "siemianowice śląskie", "słupsk", "sopot", "sosnowiec", "stalowa wola",
            "stargard", "suwałki", "szczecin", "świętochłowice", "tarnobrzeg", "tarnów",
            "tomaszów mazowiecki", "toruń", "tychy", "warszawa", "włocławek", "wrocław",
            "zabrze", "zamość", "zielona góra", "żory"
        )

        /**
         * Country-aware hierarchy formatter:
         * - In Poland: gm. X • pow. Y • woj. Z (with smart deduplication)
         * - Outside Poland: Municipality • County • State (without Polish abbreviations)
         */
        fun formatHierarchy(place: PlaceInfo?): String {
            if (place == null) return ""
            val countryCode = place.countryCode.uppercase()
            val isPoland = countryCode == "PL" ||
                    place.country.equals("Polska", ignoreCase = true) ||
                    place.country.equals("Poland", ignoreCase = true)

            val city = place.city.trim()
            val rawGmina = place.gmina?.trim()
            val rawPowiat = place.powiat?.trim()
            val cleanGmina = rawGmina?.replace("Gmina ", "", ignoreCase = true)
                ?.replace("gmina ", "", ignoreCase = true)?.trim()
            val cleanPowiat = rawPowiat?.replace("Powiat ", "", ignoreCase = true)
                ?.replace("powiat ", "", ignoreCase = true)?.trim()
            val voivodeship = place.voivodeship
                .replace("województwo ", "", ignoreCase = true)
                .replace("województwo", "", ignoreCase = true)
                .trim()

            val cityLower = city.lowercase(Locale.ROOT)
            val isCountyCity = POLISH_COUNTY_CITIES.contains(cityLower) ||
                    (cleanPowiat != null && cleanPowiat.equals(city, ignoreCase = true))

            if (isPoland) {
                val parts = mutableListOf<String>()
                // Cities with county rights (miasta na prawach powiatu) are self-governing counties:
                // They have NO separate rural gmina. Suppress "gm." and "pow." entirely.
                if (!isCountyCity) {
                    // Clean deduplication: omit gm. X if city is X
                    if (!cleanGmina.isNullOrEmpty() && !cleanGmina.equals(city, ignoreCase = true)) {
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
                // International formatting
                val parts = mutableListOf<String>()
                if (!cleanGmina.isNullOrEmpty() && !cleanGmina.equals(city, ignoreCase = true)) {
                    parts.add(cleanGmina)
                }
                if (!cleanPowiat.isNullOrEmpty() && !cleanPowiat.equals(city, ignoreCase = true) && !cleanPowiat.equals(cleanGmina, ignoreCase = true)) {
                    parts.add(cleanPowiat)
                }
                if (voivodeship.isNotEmpty() && !voivodeship.equals("Unknown Region", ignoreCase = true)) {
                    parts.add(voivodeship)
                }
                return parts.joinToString(" • ")
            }
        }
    }

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)
    private val ioScope = CoroutineScope(Dispatchers.IO)

    @SuppressLint("MissingPermission")
    fun updateSamplingInterval(intervalMs: Long, minIntervalMs: Long) {
        currentIntervalMs = intervalMs
        currentMinIntervalMs = minIntervalMs
        isGpsStopped = false
        try {
            StationaryDetector.getInstance(context).startListening()
        } catch (_: Exception) {}
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(minIntervalMs)
            .build()
        synchronized(activeCallbacks) {
            for (cb in activeCallbacks) {
                try {
                    fusedLocationClient.requestLocationUpdates(req, cb, Looper.getMainLooper())
                } catch (_: Exception) {}
            }
        }
    }

    fun stopLocationUpdates() {
        isGpsStopped = true
        try {
            StationaryDetector.getInstance(context).stopListening()
        } catch (_: Exception) {}
        synchronized(activeCallbacks) {
            for (cb in activeCallbacks) {
                try {
                    fusedLocationClient.removeLocationUpdates(cb)
                } catch (_: Exception) {}
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

    // Displacement tracking for zero-motion confirmation
    private var lastFixLat: Double = 0.0
    private var lastFixLng: Double = 0.0
    private var lastFixTimestamp: Long = 0L

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

        // Accelerometer-based physical motion check:
        // Strictly for indoor desk / resting clamp.
        // NEVER clamp if raw GPS reports positive velocity (> 1.2 m/s or ~4.3 km/h) or displacement > 3.0m!
        // Newton's 1st Law: uniform highway cruising has zero acceleration variance by physical definition.
        val isMoving = (rawSpeed != null && rawSpeed > 1.2f) || distMoved > 3.0f
        if (!isMoving) {
            val isPhysicallyStationary = try {
                StationaryDetector.getInstance(context).isPhysicallyStationary.value
            } catch (_: Exception) { false }

            if (isPhysicallyStationary) {
                kalmanSpeed = 0f
                lastValidSpeedMs = 0f
                return 0f
            }
        }

        if (rawSpeed == null) {
            // Keep last valid speed rather than dropping to null or 0 (anti-flicker)
            return lastValidSpeedMs
        }

        // Check if raw speed falls within stationary noise floor:
        // 1) Below physical locomotion threshold (< 0.5 m/s or ~1.8 km/h)
        // 2) Or raw speed is smaller than the GPS speed uncertainty margin (noise floor)
        // 3) Or physical position displacement over the interval confirms zero motion
        val isStationaryNoise = rawSpeed < STATIONARY_THRESHOLD ||
                (gpsAccuracyMps != null && gpsAccuracyMps > 0.8f && rawSpeed <= gpsAccuracyMps && rawSpeed < 1.0f) ||
                (isStationaryDisplacement && rawSpeed < 0.8f)

        // Clamp stationary noise to true 0
        val cleanRaw = if (isStationaryNoise) 0f else rawSpeed

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

        // When driving (> 25 km/h), require at least 350m inside the new territory OR sustained for 7+ seconds
        val minDistanceRequired = if (speedKmh > 25f) 350f else 150f
        val minDurationRequiredMs = if (speedKmh > 25f) 7_000L else 4_000L

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

        // Kinematic turn tracking: if vehicle turns (heading change >= 30 deg at speed >= 5 km/h),
        // record turn timestamp so we promptly switch to the new street.
        if (bearing != null && speedKmh >= 5f) {
            val prev = lastSustainedBearing
            if (prev != null) {
                val delta = kotlin.math.abs(((bearing - prev + 540) % 360) - 180)
                if (delta >= 30f) {
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
            candidateStreetPl = null
            candidateStreetBase = null
            candidateStreetCount = 0
            return multiData
        }

        // Same base road! Update house number or minor variation immediately
        if (rawBase.equals(committedStreetBase, ignoreCase = true)) {
            committedStreetPl = rawStreetPl
            candidateStreetPl = null
            candidateStreetBase = null
            candidateStreetCount = 0
            return multiData
        }

        // Different road detected!
        val isCommittedMajor = RoadNameNormalizer.isMajorRoad(committedStreetBase)
        val isCandidateMajor = RoadNameNormalizer.isMajorRoad(rawBase)

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

        val isRecentTurn = (now - lastTurnTimestamp) < 14_000L

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
                // Never abandon DK/DW/A/S for a parallel or side street unless sustained for 10s and 7 fixes
                rawRequiredCount = when {
                    isCommittedMajor && !isCandidateMajor -> 7
                    !isCommittedMajor && isCandidateMajor -> 2 // Snap onto highway corridor quickly
                    else -> 4
                }
                rawRequiredDuration = when {
                    isCommittedMajor && !isCandidateMajor -> 10_000L
                    !isCommittedMajor && isCandidateMajor -> 2_000L
                    else -> 4_500L
                }
            }
            speedKmh > 15f -> {
                // Moderate city driving / cycling:
                rawRequiredCount = if (isCommittedMajor && !isCandidateMajor) 5 else 3
                rawRequiredDuration = if (isCommittedMajor && !isCandidateMajor) 6_000L else 3_000L
            }
            else -> {
                // Slow driving / cycling / walking (1.2 - 15 km/h):
                // Protect major roads (DK*, DW*, etc.) and main thoroughfares against side-street hopping
                rawRequiredCount = if (isCommittedMajor && !isCandidateMajor) 5 else 3
                rawRequiredDuration = if (isCommittedMajor && !isCandidateMajor) 6_000L else 2_500L
            }
        }

        // When crossing into a confirmed new locality or after making a physical turn,
        // relax threshold to adopt the new street promptly (2 confirmations / 2 seconds)
        val requiredCount = when {
            isLocalityTransition || isRecentTurn -> minOf(rawRequiredCount, 2)
            else -> rawRequiredCount
        }
        val requiredDuration = when {
            isLocalityTransition || isRecentTurn -> minOf(rawRequiredDuration, 2_000L)
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
            return multiData.copy(
                en = multiData.en.copy(street = committedStreetPl),
                pl = multiData.pl.copy(street = committedStreetPl),
                native = multiData.native.copy(street = committedStreetPl)
            )
        } else {
            // Keep current committed street (filter out momentary cross-street and side-street jitter)
            return multiData.copy(
                en = multiData.en.copy(street = committedStreetPl),
                pl = multiData.pl.copy(street = committedStreetPl),
                native = multiData.native.copy(street = committedStreetPl)
            )
        }
    }

    // ── Single Source of Truth (SSOT) Master Location Pipeline ────────────────
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
                val cachedMulti = resolveMultiLanguageData(initialCoords.first, initialCoords.second)
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

            val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, currentIntervalMs)
                .setMinUpdateIntervalMillis(currentMinIntervalMs)
                .build()

            val locationCallback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    result.lastLocation?.let { location ->
                        val currentProfile = TripManager.getInstance(context).activeTrip.value?.activityProfile
                            ?: TripManager.getInstance(context).activityProfile.value

                        // Stage 1-4 Filter: drop coarse cellular fallbacks, GPS spikes, and kinematic teleport jumps
                        if (!GpsFilterEngine.getInstance().filterLocation(location, currentProfile)) {
                            return
                        }

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

                        prefs.edit()
                            .putFloat("lat", location.latitude.toFloat())
                            .putFloat("lng", location.longitude.toFloat())
                            .putFloat("speed", speed)
                            .putFloat("accuracy", if (location.hasAccuracy()) location.accuracy else 0f)
                            .apply()

                        val speedKmh = speed * 3.6f
                        val alt = if (location.hasAltitude()) location.altitude else null
                        val currentBearing = if (location.hasBearing() && (location.hasSpeed() && location.speed >= 1.2f)) {
                            lastValidBearing = location.bearing
                            location.bearing
                        } else {
                            lastValidBearing
                        }

                        // 1. Immediate TripManager kinematic notification
                        TripManager.getInstance(context).onLocationUpdate(
                            location.latitude,
                            location.longitude,
                            speed,
                            committedPlace?.let { it.pl.takeIf { p -> p.isValid() } ?: it.en }
                        )

                        // 2. Immediate LiveSharing telemetry notification
                        val immediatePlaceName = committedPlace?.let {
                            val p = it.pl.takeIf { pl -> pl.isValid() } ?: it.en
                            if (!p.street.isNullOrBlank()) "${p.city}, ${p.street}" else p.city
                        }
                        LiveSharingManager.getInstance(context).onLocationUpdate(
                            lat = location.latitude,
                            lng = location.longitude,
                            speedKmh = speedKmh,
                            altitude = alt,
                            bearing = currentBearing,
                            placeName = immediatePlaceName,
                            trekkingBadge = null
                        )

                        // 3. Immediate UI emission with latest kinematics & cached locality (anti-lag)
                        val fastSnapshot = MultiLocationSnapshot(
                            multiPlace = committedPlace,
                            speedMs = speed,
                            speedKmh = speedKmh,
                            lat = location.latitude,
                            lng = location.longitude,
                            altitude = alt,
                            bearing = currentBearing,
                            accuracy = if (location.hasAccuracy()) location.accuracy else null,
                            timestamp = location.time.takeIf { it > 0L } ?: System.currentTimeMillis()
                        )
                        lastLocationSnapshot = fastSnapshot
                        trySend(fastSnapshot)

                        // 4. Asynchronous geocoding enrichment on IO pool (never freezes kinematics or UI)
                        ioScope.launch {
                            val rawMultiData = resolveMultiLanguageData(location.latitude, location.longitude)
                            val borderStabilized = applyBorderHysteresis(location.latitude, location.longitude, rawMultiData)
                            val stabilizedMultiData = applyStreetHysteresis(speed, borderStabilized, currentBearing)

                            // Crucial: update committedPlace with stabilized multi data to eradicate street flickering!
                            committedPlace = stabilizedMultiData

                            // Update TripManager with verified locality hierarchy without injecting duplicate points
                            TripManager.getInstance(context).onLocalityEnriched(stabilizedMultiData.pl)

                            val enrichedSnapshot = MultiLocationSnapshot(
                                multiPlace = stabilizedMultiData,
                                speedMs = speed,
                                speedKmh = speedKmh,
                                lat = location.latitude,
                                lng = location.longitude,
                                altitude = alt,
                                bearing = currentBearing,
                                accuracy = if (location.hasAccuracy()) location.accuracy else null,
                                timestamp = location.time.takeIf { it > 0L } ?: System.currentTimeMillis()
                            )
                            lastLocationSnapshot = enrichedSnapshot
                            trySend(enrichedSnapshot)
                        }
                    }
                }
            }

            activeCallbacks.add(locationCallback)
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
                activeCallbacks.remove(locationCallback)
                fusedLocationClient.removeLocationUpdates(locationCallback)
            }
        }.shareIn(
            scope = managerScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5000),
            replay = 1
        )
    }

    /**
     * Continuous location flow mapped to the requested display language.
     * Consumes the single shared master location pipeline (SSOT).
     */
    fun getLocationUpdates(displayLanguage: DisplayLanguage): Flow<LocationData> {
        return masterLocationFlow.map { it.toLocationData(displayLanguage) }
    }

    /**
     * Lightweight flow for map composable (lat, lng, bearing).
     * Reuses the single shared master location pipeline without duplicate callbacks.
     * Bearing is only non-null when moving (speed >= 1.2 m/s / 4.3 km/h).
     */
    fun getLocationRaw(): Flow<Triple<Double, Double, Float?>> {
        return masterLocationFlow.map {
            val movingBearing = if (it.speedMs >= 1.2f) it.bearing else null
            Triple(it.lat, it.lng, movingBearing)
        }
    }

    // ── One-shot location ────────────────────────────────
    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocationSingle(displayLanguage: DisplayLanguage): LocationData =
        suspendCancellableCoroutine { cont ->
            try {
                fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
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

                            ioScope.launch {
                                val multiData = resolveMultiLanguageData(location.latitude, location.longitude)
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

    // ── Place resolution ───────────────────────────────────────────────────────

    fun resolveMultiLanguageData(lat: Double, lng: Double): MultiLanguagePlaceInfo {
        val now = System.currentTimeMillis()
        val gridKey = "${String.format(Locale.ROOT, "%.4f", lat)}_${String.format(Locale.ROOT, "%.4f", lng)}"
        val cached = spatialPlaceCache[gridKey]
        if (cached != null && (now - cached.timestamp) < 30 * 60 * 1000L) {
            return cached.data
        }

        // Check persistent SQLite spatial cache for fresh hits within 30-minute window (~15m cell resolution)
        try {
            val diskCached = SpatialCacheHelper.getInstance(context).get(lat, lng, maxAgeMs = 30 * 60 * 1000L)
            if (diskCached != null) {
                spatialPlaceCache[gridKey] = CachedMultiPlace(now, lat, lng, diskCached)
                return diskCached
            }
        } catch (_: Exception) {}

        val prefs = context.getSharedPreferences("where_am_i_prefs", Context.MODE_PRIVATE)

        val baseAddress = geocode(lat, lng, Locale.getDefault())
        val countryCode = baseAddress?.countryCode?.uppercase() ?: ""
        val nativeLocale = if (countryCode.isNotEmpty()) Locale("", countryCode) else Locale.getDefault()

        val enAddress = geocode(lat, lng, Locale.US) ?: baseAddress
        val plAddress = geocode(lat, lng, Locale("pl", "PL")) ?: baseAddress
        val nativeAddress = geocode(lat, lng, nativeLocale) ?: baseAddress

        // Fetch shared OSM enrichment once to avoid rapid-fire HTTP 429 rate limits
        val sharedOsm = geocodeWithOsm(lat, lng, if (countryCode == "PL") "pl" else Locale.getDefault().language)

        val enPlace   = resolvePlace(lat, lng, "en",     enAddress,     "en",     countryCode, prefs, sharedOsm)
        val plPlace   = resolvePlace(lat, lng, "pl",     plAddress,     "pl",     countryCode, prefs, sharedOsm)
        val nativePlace = resolvePlace(lat, lng, "native", nativeAddress, nativeLocale.language, countryCode, prefs, sharedOsm)

        // If online geocoding failed or returned unknown (e.g. offline tunnel/cell cutout), fall back to persistent cache of any age
        if (enPlace.city == "Unknown City" && plPlace.city == "Unknown City") {
            try {
                val fallbackDisk = SpatialCacheHelper.getInstance(context).get(lat, lng, maxAgeMs = null)
                if (fallbackDisk != null) {
                    spatialPlaceCache[gridKey] = CachedMultiPlace(now, lat, lng, fallbackDisk)
                    return fallbackDisk
                }
            } catch (_: Exception) {}
        }

        if (enPlace.city != "Unknown City")     saveLastGood(prefs, "en",     lat, lng, enPlace)
        if (plPlace.city != "Unknown City")     saveLastGood(prefs, "pl",     lat, lng, plPlace)
        if (nativePlace.city != "Unknown City") saveLastGood(prefs, "native", lat, lng, nativePlace)

        val result = MultiLanguagePlaceInfo(en = enPlace, pl = plPlace, native = nativePlace)
        if (enPlace.city != "Unknown City" || plPlace.city != "Unknown City") {
            spatialPlaceCache[gridKey] = CachedMultiPlace(now, lat, lng, result)
            try {
                SpatialCacheHelper.getInstance(context).put(lat, lng, result)
            } catch (_: Exception) {}
        }
        return result
    }

    private fun resolvePlace(
        lat: Double, lng: Double,
        cacheKey: String,
        address: Address?,
        osmLang: String,
        countryCode: String,
        prefs: SharedPreferences,
        sharedOsm: OsmPlaceResult? = null
    ): PlaceInfo {
        val lastGood = loadLastGood(prefs, cacheKey)
        val lastGoodLat = prefs.getFloat("last_good_lat", Float.MIN_VALUE).toDouble()
        val lastGoodLng = prefs.getFloat("last_good_lng", Float.MIN_VALUE).toDouble()
        val distToLastGood = if (lastGood != null && lastGoodLat != Float.MIN_VALUE.toDouble()) {
            distanceBetween(lat, lng, lastGoodLat, lastGoodLng)
        } else Float.MAX_VALUE

        val osm = sharedOsm ?: geocodeWithOsm(lat, lng, osmLang)

        // Tier 1 – Geocoder returned locality. Enrich with OSM canonical road ref (DK52) & administrative gmina/powiat
        if (address?.locality != null) {
            val basePlace = address.toPlaceInfo(countryCode)
            val canonicalStreet = if (osm != null && !osm.roadRef.isNullOrBlank()) {
                RoadNameNormalizer.normalize(address.thoroughfare ?: osm.street, osm.roadRef, address.subThoroughfare)
            } else {
                basePlace.street ?: osm?.street
            }

            // Administrative hierarchy resolution with decay protection (never lose gmina due to transient geocoder glitch)
            val localityKey = basePlace.city.lowercase(Locale.ROOT)
            val isCountyCity = POLISH_COUNTY_CITIES.contains(localityKey)
            val candidateGmina = if (isCountyCity) null else (osm?.municipality ?: basePlace.gmina)
            val effectiveGmina = if (isCountyCity) {
                null
            } else if (!candidateGmina.isNullOrBlank()) {
                prefs.edit().putString("loc_gmina_$localityKey", candidateGmina).apply()
                candidateGmina
            } else {
                prefs.getString("loc_gmina_$localityKey", null)
                    ?: if (lastGood?.city.equals(basePlace.city, ignoreCase = true)) lastGood?.gmina else null
            }

            val effectivePowiat = osm?.county ?: basePlace.powiat ?: (if (distToLastGood < 3000f) lastGood?.powiat else null)
            val effectiveVoivodeship = osm?.state ?: (if (distToLastGood < 5000f) lastGood?.voivodeship else null) ?: basePlace.voivodeship

            return basePlace.copy(
                street = canonicalStreet,
                roadRef = osm?.roadRef ?: basePlace.roadRef,
                gmina = effectiveGmina,
                powiat = effectivePowiat,
                voivodeship = effectiveVoivodeship
            )
        }

        // Tier 2a – Close to last known good
        if (lastGood != null && distToLastGood < 300f) {
            return lastGood
        }

        // Tier 2b – OpenStreetMap Nominatim
        if (osm != null) {
            val city = osm.city ?: osm.municipality ?: osm.county
            if (city != null) {
                val localityKey = city.lowercase(Locale.ROOT)
                val isCountyCity = POLISH_COUNTY_CITIES.contains(localityKey)
                val effectiveGmina = if (isCountyCity) {
                    null
                } else if (!osm.municipality.isNullOrBlank()) {
                    prefs.edit().putString("loc_gmina_$localityKey", osm.municipality).apply()
                    osm.municipality
                } else {
                    prefs.getString("loc_gmina_$localityKey", null)
                        ?: if (lastGood?.city.equals(city, ignoreCase = true)) lastGood?.gmina else null
                }

                return PlaceInfo(
                    city = city,
                    street = osm.street ?: address?.thoroughfare?.let { RoadNameNormalizer.normalize(it, houseNumber = address.subThoroughfare) },
                    roadRef = osm.roadRef,
                    gmina = effectiveGmina,
                    powiat = osm.county ?: (if (distToLastGood < 3000f) lastGood?.powiat else null),
                    voivodeship = osm.state ?: address?.adminArea ?: lastGood?.voivodeship ?: "Unknown Region",
                    country = osm.country ?: address?.countryName ?: lastGood?.country ?: "Unknown Country",
                    countryCode = osm.countryCode ?: countryCode
                )
            }
        }

        // Tier 3 – Last known good
        if (lastGood != null) return lastGood

        return address?.toPlaceInfo(countryCode) ?: PlaceInfo("Unknown City", null, null, null, null, "Unknown Region", "Unknown Country", countryCode)
    }

    // ── OSM Nominatim ─────────────────────────────────────────────────────────

    private fun geocodeWithOsm(lat: Double, lng: Double, language: String): OsmPlaceResult? {
        val now = System.currentTimeMillis()
        // Quantize coordinates to ~100m grid cell so local movements don't hammer Nominatim
        val osmKey = "${String.format(Locale.ROOT, "%.3f", lat)}_${String.format(Locale.ROOT, "%.3f", lng)}_$language"
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
                    "&accept-language=$language&zoom=18&addressdetails=1"
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
                val houseNum = str("house_number")
                val rawRef = str("ref")

                // GEO-03: Normalize road name (DK52, DW946, A4, S7) and strip house numbers from major highways
                val normalizedStreet = RoadNameNormalizer.normalize(rawRoad, rawRef, houseNum)

                val rawMunicipality = str("municipality")
                    ?: str("commune")
                    ?: str("gmina")
                    ?: str("district")
                    ?: str("city_district")
                    ?: str("subdistrict")
                    ?: str("local_administrative_area")

                val osmResult = OsmPlaceResult(
                    city = str("city") ?: str("town") ?: str("village") ?: str("hamlet") ?: str("suburb"),
                    street = normalizedStreet,
                    roadRef = rawRef,
                    municipality = rawMunicipality,
                    county = str("county"),
                    state = str("state"),
                    country = str("country"),
                    countryCode = str("country_code")
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
        val country = prefs.getString("last_good_country_$key", null) ?: return null
        val cc = prefs.getString("last_good_cc_$key", "") ?: ""
        return PlaceInfo(city, street, roadRef, gmina, powiat, state, country, cc)
    }

    private fun distanceBetween(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
        val results = FloatArray(1)
        AndroidLocation.distanceBetween(lat1, lng1, lat2, lng2, results)
        return results[0]
    }

    fun resolveLocationData(lat: Double, lng: Double, speedMs: Float?, displayLanguage: DisplayLanguage): LocationData {
        val multiData = resolveMultiLanguageData(lat, lng)
        return buildLocationData(multiData, speedMs ?: lastValidSpeedMs, displayLanguage)
    }

    private fun buildLocationData(
        multiData: MultiLanguagePlaceInfo,
        speedMs: Float?,
        displayLanguage: DisplayLanguage
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
            isLoading = false
        )
    }

    private fun geocode(lat: Double, lng: Double, locale: Locale): Address? {
        val geocoder = Geocoder(context, locale)
        return try {
            @Suppress("DEPRECATION")
            val addresses = geocoder.getFromLocation(lat, lng, 1)
            if (!addresses.isNullOrEmpty()) addresses[0] else null
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun Address.toPlaceInfo(countryCode: String): PlaceInfo {
        val cityName = locality ?: subLocality ?: subAdminArea ?: "Unknown City"
        val thoroughfare = this.thoroughfare
        val houseNum = this.subThoroughfare
        val streetName = RoadNameNormalizer.normalize(thoroughfare, houseNumber = houseNum)
        val gminaName = subLocality
        val powiatName = subAdminArea
        val stateName = adminArea ?: "Unknown Region"
        val countryName = this.countryName ?: "Unknown Country"
        val cc = this.countryCode?.uppercase() ?: countryCode
        return PlaceInfo(cityName, streetName, null, gminaName, powiatName, stateName, countryName, cc)
    }
}
