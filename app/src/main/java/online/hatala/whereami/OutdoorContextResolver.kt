package online.hatala.whereami

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

object OutdoorContextResolver {

    private const val USER_AGENT = "WhereAmIPersonalApp/2.2 (outdoor-context)"
    private val queryMutex = Mutex()
    @Volatile
    private var lastQueryTime = 0L
    @Volatile
    private var cooldownUntil = 0L
    @Volatile
    private var consecutiveFailures = 0

    private const val MIN_QUERY_INTERVAL_MS = 15_000L
    private const val RATE_LIMIT_COOLDOWN_MS = 30_000L

    fun resolveOffline(lat: Double, lng: Double, context: Context): OutdoorContext {
        OfflineOutdoorRegionIndex.initIfNeeded(context)
        val offlineMatches = OfflineOutdoorRegionIndex.findContainingRegions(lat, lng)
        var offlineMountainRange: OutdoorArea? = null
        val offlineProtected = mutableListOf<OutdoorArea>()
        for (item in offlineMatches) {
            when (item.area.kind) {
                OutdoorAreaKind.MOUNTAIN_RANGE -> if (offlineMountainRange == null) offlineMountainRange = item.area
                OutdoorAreaKind.NATIONAL_PARK,
                OutdoorAreaKind.LANDSCAPE_PARK,
                OutdoorAreaKind.NATURE_RESERVE -> offlineProtected.add(item.area)
                OutdoorAreaKind.VALLEY -> {}
            }
        }
        return OutdoorContext(
            mountainRange = offlineMountainRange,
            protectedAreas = offlineProtected
        )
    }

    suspend fun resolveOutdoorContext(
        lat: Double,
        lng: Double,
        context: Context,
        allowNetwork: Boolean = true
    ): OutdoorContext = withContext(Dispatchers.IO) {
        OfflineOutdoorRegionIndex.initIfNeeded(context)

        // 1. Offline Bundled Regions (0 ms, always available)
        val offlineMatches = OfflineOutdoorRegionIndex.findContainingRegions(lat, lng)
        var offlineMountainRange: OutdoorArea? = null
        val offlineProtected = mutableListOf<OutdoorArea>()
        for (item in offlineMatches) {
            when (item.area.kind) {
                OutdoorAreaKind.MOUNTAIN_RANGE -> if (offlineMountainRange == null) offlineMountainRange = item.area
                OutdoorAreaKind.NATIONAL_PARK,
                OutdoorAreaKind.LANDSCAPE_PARK,
                OutdoorAreaKind.NATURE_RESERVE -> offlineProtected.add(item.area)
                OutdoorAreaKind.VALLEY -> {}
            }
        }

        // 2. Check persistent SQLite spatial cache
        try {
            val cached = SpatialCacheHelper.getInstance(context).getOutdoorContext(lat, lng)
            if (cached != null) {
                // Merge offline regions in case offline asset has tighter name
                val mergedRange = cached.mountainRange ?: offlineMountainRange
                val mergedProtected = (cached.protectedAreas + offlineProtected).distinctBy { it.id }
                return@withContext cached.copy(
                    mountainRange = mergedRange,
                    protectedAreas = mergedProtected
                )
            }
        } catch (_: Exception) {}

        if (!allowNetwork) {
            return@withContext OutdoorContext(
                mountainRange = offlineMountainRange,
                protectedAreas = offlineProtected
            )
        }

        // 3. Network query via Overpass API
        val now = System.currentTimeMillis()
        if (now < cooldownUntil) {
            return@withContext OutdoorContext(
                mountainRange = offlineMountainRange,
                protectedAreas = offlineProtected
            )
        }

        queryMutex.withLock {
            val elapsed = System.currentTimeMillis() - lastQueryTime
            if (elapsed < MIN_QUERY_INTERVAL_MS) {
                // If queried recently, return offline baseline
                return@withContext OutdoorContext(
                    mountainRange = offlineMountainRange,
                    protectedAreas = offlineProtected
                )
            }

            try {
                lastQueryTime = System.currentTimeMillis()
                val ql = buildOverpassQuery(lat, lng)
                val urlStr = "https://overpass-api.de/api/interpreter?data=" + URLEncoder.encode(ql, "UTF-8")
                val conn = URL(urlStr).openConnection() as HttpURLConnection
                conn.setRequestProperty("User-Agent", USER_AGENT)
                conn.connectTimeout = 4000
                conn.readTimeout = 6000

                if (conn.responseCode == 429 || conn.responseCode >= 500) {
                    consecutiveFailures++
                    val backoffMs = (30_000L * (1L shl consecutiveFailures.coerceAtMost(5))).coerceAtMost(600_000L)
                    cooldownUntil = System.currentTimeMillis() + backoffMs
                    TelemetryLogger.log("OUTDOOR", "Overpass HTTP ${conn.responseCode}; backoff active for ${backoffMs / 1000}s (failure #$consecutiveFailures)")
                    return@withContext OutdoorContext(
                        mountainRange = offlineMountainRange,
                        protectedAreas = offlineProtected
                    )
                }

                if (conn.responseCode == 200) {
                    consecutiveFailures = 0
                    val body = conn.inputStream.bufferedReader().readText()
                    val parsed = parseOverpassResponse(body, lat, lng)
                    val mergedRange = parsed.mountainRange ?: offlineMountainRange
                    val mergedProtected = (parsed.protectedAreas + offlineProtected).distinctBy { it.name.lowercase(Locale.ROOT) }
                    val finalContext = parsed.copy(
                        mountainRange = mergedRange,
                        protectedAreas = mergedProtected
                    )

                    try {
                        SpatialCacheHelper.getInstance(context).putOutdoorContext(lat, lng, finalContext)
                    } catch (_: Exception) {}

                    return@withContext finalContext
                }
            } catch (e: Exception) {
                consecutiveFailures++
                val backoffMs = (30_000L * (1L shl consecutiveFailures.coerceAtMost(5))).coerceAtMost(600_000L)
                cooldownUntil = System.currentTimeMillis() + backoffMs
                TelemetryLogger.log("OUTDOOR", "Overpass resolve error: ${e.message}; backoff active for ${backoffMs / 1000}s (failure #$consecutiveFailures)")
            }
        }

        return@withContext OutdoorContext(
            mountainRange = offlineMountainRange,
            protectedAreas = offlineProtected
        )
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
        val protected = mutableListOf<OutdoorArea>()
        var valley: OutdoorArea? = null
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
            } else if (tourism == "viewpoint") {
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
            }
        }

        val sortedFeatures = features.sortedBy { it.distanceMeters }
        val distinctTrails = trails.distinctBy { it.name + "_" + it.kind }

        return OutdoorContext(
            mountainRange = range,
            protectedAreas = protected.distinctBy { it.name },
            valley = valley,
            activeTrails = distinctTrails,
            nearestFeatures = sortedFeatures,
            waterBody = water
        )
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
