package com.example.whereiam

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
    val geoPoint: GeoPoint
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
                conn.setRequestProperty("User-Agent", "WhereIAmPersonalApp/1.1 (android)")
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
        val isStreetQuery = trimmed.startsWith("ul", ignoreCase = true) || hasDigits
        val cityAvailable = !currentCity.isNullOrBlank() && !trimmed.contains(currentCity, ignoreCase = true)

        // If the query looks like a street or house number and we have a current locality,
        // search in the current locality first to avoid resolving "Zielna" to Suwałki instead of Czaniec!
        if (cityAvailable && (isStreetQuery || trimmed.length <= 15)) {
            val localAddress = "$trimmed, $currentCity"
            tryGeocoder(localAddress)
            if (results.isEmpty()) {
                tryNominatim(localAddress)
            }
        }

        // Search global query if nothing found locally or if query doesn't match local context
        if (results.isEmpty()) {
            tryGeocoder(trimmed)
            if (results.isEmpty()) {
                tryNominatim(trimmed)
            }
        }

        // Fallback: if user typed something generic and we haven't tried with city yet
        if (results.isEmpty() && cityAvailable) {
            val fallbackLocal = "$trimmed, $currentCity"
            tryGeocoder(fallbackLocal)
            if (results.isEmpty()) {
                tryNominatim(fallbackLocal)
            }
        }

        return@withContext results
    }

    suspend fun reverseGeocode(
        context: Context,
        geoPoint: GeoPoint
    ): SearchResultItem = withContext(Dispatchers.IO) {
        // 1. Try Android Geocoder
        try {
            val geocoder = Geocoder(context, Locale.getDefault())
            @Suppress("DEPRECATION")
            val addresses = geocoder.getFromLocation(geoPoint.latitude, geoPoint.longitude, 1)
            if (!addresses.isNullOrEmpty()) {
                val addr = addresses[0]
                val street = addr.thoroughfare
                val houseNum = addr.subThoroughfare
                val locality = addr.locality ?: addr.subLocality ?: addr.subAdminArea ?: "Point on Map"
                val title = when {
                    !street.isNullOrBlank() && !houseNum.isNullOrBlank() -> "$street $houseNum"
                    !street.isNullOrBlank() -> street
                    else -> locality
                }
                val subtitle = listOfNotNull(
                    locality.takeIf { it != title },
                    addr.adminArea,
                    addr.countryName
                ).distinct().joinToString(", ")
                return@withContext SearchResultItem(
                    title = title,
                    subtitle = subtitle,
                    geoPoint = geoPoint
                )
            }
        } catch (_: Exception) {}

        // 2. Try Nominatim reverse geocoding fallback
        try {
            val urlStr = "https://nominatim.openstreetmap.org/reverse?format=json&lat=${geoPoint.latitude}&lon=${geoPoint.longitude}&zoom=18&addressdetails=1"
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "WhereIAmPersonalApp/1.1")
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            if (conn.responseCode == 200) {
                val responseText = conn.inputStream.bufferedReader().readText()
                val json = org.json.JSONObject(responseText)
                val addressObj = json.optJSONObject("address")
                val road = addressObj?.optString("road")?.takeIf { it.isNotBlank() }
                val houseNum = addressObj?.optString("house_number")?.takeIf { it.isNotBlank() }
                val city = addressObj?.optString("city")?.takeIf { it.isNotBlank() }
                    ?: addressObj?.optString("town")?.takeIf { it.isNotBlank() }
                    ?: addressObj?.optString("village")?.takeIf { it.isNotBlank() }
                    ?: addressObj?.optString("municipality")?.takeIf { it.isNotBlank() }
                    ?: "Point on Map"
                val title = when {
                    road != null && houseNum != null -> "$road $houseNum"
                    road != null -> road
                    else -> city
                }
                val subtitle = listOfNotNull(
                    city.takeIf { it != title },
                    addressObj?.optString("county")?.takeIf { it.isNotBlank() },
                    addressObj?.optString("state")?.takeIf { it.isNotBlank() },
                    addressObj?.optString("country")?.takeIf { it.isNotBlank() }
                ).distinct().joinToString(", ")
                return@withContext SearchResultItem(
                    title = title,
                    subtitle = subtitle,
                    geoPoint = geoPoint
                )
            }
        } catch (_: Exception) {}

        val coordsTitle = String.format(Locale.US, "Location (%.5f, %.5f)", geoPoint.latitude, geoPoint.longitude)
        return@withContext SearchResultItem(title = coordsTitle, subtitle = "", geoPoint = geoPoint)
    }
}

