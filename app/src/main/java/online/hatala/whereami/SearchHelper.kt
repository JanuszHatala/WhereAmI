package online.hatala.whereami

import android.content.Context
import android.location.Address
import android.location.Geocoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

enum class SearchCategory(val label: String, val emoji: String) {
    ALL("All", "🔍"),
    PEAKS_PASSES("Peaks & Passes", "⛰️"),
    TRAILS("Trails & Routes", "🥾"),
    RANGES_PARKS("Ranges & Parks", "🌲"),
    WATER_VALLEYS("Water & Valleys", "🌊"),
    HUTS_POIS("Huts & POIs", "🛖")
}

enum class SearchGeometryType {
    POINT,
    POLYGON,
    POLYLINE
}

data class SearchResultGeometry(
    val polygon: List<GeoPoint>? = null,
    val polylines: List<List<GeoPoint>>? = null
)

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
    val rankScore: Double = 0.0,
    val geometryType: SearchGeometryType = SearchGeometryType.POINT,
    val osmType: String? = null,
    val osmId: Long? = null,
    val elevationMeters: Int? = null,
    val trailColor: TrailColor? = null,
    val outdoorAreaId: String? = null,
    val outdoorRegionLabel: String? = null
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
        recentPlaces: List<SearchResultItem> = emptyList(),
        category: SearchCategory = SearchCategory.ALL
    ): List<SearchResultItem> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.length < 2) return@withContext emptyList()

        val candidates = mutableListOf<PlaceSearchRanker.Candidate>()

        fun addCandidate(c: PlaceSearchRanker.Candidate) {
            if (category == SearchCategory.ALL || matchesCategory(category, c.item)) {
                candidates.add(c)
            }
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

        val photonHits = tryPhoton(trimmed, originLat, originLon, category = category)
        for (hit in photonHits) {
            addCandidate(
                PlaceSearchRanker.Candidate(
                    item = hit,
                    placeType = hit.placeType,
                    importance = hit.importance
                )
            )
        }

        // Overpass outdoor search for trails, peaks, passes, valleys, waters when specific category chosen
        if (category != SearchCategory.ALL && trimmed.length >= 3) {
            val overpassHits = searchOverpassOutdoor(trimmed, category, originLat, originLon)
            for (hit in overpassHits) {
                addCandidate(
                    PlaceSearchRanker.Candidate(
                        item = hit,
                        placeType = hit.placeType,
                        importance = 0.75
                    )
                )
            }
        }

        // Fallback when Photon (and Overpass) yielded no matching candidates
        if (candidates.isEmpty()) {
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

            if (category == SearchCategory.ALL && cityAvailable && isExplicitStreet) {
                val localAddress = "$trimmed, $currentCity"
                fallback += tryGeocoder(context, localAddress)
                fallback += tryNominatim(localAddress, originLat, originLon)
            }

            if (category == SearchCategory.ALL) {
                fallback += tryGeocoder(context, trimmed)
            }
            if (fallback.size < 5) {
                fallback += tryNominatim(trimmed, originLat, originLon)
            }

            if (category == SearchCategory.ALL && fallback.isEmpty() && cityAvailable && hasDigits) {
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

        val ranked = PlaceSearchRanker.rank(
            query = trimmed,
            candidates = candidates,
            originLat = originLat,
            originLon = originLon,
            countryCode = countryCode
        )
        return@withContext enrichSearchResults(context, ranked)
    }

    fun iconForPlaceType(placeType: String?): String {
        val t = placeType?.lowercase(Locale.ROOT) ?: return "📍"
        return when {
            t in listOf("peak", "volcano") -> "🏔️"
            t in listOf("mountain_pass", "saddle", "pass", "mountain_range", "range") -> "⛰️"
            t in listOf("national_park", "landscape_park", "protected_area", "nature_reserve", "forest", "wood") -> "🌲"
            t in listOf("water", "river", "lake", "lake / water", "stream", "waterway", "spring", "waterfall") -> "🌊"
            t == "valley" -> "🏞️"
            t in listOf("alpine_hut", "wilderness_hut", "mountain hut", "shelter") -> "🛖"
            t == "viewpoint" -> "🔭"
            t in listOf("trail", "route", "path", "footway", "cycleway", "mtb", "track") || t.startsWith("trail (") -> "🥾"
            t in listOf("city", "town", "municipality", "borough") -> "🏙️"
            t in listOf("village", "hamlet", "suburb", "locality", "isolated_dwelling", "neighbourhood", "neighborhood") -> "🏘️"
            t in listOf("street", "highway", "road", "residential", "pedestrian", "primary", "secondary", "tertiary", "unclassified", "service") -> "🛣️"
            t == "saved" -> "⭐"
            t == "recent" -> "🕒"
            else -> "📍"
        }
    }

    internal fun matchesCategory(category: SearchCategory, item: SearchResultItem): Boolean {
        if (category == SearchCategory.ALL) return true
        val t = item.placeType?.lowercase(Locale.ROOT) ?: ""
        return when (category) {
            SearchCategory.PEAKS_PASSES ->
                t in listOf("peak", "volcano", "saddle", "mountain_pass", "pass")
            SearchCategory.TRAILS ->
                t in listOf("trail", "route", "path", "footway", "cycleway", "mtb", "track") ||
                    t.startsWith("trail (") ||
                    item.geometryType == SearchGeometryType.POLYLINE
            SearchCategory.RANGES_PARKS ->
                t in listOf("mountain_range", "range", "national_park", "landscape_park", "protected_area", "nature_reserve") ||
                    item.geometryType == SearchGeometryType.POLYGON
            SearchCategory.WATER_VALLEYS ->
                t in listOf("water", "river", "lake", "lake / water", "stream", "waterway", "valley", "spring", "waterfall")
            SearchCategory.HUTS_POIS ->
                t in listOf("alpine_hut", "wilderness_hut", "mountain hut", "shelter", "viewpoint", "camp_site", "picnic_site")
            SearchCategory.ALL -> true
        }
    }

    private suspend fun enrichSearchResults(
        context: Context,
        results: List<SearchResultItem>
    ): List<SearchResultItem> {
        if (results.isEmpty()) return results
        val db = try { TripDatabaseHelper.getInstance(context) } catch (_: Exception) { null }

        // Batch-resolve DEM elevation for mountain objects missing OSM `ele`
        val mountainTypes = setOf("peak", "volcano", "saddle", "mountain_pass", "pass", "alpine_hut", "wilderness_hut", "mountain hut", "viewpoint")
        val missingEleIndices = results.indices.filter { idx ->
            val item = results[idx]
            item.elevationMeters == null && (item.placeType?.lowercase(Locale.ROOT) in mountainTypes)
        }
        val fetchedAltitudes = if (missingEleIndices.isNotEmpty()) {
            try {
                ElevationRepository.fetchAltitudes(
                    points = missingEleIndices.map { results[it].geoPoint },
                    db = db,
                    isSingleLookup = true
                )
            } catch (_: Exception) {
                emptyList()
            }
        } else emptyList()

        val eleByIndex = mutableMapOf<Int, Int>()
        missingEleIndices.forEachIndexed { listIdx, resIdx ->
            val alt = fetchedAltitudes.getOrNull(listIdx)
            if (alt != null && alt > 0.0) {
                eleByIndex[resIdx] = alt.toInt()
            }
        }

        // Resolve offline / cached outdoor regions (mountain range, park, reserve) for each result
        return results.mapIndexed { idx, item ->
            val ctx = try {
                OutdoorContextResolver.resolveOutdoorContext(
                    item.geoPoint.latitude,
                    item.geoPoint.longitude,
                    context,
                    allowNetwork = false
                )
            } catch (_: Exception) {
                null
            }
            val regionNames = buildList {
                ctx?.mountainRange?.name?.takeIf { !it.equals(item.title, ignoreCase = true) }?.let { add(it) }
                ctx?.protectedAreas?.forEach { area ->
                    if (!area.name.equals(item.title, ignoreCase = true)) add(area.name)
                }
            }.distinct()

            item.copy(
                elevationMeters = item.elevationMeters ?: eleByIndex[idx],
                outdoorRegionLabel = regionNames.takeIf { it.isNotEmpty() }?.joinToString(" · ")
            )
        }
    }

    internal fun buildPhotonUrl(
        q: String,
        lat: Double?,
        lon: Double?,
        limit: Int = 10,
        category: SearchCategory = SearchCategory.ALL
    ): String {
        val encoded = URLEncoder.encode(q.trim(), "UTF-8")
        val lang = Locale.getDefault().language.takeIf { it.length == 2 } ?: "en"
        val sb = StringBuilder("https://photon.komoot.io/api/?q=$encoded&limit=$limit&lang=$lang")
        sb.append("&location_bias_scale=$PHOTON_BIAS_SCALE")
        if (lat != null && lon != null) {
            sb.append("&lat=").append(String.format(Locale.US, "%.5f", lat))
            sb.append("&lon=").append(String.format(Locale.US, "%.5f", lon))
        }
        val tags = when (category) {
            SearchCategory.PEAKS_PASSES -> listOf("natural:peak", "natural:volcano", "natural:saddle", "mountain_pass:yes")
            SearchCategory.TRAILS -> listOf("highway:path", "highway:footway", "highway:track", "highway:cycleway", "route:hiking", "route:foot", "route:mtb", "route:bicycle")
            SearchCategory.RANGES_PARKS -> listOf("boundary:national_park", "boundary:protected_area", "leisure:nature_reserve", "natural:mountain_range")
            SearchCategory.WATER_VALLEYS -> listOf("waterway:river", "waterway:stream", "natural:water", "natural:valley", "natural:spring")
            SearchCategory.HUTS_POIS -> listOf("tourism:alpine_hut", "tourism:wilderness_hut", "tourism:shelter", "tourism:viewpoint")
            SearchCategory.ALL -> emptyList()
        }
        for (tag in tags) {
            sb.append("&osm_tag=").append(URLEncoder.encode(tag, "UTF-8"))
        }
        return sb.toString()
    }

    internal fun tryPhoton(
        q: String,
        lat: Double?,
        lon: Double?,
        limit: Int = 10,
        category: SearchCategory = SearchCategory.ALL
    ): List<SearchResultItem> {
        return try {
            val urlStr = buildPhotonUrl(q, lat, lon, limit, category)
            val conn = URL(urlStr).openConnection() as HttpURLConnection
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
            val osmId = props.optLong("osm_id").takeIf { it > 0 }
            val osmType = props.optString("osm_type", "").takeIf { it.isNotBlank() }
            val extraTags = props.optJSONObject("extra_tags")
            val ele = extraTags?.optString("ele")?.toDoubleOrNull()?.toInt()
                ?: props.optString("ele").toDoubleOrNull()?.toInt()
            val geomType = when {
                osmKey in listOf("waterway", "route") -> SearchGeometryType.POLYLINE
                osmKey in listOf("boundary", "leisure", "landuse") || osmValue in listOf("mountain_range", "valley", "water", "national_park", "protected_area", "nature_reserve") -> SearchGeometryType.POLYGON
                else -> SearchGeometryType.POINT
            }
            val rawPropType = props.optString("type", "").takeIf { it.isNotBlank() && it != "other" }
            val type = when {
                osmKey == "mountain_pass" || osmValue == "mountain_pass" || osmValue == "saddle" -> "mountain_pass"
                osmKey in listOf("natural", "tourism", "waterway", "route", "boundary", "leisure") &&
                    osmValue.isNotBlank() && osmValue != "yes" -> osmValue
                rawPropType != null -> rawPropType
                osmValue.isNotBlank() && osmValue != "yes" -> osmValue
                osmKey.isNotBlank() -> osmKey
                else -> null
            }
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
                    importance = importance,
                    geometryType = geomType,
                    osmType = osmType,
                    osmId = osmId,
                    elevationMeters = ele
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
                "https://nominatim.openstreetmap.org/search?q=$encodedQuery&format=json&addressdetails=1&extratags=1&limit=8"
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

            val extratags = obj.optJSONObject("extratags")
            val ele = extratags?.optString("ele")?.toDoubleOrNull()?.toInt()
            val osmElemType = obj.optString("osm_type", "").takeIf { it.isNotBlank() }
            val osmId = obj.optLong("osm_id").takeIf { it > 0 }

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
                    importance = importance,
                    osmType = osmElemType,
                    osmId = osmId,
                    elevationMeters = ele
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
                val countryCode = addr.countryCode?.lowercase(Locale.ROOT) ?: ""

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
        val countryCode = addressObj?.optString("country_code", "")?.lowercase(Locale.ROOT) ?: ""

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

    suspend fun discoverNearbyByCategory(
        context: Context,
        category: SearchCategory,
        originLat: Double?,
        originLon: Double?
    ): List<SearchResultItem> = withContext(Dispatchers.IO) {
        if (originLat == null || originLon == null) return@withContext emptyList()
        when (category) {
            SearchCategory.ALL -> emptyList()
            SearchCategory.RANGES_PARKS -> fetchNearbyOutdoorAreas(originLat, originLon)
            SearchCategory.PEAKS_PASSES -> fetchNearbyOutdoorNodes(originLat, originLon, "peak|saddle|volcano")
            SearchCategory.TRAILS -> fetchNearbyOutdoorTrails(originLat, originLon)
            SearchCategory.WATER_VALLEYS -> fetchNearbyWaterAndValleys(originLat, originLon)
            SearchCategory.HUTS_POIS -> fetchNearbyHutsAndPois(originLat, originLon)
        }
    }

    suspend fun fetchResultGeometry(context: Context, item: SearchResultItem): SearchResultGeometry? = withContext(Dispatchers.IO) {
        val osmId = item.osmId ?: return@withContext null
        val osmType = item.osmType ?: "relation"

        if (item.geometryType == SearchGeometryType.POLYGON) {
            val typeChar = if (osmType.startsWith("w", ignoreCase = true)) "W" else "R"
            val url = "https://nominatim.openstreetmap.org/details?osmtype=$typeChar&osmid=$osmId&format=json&polygon_geojson=1"
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.setRequestProperty("User-Agent", USER_AGENT)
                conn.connectTimeout = 6000
                conn.readTimeout = 6000
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().readText()
                    val obj = JSONObject(body)
                    val geojson = obj.optJSONObject("geometry") ?: obj.optJSONObject("geojson")
                    if (geojson != null) {
                        val poly = BoundaryHelper.parseGeoJsonPolygon(geojson)
                        if (poly != null && poly.size >= 3) {
                            return@withContext SearchResultGeometry(polygon = poly)
                        }
                    }
                }
            } catch (_: Exception) {}
        } else if (item.geometryType == SearchGeometryType.POLYLINE) {
            try {
                val ql = if (osmType.startsWith("w", ignoreCase = true)) {
                    "[out:json][timeout:10]; way($osmId); (._; >;); out skel qt;"
                } else {
                    "[out:json][timeout:12]; relation($osmId); (._; >;); out skel qt;"
                }
                val body = OutdoorContextResolver.executeOverpassQuery(
                    ql = ql,
                    connectTimeoutMs = 6000,
                    readTimeoutMs = 10000,
                    tag = "SEARCH"
                )
                if (body != null) {
                    val parsedTrails = TrailVectorRepository.parseOverpassTrails(body)
                    if (parsedTrails.isNotEmpty()) {
                        val lines = parsedTrails.map { it.points }
                        return@withContext SearchResultGeometry(polylines = lines)
                    }
                }
            } catch (_: Exception) {}
        }
        null
    }

    private fun searchOverpassOutdoor(
        query: String,
        category: SearchCategory,
        originLat: Double?,
        originLon: Double?
    ): List<SearchResultItem> {
        return try {
            val qEscaped = query.replace("\"", "")
            val areaClause = if (originLat != null && originLon != null) "(around:80000,$originLat,$originLon)" else ""
            val filter = when (category) {
                SearchCategory.PEAKS_PASSES -> """node[natural~"^(peak|saddle|volcano)$"]$areaClause[name~"$qEscaped",i]; out center 12;"""
                SearchCategory.TRAILS -> """rel[type=route][route~"^(hiking|foot|mtb|bicycle|canoe|kayak)$"]$areaClause[name~"$qEscaped",i]; out center 12;"""
                SearchCategory.RANGES_PARKS -> """(rel[boundary~"^(national_park|protected_area)$"]$areaClause[name~"$qEscaped",i]; node[natural~"^(mountain_range|valley|massif)$"]$areaClause[name~"$qEscaped",i]; way[natural~"^(mountain_range|valley|massif)$"]$areaClause[name~"$qEscaped",i]; rel[natural~"^(mountain_range|valley|massif)$"]$areaClause[name~"$qEscaped",i];); out center 15;"""
                SearchCategory.WATER_VALLEYS -> """(way[waterway=river]$areaClause[name~"$qEscaped",i]; rel[waterway=river]$areaClause[name~"$qEscaped",i]; way[natural=water]$areaClause[name~"$qEscaped",i]; rel[natural=water]$areaClause[name~"$qEscaped",i]; way[natural=valley]$areaClause[name~"$qEscaped",i]; rel[natural=valley]$areaClause[name~"$qEscaped",i];); out center 12;"""
                SearchCategory.HUTS_POIS -> """(node[tourism~"^(alpine_hut|wilderness_hut|viewpoint)$"]$areaClause[name~"$qEscaped",i]; way[tourism~"^(alpine_hut|wilderness_hut)$"]$areaClause[name~"$qEscaped",i];); out center 12;"""
                else -> return emptyList()
            }
            val ql = "[out:json][timeout:8]; $filter"
            val body = OutdoorContextResolver.executeOverpassQuery(
                ql = ql,
                connectTimeoutMs = 5000,
                readTimeoutMs = 8000,
                tag = "SEARCH"
            ) ?: return emptyList()
            parseOverpassSearchResults(body, originLat, originLon)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun fetchNearbyOutdoorAreas(lat: Double, lon: Double): List<SearchResultItem> {
        return try {
            val ql = """[out:json][timeout:10]; (relation(around:50000,$lat,$lon)[boundary~"^(national_park|protected_area)$"][name]; way(around:25000,$lat,$lon)[leisure="nature_reserve"][name]; node(around:40000,$lat,$lon)[natural~"^(mountain_range|massif|valley)$"][name]; way(around:40000,$lat,$lon)[natural~"^(mountain_range|massif|valley)$"][name]; relation(around:40000,$lat,$lon)[natural~"^(mountain_range|massif|valley)$"][name];); out center 20;"""
            val body = OutdoorContextResolver.executeOverpassQuery(
                ql = ql,
                connectTimeoutMs = 5000,
                readTimeoutMs = 10000,
                tag = "SEARCH"
            ) ?: return emptyList()
            parseOverpassSearchResults(body, lat, lon)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun fetchNearbyOutdoorNodes(lat: Double, lon: Double, naturalFilter: String): List<SearchResultItem> {
        return try {
            val ql = """[out:json][timeout:8]; node(around:25000,$lat,$lon)[natural~"^($naturalFilter)$"][name]; out 15;"""
            val body = OutdoorContextResolver.executeOverpassQuery(
                ql = ql,
                connectTimeoutMs = 5000,
                readTimeoutMs = 8000,
                tag = "SEARCH"
            ) ?: return emptyList()
            parseOverpassSearchResults(body, lat, lon)
        } catch (_: Exception) { emptyList() }
    }

    private fun fetchNearbyOutdoorTrails(lat: Double, lon: Double): List<SearchResultItem> {
        return try {
            val ql = """[out:json][timeout:8]; rel(around:15000,$lat,$lon)[type=route][route~"^(hiking|foot|mtb|bicycle|canoe|kayak)$"][name]; out center 15;"""
            val body = OutdoorContextResolver.executeOverpassQuery(
                ql = ql,
                connectTimeoutMs = 5000,
                readTimeoutMs = 8000,
                tag = "SEARCH"
            ) ?: return emptyList()
            parseOverpassSearchResults(body, lat, lon)
        } catch (_: Exception) { emptyList() }
    }

    private fun fetchNearbyWaterAndValleys(lat: Double, lon: Double): List<SearchResultItem> {
        return try {
            val ql = """[out:json][timeout:8]; (way(around:15000,$lat,$lon)[natural~"^(water|valley)$"][name]; way(around:15000,$lat,$lon)[waterway=river][name];); out center 15;"""
            val body = OutdoorContextResolver.executeOverpassQuery(
                ql = ql,
                connectTimeoutMs = 5000,
                readTimeoutMs = 8000,
                tag = "SEARCH"
            ) ?: return emptyList()
            parseOverpassSearchResults(body, lat, lon)
        } catch (_: Exception) { emptyList() }
    }

    private fun fetchNearbyHutsAndPois(lat: Double, lon: Double): List<SearchResultItem> {
        return try {
            val ql = """[out:json][timeout:8]; (node(around:20000,$lat,$lon)[tourism~"^(alpine_hut|wilderness_hut|viewpoint)$"][name]; way(around:20000,$lat,$lon)[tourism~"^(alpine_hut|wilderness_hut)$"][name];); out center 15;"""
            val body = OutdoorContextResolver.executeOverpassQuery(
                ql = ql,
                connectTimeoutMs = 5000,
                readTimeoutMs = 8000,
                tag = "SEARCH"
            ) ?: return emptyList()
            parseOverpassSearchResults(body, lat, lon)
        } catch (_: Exception) { emptyList() }
    }

    private fun parseOverpassSearchResults(body: String, originLat: Double?, originLon: Double?): List<SearchResultItem> {
        val root = JSONObject(body)
        val elements = root.optJSONArray("elements") ?: return emptyList()
        val list = mutableListOf<SearchResultItem>()

        for (i in 0 until elements.length()) {
            val el = elements.optJSONObject(i) ?: continue
            val id = el.optLong("id")
            val type = el.optString("type")
            val tags = el.optJSONObject("tags") ?: continue
            val name = tags.optString("name").takeIf { it.isNotBlank() } ?: continue

            val lat = if (el.has("lat")) el.optDouble("lat", Double.NaN)
                      else el.optJSONObject("center")?.optDouble("lat", Double.NaN) ?: Double.NaN
            val lon = if (el.has("lon")) el.optDouble("lon", Double.NaN)
                      else el.optJSONObject("center")?.optDouble("lon", Double.NaN) ?: Double.NaN
            if (lat.isNaN() || lon.isNaN()) continue

            val dist = if (originLat != null && originLon != null) computeDistanceMeters(originLat, originLon, lat, lon) else null
            val ele = tags.optString("ele").toDoubleOrNull()?.toInt()

            val geomType = when {
                tags.has("route") || tags.optString("waterway") == "river" -> SearchGeometryType.POLYLINE
                tags.has("boundary") || tags.optString("natural") in listOf("water", "valley", "mountain_range") -> SearchGeometryType.POLYGON
                else -> SearchGeometryType.POINT
            }

            val natural = tags.optString("natural")
            val tourism = tags.optString("tourism")
            val route = tags.optString("route")
            val waterway = tags.optString("waterway")

            val kindLabel = when {
                natural == "peak" -> "Peak"
                natural == "saddle" || tags.optString("mountain_pass") == "yes" -> "Pass"
                natural == "valley" -> "Valley"
                natural == "water" -> "Lake / Water"
                waterway == "river" -> "River"
                tourism in listOf("alpine_hut", "wilderness_hut") -> "Mountain Hut"
                tourism == "viewpoint" -> "Viewpoint"
                route.isNotBlank() -> "Trail (${route.uppercase(Locale.ROOT)})"
                tags.optString("boundary") == "national_park" -> "National Park"
                tags.optString("boundary") == "protected_area" -> "Landscape Park"
                else -> "Outdoor POI"
            }

            val title = name
            val subtitle = dist?.let { "$kindLabel · ${LiveGuest.formatDistance(it)}" } ?: kindLabel

            val trailColor = if (tags.has("osmc:symbol") || tags.has("colour")) {
                OutdoorContextResolver.parseTrailColor(tags.optString("osmc:symbol"), tags.optString("colour"))
            } else null

            list.add(
                SearchResultItem(
                    title = title,
                    subtitle = subtitle,
                    geoPoint = GeoPoint(lat, lon),
                    distanceMeters = dist,
                    placeType = kindLabel.lowercase(Locale.ROOT),
                    source = "overpass",
                    importance = 0.75,
                    geometryType = geomType,
                    osmType = type,
                    osmId = id,
                    elevationMeters = ele,
                    trailColor = trailColor
                )
            )
        }
        return list.sortedBy { it.distanceMeters ?: Float.MAX_VALUE }
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
            val body = OutdoorContextResolver.executeOverpassQuery(
                ql = query,
                connectTimeoutMs = 5000,
                readTimeoutMs = 8000,
                tag = "SEARCH"
            ) ?: return null
            selectNearestTouristicPoi(body, lat, lon, maxDistanceMeters = radius.toFloat())
        } catch (_: Exception) {
            null
        }
    }
}

