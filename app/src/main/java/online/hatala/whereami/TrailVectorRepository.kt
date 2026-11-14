package online.hatala.whereami

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

data class TrailVectorSegment(
    val id: Long,
    val name: String,
    val ref: String? = null,
    val kind: TrailRouteKind = TrailRouteKind.HIKING,
    val color: TrailColor = TrailColor.NONE,
    val network: String? = null,
    val points: List<GeoPoint>
)

data class WaymarkedRelationMeta(
    val relationId: Long,
    val name: String? = null,
    val ref: String? = null,
    val route: String? = null,
    val educational: Boolean = false,
    val osmcSymbol: String? = null,
    val colour: String? = null
)

object TrailVectorRepository {

    private const val USER_AGENT = "WhereAmIPersonalApp/2.4 (vector-trails)"
    private const val TILE_SIZE_DEG = 0.08
    private const val WAYMARKED_ZOOM = 12
    private const val MERCATOR_ORIGIN_SHIFT = 20037508.342789244
    private const val CACHE_TTL_MS = 7L * 24L * 60L * 60L * 1000L
    private const val FAILURE_COOLDOWN_MS = 15_000L
    private const val MAX_WAYMARKED_CACHED_TILES = 144
    private const val MAX_WAYMARKED_NETWORK_TILES = 16
    private const val MAX_WIDE_WAYMARKED_SPAN_TILES = 36
    private const val MACRO_GRID_DEG = 2.0
    private const val MAX_MACRO_SPAN_DEG = 6.0
    private const val MAX_FALLBACK_TILES = 16
    private const val MAX_DETAIL_FETCHES_PER_BATCH = 20

    private val memoryCache = ConcurrentHashMap<String, List<TrailVectorSegment>>()
    private val relMetaCache = ConcurrentHashMap<String, WaymarkedRelationMeta>()
    private val pendingTiles = ConcurrentHashMap.newKeySet<String>()
    private val failedTileTimestamps = ConcurrentHashMap<String, Long>()

    private fun logDebug(message: String) {
        TelemetryLogger.log("TRAILS", message)
        try {
            android.util.Log.d("TRAILS", message)
        } catch (_: Throwable) {
        }
    }

    /** Computes integer grid key using floor() so negative latitudes and longitudes never alias around zero. */
    fun toTileKey(lat: Double, lon: Double): String {
        val latIdx = floor(lat / TILE_SIZE_DEG).toInt()
        val lonIdx = floor(lon / TILE_SIZE_DEG).toInt()
        return "tile_${latIdx}_${lonIdx}"
    }

