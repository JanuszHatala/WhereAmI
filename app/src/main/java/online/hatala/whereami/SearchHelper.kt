package online.hatala.whereami

import android.content.Context
import android.location.Address
import android.location.Geocoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.osmdroid.util.GeoPoint
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

data class SearchResultItem(
    val title: String,
    val subtitle: String,
    val geoPoint: GeoPoint,
    val localityName: String? = null,
    val countryCode: String? = null,
    val municipalityName: String? = null,
    val distanceMeters: Float? = null,
    val placeType: String? = null,
    val source: String? = null,
    val importance: Double = 0.0,
    val rankScore: Double = 0.0
)

data class TouristicPoi(
    val name: String,
    val kind: String,
    val latitude: Double,
    val longitude: Double,
    val distanceMeters: Float
)

object SearchHelper {

    private const val USER_AGENT = "WhereAmIPersonalApp/1.1 (android; place-search)"
    private const val PHOTON_BIAS_SCALE = 0.5
    private const val VIEWBOX_RADIUS_DEG = 0.75 // ~80 km soft bias for Nominatim fallback

    /**
     * Near-me-first place search: Photon (location-biased) primary, Nominatim/Geocoder fallback,
     * merged with saved/recent local hits and ranked by [PlaceSearchRanker].
     */
    suspend fun searchLocation(
        context: Context,
        query: String,
        currentCity: String? = null,
        originLat: Double? = null,
        originLon: Double? = null,
        countryCode: String? = null,
        savedPlaces: List<SavedPlace> = emptyList(),
        recentPlaces: List<SearchResultItem> = emptyList()
    ): List<SearchResultItem> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.length < 2) return@withContext emptyList()

        val candidates = mutableListOf<PlaceSearchRanker.Candidate>()

        fun addCandidate(c: PlaceSearchRanker.Candidate) {
            candidates.add(c)
        }

        // Instant local hits (diacritic-insensitive via SearchNormalizer)
        for (place in savedPlaces) {
            if (SearchNormalizer.matchesSavedPlace(trimmed, place)) {
                val subtitle = listOfNotNull(
                    place.street.takeIf { it.isNotBlank() },
                    place.locality.takeIf { it.isNotBlank() },
                    place.category.displayName
                ).joinToString(" · ")
                addCandidate(
                    PlaceSearchRanker.Candidate(
                        item = SearchResultItem(
                            title = place.name,
                            subtitle = if (subtitle.isNotBlank()) subtitle else "Saved place",
                            geoPoint = place.geoPoint,
                            localityName = place.locality.takeIf { it.isNotBlank() },
                            countryCode = countryCode,
                            placeType = "saved",
                            source = "saved"
                        ),
                        placeType = "saved",
                        isSaved = true
                    )
                )
            }
        }
        for (recent in recentPlaces) {
            if (SearchNormalizer.matchesQuery(trimmed, recent.title, recent.subtitle, recent.localityName)) {
                addCandidate(
                    PlaceSearchRanker.Candidate(
                        item = recent.copy(placeType = recent.placeType ?: "recent", source = "recent"),
                        placeType = recent.placeType ?: "recent",
                        isRecent = true
                    )
                )
            }
        }

        val photonHits = tryPhoton(trimmed, originLat, originLon)
        for (hit in photonHits) {
            addCandidate(
                PlaceSearchRanker.Candidate(
                    item = hit,
                    placeType = hit.placeType,
                    importance = hit.importance
                )
            )
        }

        // Fallback when Photon is empty/unavailable
        if (photonHits.isEmpty()) {
            val fallback = mutableListOf<SearchResultItem>()
            val hasDigits = trimmed.any { it.isDigit() }
            val isExplicitStreet = trimmed.startsWith("ul.", ignoreCase = true) ||
                trimmed.startsWith("ul ", ignoreCase = true) ||
                trimmed.startsWith("ulica", ignoreCase = true) ||
                trimmed.startsWith("aleja", ignoreCase = true) ||
                trimmed.startsWith("al.", ignoreCase = true) ||
                trimmed.startsWith("pl.", ignoreCase = true) ||
                trimmed.startsWith("plac", ignoreCase = true)
            val cityAvailable = !currentCity.isNullOrBlank() &&
                !trimmed.contains(currentCity, ignoreCase = true)

            if (cityAvailable && isExplicitStreet) {
                val localAddress = "$trimmed, $currentCity"
                fallback += tryGeocoder(context, localAddress)
                fallback += tryNominatim(localAddress, originLat, originLon)
            }

            fallback += tryGeocoder(context, trimmed)
            if (fallback.size < 5) {
                fallback += tryNominatim(trimmed, originLat, originLon)
            }

            if (fallback.isEmpty() && cityAvailable && hasDigits) {
                val localAddress = "$trimmed, $currentCity"
                fallback += tryGeocoder(context, localAddress)
                fallback += tryNominatim(localAddress, originLat, originLon)
            }

            for (hit in fallback) {
                addCandidate(
                    PlaceSearchRanker.Candidate(
                        item = hit,
                        placeType = hit.placeType,
                        importance = hit.importance
                    )
                )
            }
        }

        return@withContext PlaceSearchRanker.rank(
            query = trimmed,
            candidates = candidates,
            originLat = originLat,
            originLon = originLon,
            countryCode = countryCode
        )
    }

    internal fun tryPhoton(
        q: String,
        lat: Double?,
        lon: Double?,
        limit: Int = 10
    ): List<SearchResultItem> {
        return try {
            val encoded = URLEncoder.encode(q.trim(), "UTF-8")
            val lang = Locale.getDefault().language.takeIf { it.length == 2 } ?: "en"
            val sb = StringBuilder("https://photon.komoot.io/api/?q=$encoded&limit=$limit&lang=$lang")
            sb.append("&location_bias_scale=$PHOTON_BIAS_SCALE")
            if (lat != null && lon != null) {
                sb.append("&lat=").append(String.format(Locale.US, "%.5f", lat))
                sb.append("&lon=").append(String.format(Locale.US, "%.5f", lon))
            }
            val conn = URL(sb.toString()).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            if (conn.responseCode != 200) return emptyList()
            val body = conn.inputStream.bufferedReader().readText()
            parsePhotonResponse(body)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Pure Photon GeoJSON parser (unit-testable). */
    fun parsePhotonResponse(jsonStr: String): List<SearchResultItem> {
        val root = org.json.JSONObject(jsonStr)
        val features = root.optJSONArray("features") ?: return emptyList()
        val out = mutableListOf<SearchResultItem>()
        for (i in 0 until features.length()) {
            val feature = features.optJSONObject(i) ?: continue
            val geometry = feature.optJSONObject("geometry") ?: continue
            val coords = geometry.optJSONArray("coordinates") ?: continue
            if (coords.length() < 2) continue
            val lon = coords.getDouble(0)
            val lat = coords.getDouble(1)
            val props = feature.optJSONObject("properties") ?: continue

            val name = props.optString("name", "").takeIf { it.isNotBlank() }
            val street = props.optString("street", "").takeIf { it.isNotBlank() }
            val house = props.optString("housenumber", "").takeIf { it.isNotBlank() }
            val city = props.optString("city", "").takeIf { it.isNotBlank() }
                ?: props.optString("town", "").takeIf { it.isNotBlank() }
                ?: props.optString("village", "").takeIf { it.isNotBlank() }
                ?: props.optString("municipality", "").takeIf { it.isNotBlank() }
            val district = props.optString("district", "").takeIf { it.isNotBlank() }
            val state = props.optString("state", "").takeIf { it.isNotBlank() }
            val country = props.optString("country", "").takeIf { it.isNotBlank() }
            val countryCode = props.optString("countrycode", "")
                .takeIf { it.isNotBlank() }?.lowercase(Locale.ROOT)
            val osmKey = props.optString("osm_key", "")
            val osmValue = props.optString("osm_value", "")
            val type = props.optString("type", "").takeIf { it.isNotBlank() }
                ?: osmValue.takeIf { it.isNotBlank() }
                ?: osmKey.takeIf { it.isNotBlank() }
            val importance = props.optDouble("importance", 0.0)

            val title = when {
                street != null && house != null -> "$street $house"
                name != null -> name
                street != null -> street
                city != null -> city
                else -> continue
            }

            val subtitle = listOfNotNull(
                city?.takeIf { it != title },
                district?.takeIf { it != title && it != city },
                state?.takeIf { it != title },
                country
            ).distinct().joinToString(", ")

            out.add(
                SearchResultItem(
                    title = title,
                    subtitle = subtitle,
                    geoPoint = GeoPoint(lat, lon),
                    localityName = city,
                    countryCode = countryCode,
                    placeType = type,
                    source = "photon",
                    importance = importance
                )
            )
        }
        return out
    }

    private fun tryGeocoder(context: Context, q: String): List<SearchResultItem> {
        return try {
            val geocoder = Geocoder(context, Locale.getDefault())
            @Suppress("DEPRECATION")
            val addresses: List<Address>? = geocoder.getFromLocationName(q, 5)
            if (addresses.isNullOrEmpty()) return emptyList()
            addresses.mapNotNull { addr ->
                if (!addr.hasLatitude() || !addr.hasLongitude()) return@mapNotNull null
                val street = addr.thoroughfare
                val feature = addr.featureName
                val subThoroughfare = addr.subThoroughfare
                val title = when {
                    !street.isNullOrBlank() && !subThoroughfare.isNullOrBlank() -> "$street $subThoroughfare"
                    !street.isNullOrBlank() && !feature.isNullOrBlank() && feature != street -> "$street $feature"
                    !street.isNullOrBlank() -> street
                    !feature.isNullOrBlank() -> feature
                    !addr.locality.isNullOrBlank() -> addr.locality
                    else -> q
                }
                val subtitle = listOfNotNull(
                    addr.locality?.takeIf { it != title },
                    addr.subAdminArea,
                    addr.adminArea,
                    addr.countryName
                ).distinct().joinToString(", ")
                val placeType = when {
                    !street.isNullOrBlank() -> "street"
                    !addr.locality.isNullOrBlank() -> "city"
                    else -> null
                }
                SearchResultItem(
                    title = title,
                    subtitle = subtitle,
                    geoPoint = GeoPoint(addr.latitude, addr.longitude),
                    localityName = addr.locality,
                    countryCode = addr.countryCode?.lowercase(Locale.ROOT),
                    placeType = placeType,
                    source = "geocoder"
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun tryNominatim(
        q: String,
        originLat: Double?,
        originLon: Double?
    ): List<SearchResultItem> {
        return try {
            val encodedQuery = URLEncoder.encode(q.trim(), "UTF-8")
            val sb = StringBuilder(
                "https://nominatim.openstreetmap.org/search?q=$encodedQuery&format=json&addressdetails=1&limit=8"
            )
            // Soft viewbox bias (no bounded=1): near results preferred, far still allowed
            if (originLat != null && originLon != null) {
                val dLat = VIEWBOX_RADIUS_DEG
                val dLon = VIEWBOX_RADIUS_DEG / kotlin.math.cos(Math.toRadians(originLat)).coerceAtLeast(0.2)
                val minLon = originLon - dLon
                val maxLon = originLon + dLon
                val minLat = originLat - dLat
                val maxLat = originLat + dLat
                sb.append("&viewbox=")
                    .append(String.format(Locale.US, "%.5f,%.5f,%.5f,%.5f", minLon, maxLat, maxLon, minLat))
            }
            val conn = URL(sb.toString()).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            if (conn.responseCode != 200) return emptyList()
            val body = conn.inputStream.bufferedReader().readText()
            parseNominatimSearch(body)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun parseNominatimSearch(jsonStr: String): List<SearchResultItem> {
        val array = JSONArray(jsonStr)
        val out = mutableListOf<SearchResultItem>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val displayName = obj.optString("display_name", "")
            val lat = obj.getDouble("lat")
            val lon = obj.getDouble("lon")
            val osmClass = obj.optString("class", "")
            val osmType = obj.optString("type", "")
            val importance = obj.optDouble("importance", 0.0)

            val addressObj = obj.optJSONObject("address")
            val road = addressObj?.optString("road")?.takeIf { it.isNotBlank() }
            val houseNumber = addressObj?.optString("house_number")?.takeIf { it.isNotBlank() }
            val city = addressObj?.optString("city")?.takeIf { it.isNotBlank() }
                ?: addressObj?.optString("town")?.takeIf { it.isNotBlank() }
                ?: addressObj?.optString("village")?.takeIf { it.isNotBlank() }
                ?: addressObj?.optString("municipality")?.takeIf { it.isNotBlank() }
            val countryCode = addressObj?.optString("country_code")?.takeIf { it.isNotBlank() }
                ?.lowercase(Locale.ROOT)

            val title = when {
                road != null && houseNumber != null -> "$road $houseNumber"
                road != null -> road
                else -> displayName.split(", ").firstOrNull() ?: displayName
            }
            val subtitle = listOfNotNull(
                city?.takeIf { it != title },
                addressObj?.optString("county")?.takeIf { it.isNotBlank() },
                addressObj?.optString("state")?.takeIf { it.isNotBlank() },
                addressObj?.optString("country")?.takeIf { it.isNotBlank() }
            ).distinct().joinToString(", ")

            val placeType = when {
                osmClass == "highway" -> "street"
                osmType.isNotBlank() -> osmType
                osmClass.isNotBlank() -> osmClass
                else -> null
            }

            out.add(
                SearchResultItem(
                    title = title,
                    subtitle = if (subtitle.isNotBlank()) subtitle else displayName,
                    geoPoint = GeoPoint(lat, lon),
                    localityName = city,
                    countryCode = countryCode,
                    placeType = placeType,
                    source = "nominatim",
                    importance = importance
                )
            )
        }
        return out
    }

    suspend fun reverseGeocode(
        context: Context,
        geoPoint: GeoPoint
    ): SearchResultItem = withContext(Dispatchers.IO) {
        val coordsStr = String.format(Locale.US, "%.5f, %.5f", geoPoint.latitude, geoPoint.longitude)

        // 1. Try Nominatim reverse geocoding first (provides exact OSM place classification, gmina, and natural terrain tags)
        var nominatimParsed: SearchResultItem? = null
        try {
            val urlStr = "https://nominatim.openstreetmap.org/reverse?format=json&lat=${geoPoint.latitude}&lon=${geoPoint.longitude}&zoom=18&addressdetails=1"
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "WhereAmIPersonalApp/1.1 (android)")
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            if (conn.responseCode == 200) {
                val responseText = conn.inputStream.bufferedReader().readText()
                nominatimParsed = parseNominatimReverse(responseText, geoPoint.latitude, geoPoint.longitude)
            }
        } catch (_: Exception) {}

        // 1b. Outdoor / forest pins: if title is still bare coordinates, look for a nearby peak/pass/viewpoint
        if (nominatimParsed != null) {
            val needsPoiTitle = nominatimParsed.title == coordsStr ||
                looksLikeBareCoordinates(nominatimParsed.title)
            if (needsPoiTitle) {
                val poi = fetchNearbyTouristicPoi(geoPoint.latitude, geoPoint.longitude)
                if (poi != null) {
                    return@withContext nominatimParsed.copy(title = poi.name)
                }
            }
            if (nominatimParsed.localityName != null || nominatimParsed.title != coordsStr) {
                return@withContext nominatimParsed
            }
        }

        // 2. Fallback to Android Geocoder (offline or when Nominatim fails)
        try {
            val geocoder = Geocoder(context, Locale.getDefault())
            @Suppress("DEPRECATION")
            val addresses = geocoder.getFromLocation(geoPoint.latitude, geoPoint.longitude, 1)
            if (!addresses.isNullOrEmpty()) {
                val addr = addresses[0]
                val street = addr.thoroughfare
                val houseNum = addr.subThoroughfare
                val locality = addr.locality ?: addr.subLocality ?: addr.subAdminArea
                val countryCode = addr.countryCode?.lowercase(Locale.ROOT) ?: "pl"

                var isOffRoad = false
                if (addr.hasLatitude() && addr.hasLongitude()) {
                    val dist = computeDistanceMeters(
                        geoPoint.latitude, geoPoint.longitude,
                        addr.latitude, addr.longitude
                    )
                    if (dist > 150f) {
                        isOffRoad = true
                    }
                }

                val title = when {
                    isOffRoad -> coordsStr
                    !street.isNullOrBlank() && !houseNum.isNullOrBlank() -> "$street $houseNum"
                    !street.isNullOrBlank() -> street
                    else -> locality ?: coordsStr
                }

                val cleanSubAdmin = addr.subAdminArea?.replace("Powiat ", "", ignoreCase = true)
                    ?.replace("powiat ", "", ignoreCase = true)
                    ?.replace("pow. ", "", ignoreCase = true)?.trim()
                val cleanAdmin = addr.adminArea?.replace("województwo ", "", ignoreCase = true)
                    ?.replace("województwo", "", ignoreCase = true)
                    ?.replace("woj. ", "", ignoreCase = true)?.trim()

                val isPl = addr.countryCode?.equals("pl", ignoreCase = true) == true ||
                        addr.countryName?.equals("Polska", ignoreCase = true) == true ||
                        addr.countryName?.equals("Poland", ignoreCase = true) == true

                val hierarchyParts = if (isPl) {
                    listOfNotNull(
                        locality?.takeIf { it != title },
                        cleanSubAdmin?.let { "pow. $it" },
                        cleanAdmin?.let { "woj. $it" },
                        addr.countryName
                    ).distinct()
                } else {
                    listOfNotNull(
                        locality?.takeIf { it != title },
                        cleanSubAdmin,
                        cleanAdmin,
                        addr.countryName
                    ).distinct()
                }

                val subtitle = if (hierarchyParts.isNotEmpty()) hierarchyParts.joinToString(", ") else coordsStr

                val resolvedLocality = if (!addr.subLocality.isNullOrBlank()) addr.subLocality else locality
                val resolvedMunicipality = if (!addr.subLocality.isNullOrBlank() && !addr.locality.isNullOrBlank()) addr.locality else null

                return@withContext SearchResultItem(
                    title = title,
                    subtitle = subtitle,
                    geoPoint = geoPoint,
                    localityName = resolvedLocality,
                    countryCode = countryCode,
                    municipalityName = resolvedMunicipality
                )
            }
        } catch (_: Exception) {}

        return@withContext SearchResultItem(
            title = coordsStr,
            subtitle = "",
            geoPoint = geoPoint
        )
    }

    /**
     * Pure parser for Nominatim reverse geocoding JSON responses.
     * Enforces the 150m off-road threshold and natural terrain awareness so distant
     * street names are not assigned to forest/mountain/rural coordinates.
     */
    fun parseNominatimReverse(
        jsonStr: String,
        queryLat: Double,
        queryLon: Double
    ): SearchResultItem {
        val coordsStr = String.format(Locale.US, "%.5f, %.5f", queryLat, queryLon)
        val json = org.json.JSONObject(jsonStr)
        val osmClass = json.optString("class", "")
        val osmType = json.optString("type", "")
        val featureName = json.optString("name", "").takeIf { it.isNotBlank() }
        val snappedLat = json.optDouble("lat", Double.NaN)
        val snappedLon = json.optDouble("lon", Double.NaN)

        val addressObj = json.optJSONObject("address")
        val road = addressObj?.optString("road")?.takeIf { it.isNotBlank() }
        val houseNum = addressObj?.optString("house_number")?.takeIf { it.isNotBlank() }
        val city = addressObj?.optString("city")?.takeIf { it.isNotBlank() }
            ?: addressObj?.optString("town")?.takeIf { it.isNotBlank() }
            ?: addressObj?.optString("village")?.takeIf { it.isNotBlank() }
            ?: addressObj?.optString("hamlet")?.takeIf { it.isNotBlank() }
            ?: addressObj?.optString("isolated_dwelling")?.takeIf { it.isNotBlank() }
            ?: addressObj?.optString("municipality")?.takeIf { it.isNotBlank() }
        val municipality = addressObj?.optString("municipality")?.takeIf { it.isNotBlank() && it != city }
            ?: addressObj?.optString("commune")?.takeIf { it.isNotBlank() && it != city }
        val county = addressObj?.optString("county")?.takeIf { it.isNotBlank() }
        val state = addressObj?.optString("state")?.takeIf { it.isNotBlank() }
        val country = addressObj?.optString("country")?.takeIf { it.isNotBlank() }
        val countryCode = addressObj?.optString("country_code", "pl")?.lowercase(Locale.ROOT) ?: "pl"

        val isTouristicFeature = isTouristicOsmFeature(osmClass, osmType)
        val isNaturalTerrain = osmClass in listOf("natural", "landuse", "leisure", "waterway") ||
                osmType in listOf("forest", "wood", "peak", "scrub", "heath", "grass", "meadow", "fell", "wetland")

        var isOffRoad = isNaturalTerrain && !isTouristicFeature
        if (!snappedLat.isNaN() && !snappedLon.isNaN()) {
            val dist = computeDistanceMeters(
                queryLat, queryLon,
                snappedLat, snappedLon
            )
            if (dist > 150f) {
                isOffRoad = true
            }
        }
        // Named peak / viewpoint / pass at the pin: keep as title even when "off-road"
        if (isTouristicFeature && featureName != null) {
            isOffRoad = false
        }

        val title = when {
            isTouristicFeature && featureName != null -> featureName
            isOffRoad -> coordsStr
            road != null && houseNum != null -> "$road $houseNum"
            road != null -> road
            featureName != null && osmClass == "tourism" -> featureName
            else -> city ?: coordsStr
        }

        val cleanMunicipality = municipality?.replace("Gmina ", "", ignoreCase = true)
            ?.replace("gmina ", "", ignoreCase = true)
            ?.replace("gm. ", "", ignoreCase = true)?.trim()
        val cleanCounty = county?.replace("Powiat ", "", ignoreCase = true)
            ?.replace("powiat ", "", ignoreCase = true)
            ?.replace("pow. ", "", ignoreCase = true)?.trim()
        val cleanState = state?.replace("województwo ", "", ignoreCase = true)
            ?.replace("województwo", "", ignoreCase = true)
            ?.replace("woj. ", "", ignoreCase = true)?.trim()

        val isPoland = countryCode == "pl" || (country != null && (country.equals("Polska", ignoreCase = true) || country.equals("Poland", ignoreCase = true)))

        val subtitleParts = if (isPoland) {
            listOfNotNull(
                city?.takeIf { it != title },
                cleanMunicipality?.let { "gm. $it" },
                cleanCounty?.let { "pow. $it" },
                cleanState?.let { "woj. $it" },
                country
            ).distinct()
        } else {
            listOfNotNull(
                city?.takeIf { it != title },
                cleanMunicipality,
                cleanCounty,
                cleanState,
                country
            ).distinct()
        }

        val subtitle = if (subtitleParts.isNotEmpty()) subtitleParts.joinToString(", ") else coordsStr

        return SearchResultItem(
            title = title,
            subtitle = subtitle,
            geoPoint = GeoPoint(queryLat, queryLon),
            localityName = city,
            countryCode = countryCode,
            municipalityName = cleanMunicipality
        )
    }

    /**
     * Pure haversine calculation in meters (independent of Android framework).
     */
    fun computeDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return (6371000.0 * c).toFloat()
    }

    fun isTouristicOsmFeature(osmClass: String, osmType: String): Boolean {
        val c = osmClass.lowercase(Locale.ROOT)
        val t = osmType.lowercase(Locale.ROOT)
        if (c == "natural" && t in listOf("peak", "saddle", "volcano")) return true
        if (c == "tourism" && t in listOf("viewpoint", "alpine_hut", "wilderness_hut", "picnic_site")) return true
        return t == "mountain_pass" || c == "mountain_pass"
    }

    fun looksLikeBareCoordinates(title: String): Boolean {
        return title.matches(Regex("""^-?\d+\.\d+,\s*-?\d+\.\d+$"""))
    }

    /**
     * Parse Overpass JSON elements and pick the best nearby touristic POI.
     * Priority: peak > mountain_pass/saddle > viewpoint > shelter/hut. Within a kind, nearest wins.
     */
    fun selectNearestTouristicPoi(
        overpassJson: String,
        queryLat: Double,
        queryLon: Double,
        maxDistanceMeters: Float = 400f
    ): TouristicPoi? {
        val root = org.json.JSONObject(overpassJson)
        val elements = root.optJSONArray("elements") ?: return null
        var best: TouristicPoi? = null
        var bestRank = Int.MAX_VALUE

        for (i in 0 until elements.length()) {
            val el = elements.optJSONObject(i) ?: continue
            val lat = el.optDouble("lat", Double.NaN)
            val lon = el.optDouble("lon", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue
            val tags = el.optJSONObject("tags") ?: continue
            val name = tags.optString("name", "").takeIf { it.isNotBlank() } ?: continue
            val kind = touristicKindFromTags(tags) ?: continue
            val dist = computeDistanceMeters(queryLat, queryLon, lat, lon)
            if (dist > maxDistanceMeters) continue
            val rank = touristicKindPriority(kind) * 1_000_000 + dist.toInt()
            if (rank < bestRank) {
                bestRank = rank
                best = TouristicPoi(name, kind, lat, lon, dist)
            }
        }
        return best
    }

    private fun touristicKindFromTags(tags: org.json.JSONObject): String? {
        val natural = tags.optString("natural", "")
        val tourism = tags.optString("tourism", "")
        val mountainPass = tags.optString("mountain_pass", "")
        return when {
            natural == "peak" || natural == "volcano" -> "peak"
            natural == "saddle" || mountainPass == "yes" -> "pass"
            tourism == "viewpoint" -> "viewpoint"
            tourism in listOf("alpine_hut", "wilderness_hut", "picnic_site") -> "shelter"
            else -> null
        }
    }

    /** Lower = preferred. */
    private fun touristicKindPriority(kind: String): Int = when (kind) {
        "peak" -> 0
        "pass" -> 1
        "viewpoint" -> 2
        "shelter" -> 3
        else -> 9
    }

    private fun fetchNearbyTouristicPoi(lat: Double, lon: Double): TouristicPoi? {
        return try {
            val radius = 400
            val query = """
                [out:json][timeout:8];
                (
                  node(around:$radius,$lat,$lon)[natural=peak][name];
                  node(around:$radius,$lat,$lon)[natural=saddle][name];
                  node(around:$radius,$lat,$lon)[natural=volcano][name];
                  node(around:$radius,$lat,$lon)[tourism=viewpoint][name];
                  node(around:$radius,$lat,$lon)[tourism=alpine_hut][name];
                  node(around:$radius,$lat,$lon)[tourism=wilderness_hut][name];
                  node(around:$radius,$lat,$lon)[mountain_pass=yes][name];
                );
                out body;
            """.trimIndent().replace("\n", "")
            val urlStr = "https://overpass-api.de/api/interpreter?data=" + URLEncoder.encode(query, "UTF-8")
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "WhereAmIPersonalApp/1.1 (android)")
            conn.connectTimeout = 5000
            conn.readTimeout = 8000
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().readText()
            selectNearestTouristicPoi(body, lat, lon, maxDistanceMeters = radius.toFloat())
        } catch (_: Exception) {
            null
        }
    }
}

