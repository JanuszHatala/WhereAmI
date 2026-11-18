package online.hatala.whereami

import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs

data class TripRouteSnapshot(
    val distanceMeters: Double,
    val placesVisited: List<VisitedPlace>
)

data class PlaceDetailsSnapshot(
    val capturedAtMs: Long,
    val primaryPlace: PlaceInfo?,
    val secondaryPlace: PlaceInfo?,
    val hierarchySubtitle: String,
    val lat: Double?,
    val lng: Double?,
    val altitudeM: Double?,
    val accuracyM: Float?,
    val speedMs: Float?,
    val dataFreshness: PlaceDataFreshness,
    val staleDistanceMeters: Float?,
    val outdoorContext: OutdoorContext?,
    val tripRoute: TripRouteSnapshot?
)

data class NominatimExtendedMetadata(
    val postcode: String? = null,
    val countryCodeIso2: String? = null,
    val iso3166Lvl4: String? = null,
    val terytSimc: String? = null,
    val municipalityCode: String? = null,
    val osmType: String? = null,
    val osmId: Long? = null,
    val osmClass: String? = null,
    val osmPlaceType: String? = null,
    val placeRank: Int? = null,
    val adminLevel: Int? = null,
    val wikidataId: String? = null,
    val wikipediaTag: String? = null,
    val population: String? = null,
    val displayName: String? = null
) {
    val hasAnyCode: Boolean
        get() = listOfNotNull(
            postcode,
            iso3166Lvl4,
            terytSimc,
            municipalityCode,
            osmId?.toString(),
            wikidataId,
            wikipediaTag,
            population
        ).isNotEmpty()

    companion object {
        fun fromNominatimJson(root: JSONObject): NominatimExtendedMetadata {
            val addr = root.optJSONObject("address")
            val extratags = root.optJSONObject("extratags")

            fun addrStr(key: String): String? = addr?.optString(key)?.takeIf { it.isNotBlank() }
            fun extraStr(key: String): String? = extratags?.optString(key)?.takeIf { it.isNotBlank() }

            val isoLvl4 = addrStr("ISO3166-2-lvl4")
                ?: addrStr("ISO3166-2-lvl6")
                ?: addrStr("ISO3166-2-lvl3")
            val terytSimc = extraStr("teryt:simc")
                ?: extraStr("ref:teryt")
                ?: extraStr("teryt:terc")
            val municipalityCode = extraStr("ref:lau")
                ?: extraStr("ref:nuts")
                ?: extraStr("ref:ine")
                ?: extraStr("de:amtlicher_gemeindeschluessel")
                ?: extraStr("ref:INSEE")

            val osmTypeRaw = root.optString("osm_type").takeIf { it.isNotBlank() }
            val osmIdVal = root.optLong("osm_id", -1L).takeIf { it > 0L }
            val placeRankVal = root.optInt("place_rank", -1).takeIf { it > 0 }
            val adminLevelVal = extraStr("admin_level")?.toIntOrNull()

            return NominatimExtendedMetadata(
                postcode = addrStr("postcode"),
                countryCodeIso2 = addrStr("country_code")?.uppercase(Locale.ROOT),
                iso3166Lvl4 = isoLvl4,
                terytSimc = terytSimc,
                municipalityCode = municipalityCode,
                osmType = osmTypeRaw,
                osmId = osmIdVal,
                osmClass = root.optString("class").takeIf { it.isNotBlank() },
                osmPlaceType = root.optString("type").takeIf { it.isNotBlank() },
                placeRank = placeRankVal,
                adminLevel = adminLevelVal,
                wikidataId = extraStr("wikidata"),
                wikipediaTag = extraStr("wikipedia"),
                population = extraStr("population"),
                displayName = root.optString("display_name").takeIf { it.isNotBlank() }
            )
        }
    }
}

object CoordinateFormatHelper {
    fun formatDecimal(lat: Double, lng: Double): String =
        String.format(Locale.US, "%.5f, %.5f", lat, lng)

    fun formatDms(lat: Double, lng: Double): String {
        val latHemisphere = if (lat >= 0.0) "N" else "S"
        val lngHemisphere = if (lng >= 0.0) "E" else "W"
        return "${formatAxisDms(abs(lat), latHemisphere)} ${formatAxisDms(abs(lng), lngHemisphere)}"
    }

    private fun formatAxisDms(absDeg: Double, hemisphere: String): String {
        val totalSeconds = absDeg * 3600.0
        val deg = (totalSeconds / 3600.0).toInt()
        val remAfterDeg = totalSeconds - deg * 3600.0
        val min = (remAfterDeg / 60.0).toInt()
        val sec = (remAfterDeg - min * 60.0).coerceAtLeast(0.0)
        return String.format(Locale.US, "%d°%02d'%04.1f\"%s", deg, min, sec, hemisphere)
    }
}