    /** Converts WGS84 coordinates into a Slippy Map tile `(x, y)` index at zoom 12. */
    fun latLonToTileZ12(lat: Double, lon: Double): Pair<Int, Int> {
        val n = (1 shl WAYMARKED_ZOOM).toDouble()
        val maxIdx = (1 shl WAYMARKED_ZOOM) - 1
        val clampedLat = lat.coerceIn(-85.0511, 85.0511)
        val x = floor((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, maxIdx)
        val latRad = Math.toRadians(clampedLat)
        val y = floor((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n).toInt().coerceIn(0, maxIdx)
        return x to y
    }

    /** Converts Web Mercator (`EPSG:3857`) coordinates in meters into a WGS84 [GeoPoint]. */
    fun mercatorToLatLon(mx: Double, my: Double): GeoPoint {
        val lon = (mx / MERCATOR_ORIGIN_SHIFT) * 180.0
        val lat = Math.toDegrees(atan(sinh((my / MERCATOR_ORIGIN_SHIFT) * PI)))
        return GeoPoint(lat, lon)
    }

    private fun getCacheDir(context: Context): File {
        val dir = File(context.cacheDir, "trail_vectors")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    internal fun resetCachesForTest() {
        memoryCache.clear()
        relMetaCache.clear()
        pendingTiles.clear()
        failedTileTimestamps.clear()
    }

    private fun isTileOnFailureCooldown(key: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val failedAt = failedTileTimestamps[key] ?: return false
        if (nowMs - failedAt < FAILURE_COOLDOWN_MS) return true
        failedTileTimestamps.remove(key, failedAt)
        return false
    }

    private fun markTileFailed(key: String, nowMs: Long = System.currentTimeMillis()) {
        failedTileTimestamps[key] = nowMs
    }

    /**
     * Fetches vector trails for [bbox], optionally restricting to [requestedKinds].
     * Uses Waymarked Trails z=12 GeoJSON vector tiles (~0.25s worldwide) as Tier 1 for
     * hiking/educational/cycling/MTB routes, falling back to hardened Overpass and
     * viewport-centered OSM `/api/0.6/map` XML when Waymarked fails or KAYAKING is requested.
     */
    suspend fun getTrailsForBoundingBox(
        context: Context,
        bbox: BoundingBox,
        allowNetwork: Boolean = true,
        requestedKinds: Set<TrailRouteKind> = emptySet(),
        waymarkedTileUrlTemplate: String = "https://{sub}.waymarkedtrails.org/api/v1/tiles/12/{x}/{y}.json",
        waymarkedDetailsUrlTemplate: String = "https://{sub}.waymarkedtrails.org/api/v1/details/relation/{id}",
        overpassEndpoints: List<String>? = null,
        osmMapBaseUrl: String = "https://api.openstreetmap.org/api/0.6/map"
    ): List<TrailVectorSegment> = withContext(Dispatchers.IO) {
        val subdomains = subdomainsForKinds(requestedKinds)
        val wantWaymarked = subdomains.isNotEmpty()
        val wantKayakingFallback = TrailRouteKind.KAYAKING in requestedKinds
        val latSpan = kotlin.math.abs(bbox.latNorth - bbox.latSouth)
        val lonSpan = kotlin.math.abs(bbox.lonEast - bbox.lonWest)
        val isWideViewport = latSpan > 1.5 || lonSpan > 2.0

        val results = mutableListOf<TrailVectorSegment>()
        var anyWaymarkedOk = false

        if (wantWaymarked) {
            val (wmSegments, allOrAnyOk) = fetchWaymarkedForBoundingBox(
                context = context,
                bbox = bbox,
                subdomains = subdomains,
                allowNetwork = allowNetwork,
                tileUrlTemplate = waymarkedTileUrlTemplate,
                detailsUrlTemplate = waymarkedDetailsUrlTemplate
            )
            results.addAll(wmSegments)
            anyWaymarkedOk = allOrAnyOk
        }

        if (isWideViewport && latSpan <= MAX_MACRO_SPAN_DEG && lonSpan <= MAX_MACRO_SPAN_DEG) {
            results.addAll(
                fetchMacroRoutesForWideZoom(
                    context = context,
                    bbox = bbox,
                    requestedKinds = requestedKinds,
                    allowNetwork = allowNetwork,
                    overpassEndpoints = overpassEndpoints
                )
            )
        }

        val needTier2Fallback = !isWideViewport && (wantKayakingFallback || (wantWaymarked && !anyWaymarkedOk))
        if (needTier2Fallback) {
            val fallbackSegments = fetchOverpassOrOsmFallback(
                context = context,
                bbox = bbox,
                allowNetwork = allowNetwork,
                kayakOnly = wantKayakingFallback && anyWaymarkedOk && requestedKinds.all { it == TrailRouteKind.KAYAKING || !wantWaymarked },
                overpassEndpoints = overpassEndpoints,
                osmMapBaseUrl = osmMapBaseUrl
            )
            results.addAll(fallbackSegments)
        }

        results.distinctBy { "${it.id}_${it.kind.name}_${it.points.firstOrNull()?.latitude}_${it.points.firstOrNull()?.longitude}" }
    }

    private fun subdomainsForKinds(requestedKinds: Set<TrailRouteKind>): List<String> {
        if (requestedKinds.isEmpty()) return listOf("hiking", "cycling", "mtb")
        val subs = mutableListOf<String>()
        if (TrailRouteKind.HIKING in requestedKinds || TrailRouteKind.EDUCATIONAL in requestedKinds) subs.add("hiking")
        if (TrailRouteKind.CYCLING in requestedKinds) subs.add("cycling")
        if (TrailRouteKind.MTB in requestedKinds) subs.add("mtb")
        return subs
    }

    private suspend fun fetchWaymarkedForBoundingBox(
        context: Context,
        bbox: BoundingBox,
        subdomains: List<String>,
        allowNetwork: Boolean,
        tileUrlTemplate: String,
        detailsUrlTemplate: String
    ): Pair<List<TrailVectorSegment>, Boolean> = coroutineScope {
        val (xMinRaw, yMaxRaw) = latLonToTileZ12(bbox.latSouth, bbox.lonWest)
        val (xMaxRaw, yMinRaw) = latLonToTileZ12(bbox.latNorth, bbox.lonEast)
        val xStart = minOf(xMinRaw, xMaxRaw)
        val xEnd = maxOf(xMinRaw, xMaxRaw)
        val yStart = minOf(yMinRaw, yMaxRaw)
        val yEnd = maxOf(yMinRaw, yMaxRaw)

        val (cx, cy) = latLonToTileZ12(
            (bbox.latSouth + bbox.latNorth) / 2.0,
            (bbox.lonWest + bbox.lonEast) / 2.0
        )
        val tileCoords = mutableListOf<Pair<Int, Int>>()
        for (tx in xStart..xEnd) {
            for (ty in yStart..yEnd) {
                tileCoords.add(tx to ty)
            }
        }
        val sortedCoords = tileCoords
            .sortedBy { (tx, ty) -> kotlin.math.abs(tx - cx) + kotlin.math.abs(ty - cy) }
            .take(MAX_WAYMARKED_CACHED_TILES)

        val allowTileNetwork = allowNetwork && tileCoords.size <= MAX_WIDE_WAYMARKED_SPAN_TILES
        val networkAllowedCoords = if (allowTileNetwork) {
            sortedCoords.take(MAX_WAYMARKED_NETWORK_TILES).toHashSet()
        } else {
            emptySet()
        }

        data class LoadedTile(
            val sub: String,
            val tx: Int,
            val ty: Int,
            val segKey: String,
            val jsonStr: String?,
            val cachedSegs: List<TrailVectorSegment>?
        )

        val loadedTiles = mutableListOf<LoadedTile>()
        var anySourceOk = false

        val fetchJobs = sortedCoords.flatMap { xy ->
            val (tx, ty) = xy
            val canFetchThisTile = xy in networkAllowedCoords
            subdomains.map { sub ->
                async {
                    val key = "wm_${sub}_12_${tx}_${ty}"
                    val segKey = "wm_seg_${sub}_12_${tx}_${ty}"
                    val memSegs = memoryCache[segKey]
                    if (memSegs != null) {
                        return@async LoadedTile(sub, tx, ty, segKey, null, memSegs) to true
                    }
                    val file = File(getCacheDir(context), "$key.geojson")
                    val cachedJson = readFreshCacheFile(file)
                    if (cachedJson != null) {
                        LoadedTile(sub, tx, ty, segKey, cachedJson, null) to true
                    } else if (!canFetchThisTile || isTileOnFailureCooldown(key) || !pendingTiles.add(key)) {
                        null to false
                    } else {
                        try {
                            val urlStr = tileUrlTemplate
                                .replace("{sub}", sub)
                                .replace("{x}", tx.toString())
                                .replace("{y}", ty.toString())
                            val body = httpGetString(urlStr, connectTimeoutMs = 6000, readTimeoutMs = 10000)
                            if (body != null) {
                                try {
                                    file.writeText(body)
                                } catch (_: Exception) {
                                }
                                DataAcquisitionManager.reportSuccess()
                                LoadedTile(sub, tx, ty, segKey, body, null) to true
                            } else {
                                markTileFailed(key)
                                null to false
                            }
                        } catch (e: Exception) {
                            markTileFailed(key)
                            logDebug("Waymarked tile $key error: ${e.message}")
                            null to false
                        } finally {
                            pendingTiles.remove(key)
                        }
                    }
                }
            }
        }

        for ((tileEntry, ok) in fetchJobs.awaitAll()) {
            if (ok) anySourceOk = true
            if (tileEntry != null) loadedTiles.add(tileEntry)
        }

        if (loadedTiles.isEmpty()) return@coroutineScope emptyList<TrailVectorSegment>() to anySourceOk

        // Preload cached relation metadata from disk for JSON-loaded tiles and queue any missing ones
        val uncachedRels = linkedSetOf<Pair<String, Long>>()
        for (entry in loadedTiles) {
            val jsonStr = entry.jsonStr ?: continue
            for (relId in extractRelationIdsFromWaymarkedTile(jsonStr)) {
                val metaKey = "${entry.sub}:$relId"
                if (!relMetaCache.containsKey(metaKey)) {
                    val diskMeta = loadRelationMetaFromDisk(context, entry.sub, relId)
                    if (diskMeta != null) {
                        relMetaCache[metaKey] = diskMeta
                    } else if (allowTileNetwork) {
                        uncachedRels.add(entry.sub to relId)
                    }
                }
            }
        }

        val fetchedMetaRelIds = HashSet<Long>()
        if (allowTileNetwork && uncachedRels.isNotEmpty()) {
            val sem = Semaphore(6)
            uncachedRels.take(MAX_DETAIL_FETCHES_PER_BATCH).map { (sub, relId) ->
                async {
                    sem.withPermit {
                        val urlStr = detailsUrlTemplate
                            .replace("{sub}", sub)
                            .replace("{id}", relId.toString())
                        val body = httpGetString(urlStr, connectTimeoutMs = 4000, readTimeoutMs = 5000)
                        if (body != null) {
                            val meta = parseWaymarkedRelationDetailsJson(relId, body)
                            if (meta != null) {
                                relMetaCache["$sub:$relId"] = meta
                                saveRelationMetaToDisk(context, sub, meta)
                                synchronized(fetchedMetaRelIds) { fetchedMetaRelIds.add(relId) }
                            }
                        }
                    }
                }
            }.awaitAll()
        }

        val segments = mutableListOf<TrailVectorSegment>()
        for (entry in loadedTiles) {
            if (entry.cachedSegs != null) {
                segments.addAll(entry.cachedSegs)
            } else if (entry.jsonStr != null) {
                val parsed = parseWaymarkedTileJson(
                    jsonStr = entry.jsonStr,
                    subdomain = entry.sub,
                    relationMeta = relMetaCache,
                    tileX = entry.tx,
                    tileY = entry.ty
                )
                val tileRels = extractRelationIdsFromWaymarkedTile(entry.jsonStr)
                if (tileRels.all { relMetaCache.containsKey("${entry.sub}:$it") }) {
                    memoryCache[entry.segKey] = parsed
                }
                segments.addAll(parsed)
            }
        }
        segments to anySourceOk
    }

    /** Enriches a single Waymarked [segment] with relation metadata if its name was deferred on tap. */
    suspend fun resolveTrailDetailsIfMissing(
        context: Context,
        segment: TrailVectorSegment,
        detailsUrlTemplate: String = "https://{sub}.waymarkedtrails.org/api/v1/details/relation/{id}"
    ): TrailVectorSegment = withContext(Dispatchers.IO) {
        val relId = segment.id / 100_000L
        if (relId <= 0L || !segment.name.startsWith("Trail #")) return@withContext segment
        val sub = when (segment.kind) {
            TrailRouteKind.CYCLING -> "cycling"
            TrailRouteKind.MTB -> "mtb"
            TrailRouteKind.HIKING, TrailRouteKind.EDUCATIONAL -> "hiking"
            TrailRouteKind.KAYAKING -> return@withContext segment
        }
        val meta = relMetaCache["$sub:$relId"]
            ?: loadRelationMetaFromDisk(context, sub, relId)?.also { relMetaCache["$sub:$relId"] = it }
            ?: httpGetString(
                detailsUrlTemplate.replace("{sub}", sub).replace("{id}", relId.toString()),
                connectTimeoutMs = 4000,
                readTimeoutMs = 5000
            )?.let { parseWaymarkedRelationDetailsJson(relId, it) }?.also {
                relMetaCache["$sub:$relId"] = it
                saveRelationMetaToDisk(context, sub, it)
            }
            ?: return@withContext segment

        val updatedKind = if (sub == "hiking" && meta.educational) TrailRouteKind.EDUCATIONAL else segment.kind
        val parsedColor = OutdoorContextResolver.parseTrailColor(meta.osmcSymbol, meta.colour)
        segment.copy(
            name = meta.name ?: meta.ref ?: segment.ref ?: segment.name,
            ref = meta.ref ?: segment.ref,
            kind = updatedKind,
            color = if (parsedColor != TrailColor.NONE) parsedColor else segment.color
        )
    }

    private suspend fun fetchOverpassOrOsmFallback(
        context: Context,
        bbox: BoundingBox,
        allowNetwork: Boolean,
        kayakOnly: Boolean,
        overpassEndpoints: List<String>?,
        osmMapBaseUrl: String
    ): List<TrailVectorSegment> = coroutineScope {
        val minLat = bbox.latSouth
        val maxLat = bbox.latNorth
        val minLon = bbox.lonWest
        val maxLon = bbox.lonEast

        val results = mutableListOf<TrailVectorSegment>()
        val startLatIdx = floor(minLat / TILE_SIZE_DEG).toInt()
        val endLatIdx = floor(maxLat / TILE_SIZE_DEG).toInt()
        val startLonIdx = floor(minLon / TILE_SIZE_DEG).toInt()
        val endLonIdx = floor(maxLon / TILE_SIZE_DEG).toInt()

        val focusLatIdx = floor(((minLat + maxLat) / 2.0) / TILE_SIZE_DEG).toInt()
        val focusLonIdx = floor(((minLon + maxLon) / 2.0) / TILE_SIZE_DEG).toInt()
        val fbTileList = mutableListOf<Pair<Int, Int>>()
        for (latIdx in startLatIdx..endLatIdx) {
            for (lonIdx in startLonIdx..endLonIdx) {
                fbTileList.add(latIdx to lonIdx)
            }
        }
        val cappedFbTiles = fbTileList
            .sortedBy { (la, lo) -> kotlin.math.abs(la - focusLatIdx) + kotlin.math.abs(lo - focusLonIdx) }
            .take(MAX_FALLBACK_TILES)

        for ((latIdx, lonIdx) in cappedFbTiles) {
                val baseKey = "tile_${latIdx}_${lonIdx}"
                val key = if (kayakOnly) "${baseKey}_kayak" else "${baseKey}_fb"
                val tileSouth = latIdx * TILE_SIZE_DEG
                val tileNorth = (latIdx + 1) * TILE_SIZE_DEG
                val tileWest = lonIdx * TILE_SIZE_DEG
                val tileEast = (lonIdx + 1) * TILE_SIZE_DEG

                val cached = memoryCache[key]
                if (cached != null) {
                    results.addAll(cached)
                    continue
                }

                val diskFile = File(getCacheDir(context), "$key.json")
                if (diskFile.exists() && System.currentTimeMillis() - diskFile.lastModified() < CACHE_TTL_MS) {
                    try {
                        val parsed = parseTileJson(diskFile.readText())
                        if (parsed.isNotEmpty()) {
                            memoryCache[key] = parsed
                            results.addAll(parsed)
                            continue
                        }
                    } catch (_: Exception) {
                    }
                }

                if (!allowNetwork || isTileOnFailureCooldown(key) || !pendingTiles.add(key)) continue

                try {
                    val s = String.format(Locale.US, "%.4f", tileSouth)
                    val w = String.format(Locale.US, "%.4f", tileWest)
                    val n = String.format(Locale.US, "%.4f", tileNorth)
                    val e = String.format(Locale.US, "%.4f", tileEast)

                    val ql = if (kayakOnly) {
                        """
                            [out:json][timeout:12];
                            relation["type"="route"]["route"~"^(canoe|kayak)$"]($s,$w,$n,$e)->.rels;
                            way(r.rels)($s,$w,$n,$e)->.rw;
                            .rels out;
                            .rw out tags geom;
                        """.trimIndent().replace("\n", "")
                    } else {
                        """
                            [out:json][timeout:12];
                            (
                              way["highway"~"^(path|footway|track|cycleway)$"]["osmc:symbol"]($s,$w,$n,$e);
                              way["highway"~"^(path|footway|track|cycleway)$"]["sac_scale"]($s,$w,$n,$e);
                              way["highway"~"^(path|footway|track|cycleway)$"]["educational"="yes"]($s,$w,$n,$e);
                            )->.dw;
                            relation["type"="route"]["route"~"^(hiking|foot|mtb|bicycle|canoe|kayak)$"]($s,$w,$n,$e)->.rels;
                            way(r.rels)($s,$w,$n,$e)->.rw;
                            .rels out;
                            (.dw; .rw;) out tags geom;
                        """.trimIndent().replace("\n", "")
                    }

                    val body = if (overpassEndpoints != null) {
                        OutdoorContextResolver.executeOverpassQuery(
                            ql = ql,
                            connectTimeoutMs = 6000,
                            readTimeoutMs = 12000,
                            tag = "TRAILS",
                            endpoints = overpassEndpoints
                        )
                    } else {
                        OutdoorContextResolver.executeOverpassQuery(
                            ql = ql,
                            connectTimeoutMs = 6000,
                            readTimeoutMs = 12000,
                            tag = "TRAILS"
                        )
                    }

                    if (body != null) {
                        val parsed = parseOverpassTrails(body)
                        memoryCache[key] = parsed
                        saveTileJson(diskFile, parsed)
                        results.addAll(parsed)
                        continue
                    }

                    // Fallback: center OSM /api/0.6/map XML on the actual visible viewport center (never off-screen tile center),
                    // bisecting once if node count exceeds OSM API limits.
                    val focusLat = ((bbox.latSouth + bbox.latNorth) / 2.0).coerceIn(tileSouth, tileNorth)
                    val focusLon = ((bbox.lonWest + bbox.lonEast) / 2.0).coerceIn(tileWest, tileEast)
                    val halfLat = minOf((bbox.latNorth - bbox.latSouth).coerceAtLeast(0.01) / 2.0, 0.015)
                    val halfLon = minOf((bbox.lonEast - bbox.lonWest).coerceAtLeast(0.01) / 2.0, 0.02)

                    val xml = OutdoorContextResolver.fetchOsmMapXml(
                        minLat = focusLat - halfLat,
                        minLon = focusLon - halfLon,
                        maxLat = focusLat + halfLat,
                        maxLon = focusLon + halfLon,
                        connectTimeoutMs = 6000,
                        readTimeoutMs = 12000,
                        baseUrl = osmMapBaseUrl
                    ) ?: OutdoorContextResolver.fetchOsmMapXml(
                        minLat = focusLat - halfLat * 0.5,
                        minLon = focusLon - halfLon * 0.5,
                        maxLat = focusLat + halfLat * 0.5,
                        maxLon = focusLon + halfLon * 0.5,
                        connectTimeoutMs = 6000,
                        readTimeoutMs = 12000,
                        baseUrl = osmMapBaseUrl
                    )

                    if (xml != null) {
                        val parsedXml = parseOsmMapXmlTrails(xml)
                            .let { list -> if (kayakOnly) list.filter { it.kind == TrailRouteKind.KAYAKING } else list }
                        if (parsedXml.isNotEmpty()) {
                            memoryCache[key] = parsedXml
                            saveTileJson(diskFile, parsedXml)
                        }
                        results.addAll(parsedXml)
                    } else {
                        markTileFailed(key)
                    }
                } catch (e: Exception) {
                    markTileFailed(key)
                    logDebug("Fallback fetch tile $key error: ${e.message}")
                } finally {
                    pendingTiles.remove(key)
                }
        }
        return@coroutineScope results
    }

    /**
     * Fetches long-distance national/international routes (`iwn|nwn|icn|ncn` and canoe/kayak waterways)
     * across wide viewports (`latSpan`/`lonSpan` > 1.5 deg) so trails stay visible when zoomed out.
     */
    private suspend fun fetchMacroRoutesForWideZoom(
        context: Context,
        bbox: BoundingBox,
        requestedKinds: Set<TrailRouteKind>,
        allowNetwork: Boolean,
        overpassEndpoints: List<String>?
    ): List<TrailVectorSegment> = withContext(Dispatchers.IO) {
        val startLatBin = floor(bbox.latSouth / MACRO_GRID_DEG).toInt()
        val endLatBin = floor(bbox.latNorth / MACRO_GRID_DEG).toInt()
        val startLonBin = floor(bbox.lonWest / MACRO_GRID_DEG).toInt()
        val endLonBin = floor(bbox.lonEast / MACRO_GRID_DEG).toInt()

        val wantHiking = requestedKinds.isEmpty() ||
            TrailRouteKind.HIKING in requestedKinds ||
            TrailRouteKind.EDUCATIONAL in requestedKinds
        val wantCycling = requestedKinds.isEmpty() || TrailRouteKind.CYCLING in requestedKinds
        val wantMtb = requestedKinds.isEmpty() || TrailRouteKind.MTB in requestedKinds
        val wantKayak = requestedKinds.isEmpty() || TrailRouteKind.KAYAKING in requestedKinds

        val routeTypes = buildList {
            if (wantHiking) add("hiking"); if (wantHiking) add("foot")
            if (wantCycling) add("bicycle")
            if (wantMtb) add("mtb")
        }.distinct()
        val kindTag = listOfNotNull(
            if (wantHiking) "h" else null,
            if (wantCycling) "c" else null,
            if (wantMtb) "m" else null,
            if (wantKayak) "k" else null
        ).joinToString("")

        val results = mutableListOf<TrailVectorSegment>()
        var binCount = 0
        for (latBin in startLatBin..endLatBin) {
            for (lonBin in startLonBin..endLonBin) {
                binCount++
                if (binCount > 6) break
                val key = "macro_${kindTag}_${latBin}_${lonBin}"
                memoryCache[key]?.let { results.addAll(it); continue }

                val diskFile = File(getCacheDir(context), "$key.json")
                if (diskFile.exists() && System.currentTimeMillis() - diskFile.lastModified() < CACHE_TTL_MS) {
                    try {
                        val parsed = parseTileJson(diskFile.readText())
                        if (parsed.isNotEmpty()) {
                            memoryCache[key] = parsed
                            results.addAll(parsed)
                            continue
                        }
                    } catch (_: Exception) {
                    }
                }

                if (!allowNetwork || isTileOnFailureCooldown(key) || !pendingTiles.add(key)) continue
                try {
                    val s = String.format(Locale.US, "%.4f", latBin * MACRO_GRID_DEG)
                    val w = String.format(Locale.US, "%.4f", lonBin * MACRO_GRID_DEG)
                    val n = String.format(Locale.US, "%.4f", (latBin + 1) * MACRO_GRID_DEG)
                    val e = String.format(Locale.US, "%.4f", (lonBin + 1) * MACRO_GRID_DEG)

                    val blocks = buildString {
                        if (routeTypes.isNotEmpty()) {
                            val rRegex = "^(${routeTypes.joinToString("|")})$"
                            append("""relation["type"="route"]["route"~"$rRegex"]["network"~"^(iwn|nwn|icn|ncn)$"]($s,$w,$n,$e);""")
                        }
                        if (wantKayak) {
                            append("""relation["type"="route"]["route"~"^(canoe|kayak)$"]($s,$w,$n,$e);""")
                        }
                    }
                    if (blocks.isBlank()) continue
                    val ql = "[out:json][timeout:18];($blocks)->.rels;way(r.rels)($s,$w,$n,$e)->.rw;.rels out;.rw out tags geom;"

                    val body = if (overpassEndpoints != null) {
                        OutdoorContextResolver.executeOverpassQuery(ql, 8000, 18000, "MACRO_TRAILS", endpoints = overpassEndpoints)
                    } else {
                        OutdoorContextResolver.executeOverpassQuery(ql, 8000, 18000, "MACRO_TRAILS")
                    }
                    if (body != null) {
                        val parsed = parseOverpassTrails(body)
                        memoryCache[key] = parsed
                        saveTileJson(diskFile, parsed)
                        results.addAll(parsed)
                    } else {
                        markTileFailed(key)
                    }
                } catch (ex: Exception) {
                    markTileFailed(key)
                    logDebug("Macro fetch $key error: ${ex.message}")
                } finally {
                    pendingTiles.remove(key)
                }
            }
        }
        results
    }

    /**
     * Loads full polyline member segments for a trail relation or waterway [osmId], checking
     * disk cache and querying Overpass worldwide. When [nearLatLng] is supplied (e.g. from a
     * river/waterway pin), tries the matching OSM element type (`way` then `relation`, or
     * `relation` then `way`) so numeric ID collisions across continents never return an unrelated
     * geometry.
     */
    suspend fun fetchTrailRouteByOsmId(
        context: Context,
        osmId: Long,
        preferRelation: Boolean = true,
        nearLatLng: GeoPoint? = null,
        allowNetwork: Boolean = true,
        overpassEndpoints: List<String>? = null
    ): List<List<GeoPoint>> = withContext(Dispatchers.IO) {
        if (osmId <= 0L) return@withContext emptyList()
        val typeTag = if (preferRelation) "r" else "w"
        val diskFile = File(getCacheDir(context), "rel_geom_${typeTag}_${osmId}.json")
        if (diskFile.exists() && System.currentTimeMillis() - diskFile.lastModified() < CACHE_TTL_MS) {
            try {
                val parsed = parseTileJson(diskFile.readText()).map { it.points }.filter { it.size >= 2 }
                if (parsed.isNotEmpty()) return@withContext parsed
            } catch (_: Exception) {
            }
        }

        fun extractWays(body: String): List<List<GeoPoint>> {
            val elements = JSONObject(body).optJSONArray("elements") ?: return emptyList()
            val lines = mutableListOf<List<GeoPoint>>()
            for (i in 0 until elements.length()) {
                val el = elements.optJSONObject(i) ?: continue
                if (el.optString("type") == "way") {
                    val geom = el.optJSONArray("geometry") ?: continue
                    val pts = ArrayList<GeoPoint>(geom.length())
                    for (g in 0 until geom.length()) {
                        val pt = geom.optJSONObject(g) ?: continue
                        val lat = pt.optDouble("lat", Double.NaN)
                        val lon = pt.optDouble("lon", Double.NaN)
                        if (!lat.isNaN() && !lon.isNaN()) pts.add(GeoPoint(lat, lon))
                    }
                    if (pts.size >= 2) lines.add(pts)
                }
            }
            return lines
        }

        fun matchesProximity(lines: List<List<GeoPoint>>): Boolean {
            val anchor = nearLatLng ?: return lines.isNotEmpty()
            return lines.any { pts ->
                pts.any { p -> p.distanceToAsDouble(anchor) <= 50_000.0 }
            }
        }

        if (allowNetwork) {
            try {
                val primaryQl = if (preferRelation) {
                    "[out:json][timeout:15];(relation($osmId);way(r););out body geom;"
                } else {
                    "[out:json][timeout:15];way($osmId);out body geom;"
                }
                val primaryBody = if (overpassEndpoints != null) {
                    OutdoorContextResolver.executeOverpassQuery(primaryQl, 6000, 15000, "TRAIL_ROUTE", endpoints = overpassEndpoints)
                } else {
                    OutdoorContextResolver.executeOverpassQuery(primaryQl, 6000, 15000, "TRAIL_ROUTE")
                }
                val primaryLines = primaryBody?.let { extractWays(it) }?.takeIf { matchesProximity(it) }

                val finalLines = primaryLines ?: run {
                    val secondaryQl = if (preferRelation) {
                        "[out:json][timeout:15];way($osmId);out body geom;"
                    } else {
                        "[out:json][timeout:15];(relation($osmId);way(r););out body geom;"
                    }
                    val secondaryBody = if (overpassEndpoints != null) {
                        OutdoorContextResolver.executeOverpassQuery(secondaryQl, 6000, 15000, "TRAIL_ROUTE", endpoints = overpassEndpoints)
                    } else {
                        OutdoorContextResolver.executeOverpassQuery(secondaryQl, 6000, 15000, "TRAIL_ROUTE")
                    }
                    secondaryBody?.let { extractWays(it) }?.takeIf { matchesProximity(it) } ?: emptyList()
                }

                if (finalLines.isNotEmpty()) {
                    val synthetic = finalLines.mapIndexed { idx, pts ->
                        TrailVectorSegment(id = osmId * 1000L + idx, name = "Trail #$osmId", points = pts)
                    }
                    saveTileJson(diskFile, synthetic)
                    return@withContext finalLines
                }
            } catch (e: Exception) {
                logDebug("fetchTrailRouteByOsmId($osmId) error: ${e.message}")
            }
        }

        val cachedFromMem = memoryCache.values.flatten()
            .filter { it.id == osmId || it.id / 100_000L == osmId || it.id / 1_000L == osmId }
            .map { it.points }
            .filter { it.size >= 2 }
        cachedFromMem
    }

    private fun readFreshCacheFile(file: File): String? {
        if (!file.exists()) return null
        if (System.currentTimeMillis() - file.lastModified() > CACHE_TTL_MS) return null
        return try {
            file.readText().takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun loadRelationMetaFromDisk(context: Context, subdomain: String, relId: Long): WaymarkedRelationMeta? {
        val file = File(getCacheDir(context), "wm_rel_${subdomain}_${relId}.json")
        val raw = readFreshCacheFile(file) ?: return null
        return try {
            val obj = JSONObject(raw)
            WaymarkedRelationMeta(
                relationId = obj.getLong("id"),
                name = obj.optString("name").takeIf { it.isNotBlank() && it != "null" },
                ref = obj.optString("ref").takeIf { it.isNotBlank() && it != "null" },
                route = obj.optString("route").takeIf { it.isNotBlank() && it != "null" },
                educational = obj.optBoolean("educational", false),
                osmcSymbol = obj.optString("osmcSymbol").takeIf { it.isNotBlank() && it != "null" },
                colour = obj.optString("colour").takeIf { it.isNotBlank() && it != "null" }
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun saveRelationMetaToDisk(context: Context, subdomain: String, meta: WaymarkedRelationMeta) {
        try {
            val file = File(getCacheDir(context), "wm_rel_${subdomain}_${meta.relationId}.json")
            val obj = JSONObject().apply {
                put("id", meta.relationId)
                put("name", meta.name ?: JSONObject.NULL)
                put("ref", meta.ref ?: JSONObject.NULL)
                put("route", meta.route ?: JSONObject.NULL)
                put("educational", meta.educational)
                put("osmcSymbol", meta.osmcSymbol ?: JSONObject.NULL)
                put("colour", meta.colour ?: JSONObject.NULL)
            }
            file.writeText(obj.toString())
        } catch (_: Exception) {
        }
    }

    private fun httpGetString(urlStr: String, connectTimeoutMs: Int, readTimeoutMs: Int): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json")
            }
            val code = conn.responseCode
            if (code == 200) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                logDebug("HTTP $code for $urlStr")
                if (code == 429) DataAcquisitionManager.reportRateLimited()
                else if (code >= 500) DataAcquisitionManager.reportCachedOnly("Trail Tiles HTTP $code")
                null
            }
        } catch (e: Exception) {
            logDebug("HTTP error for $urlStr: ${e.message}")
            DataAcquisitionManager.reportError(e)
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun extractRelationIdsFromWaymarkedTile(jsonStr: String): Set<Long> {
        return try {
            val root = JSONObject(jsonStr)
            val features = root.optJSONArray("features") ?: return emptySet()
            val ids = linkedSetOf<Long>()
            for (i in 0 until features.length()) {
                val props = features.optJSONObject(i)?.optJSONObject("properties") ?: continue
                if (props.optString("type") != "way") continue
                val top = props.optJSONArray("top_relations")
                if (top != null) {
                    for (j in 0 until top.length()) {
                        val id = top.optLong(j, -1L)
                        if (id > 0L) ids.add(id)
                    }
                }
            }
            ids
        } catch (_: Exception) {
            emptySet()
        }
    }

    /** Parses `/api/v1/details/relation/{id}` JSON into [WaymarkedRelationMeta]. */
    fun parseWaymarkedRelationDetailsJson(relationId: Long, jsonStr: String): WaymarkedRelationMeta? {
        return try {
            val root = JSONObject(jsonStr)
            val tags = root.optJSONObject("tags") ?: JSONObject()
            val name = root.optString("name").takeIf { it.isNotBlank() && it != "null" }
                ?: tags.optString("name").takeIf { it.isNotBlank() && it != "null" }
            val ref = root.optString("ref").takeIf { it.isNotBlank() && it != "null" }
                ?: tags.optString("ref").takeIf { it.isNotBlank() && it != "null" }
            val route = tags.optString("route").takeIf { it.isNotBlank() && it != "null" }
            val edu = tags.optString("educational").equals("yes", ignoreCase = true) ||
                route.equals("educational", ignoreCase = true)
            val osmc = tags.optString("osmc:symbol").takeIf { it.isNotBlank() && it != "null" }
            val colour = tags.optString("colour").ifEmpty { tags.optString("color") }
                .takeIf { it.isNotBlank() && it != "null" }
            WaymarkedRelationMeta(
                relationId = relationId,
                name = name,
                ref = ref,
                route = route,
                educational = edu,
                osmcSymbol = osmc,
                colour = colour
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Parses a Waymarked Trails shield identifier (such as `osmc_REG_white_yellow-bar`,
     * `osmc_REG_white_yellow-diamond_0054004d0042_black`, `cbox_LOC_red`, or `ctb_LOC_615CF3_00560048`)
     * into the dominant blaze [TrailColor], avoiding label text color false positives.
     */
    fun parseWaymarkedShieldColor(shield: String): TrailColor {
        val tokens = shield.split('_')
        if (tokens.size < 3) return TrailColor.NONE
        val hexTextRegex = Regex("^[0-9a-fA-F]{4,}$")
        val prefix = tokens[0].lowercase(Locale.US)

        if (prefix == "osmc") {
            val strippedEndIdx = tokens.indexOfFirst { tok ->
                tok.length >= 4 && tok.length % 4 == 0 && hexTextRegex.matches(tok)
            }
            val symbolTokens = if (strippedEndIdx >= 3) tokens.subList(0, strippedEndIdx) else tokens
            for (tok in symbolTokens.drop(3)) {
                val c = matchTrailColorToken(tok)
                if (c != TrailColor.NONE) return c
            }
            val bgColor = matchTrailColorToken(symbolTokens[2])
            if (bgColor != TrailColor.NONE) return bgColor
            return TrailColor.NONE
        }

        if (prefix == "cbox" || prefix == "ctb") {
            return matchTrailColorToken(tokens[2])
        }

        return TrailColor.NONE
    }

    private fun matchTrailColorToken(token: String): TrailColor {
        val lower = token.lowercase(Locale.US)
        val base = lower.substringBefore('-')
        val byWord = OutdoorContextResolver.parseTrailColor(null, base)
        if (byWord != TrailColor.NONE) return byWord
        val rawHex = token.removePrefix("#")
        if (rawHex.length == 6 && rawHex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            val rgb = rawHex.toInt(16)
            val r = (rgb shr 16) and 0xFF
            val g = (rgb shr 8) and 0xFF
            val b = rgb and 0xFF
            return when {
                r < 55 && g < 55 && b < 55 -> TrailColor.BLACK
                r > 180 && g > 160 && b < 110 -> TrailColor.YELLOW
                r >= g + 45 && r >= b + 45 -> TrailColor.RED
                g >= r + 35 && g >= b + 35 -> TrailColor.GREEN
                b >= r + 40 && b >= g + 25 -> TrailColor.BLUE
                else -> TrailColor.NONE
            }
        }
        return TrailColor.NONE
    }

    /** Decodes UTF-16BE hex ref payloads from Waymarked Trails shield strings (e.g., `ref_LOC_0054004a` -> `TJ`). */
    fun parseWaymarkedShieldRef(shield: String): String? {
        val tokens = shield.split('_')
        val hexTok = tokens.lastOrNull { tok ->
            tok.length >= 4 && tok.length % 4 == 0 && tok.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
        } ?: return null
        return try {
            val sb = StringBuilder(hexTok.length / 4)
            var idx = 0
            while (idx + 4 <= hexTok.length) {
                val codePoint = hexTok.substring(idx, idx + 4).toInt(16)
                if (codePoint >= 0x20) sb.append(codePoint.toChar())
                idx += 4
            }
            sb.toString().trim().takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Parses a Waymarked Trails `z=12` EPSG:3857 GeoJSON vector tile into [TrailVectorSegment]s,
     * applying cached/fetched [relationMeta] when available.
     */
    fun parseWaymarkedTileJson(
        jsonStr: String,
        subdomain: String,
        relationMeta: Map<String, WaymarkedRelationMeta> = emptyMap(),
        tileX: Int = 0,
        tileY: Int = 0
    ): List<TrailVectorSegment> {
        val root = try {
            JSONObject(jsonStr)
        } catch (_: Exception) {
            return emptyList()
        }
        val features = root.optJSONArray("features") ?: return emptyList()
        val segments = mutableListOf<TrailVectorSegment>()
        var localSeq = 0

        for (i in 0 until features.length()) {
            val feature = features.optJSONObject(i) ?: continue
            val props = feature.optJSONObject("properties") ?: continue
            if (props.optString("type") != "way") continue

            val geom = feature.optJSONObject("geometry") ?: continue
            val geomType = geom.optString("type")
            val coordsArr = geom.optJSONArray("coordinates") ?: continue

            val lines = mutableListOf<List<GeoPoint>>()
            when (geomType) {
                "LineString" -> {
                    val pts = parseMercatorLineCoords(coordsArr)
                    if (pts.size >= 2) lines.add(pts)
                }
                "MultiLineString" -> {
                    for (lineIdx in 0 until coordsArr.length()) {
                        val subCoords = coordsArr.optJSONArray(lineIdx) ?: continue
                        val pts = parseMercatorLineCoords(subCoords)
                        if (pts.size >= 2) lines.add(pts)
                    }
                }
                else -> continue
            }
            if (lines.isEmpty()) continue

            val shields = mutableListOf<String>()
            val shieldsArr = props.optJSONArray("shields")
            if (shieldsArr != null) {
                for (sIdx in 0 until shieldsArr.length()) {
                    val s = shieldsArr.optString(sIdx)
                    if (s.isNotBlank() && s != "null") shields.add(s)
                }
            }
            val rawShieldColors = shields.map { parseWaymarkedShieldColor(it) }
            val shieldColors = rawShieldColors.filter { it != TrailColor.NONE }
            val fallbackRef = shields.firstNotNullOfOrNull { parseWaymarkedShieldRef(it) }

            val relIds = mutableListOf<Long>()
            val topArr = props.optJSONArray("top_relations")
            if (topArr != null) {
                for (rIdx in 0 until topArr.length()) {
                    val id = topArr.optLong(rIdx, -1L)
                    if (id > 0L) relIds.add(id)
                }
            }
            if (relIds.isEmpty()) {
                val childArr = props.optJSONArray("child_relations")
                if (childArr != null) {
                    for (rIdx in 0 until childArr.length()) {
                        val id = childArr.optLong(rIdx, -1L)
                        if (id > 0L) relIds.add(id)
                    }
                }
            }

            if (relIds.isEmpty()) {
                val tileSalt = ((tileX * 73856093 xor tileY * 19349663) and 0x7FFF)
                val segColor = shieldColors.firstOrNull() ?: TrailColor.NONE
                val kind = when (subdomain) {
                    "cycling" -> TrailRouteKind.CYCLING
                    "mtb" -> TrailRouteKind.MTB
                    else -> TrailRouteKind.HIKING
                }
                for (pts in lines) {
                    localSeq++
                    val synthId = (tileSalt.toLong() shl 16) + localSeq
                    segments.add(
                        TrailVectorSegment(
                            id = synthId,
                            name = fallbackRef ?: "Marked Trail",
                            ref = fallbackRef,
                            kind = kind,
                            color = segColor,
                            points = pts
                        )
                    )
                }
                continue
            }

            for ((rOffset, relId) in relIds.withIndex()) {
                val meta = relationMeta["$subdomain:$relId"]
                val kind = when (subdomain) {
                    "cycling" -> TrailRouteKind.CYCLING
                    "mtb" -> TrailRouteKind.MTB
                    else -> if (meta?.educational == true) TrailRouteKind.EDUCATIONAL else TrailRouteKind.HIKING
                }
                val metaColor = if (meta != null) {
                    OutdoorContextResolver.parseTrailColor(meta.osmcSymbol, meta.colour)
                } else {
                    TrailColor.NONE
                }
                val finalColor = when {
                    metaColor != TrailColor.NONE -> metaColor
                    meta != null && relIds.size == 1 -> shieldColors.firstOrNull() ?: TrailColor.NONE
                    meta != null -> TrailColor.NONE
                    else -> rawShieldColors.getOrNull(rOffset) ?: shieldColors.firstOrNull() ?: TrailColor.NONE
                }
                val ref = meta?.ref ?: fallbackRef
                val name = meta?.name ?: ref ?: "Trail #$relId"

                for (pts in lines) {
                    localSeq++
                    val suffix = ((tileX * 73856093 xor tileY * 19349663 xor localSeq) and 0xFFFF).toLong()
                    segments.add(
                        TrailVectorSegment(
                            id = relId * 100_000L + suffix,
                            name = name,
                            ref = ref,
                            kind = kind,
                            color = finalColor,
                            points = pts
                        )
                    )
                }
            }
        }
        return segments
    }

    private fun parseMercatorLineCoords(coordsArr: JSONArray): List<GeoPoint> {
        val pts = ArrayList<GeoPoint>(coordsArr.length())
        for (k in 0 until coordsArr.length()) {
            val pair = coordsArr.optJSONArray(k) ?: continue
            if (pair.length() < 2) continue
            val mx = pair.optDouble(0, Double.NaN)
            val my = pair.optDouble(1, Double.NaN)
            if (!mx.isNaN() && !my.isNaN()) {
                pts.add(mercatorToLatLon(mx, my))
            }
        }
        return pts
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
