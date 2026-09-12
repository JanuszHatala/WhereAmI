package com.example.whereiam

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
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
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
)

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

class LocationManager(private val context: Context) {

    companion object {
        @Volatile
        private var currentIntervalMs: Long = 1500L
        @Volatile
        private var currentMinIntervalMs: Long = 1000L
        @Volatile
        private var isGpsStopped: Boolean = false

        private val activeCallbacks = Collections.synchronizedSet(mutableSetOf<LocationCallback>())

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
            val gmina = place.gmina?.trim()
            val powiat = place.powiat?.trim()
            val voivodeship = place.voivodeship
                .replace("województwo ", "", ignoreCase = true)
                .replace("województwo", "", ignoreCase = true)
                .trim()

            if (isPoland) {
                val parts = mutableListOf<String>()
                if (!gmina.isNullOrEmpty() && !gmina.equals(city, ignoreCase = true)) {
                    val cleanGmina = gmina.replace("Gmina ", "", ignoreCase = true)
                        .replace("gmina ", "", ignoreCase = true).trim()
                    parts.add("gm. $cleanGmina")
                }
                if (!powiat.isNullOrEmpty() && !powiat.equals(city, ignoreCase = true)) {
                    val cleanPowiat = powiat.replace("Powiat ", "", ignoreCase = true)
                        .replace("powiat ", "", ignoreCase = true).trim()
                    parts.add("pow. $cleanPowiat")
                }
                if (voivodeship.isNotEmpty() && !voivodeship.equals("Unknown Region", ignoreCase = true)) {
                    parts.add("woj. $voivodeship")
                }
                return parts.joinToString(" • ")
            } else {
                // International formatting
                val parts = mutableListOf<String>()
                if (!gmina.isNullOrEmpty() && !gmina.equals(city, ignoreCase = true)) {
                    parts.add(gmina)
                }
                if (!powiat.isNullOrEmpty() && !powiat.equals(city, ignoreCase = true) && !powiat.equals(gmina, ignoreCase = true)) {
                    parts.add(powiat)
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
        synchronized(activeCallbacks) {
            for (cb in activeCallbacks) {
                try {
                    fusedLocationClient.removeLocationUpdates(cb)
                } catch (_: Exception) {}
            }
        }
    }

    // ── Hybrid Speed Smoothing (Kalman + Moving Average) ──────────────────────
    private var kalmanSpeed: Float? = null
    private var kalmanVariance = 4f
    private val BASE_PROCESS_NOISE = 0.3f
    private val ADAPTIVE_FACTOR = 0.5f
    private val DEFAULT_MEAS_NOISE = 1.44f
    private val STATIONARY_THRESHOLD = 0.3f // m/s (~1 km/h)

    // Rolling window for hybrid smoothing
    private val speedWindow = ArrayDeque<Float>()
    private val SPEED_WINDOW_SIZE = 3
    private var lastValidSpeedMs: Float = 0f

    private fun hybridSpeedUpdate(rawSpeed: Float?, gpsAccuracyMps: Float?): Float {
        if (rawSpeed == null) {
            // Keep last valid speed rather than dropping to null or 0 (anti-flicker)
            return lastValidSpeedMs
        }

        // Clamp stationary noise to true 0
        val cleanRaw = if (rawSpeed < STATIONARY_THRESHOLD) 0f else rawSpeed

        val measNoise = when {
            gpsAccuracyMps != null && gpsAccuracyMps > 0f -> gpsAccuracyMps * gpsAccuracyMps
            else -> DEFAULT_MEAS_NOISE
        }

        if (kalmanSpeed == null) {
            kalmanSpeed = cleanRaw
            kalmanVariance = measNoise
            lastValidSpeedMs = cleanRaw
            return cleanRaw
        }

        // Kalman step
        val innovation = cleanRaw - kalmanSpeed!!
        val adaptiveProcessNoise = BASE_PROCESS_NOISE + ADAPTIVE_FACTOR * innovation * innovation
        val predictedVariance = kalmanVariance + adaptiveProcessNoise
        val gain = predictedVariance / (predictedVariance + measNoise)
        kalmanSpeed = kalmanSpeed!! + gain * innovation
        kalmanVariance = (1f - gain) * predictedVariance

        val kSpeed = if (cleanRaw == 0f && kalmanSpeed!! < 0.4f) 0f else kalmanSpeed!!

        // Windowed moving average on top of Kalman for extra smoothness
        if (speedWindow.size >= SPEED_WINDOW_SIZE) speedWindow.removeFirst()
        speedWindow.addLast(kSpeed)

        val smoothed = speedWindow.average().toFloat()
        val finalSpeed = if (smoothed < 0.2f) 0f else smoothed
        lastValidSpeedMs = finalSpeed
        return finalSpeed
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

    private fun extractStreetBase(street: String?): String? {
        if (street.isNullOrBlank()) return null
        // Strip trailing house numbers (e.g. "Zagłębocze 9" -> "Zagłębocze", "ul. Kościuszki 14A" -> "ul. Kościuszki")
        return street.replace(Regex("""\s+\d+([a-zA-Z]|/\d+)?$"""), "").trim()
    }

    private fun applyStreetHysteresis(
        speedMs: Float,
        multiData: MultiLanguagePlaceInfo
    ): MultiLanguagePlaceInfo {
        val rawStreetPl = multiData.pl.street
        val rawBase = extractStreetBase(rawStreetPl)
        val now = System.currentTimeMillis()
        val speedKmh = speedMs * 3.6f

        // If no street detected, keep committed street if vehicle is in motion (bridges, short gaps)
        if (rawStreetPl.isNullOrBlank()) {
            if (committedStreetPl != null && speedKmh > 10f && (now - candidateStreetFirstSeenTime) < 10_000L) {
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

        // Different road detected! Check if vehicle is in motion (driving/cycling)
        if (speedKmh > 15f) {
            // Require 3 consecutive readings (or sustained for 3.5s) before switching streets
            if (candidateStreetBase != null && candidateStreetBase.equals(rawBase, ignoreCase = true)) {
                candidateStreetCount++
            } else {
                candidateStreetPl = rawStreetPl
                candidateStreetBase = rawBase
                candidateStreetCount = 1
                candidateStreetFirstSeenTime = now
            }

            val candidateDuration = now - candidateStreetFirstSeenTime
            if (candidateStreetCount >= 3 || candidateDuration >= 3500L) {
                TelemetryLogger.log("STREET", "Switch committed: '$committedStreetPl' -> '$rawStreetPl'")
                committedStreetPl = candidateStreetPl
                committedStreetBase = candidateStreetBase
                candidateStreetPl = null
                candidateStreetBase = null
                candidateStreetCount = 0
                return multiData.copy(
                    en = multiData.en.copy(street = committedStreetPl),
                    pl = multiData.pl.copy(street = committedStreetPl),
                    native = multiData.native.copy(street = committedStreetPl)
                )
            } else {
                // Reject momentary cross-street blip: keep current committed street
                return multiData.copy(
                    en = multiData.en.copy(street = committedStreetPl),
                    pl = multiData.pl.copy(street = committedStreetPl),
                    native = multiData.native.copy(street = committedStreetPl)
                )
            }
        } else {
            // Walking or slow speed: update after 2 confirmations
            if (candidateStreetBase.equals(rawBase, ignoreCase = true)) {
                candidateStreetCount++
            } else {
                candidateStreetBase = rawBase
                candidateStreetCount = 1
            }
            if (candidateStreetCount >= 2) {
                committedStreetPl = rawStreetPl
                committedStreetBase = rawBase
                candidateStreetCount = 0
                return multiData
            } else {
                return multiData.copy(
                    en = multiData.en.copy(street = committedStreetPl),
                    pl = multiData.pl.copy(street = committedStreetPl),
                    native = multiData.native.copy(street = committedStreetPl)
                )
            }
        }
    }

    // ── Continuous location flow ───────────────────────────────────────────────
    @SuppressLint("MissingPermission")
    fun getLocationUpdates(displayLanguage: DisplayLanguage): Flow<LocationData> = callbackFlow {
        val prefs = context.getSharedPreferences("where_i_am_prefs", Context.MODE_PRIVATE)
        // Initial state holding last known data if available
        val initialSpeed = if (prefs.contains("speed")) prefs.getFloat("speed", 0f) else 0f
        val initialCoords = if (prefs.contains("lat") && prefs.contains("lng")) {
            Pair(prefs.getFloat("lat", 0f).toDouble(), prefs.getFloat("lng", 0f).toDouble())
        } else null

        if (initialCoords != null) {
            val cachedData = resolveLocationData(initialCoords.first, initialCoords.second, initialSpeed, displayLanguage)
            trySend(cachedData)
        } else {
            trySend(LocationData(null, null, initialSpeed, null, true))
        }

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, currentIntervalMs)
            .setMinUpdateIntervalMillis(currentMinIntervalMs)
            .build()

        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    val rawSpeed = if (location.hasSpeed()) location.speed else null
                    val accuracy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasSpeedAccuracy())
                        location.speedAccuracyMetersPerSecond else null
                    val speed = hybridSpeedUpdate(rawSpeed, accuracy)

                    prefs.edit()
                        .putFloat("lat", location.latitude.toFloat())
                        .putFloat("lng", location.longitude.toFloat())
                        .putFloat("speed", speed)
                        .apply()

                    // CRITICAL FIX FOR ANR / SYSTEM FREEZE:
                    // Perform geocoding asynchronously on IO thread to never block main looper!
                    ioScope.launch {
                        val rawMultiData = resolveMultiLanguageData(location.latitude, location.longitude)
                        val borderStabilized = applyBorderHysteresis(location.latitude, location.longitude, rawMultiData)
                        val stabilizedMultiData = applyStreetHysteresis(speed, borderStabilized)

                        val data = buildLocationData(stabilizedMultiData, speed, displayLanguage)

                        // Notify TripManager
                        TripManager.getInstance(context).onLocationUpdate(
                            location.latitude,
                            location.longitude,
                            speed,
                            data.primaryPlace
                        )

                        // Notify LiveSharingManager
                        val speedKmh = speed * 3.6f
                        val alt = if (location.hasAltitude()) location.altitude else null
                        val placeName = data.primaryPlace?.let { p ->
                            if (!p.street.isNullOrBlank()) "${p.city}, ${p.street}" else p.city
                        }
                        LiveSharingManager.getInstance(context).onLocationUpdate(
                            lat = location.latitude,
                            lng = location.longitude,
                            speedKmh = speedKmh,
                            altitude = alt,
                            placeName = placeName,
                            trekkingBadge = null
                        )

                        trySend(data)
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
                trySend(LocationData(null, null, lastValidSpeedMs, e.message ?: "Failed to get location", false))
            }
        }

        awaitClose {
            activeCallbacks.remove(locationCallback)
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
    }

    /**
     * Lightweight flow for map composable (lat, lng, bearing).
     */
    @SuppressLint("MissingPermission")
    fun getLocationRaw(): Flow<Triple<Double, Double, Float?>> = callbackFlow {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
            .setMinUpdateIntervalMillis(500)
            .build()

        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { loc ->
                    val bearing = if (loc.hasBearing() && (loc.hasSpeed() && loc.speed >= 1.4f))
                        loc.bearing else null
                    trySend(Triple(loc.latitude, loc.longitude, bearing))
                }
            }
        }

        activeCallbacks.add(cb)
        if (!isGpsStopped) {
            fusedLocationClient.requestLocationUpdates(locationRequest, cb, Looper.getMainLooper())
        }
        awaitClose {
            activeCallbacks.remove(cb)
            fusedLocationClient.removeLocationUpdates(cb)
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
                            val speed = hybridSpeedUpdate(rawSpeed, accuracy)

                            val prefs = context.getSharedPreferences("where_i_am_prefs", Context.MODE_PRIVATE)
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
        val prefs = context.getSharedPreferences("where_i_am_prefs", Context.MODE_PRIVATE)
        val lat = if (prefs.contains("lat")) prefs.getFloat("lat", 0f).toDouble() else null
        val lng = if (prefs.contains("lng")) prefs.getFloat("lng", 0f).toDouble() else null
        val lastSpeed = if (prefs.contains("speed")) prefs.getFloat("speed", 0f) else lastValidSpeedMs
        if (lat != null && lng != null) {
            cont.resume(resolveLocationData(lat, lng, lastSpeed, displayLanguage))
        } else {
            cont.resume(LocationData(null, null, lastSpeed, errorMsg ?: "Location unavailable", false))
        }
    }

    // ── Place resolution ───────────────────────────────────────────────────────

    fun resolveMultiLanguageData(lat: Double, lng: Double): MultiLanguagePlaceInfo {
        val prefs = context.getSharedPreferences("where_i_am_prefs", Context.MODE_PRIVATE)

        val baseAddress = geocode(lat, lng, Locale.getDefault())
        val countryCode = baseAddress?.countryCode?.uppercase() ?: ""
        val nativeLocale = if (countryCode.isNotEmpty()) Locale("", countryCode) else Locale.getDefault()

        val enAddress = geocode(lat, lng, Locale.US) ?: baseAddress
        val plAddress = geocode(lat, lng, Locale("pl", "PL")) ?: baseAddress
        val nativeAddress = geocode(lat, lng, nativeLocale) ?: baseAddress

        val enPlace   = resolvePlace(lat, lng, "en",     enAddress,     "en",     countryCode, prefs)
        val plPlace   = resolvePlace(lat, lng, "pl",     plAddress,     "pl",     countryCode, prefs)
        val nativePlace = resolvePlace(lat, lng, "native", nativeAddress, nativeLocale.language, countryCode, prefs)

        if (enPlace.city != "Unknown City")     saveLastGood(prefs, "en",     lat, lng, enPlace)
        if (plPlace.city != "Unknown City")     saveLastGood(prefs, "pl",     lat, lng, plPlace)
        if (nativePlace.city != "Unknown City") saveLastGood(prefs, "native", lat, lng, nativePlace)

        return MultiLanguagePlaceInfo(en = enPlace, pl = plPlace, native = nativePlace)
    }

    private fun resolvePlace(
        lat: Double, lng: Double,
        cacheKey: String,
        address: Address?,
        osmLang: String,
        countryCode: String,
        prefs: SharedPreferences
    ): PlaceInfo {
        // Tier 1 – Geocoder returned locality
        if (address?.locality != null) {
            return address.toPlaceInfo(countryCode)
        }

        // Tier 2a – Close to last known good
        val lastGood = loadLastGood(prefs, cacheKey)
        val lastGoodLat = prefs.getFloat("last_good_lat", Float.MIN_VALUE).toDouble()
        val lastGoodLng = prefs.getFloat("last_good_lng", Float.MIN_VALUE).toDouble()
        if (lastGood != null && lastGoodLat != Float.MIN_VALUE.toDouble()) {
            val distM = distanceBetween(lat, lng, lastGoodLat, lastGoodLng)
            if (distM < 300f) return lastGood
        }

        // Tier 2b – OpenStreetMap Nominatim
        val osm = geocodeWithOsm(lat, lng, osmLang)
        if (osm != null) {
            val city = osm.city ?: osm.municipality ?: osm.county
            if (city != null) {
                return PlaceInfo(
                    city = city,
                    street = osm.street ?: address?.thoroughfare,
                    roadRef = osm.roadRef,
                    gmina = osm.municipality,
                    powiat = osm.county,
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
        return try {
            val urlStr = "https://nominatim.openstreetmap.org/reverse" +
                    "?format=json&lat=$lat&lon=$lng" +
                    "&accept-language=$language&zoom=18&addressdetails=1"
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "WhereIAmPersonalApp/1.1 (android)")
            conn.connectTimeout = 6000
            conn.readTimeout = 6000

            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().readText()
                val root = JSONObject(body)
                val addrObj = root.optJSONObject("address") ?: return null

                fun str(key: String) = addrObj.optString(key).takeIf { it.isNotEmpty() }

                val rawRoad = str("road") ?: str("street") ?: str("pedestrian") ?: str("footway")
                val houseNum = str("house_number")
                var rawRef = str("ref")

                // Clean up road references like "Krajowa 52" -> "DK 52", "Wojewódzka 948" -> "DW 948"
                var cleanedRoad = rawRoad
                if (cleanedRoad != null) {
                    if (cleanedRoad.startsWith("Krajowa ", ignoreCase = true)) {
                        val num = cleanedRoad.substring(8).trim()
                        cleanedRoad = "DK $num"
                    } else if (cleanedRoad.startsWith("Droga Krajowa ", ignoreCase = true)) {
                        val num = cleanedRoad.substring(14).trim()
                        cleanedRoad = "DK $num"
                    } else if (cleanedRoad.startsWith("Wojewódzka ", ignoreCase = true)) {
                        val num = cleanedRoad.substring(11).trim()
                        cleanedRoad = "DW $num"
                    }
                }

                // If road is a major numbered highway (e.g. "DK 52"), don't append random adjacent house numbers to highway name
                val isHighway = cleanedRoad != null && (cleanedRoad.startsWith("DK ") || cleanedRoad.startsWith("DW ") || cleanedRoad.startsWith("A") || cleanedRoad.startsWith("S"))

                val streetName = when {
                    cleanedRoad != null && houseNum != null && !isHighway -> "$cleanedRoad $houseNum"
                    cleanedRoad != null -> cleanedRoad
                    houseNum != null -> houseNum
                    else -> null
                }

                OsmPlaceResult(
                    city = str("city") ?: str("town") ?: str("village") ?: str("hamlet") ?: str("suburb"),
                    street = streetName,
                    roadRef = rawRef,
                    municipality = str("municipality"),
                    county = str("county"),
                    state = str("state"),
                    country = str("country"),
                    countryCode = str("country_code")
                )
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
        val streetName = when {
            !thoroughfare.isNullOrBlank() && !houseNum.isNullOrBlank() -> "$thoroughfare $houseNum"
            !thoroughfare.isNullOrBlank() -> thoroughfare
            !houseNum.isNullOrBlank() -> houseNum
            else -> null
        }
        val gminaName = subLocality
        val powiatName = subAdminArea
        val stateName = adminArea ?: "Unknown Region"
        val countryName = this.countryName ?: "Unknown Country"
        val cc = this.countryCode?.uppercase() ?: countryCode
        return PlaceInfo(cityName, streetName, null, gminaName, powiatName, stateName, countryName, cc)
    }
}
