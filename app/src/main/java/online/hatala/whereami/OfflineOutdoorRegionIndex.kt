package online.hatala.whereami

import android.content.Context
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.io.InputStream
import java.util.Locale

object OfflineOutdoorRegionIndex {

    @Volatile
    private var isInitialized = false
    private val regionList = mutableListOf<OutdoorAreaWithPolygon>()

    fun initIfNeeded(context: Context) {
        if (isInitialized) return
        synchronized(this) {
            if (isInitialized) return
            try {
                context.assets.open("outdoor_regions_pl_border.json").use { stream ->
                    parseGeoJson(stream)
                }
            } catch (e: Exception) {
                TelemetryLogger.log("OUTDOOR", "Failed to load offline outdoor regions asset: ${e.message}")
            }
            isInitialized = true
        }
    }

    fun loadFromJsonString(jsonStr: String) {
        parseGeoJson(jsonStr.byteInputStream())
        isInitialized = true
    }

    internal fun parseGeoJson(stream: InputStream) {
        val text = stream.bufferedReader().use { it.readText() }
        val root = JSONObject(text)
        val features = root.optJSONArray("features") ?: return
        val parsed = mutableListOf<OutdoorAreaWithPolygon>()

        for (i in 0 until features.length()) {
            val feat = features.optJSONObject(i) ?: continue
            val props = feat.optJSONObject("properties") ?: continue
            val id = props.optString("id", "")
            val name = props.optString("name", "")
            val nameEn = props.optString("name_en").takeIf { it.isNotBlank() }
            val kindStr = props.optString("kind", OutdoorAreaKind.MOUNTAIN_RANGE.name)
            val kind = try {
                OutdoorAreaKind.valueOf(kindStr)
            } catch (_: Exception) {
                OutdoorAreaKind.MOUNTAIN_RANGE
            }
            val osmRelId = props.optLong("osm_relation_id").takeIf { it > 0 }

            val geom = feat.optJSONObject("geometry") ?: continue
            val coords = geom.optJSONArray("coordinates") ?: continue
            val ring = coords.optJSONArray(0) ?: continue

            val points = mutableListOf<GeoPoint>()
            var minLat = 90.0
            var maxLat = -90.0
            var minLon = 180.0
            var maxLon = -180.0

            for (j in 0 until ring.length()) {
                val pair = ring.optJSONArray(j) ?: continue
                if (pair.length() < 2) continue
                val lon = pair.getDouble(0)
                val lat = pair.getDouble(1)
                points.add(GeoPoint(lat, lon))
                if (lat < minLat) minLat = lat
                if (lat > maxLat) maxLat = lat
                if (lon < minLon) minLon = lon
                if (lon > maxLon) maxLon = lon
            }

            if (points.size >= 3) {
                val centerLat = (minLat + maxLat) / 2.0
                val centerLon = (minLon + maxLon) / 2.0
                val area = OutdoorArea(id, name, nameEn, kind, osmRelId, centerLat, centerLon)
                parsed.add(OutdoorAreaWithPolygon(area, minLat, maxLat, minLon, maxLon, points))
            }
        }

        synchronized(regionList) {
            regionList.clear()
            regionList.addAll(parsed)
        }
    }

    fun findContainingRegions(lat: Double, lon: Double): List<OutdoorAreaWithPolygon> {
        val matches = mutableListOf<OutdoorAreaWithPolygon>()
        val listCopy = synchronized(regionList) { ArrayList(regionList) }
        for (item in listCopy) {
            if (item.contains(lat, lon)) {
                matches.add(item)
            }
        }
        return matches
    }

    fun getPolygonByIdOrName(query: String): OutdoorAreaWithPolygon? {
        val clean = query.trim().lowercase(Locale.ROOT)
        val listCopy = synchronized(regionList) { ArrayList(regionList) }
        return listCopy.firstOrNull {
            it.area.id.equals(clean, ignoreCase = true) ||
            it.area.name.lowercase(Locale.ROOT) == clean ||
            (it.area.nameEn != null && it.area.nameEn.lowercase(Locale.ROOT) == clean) ||
            it.area.name.lowercase(Locale.ROOT).contains(clean)
        }
    }

    fun getAllRegions(): List<OutdoorAreaWithPolygon> {
        return synchronized(regionList) { ArrayList(regionList) }
    }
}
