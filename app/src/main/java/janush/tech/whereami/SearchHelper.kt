package janush.tech.whereami

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
    val municipalityName: String? = null
)

object SearchHelper {

    suspend fun searchLocation(
        context: Context,
        query: String,
        currentCity: String? = null
    ): List<SearchResultItem> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.length < 2) return@withContext emptyList()

        val results = mutableListOf<SearchResultItem>()
        val seenCoords = mutableSetOf<String>()

        fun addUniqueResult(item: SearchResultItem) {
            val key = String.format(Locale.US, "%.5f,%.5f", item.geoPoint.latitude, item.geoPoint.longitude)
            if (seenCoords.add(key)) {
                results.add(item)
            }
        }

        // Helper function to query Android Geocoder
        fun tryGeocoder(q: String) {
            try {
                val geocoder = Geocoder(context, Locale.getDefault())
                @Suppress("DEPRECATION")
                val addresses: List<Address>? = geocoder.getFromLocationName(q, 5)
                if (!addresses.isNullOrEmpty()) {
                    for (addr in addresses) {
                        val street = addr.thoroughfare
                        val feature = addr.featureName
                        val subThoroughfare = addr.subThoroughfare // house number

                        val title = when {
                            !street.isNullOrBlank() && !subThoroughfare.isNullOrBlank() -> "$street $subThoroughfare"
                            !street.isNullOrBlank() && !feature.isNullOrBlank() && feature != street -> "$street $feature"
                            !street.isNullOrBlank() -> street
                            !feature.isNullOrBlank() -> feature
                            !addr.locality.isNullOrBlank() -> addr.locality
                            else -> trimmed
                        }

                        val subtitle = listOfNotNull(
                            addr.locality?.takeIf { it != title },
                            addr.subAdminArea,
                            addr.adminArea,
                            addr.countryName
                        ).distinct().joinToString(", ")

                        addUniqueResult(
                            SearchResultItem(
                                title = title,
                                subtitle = subtitle,
                                geoPoint = GeoPoint(addr.latitude, addr.longitude)
                            )
                        )
                    }
                }
            } catch (_: Exception) {}
        }

        // Helper function to query OpenStreetMap Nominatim
        fun tryNominatim(q: String) {
            try {
                val encodedQuery = URLEncoder.encode(q.trim(), "UTF-8")
                val urlStr = "https://nominatim.openstreetmap.org/search?q=$encodedQuery&format=json&addressdetails=1&limit=5"
                val conn = URL(urlStr).openConnection() as HttpURLConnection
                conn.setRequestProperty("User-Agent", "WhereAmIPersonalApp/1.1 (android)")
                conn.connectTimeout = 6000
                conn.readTimeout = 6000

                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().readText()
                    val array = JSONArray(body)
                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        val displayName = obj.optString("display_name", "")
                        val lat = obj.getDouble("lat")
                        val lon = obj.getDouble("lon")

                        val addressObj = obj.optJSONObject("address")
                        val road = addressObj?.optString("road")?.takeIf { it.isNotBlank() }
                        val houseNumber = addressObj?.optString("house_number")?.takeIf { it.isNotBlank() }
                        val city = addressObj?.optString("city")
                            ?: addressObj?.optString("town")
                            ?: addressObj?.optString("village")
                            ?: addressObj?.optString("municipality")

                        val title = when {
                            road != null && houseNumber != null -> "$road $houseNumber"
                            road != null -> road
                            else -> {
                                val parts = displayName.split(", ")
                                parts.firstOrNull() ?: displayName
                            }
                        }

                        val subtitle = listOfNotNull(
                            city?.takeIf { it != title },
                            addressObj?.optString("county")?.takeIf { it.isNotBlank() },
                            addressObj?.optString("state")?.takeIf { it.isNotBlank() },
                            addressObj?.optString("country")?.takeIf { it.isNotBlank() }
                        ).distinct().joinToString(", ")

                        addUniqueResult(
                            SearchResultItem(
                                title = title,
                                subtitle = if (subtitle.isNotBlank()) subtitle else displayName,
                                geoPoint = GeoPoint(lat, lon)
                            )
                        )
                    }
                }
            } catch (_: Exception) {}
        }

        val hasDigits = trimmed.any { it.isDigit() }
        val isExplicitStreet = trimmed.startsWith("ul.", ignoreCase = true) ||
                trimmed.startsWith("ul ", ignoreCase = true) ||
                trimmed.startsWith("ulica", ignoreCase = true) ||
                trimmed.startsWith("aleja", ignoreCase = true) ||
                trimmed.startsWith("al.", ignoreCase = true) ||
                trimmed.startsWith("pl.", ignoreCase = true) ||
                trimmed.startsWith("plac", ignoreCase = true)

        val cityAvailable = !currentCity.isNullOrBlank() && !trimmed.contains(currentCity, ignoreCase = true)

        // 1. If user explicitly specified a street prefix and didn't mention a city, prioritize current locality
        if (cityAvailable && isExplicitStreet) {
            val localAddress = "$trimmed, $currentCity"
            tryGeocoder(localAddress)
            tryNominatim(localAddress)
        }

        // 2. Search exact query globally (e.g. other cities "Kraków", "Bielsko-Biała", "Warszawa", or addresses with city)
        tryGeocoder(trimmed)
        if (results.size < 5) {
            tryNominatim(trimmed)
        }

        // 3. If query contains street digits (e.g. "Zielona 92") and was not found globally, try in current locality
        if (results.isEmpty() && cityAvailable && hasDigits) {
            val localAddress = "$trimmed, $currentCity"
            tryGeocoder(localAddress)
            tryNominatim(localAddress)
        }

        return@withContext results
    }

    suspend fun reverseGeocode(
        context: Context,
        geoPoint: GeoPoint
    ): SearchResultItem = withContext(Dispatchers.IO) {
        val coordsStr = String.format(Locale.US, "%.5f, %.5f", geoPoint.latitude, geoPoint.longitude)

        // 1. Try Nominatim reverse geocoding first (provides exact OSM place classification, gmina, and natural terrain tags)
        try {
            val urlStr = "https://nominatim.openstreetmap.org/reverse?format=json&lat=${geoPoint.latitude}&lon=${geoPoint.longitude}&zoom=18&addressdetails=1"
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "WhereAmIPersonalApp/1.1 (android)")
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            if (conn.responseCode == 200) {
                val responseText = conn.inputStream.bufferedReader().readText()
                val parsed = parseNominatimReverse(responseText, geoPoint.latitude, geoPoint.longitude)
                if (parsed.localityName != null || parsed.title != coordsStr) {
                    return@withContext parsed
                }
            }
        } catch (_: Exception) {}

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

        val isNaturalTerrain = osmClass in listOf("natural", "landuse", "leisure", "waterway") ||
                osmType in listOf("forest", "wood", "peak", "scrub", "heath", "grass", "meadow", "fell", "wetland")

        var isOffRoad = isNaturalTerrain
        if (!snappedLat.isNaN() && !snappedLon.isNaN()) {
            val dist = computeDistanceMeters(
                queryLat, queryLon,
                snappedLat, snappedLon
            )
            if (dist > 150f) {
                isOffRoad = true
            }
        }

        val title = when {
            isOffRoad -> coordsStr
            road != null && houseNum != null -> "$road $houseNum"
            road != null -> road
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
}

