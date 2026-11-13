package online.hatala.whereami

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.xml.sax.InputSource
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.xml.parsers.DocumentBuilderFactory

object OutdoorContextResolver {

    private const val USER_AGENT = "WhereAmIPersonalApp/2.4 (outdoor-context)"
    private val OVERPASS_MIRRORS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.private.coffee/api/interpreter"
    )
    private const val OSM_API_BASE_URL = "https://api.openstreetmap.org/api/0.6/map"

    private val queryMutex = Mutex()
    private val mirrorCursor = AtomicInteger(0)
    private val mirrorCooldownUntilMs = ConcurrentHashMap<String, Long>()

    @Volatile
    private var lastQueryTime = 0L
    @Volatile
    private var cooldownUntil = 0L
    @Volatile
    private var consecutiveFailures = 0

    private const val MIN_QUERY_INTERVAL_MS = 15_000L
    private const val RATE_LIMIT_COOLDOWN_MS = 30_000L

    internal fun resetCooldownsForTest() {
        mirrorCooldownUntilMs.clear()
        cooldownUntil = 0L
        consecutiveFailures = 0
        lastQueryTime = 0L
    }

    /**
     * Reads cached outdoor context for `(lat, lng)` strictly from the SQLite spatial cache
     * without performing network I/O or referencing any hardcoded regional lists.
     */
    fun resolveOffline(lat: Double, lng: Double, context: Context): OutdoorContext {
        return try {
            SpatialCacheHelper.getInstance(context).getOutdoorContext(lat, lng)
        } catch (_: Exception) {
            null
        } ?: OutdoorContext()
    }

    suspend fun resolveOutdoorContext(
        lat: Double,
        lng: Double,
        context: Context,
        allowNetwork: Boolean = true
    ): OutdoorContext = withContext(Dispatchers.IO) {
        // 1. Check persistent SQLite spatial cache (populated dynamically anywhere worldwide)
        try {
            val cached = SpatialCacheHelper.getInstance(context).getOutdoorContext(lat, lng)
            if (cached != null) {
                return@withContext cached
            }
        } catch (_: Exception) {}

        if (!allowNetwork) {
            return@withContext OutdoorContext()
        }

        // 2. Network query via rotating Overpass mirrors, falling back to OSM 0.6 XML map endpoint
        val now = System.currentTimeMillis()
        if (now < cooldownUntil) {
            return@withContext OutdoorContext()
        }

        queryMutex.withLock {
            // Re-check cache after lock in case a sibling coroutine just populated this tile
            try {
                val cachedAfterLock = SpatialCacheHelper.getInstance(context).getOutdoorContext(lat, lng)
                if (cachedAfterLock != null) return@withContext cachedAfterLock
            } catch (_: Exception) {}

            val elapsed = System.currentTimeMillis() - lastQueryTime
            if (elapsed < MIN_QUERY_INTERVAL_MS) {
                return@withContext OutdoorContext()
            }

            try {
                lastQueryTime = System.currentTimeMillis()
                val ql = buildOverpassQuery(lat, lng)
                val body = executeOverpassQuery(
                    ql = ql,
                    connectTimeoutMs = 5000,
                    readTimeoutMs = 12000,
                    tag = "OUTDOOR"
                )
                if (body != null) {
                    consecutiveFailures = 0
                    val parsed = parseOverpassResponse(body, lat, lng)
                    try {
                        SpatialCacheHelper.getInstance(context).putOutdoorContext(lat, lng, parsed)
                    } catch (_: Exception) {}
                    return@withContext parsed
                }

                // Overpass mirrors exhausted -> fallback to bounded OSM 0.6 XML map query
                val xml = fetchOsmMapXml(
                    minLat = lat - 0.006,
                    minLon = lng - 0.008,
                    maxLat = lat + 0.006,
                    maxLon = lng + 0.008,
                    connectTimeoutMs = 5000,
                    readTimeoutMs = 10000
                )
                if (xml != null) {
                    consecutiveFailures = 0
                    val parsedXml = parseOsmMapXmlOutdoorContext(xml, lat, lng)
                    if (parsedXml.hasAnyOutdoor) {
                        try {
                            SpatialCacheHelper.getInstance(context).putOutdoorContext(lat, lng, parsedXml)
                        } catch (_: Exception) {}
                    }
                    return@withContext parsedXml
                }

                consecutiveFailures++
                val backoffMs = (RATE_LIMIT_COOLDOWN_MS * (1L shl consecutiveFailures.coerceAtMost(4))).coerceAtMost(300_000L)
                cooldownUntil = System.currentTimeMillis() + backoffMs
                TelemetryLogger.log("OUTDOOR", "All Overpass mirrors & OSM 0.6 fallback failed; backoff ${backoffMs / 1000}s")
            } catch (e: Exception) {
                consecutiveFailures++
                val backoffMs = (RATE_LIMIT_COOLDOWN_MS * (1L shl consecutiveFailures.coerceAtMost(4))).coerceAtMost(300_000L)
                cooldownUntil = System.currentTimeMillis() + backoffMs
                DataAcquisitionManager.reportError(e)
                TelemetryLogger.log("OUTDOOR", "Outdoor resolve error: ${e.message}; backoff ${backoffMs / 1000}s")
            }
        }

        return@withContext OutdoorContext()
    }

    /**
     * Executes an Overpass QL query across rotating public mirrors with HTTP 429/5xx failover
     * and health reporting to [DataAcquisitionManager].
     */
    internal fun executeOverpassQuery(
        ql: String,
        connectTimeoutMs: Int = 5000,
        readTimeoutMs: Int = 12000,
        tag: String = "OUTDOOR",
        endpoints: List<String> = OVERPASS_MIRRORS
    ): String? {
        if (endpoints.isEmpty()) return null
        val now = System.currentTimeMillis()
        val start = Math.floorMod(mirrorCursor.getAndIncrement(), endpoints.size)
        var saw429 = false
        var lastError: Throwable? = null

        for (offset in endpoints.indices) {
            val endpoint = endpoints[(start + offset) % endpoints.size]
            val mirrorCooldown = mirrorCooldownUntilMs[endpoint] ?: 0L
            if (now < mirrorCooldown) continue

            try {
                val sep = if (endpoint.contains("?")) "&" else "?"
                val urlStr = endpoint + "${sep}data=" + URLEncoder.encode(ql, "UTF-8")
                val conn = URL(urlStr).openConnection() as HttpURLConnection
                conn.setRequestProperty("User-Agent", USER_AGENT)
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty("Accept-Charset", "utf-8")
                conn.connectTimeout = connectTimeoutMs
                conn.readTimeout = readTimeoutMs

                val code = conn.responseCode
                if (code == 429) {
                    saw429 = true
                    mirrorCooldownUntilMs[endpoint] = System.currentTimeMillis() + RATE_LIMIT_COOLDOWN_MS
                    TelemetryLogger.log(tag, "Overpass HTTP 429 on $endpoint; switching mirror")
                    continue
                }
                if (code >= 500) {
                    mirrorCooldownUntilMs[endpoint] = System.currentTimeMillis() + 20_000L
                    lastError = java.io.IOException("Overpass HTTP $code")
                    TelemetryLogger.log(tag, "Overpass HTTP $code on $endpoint; switching mirror")
                    continue
                }
                if (code == 200) {
                    val body = conn.inputStream.bufferedReader().readText()
                    if (body.trimStart().startsWith("{")) {
                        mirrorCooldownUntilMs.remove(endpoint)
                        DataAcquisitionManager.reportSuccess()
                        return body
                    }
                    lastError = java.io.IOException("Non-JSON Overpass response from $endpoint")
                } else {
                    lastError = java.io.IOException("Overpass HTTP $code")
                }
            } catch (e: Exception) {
                lastError = e
                TelemetryLogger.log(tag, "Overpass error ($endpoint): ${e.message}")
            }
        }

        if (saw429) {
            DataAcquisitionManager.reportRateLimited(30)
        } else if (lastError != null) {
            DataAcquisitionManager.reportError(lastError)
        }
        return null
    }

    /**
     * Fetches bounded raw OSM XML elements from the OSM 0.6 `/api/0.6/map` endpoint
     * as a lightweight emergency fallback when Overpass mirrors are unavailable.
     */
    internal fun fetchOsmMapXml(
        minLat: Double,
        minLon: Double,
        maxLat: Double,
        maxLon: Double,
        connectTimeoutMs: Int = 5000,
        readTimeoutMs: Int = 10000,
        baseUrl: String = OSM_API_BASE_URL
    ): String? {
        return try {
            val bboxParam = String.format(Locale.US, "%.6f,%.6f,%.6f,%.6f", minLon, minLat, maxLon, maxLat)
            val urlStr = "$baseUrl?bbox=$bboxParam"
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/xml")
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs

            val code = conn.responseCode
            if (code == 429) {
                DataAcquisitionManager.reportRateLimited(30)
                return null
            }
            if (code == 200) {
                val body = conn.inputStream.bufferedReader().readText()
                if (body.trimStart().startsWith("<")) {
                    DataAcquisitionManager.reportSuccess()
                    return body
                }
            } else if (code >= 500) {
                DataAcquisitionManager.reportError(java.io.IOException("OSM 0.6 HTTP $code"))
            }
            null
        } catch (e: Exception) {
            DataAcquisitionManager.reportError(e)
            TelemetryLogger.log("OUTDOOR", "OSM 0.6 fallback error: ${e.message}")
            null
        }
    }

    fun buildOverpassQuery(lat: Double, lng: Double): String {
        return """
            [out:json][timeout:8];
            is_in($lat,$lng)->.a;
            (
              area.a[natural~"^(mountain_range|valley|massif)$"][name];
              area.a["region:type"="mountain_area"][name];
              area.a[boundary~"^(national_park|protected_area)$"][name];
              area.a[leisure="nature_reserve"][name];
            );
            out tags;
            way(around:40,$lat,$lng)[highway];
            rel(bw)[type=route][route~"^(hiking|foot|mtb|bicycle|canoe|kayak)$"];
            out tags;
            (
              node(around:12000,$lat,$lng)[natural~"^(mountain_range|massif)$"][name];
              way(around:12000,$lat,$lng)[natural~"^(mountain_range|massif)$"][name];
              node(around:3000,$lat,$lng)[natural="valley"][name];
              way(around:3000,$lat,$lng)[natural="valley"][name];
              rel(around:400,$lat,$lng)[boundary~"^(national_park|protected_area)$"][name];
              way(around:400,$lat,$lng)[leisure="nature_reserve"][name];
              node(around:400,$lat,$lng)[natural~"^(peak|saddle|volcano|spring|cave_entrance)$"][name];
              node(around:400,$lat,$lng)[mountain_pass="yes"][name];
              node(around:450,$lat,$lng)[tourism~"^(alpine_hut|wilderness_hut|viewpoint)$"][name];
              way(around:450,$lat,$lng)[tourism~"^(alpine_hut|wilderness_hut)$"][name];
              way(around:150,$lat,$lng)[waterway~"^(river|stream|canal)$"][name];
              way(around:200,$lat,$lng)[natural="water"][name];
              rel(around:200,$lat,$lng)[natural="water"][name];
              way(around:50,$lat,$lng)[highway][~"^(educational|information)$"~".*"][name];
            );
            out center tags;
        """.trimIndent().replace("\n", "")
    }

    fun parseOverpassResponse(jsonStr: String, queryLat: Double, queryLon: Double): OutdoorContext {
        val root = JSONObject(jsonStr)
        val elements = root.optJSONArray("elements") ?: return OutdoorContext()

        var range: OutdoorArea? = null
        val fallbackRanges = mutableListOf<Pair<Float, OutdoorArea>>()
        val protected = mutableListOf<OutdoorArea>()
        var valley: OutdoorArea? = null
        val fallbackValleys = mutableListOf<Pair<Float, OutdoorArea>>()
        val trails = mutableListOf<ActiveTrailInfo>()
        val features = mutableListOf<OutdoorFeatureInfo>()
        var water: OutdoorFeatureInfo? = null

        for (i in 0 until elements.length()) {
            val el = elements.optJSONObject(i) ?: continue
            val id = el.optLong("id")
            val tags = el.optJSONObject("tags") ?: continue
            val name = tags.optString("name").takeIf { it.isNotBlank() } ?: continue
            val elType = el.optString("type")

            val natural = tags.optString("natural")
            val boundary = tags.optString("boundary")
            val leisure = tags.optString("leisure")
            val tourism = tags.optString("tourism")
            val waterway = tags.optString("waterway")
            val route = tags.optString("route")

            // 1. Enclosing Areas (from is_in or boundary relations)
            if (elType == "area" || (elType == "relation" && tags.optString("type") != "route")) {
                if (natural == "mountain_range" || tags.optString("region:type") == "mountain_area" || natural == "massif") {
                    if (range == null) {
                        range = OutdoorArea(
                            id = "osm_$id",
                            name = name,
                            nameEn = tags.optString("name:en").takeIf { it.isNotBlank() },
                            kind = OutdoorAreaKind.MOUNTAIN_RANGE,
                            osmRelationId = id
                        )
                    }
                } else if (natural == "valley") {
                    if (valley == null) {
                        valley = OutdoorArea(
                            id = "osm_$id",
                            name = name,
                            nameEn = tags.optString("name:en").takeIf { it.isNotBlank() },
                            kind = OutdoorAreaKind.VALLEY,
                            osmRelationId = id
                        )
                    }
                } else if (boundary == "national_park") {
                    protected.add(
                        OutdoorArea(
                            id = "osm_$id",
                            name = name,
                            nameEn = tags.optString("name:en").takeIf { it.isNotBlank() },
                            kind = OutdoorAreaKind.NATIONAL_PARK,
                            osmRelationId = id
                        )
                    )
                } else if (boundary == "protected_area" || leisure == "nature_reserve") {
                    val kind = if (leisure == "nature_reserve" || tags.optString("protect_class") == "4") {
                        OutdoorAreaKind.NATURE_RESERVE
                    } else {
                        OutdoorAreaKind.LANDSCAPE_PARK
                    }
                    protected.add(
                        OutdoorArea(
                            id = "osm_$id",
                            name = name,
                            nameEn = tags.optString("name:en").takeIf { it.isNotBlank() },
                            kind = kind,
                            osmRelationId = id
                        )
                    )
                }
                continue
            }

            // 2. Active Trails (from rel(bw)[type=route] or educational paths)
            if (elType == "relation" && tags.optString("type") == "route") {
                val trailKind = when (route) {
                    "mtb" -> TrailRouteKind.MTB
                    "bicycle" -> TrailRouteKind.CYCLING
                    "canoe", "kayak" -> TrailRouteKind.KAYAKING
                    else -> if (tags.optString("educational") == "yes") TrailRouteKind.EDUCATIONAL else TrailRouteKind.HIKING
                }
                val osmc = tags.optString("osmc:symbol")
                val colourTag = tags.optString("colour").ifEmpty { tags.optString("color") }
                val parsedColor = parseTrailColor(osmc, colourTag)

                trails.add(
                    ActiveTrailInfo(
                        osmId = id,
                        name = name,
                        ref = tags.optString("ref").takeIf { it.isNotBlank() },
                        kind = trailKind,
                        color = parsedColor,
                        network = tags.optString("network").takeIf { it.isNotBlank() }
                    )
                )
                continue
            }

            // 3. Educational path way
            if (elType == "way" && (tags.optString("educational") == "yes" || tags.optString("highway") == "path")) {
                if (tags.has("educational") || tags.has("information")) {
                    trails.add(
                        ActiveTrailInfo(
                            osmId = id,
                            name = name,
                            kind = TrailRouteKind.EDUCATIONAL,
                            color = TrailColor.GREEN
                        )
                    )
                }
            }

            // 4. Point / Center Coordinates for POIs and Features
            val lat = if (el.has("lat")) el.optDouble("lat", Double.NaN)
                      else el.optJSONObject("center")?.optDouble("lat", Double.NaN) ?: Double.NaN
            val lon = if (el.has("lon")) el.optDouble("lon", Double.NaN)
                      else el.optJSONObject("center")?.optDouble("lon", Double.NaN) ?: Double.NaN

            if (lat.isNaN() || lon.isNaN()) continue
            val dist = SearchHelper.computeDistanceMeters(queryLat, queryLon, lat, lon)

            val ele = tags.optString("ele").toIntOrNull()

            if (natural in listOf("peak", "volcano")) {
                features.add(
                    OutdoorFeatureInfo(
                        osmId = id,
                        name = name,
                        kind = PlaceKind.PEAK,
                        elevationMeters = ele,
                        distanceMeters = dist,
                        latitude = lat,
                        longitude = lon
                    )
                )
            } else if (natural == "saddle" || tags.optString("mountain_pass") == "yes") {
                features.add(
                    OutdoorFeatureInfo(
                        osmId = id,
                        name = name,
                        kind = PlaceKind.MOUNTAIN_PASS,
                        elevationMeters = ele,
                        distanceMeters = dist,
                        latitude = lat,
                        longitude = lon
                    )
                )
            } else if (tourism in listOf("alpine_hut", "wilderness_hut")) {
                features.add(
                    OutdoorFeatureInfo(
                        osmId = id,
                        name = name,
                        kind = PlaceKind.MOUNTAIN_HUT,
                        elevationMeters = ele,
                        distanceMeters = dist,
                        latitude = lat,
                        longitude = lon
                    )
                )
            } else if (tourism == "viewpoint" || natural in listOf("spring", "cave_entrance")) {
                features.add(
                    OutdoorFeatureInfo(
                        osmId = id,
                        name = name,
                        kind = PlaceKind.POI,
                        elevationMeters = ele,
                        distanceMeters = dist,
                        latitude = lat,
                        longitude = lon
                    )
                )
            } else if (waterway in listOf("river", "stream", "canal") || natural == "water") {
                if (water == null || dist < water.distanceMeters) {
                    water = OutdoorFeatureInfo(
                        osmId = id,
                        name = name,
                        kind = PlaceKind.WATER_BODY,
                        elevationMeters = ele,
                        distanceMeters = dist,
                        latitude = lat,
                        longitude = lon
                    )
                }
            } else if (
                (natural in listOf("mountain_range", "massif") || tags.optString("region:type") == "mountain_area") &&
                dist <= 15_000f
            ) {
                fallbackRanges.add(
                    dist to OutdoorArea(
                        id = "osm_${elType}_$id",
                        name = name,
                        nameEn = tags.optString("name:en").takeIf { it.isNotBlank() },
                        kind = OutdoorAreaKind.MOUNTAIN_RANGE,
                        osmRelationId = id
                    )
                )
            } else if (natural == "valley" && dist <= 3_000f) {
                fallbackValleys.add(
                    dist to OutdoorArea(
                        id = "osm_${elType}_$id",
                        name = name,
                        nameEn = tags.optString("name:en").takeIf { it.isNotBlank() },
                        kind = OutdoorAreaKind.VALLEY,
                        osmRelationId = id
                    )
                )
            } else if ((boundary == "national_park" || boundary == "protected_area" || leisure == "nature_reserve") && dist <= 1_000f) {
                val pKind = when {
                    boundary == "national_park" -> OutdoorAreaKind.NATIONAL_PARK
                    leisure == "nature_reserve" || tags.optString("protect_class") == "4" -> OutdoorAreaKind.NATURE_RESERVE
                    else -> OutdoorAreaKind.LANDSCAPE_PARK
                }
                protected.add(
                    OutdoorArea(
                        id = "osm_${elType}_$id",
                        name = name,
                        nameEn = tags.optString("name:en").takeIf { it.isNotBlank() },
                        kind = pKind,
                        osmRelationId = id
                    )
                )
            }
        }

        val sortedFeatures = features.sortedBy { it.distanceMeters }
        val distinctTrails = trails.distinctBy { it.name + "_" + it.kind }
        val resolvedRange = range ?: fallbackRanges.minByOrNull { it.first }?.second
        val resolvedValley = valley ?: fallbackValleys.minByOrNull { it.first }?.second

        return OutdoorContext(
            mountainRange = resolvedRange,
            protectedAreas = protected.distinctBy { it.name },
            valley = resolvedValley,
            activeTrails = distinctTrails,
            nearestFeatures = sortedFeatures,
            waterBody = water
        )
    }

    /**
     * Parses OSM 0.6 `/api/0.6/map` XML payload into [OutdoorContext] when Overpass mirrors
     * are unreachable or rate-limited. Works with point nodes, tagged ways, and route relations.
     */
    fun parseOsmMapXmlOutdoorContext(xmlStr: String, queryLat: Double, queryLon: Double): OutdoorContext {
        return try {
            val dbf = DocumentBuilderFactory.newInstance()
            try {
                dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            } catch (_: Exception) {}
            val doc = dbf.newDocumentBuilder().parse(InputSource(StringReader(xmlStr)))

            val nodesLatLon = HashMap<Long, Pair<Double, Double>>()
            var range: OutdoorArea? = null
            var bestRangeDist = Float.MAX_VALUE
            var valley: OutdoorArea? = null
            var bestValleyDist = Float.MAX_VALUE
            val protected = mutableListOf<OutdoorArea>()
            val trails = mutableListOf<ActiveTrailInfo>()
            val features = mutableListOf<OutdoorFeatureInfo>()
            var water: OutdoorFeatureInfo? = null

            fun readTags(el: org.w3c.dom.Element): Map<String, String> {
                val map = HashMap<String, String>()
                val children = el.childNodes
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

            fun classifyTagElement(id: Long, prefix: String, tags: Map<String, String>, lat: Double?, lon: Double?) {
                val name = tags["name"]?.takeIf { it.isNotBlank() } ?: return
                val natural = tags["natural"].orEmpty()
                val boundary = tags["boundary"].orEmpty()
                val leisure = tags["leisure"].orEmpty()
                val tourism = tags["tourism"].orEmpty()
                val waterway = tags["waterway"].orEmpty()
                val ele = tags["ele"]?.toIntOrNull()

                if (boundary == "national_park") {
                    protected.add(OutdoorArea("osm_${prefix}_$id", name, tags["name:en"]?.takeIf { it.isNotBlank() }, OutdoorAreaKind.NATIONAL_PARK, id))
                } else if (boundary == "protected_area" || leisure == "nature_reserve") {
                    val kind = if (leisure == "nature_reserve" || tags["protect_class"] == "4") OutdoorAreaKind.NATURE_RESERVE else OutdoorAreaKind.LANDSCAPE_PARK
                    protected.add(OutdoorArea("osm_${prefix}_$id", name, tags["name:en"]?.takeIf { it.isNotBlank() }, kind, id))
                }

                if (lat == null || lon == null) {
                    if (natural == "mountain_range" || natural == "massif" || tags["region:type"] == "mountain_area") {
                        if (range == null) {
                            range = OutdoorArea("osm_${prefix}_$id", name, tags["name:en"]?.takeIf { it.isNotBlank() }, OutdoorAreaKind.MOUNTAIN_RANGE, id)
                        }
                    } else if (natural == "valley" && valley == null) {
                        valley = OutdoorArea("osm_${prefix}_$id", name, tags["name:en"]?.takeIf { it.isNotBlank() }, OutdoorAreaKind.VALLEY, id)
                    }
                    return
                }

                val dist = SearchHelper.computeDistanceMeters(queryLat, queryLon, lat, lon)
                if ((natural == "mountain_range" || natural == "massif" || tags["region:type"] == "mountain_area") && dist < bestRangeDist && dist <= 15_000f) {
                    bestRangeDist = dist
                    range = OutdoorArea("osm_${prefix}_$id", name, tags["name:en"]?.takeIf { it.isNotBlank() }, OutdoorAreaKind.MOUNTAIN_RANGE, id)
                } else if (natural == "valley" && dist < bestValleyDist && dist <= 3_000f) {
                    bestValleyDist = dist
                    valley = OutdoorArea("osm_${prefix}_$id", name, tags["name:en"]?.takeIf { it.isNotBlank() }, OutdoorAreaKind.VALLEY, id)
                } else if (natural in listOf("peak", "volcano")) {
                    features.add(OutdoorFeatureInfo(id, name, PlaceKind.PEAK, ele, dist, lat, lon))
                } else if (natural == "saddle" || tags["mountain_pass"] == "yes") {
                    features.add(OutdoorFeatureInfo(id, name, PlaceKind.MOUNTAIN_PASS, ele, dist, lat, lon))
                } else if (tourism in listOf("alpine_hut", "wilderness_hut")) {
                    features.add(OutdoorFeatureInfo(id, name, PlaceKind.MOUNTAIN_HUT, ele, dist, lat, lon))
                } else if (tourism == "viewpoint" || natural in listOf("spring", "cave_entrance")) {
                    features.add(OutdoorFeatureInfo(id, name, PlaceKind.POI, ele, dist, lat, lon))
                } else if (waterway in listOf("river", "stream", "canal") || natural == "water") {
                    if (water == null || dist < water!!.distanceMeters) {
                        water = OutdoorFeatureInfo(id, name, PlaceKind.WATER_BODY, ele, dist, lat, lon)
                    }
                }
            }

            val nodeEls = doc.getElementsByTagName("node")
            for (i in 0 until nodeEls.length) {
                val el = nodeEls.item(i) as? org.w3c.dom.Element ?: continue
                val id = el.getAttribute("id").toLongOrNull() ?: continue
                val lat = el.getAttribute("lat").toDoubleOrNull() ?: continue
                val lon = el.getAttribute("lon").toDoubleOrNull() ?: continue
                nodesLatLon[id] = lat to lon
                val tags = readTags(el)
                if (tags.isNotEmpty()) classifyTagElement(id, "node", tags, lat, lon)
            }

            val wayEls = doc.getElementsByTagName("way")
            for (i in 0 until wayEls.length) {
                val el = wayEls.item(i) as? org.w3c.dom.Element ?: continue
                val id = el.getAttribute("id").toLongOrNull() ?: continue
                val tags = readTags(el)
                if (tags.isEmpty()) continue
                var firstLatLon: Pair<Double, Double>? = null
                val children = el.childNodes
                for (c in 0 until children.length) {
                    val child = children.item(c) as? org.w3c.dom.Element ?: continue
                    if (child.tagName == "nd") {
                        val refId = child.getAttribute("ref").toLongOrNull()
                        if (refId != null && nodesLatLon.containsKey(refId)) {
                            firstLatLon = nodesLatLon[refId]
                            break
                        }
                    }
                }
                if (tags["educational"] == "yes" && tags["name"] != null) {
                    trails.add(ActiveTrailInfo(id, tags["name"]!!, kind = TrailRouteKind.EDUCATIONAL, color = TrailColor.GREEN))
                }
                classifyTagElement(id, "way", tags, firstLatLon?.first, firstLatLon?.second)
            }

            val relEls = doc.getElementsByTagName("relation")
            val validRoutes = setOf("hiking", "foot", "mtb", "bicycle", "canoe", "kayak")
            for (i in 0 until relEls.length) {
                val el = relEls.item(i) as? org.w3c.dom.Element ?: continue
                val id = el.getAttribute("id").toLongOrNull() ?: continue
                val tags = readTags(el)
                if (tags.isEmpty()) continue
                if (tags["type"] == "route" && tags["route"] in validRoutes) {
                    val name = tags["name"] ?: tags["ref"] ?: continue
                    val trailKind = when (tags["route"]) {
                        "mtb" -> TrailRouteKind.MTB
                        "bicycle" -> TrailRouteKind.CYCLING
                        "canoe", "kayak" -> TrailRouteKind.KAYAKING
                        else -> if (tags["educational"] == "yes") TrailRouteKind.EDUCATIONAL else TrailRouteKind.HIKING
                    }
                    val color = parseTrailColor(tags["osmc:symbol"], tags["colour"] ?: tags["color"])
                    trails.add(ActiveTrailInfo(id, name, tags["ref"]?.takeIf { it.isNotBlank() }, trailKind, color, tags["network"]?.takeIf { it.isNotBlank() }))
                } else {
                    classifyTagElement(id, "relation", tags, null, null)
                }
            }

            OutdoorContext(
                mountainRange = range,
                protectedAreas = protected.distinctBy { it.name },
                valley = valley,
                activeTrails = trails.distinctBy { it.name + "_" + it.kind },
                nearestFeatures = features.sortedBy { it.distanceMeters },
                waterBody = water
            )
        } catch (_: Exception) {
            OutdoorContext()
        }
    }

    fun parseTrailColor(osmcSymbol: String? = null, colourTag: String? = null): TrailColor {
        val s = osmcSymbol?.lowercase(Locale.ROOT) ?: ""
        val c = colourTag?.lowercase(Locale.ROOT) ?: ""
        return when {
            s.contains("red") || c == "red" || c == "#ff0000" -> TrailColor.RED
            s.contains("blue") || c == "blue" || c == "#0000ff" -> TrailColor.BLUE
            s.contains("yellow") || c == "yellow" || c == "#ffff00" -> TrailColor.YELLOW
            s.contains("green") || c == "green" || c == "#00ff00" -> TrailColor.GREEN
            s.contains("black") || c == "black" || c == "#000000" -> TrailColor.BLACK
            s.contains("white") || c == "white" -> TrailColor.WHITE
            s.contains("purple") || c == "purple" -> TrailColor.PURPLE
            s.contains("orange") || c == "orange" -> TrailColor.ORANGE
            s.contains("cyan") || c == "cyan" -> TrailColor.CYAN
            else -> TrailColor.NONE
        }
    }
}
