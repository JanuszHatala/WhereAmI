package online.hatala.whereami

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class TrailVectorSegment(
    val id: Long,
    val name: String,
    val ref: String? = null,
    val kind: TrailRouteKind = TrailRouteKind.HIKING,
    val color: TrailColor = TrailColor.NONE,
    val network: String? = null,
    val points: List<GeoPoint>
)

object TrailVectorRepository {

    private const val USER_AGENT = "WhereAmI-Android/2.3 (https://github.com/jh/WhereAmI)"
    private const val TILE_SIZE_DEG = 0.08
    private val memoryCache = ConcurrentHashMap<String, List<TrailVectorSegment>>()
    private val fetchMutex = Mutex()
    private val pendingTiles = ConcurrentHashMap.newKeySet<String>()

    fun toTileKey(lat: Double, lon: Double): String {
        val latIdx = (lat / TILE_SIZE_DEG).toInt()
        val lonIdx = (lon / TILE_SIZE_DEG).toInt()
        return "tile_${latIdx}_${lonIdx}"
    }

    private fun getCacheDir(context: Context): File {
        val dir = File(context.cacheDir, "trail_vectors")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    suspend fun getTrailsForBoundingBox(
        context: Context,
        bbox: BoundingBox,
        allowNetwork: Boolean = true
    ): List<TrailVectorSegment> = withContext(Dispatchers.IO) {
        val minLat = bbox.latSouth
        val maxLat = bbox.latNorth
        val minLon = bbox.lonWest
        val maxLon = bbox.lonEast

        val results = mutableListOf<TrailVectorSegment>()
        val startLatIdx = (minLat / TILE_SIZE_DEG).toInt()
        val endLatIdx = (maxLat / TILE_SIZE_DEG).toInt()
        val startLonIdx = (minLon / TILE_SIZE_DEG).toInt()
        val endLonIdx = (maxLon / TILE_SIZE_DEG).toInt()

        for (latIdx in startLatIdx..endLatIdx) {
            for (lonIdx in startLonIdx..endLonIdx) {
                val key = "tile_${latIdx}_${lonIdx}"
                val tileSouth = latIdx * TILE_SIZE_DEG
                val tileNorth = (latIdx + 1) * TILE_SIZE_DEG
                val tileWest = lonIdx * TILE_SIZE_DEG
                val tileEast = (lonIdx + 1) * TILE_SIZE_DEG

                val tileTrails = getOrFetchTile(
                    context, key,
                    tileSouth, tileWest, tileNorth, tileEast,
                    allowNetwork
                )
                results.addAll(tileTrails)
            }
        }
        return@withContext results.distinctBy { it.id.toString() + "_" + it.points.firstOrNull() }
    }

    private suspend fun getOrFetchTile(
        context: Context,
        key: String,
        s: Double, w: Double, n: Double, e: Double,
        allowNetwork: Boolean
    ): List<TrailVectorSegment> {
        memoryCache[key]?.let { return it }

        // Disk cache
        val file = File(getCacheDir(context), "$key.json")
        if (file.exists()) {
            try {
                val text = file.readText()
                val parsed = parseTileJson(text)
                memoryCache[key] = parsed
                return parsed
            } catch (_: Exception) {}
        }

        if (!allowNetwork) return emptyList()
        if (!pendingTiles.add(key)) return emptyList()

        return try {
            fetchMutex.withLock {
                // Re-check after lock
                memoryCache[key]?.let { return it }

                val ql = """
                    [out:json][timeout:15];
                    (
                      way["highway"~"^(path|footway|track|cycleway)$"]["osmc:symbol"]($s,$w,$n,$e);
                      way["highway"~"^(path|footway|track|cycleway)$"]["sac_scale"]($s,$w,$n,$e);
                      way["highway"~"^(path|footway|track|cycleway)$"]["educational"="yes"]($s,$w,$n,$e);
                      relation["type"="route"]["route"~"^(hiking|foot|mtb|bicycle|canoe|kayak)$"]($s,$w,$n,$e);
                      way(r)($s,$w,$n,$e);
                    );
                    out tags geom;
                """.trimIndent().replace("\n", "")

                val body = OutdoorContextResolver.executeOverpassQuery(
                    ql = ql,
                    connectTimeoutMs = 8000,
                    readTimeoutMs = 15000,
                    tag = "TRAILS"
                )
                if (body != null) {
                    val parsed = parseOverpassTrails(body)
                    memoryCache[key] = parsed
                    saveTileJson(file, parsed)
                    return parsed
                }

                // Fallback: bounded OSM 0.6 /api/0.6/map XML around tile center when all Overpass mirrors fail
                val cLat = (s + n) / 2.0
                val cLon = (w + e) / 2.0
                val halfLat = minOf((n - s) / 2.0, 0.015)
                val halfLon = minOf((e - w) / 2.0, 0.02)
                val xml = OutdoorContextResolver.fetchOsmMapXml(
                    cLat - halfLat, cLon - halfLon, cLat + halfLat, cLon + halfLon,
                    connectTimeoutMs = 6000,
                    readTimeoutMs = 12000
                )
                if (xml != null) {
                    val parsedXml = parseOsmMapXmlTrails(xml)
                    if (parsedXml.isNotEmpty()) {
                        memoryCache[key] = parsedXml
                    }
                    return parsedXml
                }
                emptyList()
            }
        } catch (e: Exception) {
            TelemetryLogger.log("TRAILS", "Fetch tile $key error: ${e.message}")
            emptyList()
        } finally {
            pendingTiles.remove(key)
        }
    }

    /**
     * Parses OSM 0.6 `/api/0.6/map` XML elements into [TrailVectorSegment]s when Overpass mirrors
     * are unavailable or rate-limited.
     */
    fun parseOsmMapXmlTrails(xmlStr: String): List<TrailVectorSegment> {
        return try {
            val dbf = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            try {
                dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            } catch (_: Exception) {}
            val doc = dbf.newDocumentBuilder().parse(org.xml.sax.InputSource(java.io.StringReader(xmlStr)))
            val nodesMap = HashMap<Long, GeoPoint>()
            val wayNodeRefs = HashMap<Long, List<Long>>()
            val wayTagsMap = HashMap<Long, Map<String, String>>()
            val segments = mutableListOf<TrailVectorSegment>()

            val nodeEls = doc.getElementsByTagName("node")
            for (i in 0 until nodeEls.length) {
                val el = nodeEls.item(i) as? org.w3c.dom.Element ?: continue
                val id = el.getAttribute("id").toLongOrNull() ?: continue
                val lat = el.getAttribute("lat").toDoubleOrNull() ?: continue
                val lon = el.getAttribute("lon").toDoubleOrNull() ?: continue
                nodesMap[id] = GeoPoint(lat, lon)
            }

            fun readTags(parent: org.w3c.dom.Element): Map<String, String> {
                val map = HashMap<String, String>()
                val children = parent.childNodes
                for (i in 0 until children.length) {
                    val child = children.item(i) as? org.w3c.dom.Element ?: continue
                    if (child.tagName == "tag") {
                        val k = child.getAttribute("k")
                        val v = child.getAttribute("v")
                        if (k.isNotEmpty()) map[k] = v
                    }
                }
                return map
            }

            val wayEls = doc.getElementsByTagName("way")
            for (i in 0 until wayEls.length) {
                val el = wayEls.item(i) as? org.w3c.dom.Element ?: continue
                val wayId = el.getAttribute("id").toLongOrNull() ?: continue
                val nds = ArrayList<Long>()
                val children = el.childNodes
                for (c in 0 until children.length) {
                    val child = children.item(c) as? org.w3c.dom.Element ?: continue
                    if (child.tagName == "nd") {
                        child.getAttribute("ref").toLongOrNull()?.let { nds.add(it) }
                    }
                }
                if (nds.size >= 2) {
                    wayNodeRefs[wayId] = nds
                    val tags = readTags(el)
                    if (tags.isNotEmpty()) wayTagsMap[wayId] = tags
                }
            }

            val relEls = doc.getElementsByTagName("relation")
            val validRoutes = setOf("hiking", "foot", "mtb", "bicycle", "canoe", "kayak")
            for (i in 0 until relEls.length) {
                val el = relEls.item(i) as? org.w3c.dom.Element ?: continue
                val relId = el.getAttribute("id").toLongOrNull() ?: continue
                val tags = readTags(el)
                if (tags["type"] != "route") continue
                val route = tags["route"] ?: continue
                if (route !in validRoutes) continue
                val name = tags["name"] ?: tags["ref"] ?: "Trail #$relId"
                val ref = tags["ref"]?.takeIf { it.isNotBlank() }
                val kind = when (route) {
                    "mtb" -> TrailRouteKind.MTB
                    "bicycle" -> TrailRouteKind.CYCLING
                    "canoe", "kayak" -> TrailRouteKind.KAYAKING
                    else -> if (tags["educational"] == "yes") TrailRouteKind.EDUCATIONAL else TrailRouteKind.HIKING
                }
                val color = OutdoorContextResolver.parseTrailColor(tags["osmc:symbol"], tags["colour"] ?: tags["color"])
                val network = tags["network"]?.takeIf { it.isNotBlank() }

                val children = el.childNodes
                for (c in 0 until children.length) {
                    val child = children.item(c) as? org.w3c.dom.Element ?: continue
                    if (child.tagName == "member" && child.getAttribute("type") == "way") {
                        val wayId = child.getAttribute("ref").toLongOrNull() ?: continue
                        val pts = wayNodeRefs[wayId]?.mapNotNull { nodesMap[it] } ?: emptyList()
                        if (pts.size >= 2) {
                            segments.add(
                                TrailVectorSegment(
                                    id = relId * 100_000L + wayId % 100_000L,
                                    name = name,
                                    ref = ref,
                                    kind = kind,
                                    color = color,
                                    network = network,
                                    points = pts
                                )
                            )
                        }
                    }
                }
            }

            val trailHighways = setOf("path", "footway", "track", "cycleway")
            for ((wayId, tags) in wayTagsMap) {
                if (tags["highway"] !in trailHighways) continue
                val osmc = tags["osmc:symbol"].orEmpty()
                val sac = tags["sac_scale"].orEmpty()
                val edu = tags["educational"].orEmpty()
                if (osmc.isEmpty() && sac.isEmpty() && edu != "yes") continue
                val pts = wayNodeRefs[wayId]?.mapNotNull { nodesMap[it] } ?: continue
                if (pts.size < 2) continue
                val kind = if (edu == "yes") TrailRouteKind.EDUCATIONAL else TrailRouteKind.HIKING
                val color = OutdoorContextResolver.parseTrailColor(osmc, tags["colour"] ?: tags["color"])
                segments.add(
                    TrailVectorSegment(
                        id = wayId,
                        name = tags["name"] ?: "Trail #$wayId",
                        ref = tags["ref"]?.takeIf { it.isNotBlank() },
                        kind = kind,
                        color = color,
                        network = null,
                        points = pts
                    )
                )
            }
            segments
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun extractPointsFromGeometry(geomArray: JSONArray?): List<GeoPoint> {
        if (geomArray == null || geomArray.length() < 2) return emptyList()
        val pts = ArrayList<GeoPoint>(geomArray.length())
        for (i in 0 until geomArray.length()) {
            val obj = geomArray.optJSONObject(i) ?: continue
            val lat = obj.optDouble("lat", Double.NaN)
            val lon = obj.optDouble("lon", Double.NaN)
            if (!lat.isNaN() && !lon.isNaN()) {
                pts.add(GeoPoint(lat, lon))
            }
        }
        return pts
    }

    fun parseOverpassTrails(jsonStr: String): List<TrailVectorSegment> {
        val root = JSONObject(jsonStr)
        val elements = root.optJSONArray("elements") ?: return emptyList()

        val nodesMap = mutableMapOf<Long, GeoPoint>()
        val waysMap = mutableMapOf<Long, List<Long>>()
        val waysGeomMap = mutableMapOf<Long, List<GeoPoint>>()
        val standaloneWays = mutableListOf<JSONObject>()
        val relations = mutableListOf<JSONObject>()

        for (i in 0 until elements.length()) {
            val el = elements.optJSONObject(i) ?: continue
            val type = el.optString("type")
            val id = el.optLong("id")

            when (type) {
                "node" -> {
                    val lat = el.optDouble("lat", Double.NaN)
                    val lon = el.optDouble("lon", Double.NaN)
                    if (!lat.isNaN() && !lon.isNaN()) {
                        nodesMap[id] = GeoPoint(lat, lon)
                    }
                }
                "way" -> {
                    val geomArray = el.optJSONArray("geometry")
                    if (geomArray != null && geomArray.length() >= 2) {
                        waysGeomMap[id] = extractPointsFromGeometry(geomArray)
                    }
                    val nodeIds = el.optJSONArray("nodes")
                    if (nodeIds != null) {
                        val nList = mutableListOf<Long>()
                        for (k in 0 until nodeIds.length()) {
                            nList.add(nodeIds.getLong(k))
                        }
                        waysMap[id] = nList
                    }
                    if (el.has("tags")) {
                        standaloneWays.add(el)
                    }
                }
                "relation" -> {
                    relations.add(el)
                }
            }
        }

        val segments = mutableListOf<TrailVectorSegment>()

        // 1. Process Route Relations
        for (rel in relations) {
            val relId = rel.optLong("id")
            val tags = rel.optJSONObject("tags") ?: continue
            val name = tags.optString("name", tags.optString("ref", "Trail #$relId"))
            val ref = tags.optString("ref").takeIf { it.isNotBlank() }
            val route = tags.optString("route")
            val kind = when (route) {
                "mtb" -> TrailRouteKind.MTB
                "bicycle" -> TrailRouteKind.CYCLING
                "canoe", "kayak" -> TrailRouteKind.KAYAKING
                else -> if (tags.optString("educational") == "yes") TrailRouteKind.EDUCATIONAL else TrailRouteKind.HIKING
            }
            val osmc = tags.optString("osmc:symbol")
            val colour = tags.optString("colour").ifEmpty { tags.optString("color") }
            val color = OutdoorContextResolver.parseTrailColor(osmc, colour)
            val network = tags.optString("network").takeIf { it.isNotBlank() }

            val members = rel.optJSONArray("members") ?: continue
            for (m in 0 until members.length()) {
                val member = members.optJSONObject(m) ?: continue
                if (member.optString("type") == "way") {
                    val wayId = member.optLong("ref")
                    val memberGeom = member.optJSONArray("geometry")
                    val pts = if (memberGeom != null && memberGeom.length() >= 2) {
                        extractPointsFromGeometry(memberGeom)
                    } else {
                        waysGeomMap[wayId] ?: waysMap[wayId]?.mapNotNull { nodesMap[it] } ?: emptyList()
                    }
                    if (pts.size >= 2) {
                        segments.add(
                            TrailVectorSegment(
                                id = relId * 100_000L + wayId % 100_000L,
                                name = name,
                                ref = ref,
                                kind = kind,
                                color = color,
                                network = network,
                                points = pts
                            )
                        )
                    }
                }
            }
        }

        // 2. Process Standalone ways with osmc:symbol, educational, or sac_scale
        for (way in standaloneWays) {
            val wayId = way.optLong("id")
            val tags = way.optJSONObject("tags") ?: continue
            val name = tags.optString("name", "Trail #$wayId")
            val osmc = tags.optString("osmc:symbol")
            val colour = tags.optString("colour").ifEmpty { tags.optString("color") }
            val color = OutdoorContextResolver.parseTrailColor(osmc, colour)
            val isEdu = tags.optString("educational") == "yes" || tags.has("information")
            val hasSac = tags.has("sac_scale")
            if (color == TrailColor.NONE && !isEdu && !tags.has("route") && !hasSac) continue

            val geomArray = way.optJSONArray("geometry")
            val pts = if (geomArray != null && geomArray.length() >= 2) {
                extractPointsFromGeometry(geomArray)
            } else {
                waysGeomMap[wayId] ?: waysMap[wayId]?.mapNotNull { nodesMap[it] } ?: emptyList()
            }
            if (pts.size >= 2) {
                segments.add(
                    TrailVectorSegment(
                        id = wayId,
                        name = name,
                        ref = tags.optString("ref").takeIf { it.isNotBlank() },
                        kind = if (isEdu) TrailRouteKind.EDUCATIONAL else TrailRouteKind.HIKING,
                        color = if (color != TrailColor.NONE) color else if (isEdu) TrailColor.GREEN else TrailColor.RED,
                        points = pts
                    )
                )
            }
        }

        return segments
    }

    private fun saveTileJson(file: File, segments: List<TrailVectorSegment>) {
        try {
            val arr = JSONArray()
            for (s in segments) {
                val obj = JSONObject().apply {
                    put("id", s.id)
                    put("name", s.name)
                    put("ref", s.ref)
                    put("kind", s.kind.name)
                    put("color", s.color.name)
                    put("network", s.network)
                    val ptsArr = JSONArray()
                    for (p in s.points) {
                        val pt = JSONArray().apply {
                            put(p.latitude)
                            put(p.longitude)
                        }
                        ptsArr.put(pt)
                    }
                    put("points", ptsArr)
                }
                arr.put(obj)
            }
            file.writeText(arr.toString())
        } catch (_: Exception) {}
    }

    private fun parseTileJson(jsonStr: String): List<TrailVectorSegment> {
        val arr = JSONArray(jsonStr)
        val list = mutableListOf<TrailVectorSegment>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val ptsArr = obj.getJSONArray("points")
            val pts = mutableListOf<GeoPoint>()
            for (j in 0 until ptsArr.length()) {
                val pt = ptsArr.getJSONArray(j)
                pts.add(GeoPoint(pt.getDouble(0), pt.getDouble(1)))
            }
            list.add(
                TrailVectorSegment(
                    id = obj.getLong("id"),
                    name = obj.getString("name"),
                    ref = obj.optString("ref").takeIf { it.isNotBlank() },
                    kind = try { TrailRouteKind.valueOf(obj.optString("kind")) } catch (_: Exception) { TrailRouteKind.HIKING },
                    color = try { TrailColor.valueOf(obj.optString("color")) } catch (_: Exception) { TrailColor.NONE },
                    network = obj.optString("network").takeIf { it.isNotBlank() },
                    points = pts
                )
            )
        }
        return list
    }
}
