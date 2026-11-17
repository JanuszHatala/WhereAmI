package online.hatala.whereami

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathEffect
import android.graphics.Point
import android.view.MotionEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay
import java.util.concurrent.CopyOnWriteArrayList

class VectorTrailOverlay(
    private val context: Context,
    private val scope: CoroutineScope,
    var trailThickness: TrailThickness = TrailThickness.NORMAL,
    var trailLineStyle: TrailLineStyle = TrailLineStyle.SOLID,
    var trailOpacity: TrailOpacity = TrailOpacity.NORMAL,
    var enabledKinds: Set<TrailRouteKind> = emptySet(),
    var enabledColors: Set<TrailColor> = emptySet(),
    var supplementOsmTrails: Boolean = true,
    var onTrailSelected: ((TrailVectorSegment) -> Unit)? = null,
    var onLoadingStateChange: ((Boolean) -> Unit)? = null
) : Overlay() {

    companion object {
        private val ZOOM_WIDTH_ANCHORS = floatArrayOf(7.0f, 9.0f, 11.0f, 13.0f, 14.3f, 16.5f)
        private val ZOOM_WIDTH_DPS = floatArrayOf(1.00f, 1.30f, 1.65f, 2.00f, 2.35f, 2.80f)

        /**
         * Piecewise-linear zoom-tapered stroke width calibrated against the reference hiking zooms:
         * ~2.35 dp at 500 m (z~14.3), ~2.00 dp at 1 km (z~13.0), ~1.65 dp at 5 km (z~11.0).
         */
        fun computeStrokeWidthDp(zoom: Double, thickness: TrailThickness): Float {
            val z = zoom.toFloat()
            val baseWidthDp = when {
                z <= ZOOM_WIDTH_ANCHORS[0] -> ZOOM_WIDTH_DPS[0]
                z >= ZOOM_WIDTH_ANCHORS[ZOOM_WIDTH_ANCHORS.lastIndex] -> ZOOM_WIDTH_DPS[ZOOM_WIDTH_DPS.lastIndex]
                else -> {
                    var interpolated = ZOOM_WIDTH_DPS[0]
                    for (i in 0 until ZOOM_WIDTH_ANCHORS.lastIndex) {
                        val z0 = ZOOM_WIDTH_ANCHORS[i]
                        val z1 = ZOOM_WIDTH_ANCHORS[i + 1]
                        if (z in z0..z1) {
                            val t = (z - z0) / (z1 - z0)
                            interpolated = ZOOM_WIDTH_DPS[i] + (ZOOM_WIDTH_DPS[i + 1] - ZOOM_WIDTH_DPS[i]) * t
                            break
                        }
                    }
                    interpolated
                }
            }
            return baseWidthDp * thickness.widthMultiplier
        }

        internal fun computeHaloWidthPx(widthDp: Float, density: Float): Float {
            val strokeWidthPx = widthDp * density
            val extraDp = 0.65f * (widthDp / 2.2f).coerceIn(0.45f, 1.15f)
            return strokeWidthPx + extraDp * density
        }

        internal fun computeStrokeCap(style: TrailLineStyle): Paint.Cap = when (style) {
            TrailLineStyle.DASHED -> Paint.Cap.BUTT
            TrailLineStyle.SOLID, TrailLineStyle.DOTTED -> Paint.Cap.ROUND
        }

        fun matchesFilters(
            segment: TrailVectorSegment,
            enabledKinds: Set<TrailRouteKind>,
            enabledColors: Set<TrailColor>
        ): Boolean {
            val kindOk = enabledKinds.isEmpty() || segment.kind in enabledKinds
            val colorOk = enabledColors.isEmpty() || segment.color in enabledColors
            return kindOk && colorOk
        }

        fun resolveStrokeColorHex(trail: TrailVectorSegment): String {
            if (trail.color != TrailColor.NONE) return trail.color.hexColor
            return when (trail.kind) {
                TrailRouteKind.EDUCATIONAL -> "#14B8A6"
                TrailRouteKind.MTB -> "#F97316"
                TrailRouteKind.CYCLING -> "#8B5CF6"
                TrailRouteKind.KAYAKING -> "#06B6D4"
                TrailRouteKind.HIKING -> "#EF4444"
            }
        }

        internal fun resolveVisualColorBit(trail: TrailVectorSegment): Int {
            val slot = when (trail.color) {
                TrailColor.NONE -> when (trail.kind) {
                    TrailRouteKind.HIKING -> TrailColor.RED.ordinal
                    TrailRouteKind.MTB -> TrailColor.ORANGE.ordinal
                    TrailRouteKind.CYCLING -> TrailColor.PURPLE.ordinal
                    TrailRouteKind.KAYAKING -> TrailColor.CYAN.ordinal
                    TrailRouteKind.EDUCATIONAL -> TrailColor.values().size
                }
                else -> trail.color.ordinal
            }
            return 1 shl slot
        }

        /**
         * Lateral center offset in pixels for coincident stacked trails. Uses [spacingRatio] = 1.38f
         * so adjacent colors run cleanly side by side without overlapping or muddying each other.
         */
        internal fun computeLaneOffsetPx(
            rank: Int,
            count: Int,
            strokeWidthPx: Float,
            spacingRatio: Float = 1.38f
        ): Float {
            if (count <= 1) return 0f
            val centeredSlot = rank.toFloat() - (count - 1).toFloat() * 0.5f
            return centeredSlot * strokeWidthPx * spacingRatio
        }

        internal fun undirectedAngleBucket(dx: Float, dy: Float): Int {
            var angle = kotlin.math.atan2(dy.toDouble(), dx.toDouble())
            if (angle < 0.0) angle += kotlin.math.PI
            if (angle >= kotlin.math.PI) angle -= kotlin.math.PI
            val bucket = (angle / (kotlin.math.PI / 6.0)).toInt()
            return bucket.coerceIn(0, 5)
        }

        internal fun canonicalTangentSign(ux: Float, uy: Float, angleBucket: Int): Float {
            val needFlip = if (angleBucket == 0 || angleBucket == 5) {
                ux < 0f
            } else {
                uy < 0f
            }
            return if (needFlip) -1f else 1f
        }

        internal fun alignBundleNormal(
            ux: Float,
            uy: Float,
            refUx: Float,
            refUy: Float,
            prevNx: Float,
            prevNy: Float,
            insideBundle: Boolean
        ): Pair<Float, Float> {
            var nx = -uy
            var ny = ux
            if (insideBundle && (prevNx != 0f || prevNy != 0f)) {
                if (nx * prevNx + ny * prevNy < 0f) {
                    nx = -nx
                    ny = -ny
                }
            } else if (ux * refUx + uy * refUy < 0f) {
                nx = -nx
                ny = -ny
            }
            return nx to ny
        }

        internal fun bitRankInMask(mask: Int, singleBit: Int): Int =
            Integer.bitCount(mask and (singleBit - 1))

        /**
         * Computes `[dashOnPx, dashOffPx]` intervals matching the ~3:1 crisp dash-to-gap proportion
         * (combined with [Paint.Cap.BUTT] for [TrailLineStyle.DASHED]) across zoom levels.
         */
        internal fun computeDashIntervals(
            style: TrailLineStyle,
            strokeWidthPx: Float,
            density: Float
        ): FloatArray? {
            return when (style) {
                TrailLineStyle.SOLID -> null
                TrailLineStyle.DASHED -> floatArrayOf(
                    (strokeWidthPx * 5.5f).coerceAtLeast(6.0f * density),
                    (strokeWidthPx * 1.85f).coerceAtLeast(2.4f * density)
                )
                TrailLineStyle.DOTTED -> floatArrayOf(
                    (0.5f * density).coerceAtLeast(1f),
                    (strokeWidthPx * 2.1f).coerceAtLeast(3.5f * density)
                )
            }
        }
    }

    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.parseColor("#CC0F172A")
    }

    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.parseColor("#FFFFFF")
    }

    private val currentTrails = CopyOnWriteArrayList<TrailVectorSegment>()
    @Volatile private var trailsVersion: Long = 0L
    private var lastQueriedBbox: BoundingBox? = null
    private var lastFetchedKinds: Set<TrailRouteKind>? = null
    private var lastFetchedSupplementOsm: Boolean? = null
    private var lastFailedAtMs: Long = 0L
    private var lastFailedBbox: BoundingBox? = null
    private var fetchJob: Job? = null
    private var cachedEffectStyle: TrailLineStyle? = null
    private var cachedEffectWidthBucket: Int = -1
    private var cachedPathEffect: PathEffect? = null
    var selectedTrailId: Long? = null

    // Reusable per-frame scratch buffers to avoid allocations during draw()
    private val scratchP1 = Point()
    private val scratchP2 = Point()
    private val filteredTrailsScratch = ArrayList<TrailVectorSegment>(256)
    private var coordScratchPool = ArrayList<FloatArray>(256)
    private var coordScratchLens = IntArray(256)
    private var colorBitsScratch = IntArray(256)
    private var pathPool = ArrayList<Path>(256)
    private var drawColorInts = IntArray(256)
    private var drawColorEnums = arrayOfNulls<TrailColor>(256)
    private var drawTrailIds = LongArray(256)
    private var drawnCount: Int = 0
    private val cellGrid = PrimitiveCellGrid()

    private var lastDrawZoom: Double = Double.NaN
    private var lastDrawCenterLat: Double = Double.NaN
    private var lastDrawCenterLon: Double = Double.NaN
    private var lastDrawOrientation: Float = Float.NaN
    private var lastDrawWidth: Int = -1
    private var lastDrawHeight: Int = -1
    private var lastDrawTrailsVersion: Long = -1L
    private var lastDrawFilterHash: Int = 0
    private var lastDrawStyleHash: Int = 0
    private var lastDrawClustered: Boolean = true
    private var lastCameraMoveElapsedMs: Long = 0L
    private var settleRunnableScheduled: Boolean = false
    @Volatile private var loadingActive: Boolean = false

    private fun setLoading(active: Boolean) {
        if (loadingActive == active) return
        loadingActive = active
        onLoadingStateChange?.invoke(active)
    }

    private fun intersectsViewport(pts: List<GeoPoint>, latS: Double, latN: Double, lonW: Double, lonE: Double): Boolean {
        var minLat = Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE
        var minLon = Double.MAX_VALUE
        var maxLon = -Double.MAX_VALUE
        for (i in pts.indices) {
            val p = pts[i]
            if (p.latitude < minLat) minLat = p.latitude
            if (p.latitude > maxLat) maxLat = p.latitude
            if (p.longitude < minLon) minLon = p.longitude
            if (p.longitude > maxLon) maxLon = p.longitude
        }
        return !(maxLat < latS || minLat > latN || maxLon < lonW || minLon > lonE)
    }

    private class PrimitiveCellGrid {
        companion object {
            const val EMPTY_KEY = Long.MIN_VALUE
        }
        private var keys = LongArray(1024) { EMPTY_KEY }
        private var masks = IntArray(1024)
        private var refUxs = FloatArray(1024)
        private var refUys = FloatArray(1024)
        private var maskPow2 = 1023
        private var size = 0

        fun reset(expectedEntries: Int) {
            val needed = (expectedEntries * 2).coerceAtLeast(512)
            if (keys.size < needed) {
                var cap = 1024
                while (cap < needed) cap = cap shl 1
                keys = LongArray(cap) { EMPTY_KEY }
                masks = IntArray(cap)
                refUxs = FloatArray(cap)
                refUys = FloatArray(cap)
                maskPow2 = cap - 1
            } else {
                java.util.Arrays.fill(keys, EMPTY_KEY)
            }
            size = 0
        }

        private fun hashIndex(key: Long): Int {
            var h = key xor (key ushr 33)
            h *= -0xae502812aa7333L
            h = h xor (h ushr 33)
            return h.toInt() and maskPow2
        }

        private fun ensureCapacityForInsert() {
            if (size * 4 >= keys.size * 3) {
                val oldKeys = keys
                val oldMasks = masks
                val oldUx = refUxs
                val oldUy = refUys
                val newCap = oldKeys.size shl 1
                keys = LongArray(newCap) { EMPTY_KEY }
                masks = IntArray(newCap)
                refUxs = FloatArray(newCap)
                refUys = FloatArray(newCap)
                maskPow2 = newCap - 1
                size = 0
                for (i in oldKeys.indices) {
                    val k = oldKeys[i]
                    if (k != EMPTY_KEY) {
                        var idx = hashIndex(k)
                        while (keys[idx] != EMPTY_KEY) idx = (idx + 1) and maskPow2
                        keys[idx] = k
                        masks[idx] = oldMasks[i]
                        refUxs[idx] = oldUx[i]
                        refUys[idx] = oldUy[i]
                        size++
                    }
                }
            }
        }

        fun mark(key: Long, bit: Int, refUx: Float, refUy: Float) {
            ensureCapacityForInsert()
            var idx = hashIndex(key)
            while (true) {
                val existing = keys[idx]
                if (existing == EMPTY_KEY) {
                    keys[idx] = key
                    masks[idx] = bit
                    refUxs[idx] = refUx
                    refUys[idx] = refUy
                    size++
                    return
                }
                if (existing == key) {
                    masks[idx] = masks[idx] or bit
                    return
                }
                idx = (idx + 1) and maskPow2
            }
        }

        fun findSlot(key: Long): Int {
            var idx = hashIndex(key)
            while (true) {
                val existing = keys[idx]
                if (existing == key) return idx
                if (existing == EMPTY_KEY) return -1
                idx = (idx + 1) and maskPow2
            }
        }

        fun maskAt(slot: Int): Int = masks[slot]
        fun uxAt(slot: Int): Float = refUxs[slot]
        fun uyAt(slot: Int): Float = refUys[slot]
    }

    private fun resolvePathEffect(style: TrailLineStyle, strokeWidthPx: Float, density: Float): PathEffect? {
        val bucket = (strokeWidthPx * 4f).toInt().coerceAtLeast(1)
        if (cachedEffectStyle == style && cachedEffectWidthBucket == bucket) {
            return cachedPathEffect
        }
        val intervals = computeDashIntervals(style, strokeWidthPx, density)
        val effect = intervals?.let { DashPathEffect(it, 0f) }
        cachedEffectStyle = style
        cachedEffectWidthBucket = bucket
        cachedPathEffect = effect
        return effect
    }

    fun cancelLoading() {
        fetchJob?.cancel()
        fetchJob = null
        setLoading(false)
    }

    fun onFiltersUpdated(mapView: MapView) {
        updateTrailsForViewport(mapView)
        mapView.invalidate()
    }

    fun updateTrailsForViewport(mapView: MapView) {
        val bbox = mapView.boundingBox ?: return
        val kindsScope = enabledKinds.toSet()
        val supplementScope = supplementOsmTrails
        val kindsCovered = lastFetchedKinds?.let { prevKinds ->
            prevKinds.isEmpty() || (kindsScope.isNotEmpty() && kindsScope.all { it in prevKinds })
        } == true && lastFetchedSupplementOsm == supplementScope

        val prev = lastQueriedBbox
        if (kindsCovered &&
            prev != null &&
            bbox.latSouth >= prev.latSouth &&
            bbox.latNorth <= prev.latNorth &&
            bbox.lonWest >= prev.lonWest &&
            bbox.lonEast <= prev.lonEast &&
            currentTrails.isNotEmpty()
        ) {
            return
        }

        if (fetchJob?.isActive == true) return

        val now = android.os.SystemClock.elapsedRealtime()
        val fBox = lastFailedBbox
        if (kindsCovered && currentTrails.isEmpty() && fBox != null && (now - lastFailedAtMs) < 15_000L) {
            val sameArea = kotlin.math.abs(bbox.latSouth - fBox.latSouth) < 0.004 &&
                kotlin.math.abs(bbox.latNorth - fBox.latNorth) < 0.004 &&
                kotlin.math.abs(bbox.lonWest - fBox.lonWest) < 0.004 &&
                kotlin.math.abs(bbox.lonEast - fBox.lonEast) < 0.004
            if (sameArea) return
        }

        // Expand query bbox by 25% to avoid fetching on small pans
        val latSpan = bbox.latNorth - bbox.latSouth
        val lonSpan = bbox.lonEast - bbox.lonWest
        val expanded = BoundingBox(
            bbox.latNorth + latSpan * 0.25,
            bbox.lonEast + lonSpan * 0.25,
            bbox.latSouth - latSpan * 0.25,
            bbox.lonWest - lonSpan * 0.25
        )

        fetchJob = scope.launch(Dispatchers.IO) {
            try {
                // Phase 1: Immediately serve cached offline vector tiles so trails appear instantaneously
                val cachedTrails = TrailVectorRepository.getTrailsForBoundingBox(
                    context = context,
                    bbox = expanded,
                    allowNetwork = false,
                    requestedKinds = kindsScope,
                    supplementOsmTrails = supplementOsmTrails
                )
                if (cachedTrails.isNotEmpty()) {
                    currentTrails.clear()
                    currentTrails.addAll(cachedTrails)
                    trailsVersion++
                    mapView.postInvalidate()
                }

                // Phase 2: Stream network tiles progressively and enrich metadata / Overpass supplement
                setLoading(true)
                val trails = TrailVectorRepository.getTrailsForBoundingBox(
                    context = context,
                    bbox = expanded,
                    allowNetwork = true,
                    requestedKinds = kindsScope,
                    supplementOsmTrails = supplementOsmTrails,
                    onProgressiveUpdate = { partial ->
                        if (partial.isNotEmpty()) {
                            currentTrails.clear()
                            currentTrails.addAll(partial)
                            trailsVersion++
                            mapView.postInvalidate()
                        }
                    }
                )
                if (trails.isNotEmpty()) {
                    currentTrails.clear()
                    currentTrails.addAll(trails)
                    trailsVersion++
                    lastQueriedBbox = expanded
                    lastFetchedKinds = kindsScope
                    lastFetchedSupplementOsm = supplementScope
                    lastFailedBbox = null
                } else {
                    val centerInPrev = prev != null &&
                        bbox.centerLatitude >= prev.latSouth && bbox.centerLatitude <= prev.latNorth &&
                        bbox.centerLongitude >= prev.lonWest && bbox.centerLongitude <= prev.lonEast
                    if (!centerInPrev && cachedTrails.isEmpty()) {
                        currentTrails.clear()
                        trailsVersion++
                    }
                    lastQueriedBbox = null
                    lastFetchedKinds = kindsScope
                    lastFetchedSupplementOsm = supplementScope
                    lastFailedAtMs = android.os.SystemClock.elapsedRealtime()
                    lastFailedBbox = bbox
                }
                mapView.postInvalidate()
            } finally {
                setLoading(false)
            }
        }
    }

    private val colorIntCache = HashMap<String, Int>(16)

    private fun resolveStrokeColorInt(trail: TrailVectorSegment): Int {
        val hex = resolveStrokeColorHex(trail)
        return colorIntCache.getOrPut(hex) {
            try {
                Color.parseColor(hex)
            } catch (_: Exception) {
                Color.parseColor("#EF4444")
            }
        }
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val zoom = mapView.zoomLevelDouble

        updateTrailsForViewport(mapView)

        val filterHash = enabledKinds.hashCode() * 31 + enabledColors.hashCode()
        val styleHash = ((trailThickness.ordinal * 31 + trailLineStyle.ordinal) * 31 + trailOpacity.ordinal)
        val center = mapView.mapCenter
        val centerLat = center.latitude
        val centerLon = center.longitude
        val orientation = mapView.mapOrientation
        val mapWidth = mapView.width
        val mapHeight = mapView.height
        val rev = trailsVersion

        val density = context.resources.displayMetrics.density
        val widthDp = computeStrokeWidthDp(zoom, trailThickness)
        val strokeWidthPx = widthDp * density
        val haloWidthPx = computeHaloWidthPx(widthDp, density)
        val pathEffect = resolvePathEffect(trailLineStyle, strokeWidthPx, density)
        val strokeCap = computeStrokeCap(trailLineStyle)
        val trailAlpha = (255f * trailOpacity.alphaFraction).toInt().coerceIn(0, 255)
        val darkHaloAlpha = (0x45 * trailOpacity.alphaFraction).toInt().coerceIn(0, 255)
        val lightColorHaloAlpha = (0x88 * trailOpacity.alphaFraction).toInt().coerceIn(0, 255)

        haloPaint.strokeWidth = haloWidthPx
        haloPaint.strokeCap = strokeCap
        haloPaint.pathEffect = pathEffect

        trailPaint.strokeWidth = strokeWidthPx
        trailPaint.strokeCap = strokeCap
        trailPaint.pathEffect = pathEffect

        highlightPaint.strokeWidth = strokeWidthPx + 3.5f * density
        highlightPaint.strokeCap = strokeCap

        val cameraChanged = zoom != lastDrawZoom ||
            centerLat != lastDrawCenterLat ||
            centerLon != lastDrawCenterLon ||
            orientation != lastDrawOrientation
        val nowElapsed = android.os.SystemClock.elapsedRealtime()
        if (cameraChanged && !lastDrawZoom.isNaN()) {
            lastCameraMoveElapsedMs = nowElapsed
        }
        val isActivelyMoving = (nowElapsed - lastCameraMoveElapsedMs) < 110L
        val useClusteredLanes = !isActivelyMoving

        val canReplayCachedPaths =
            rev == lastDrawTrailsVersion &&
            filterHash == lastDrawFilterHash &&
            styleHash == lastDrawStyleHash &&
            zoom == lastDrawZoom &&
            centerLat == lastDrawCenterLat &&
            centerLon == lastDrawCenterLon &&
            orientation == lastDrawOrientation &&
            mapWidth == lastDrawWidth &&
            mapHeight == lastDrawHeight &&
            useClusteredLanes == lastDrawClustered

        if (!canReplayCachedPaths) {
            filteredTrailsScratch.clear()
            val vBox = mapView.boundingBox
            val padLat = if (vBox != null) (vBox.latNorth - vBox.latSouth) * 0.1 else 0.0
            val padLon = if (vBox != null) (vBox.lonEast - vBox.lonWest) * 0.1 else 0.0
            for (seg in currentTrails) {
                if (!matchesFilters(seg, enabledKinds, enabledColors)) continue
                if (vBox != null && seg.points.size >= 2 &&
                    !intersectsViewport(seg.points, vBox.latSouth - padLat, vBox.latNorth + padLat, vBox.lonWest - padLon, vBox.lonEast + padLon)
                ) {
                    continue
                }
                filteredTrailsScratch.add(seg)
            }
            if (filteredTrailsScratch.isEmpty()) {
                drawnCount = 0
                lastDrawTrailsVersion = rev
                lastDrawFilterHash = filterHash
                lastDrawStyleHash = styleHash
                lastDrawZoom = zoom
                lastDrawCenterLat = centerLat
                lastDrawCenterLon = centerLon
                lastDrawOrientation = orientation
                lastDrawWidth = mapWidth
                lastDrawHeight = mapHeight
                return
            }

            val proj = mapView.projection ?: return
            val count = filteredTrailsScratch.size
            if (coordScratchLens.size < count) {
                coordScratchLens = IntArray(count)
                colorBitsScratch = IntArray(count)
                drawColorInts = IntArray(count)
                drawColorEnums = arrayOfNulls(count)
                drawTrailIds = LongArray(count)
            }
            while (coordScratchPool.size < count) {
                coordScratchPool.add(FloatArray(64))
            }
            while (pathPool.size < count) {
                pathPool.add(Path())
            }

            val minStepPxSq = (1.5f * density) * (1.5f * density)
            val cellPx = (strokeWidthPx * 2.4f).coerceIn(4.5f * density, 8.0f * density)
            val tolPx = (strokeWidthPx * 0.9f).coerceIn(1.5f * density, 2.8f * density)
            if (useClusteredLanes) {
                cellGrid.reset(count * 8)
            }

            fun cellKey(cx: Int, cy: Int, ang: Int): Long =
                ((cx.toLong() and 0xFFFFF) shl 24) or ((cy.toLong() and 0xFFFFF) shl 4) or (ang.toLong() and 0xF)

            fun markCell(sx: Float, sy: Float, ang: Int, bit: Int, refUx: Float, refUy: Float) {
                val cx0 = kotlin.math.floor((sx - tolPx) / cellPx).toInt()
                val cx1 = kotlin.math.floor((sx + tolPx) / cellPx).toInt()
                val cy0 = kotlin.math.floor((sy - tolPx) / cellPx).toInt()
                val cy1 = kotlin.math.floor((sy + tolPx) / cellPx).toInt()
                for (cx in cx0..cx1) {
                    for (cy in cy0..cy1) {
                        cellGrid.mark(cellKey(cx, cy, ang), bit, refUx, refUy)
                    }
                }
            }

            for (tIdx in 0 until count) {
                val trail = filteredTrailsScratch[tIdx]
                val pts = trail.points
                if (pts.size < 2) {
                    coordScratchLens[tIdx] = 0
                    continue
                }
                val needed = pts.size * 2
                var buf = coordScratchPool[tIdx]
                if (buf.size < needed) {
                    buf = FloatArray(needed)
                    coordScratchPool[tIdx] = buf
                }
                val floatLen = projectDecimatedScreenCoordsInto(proj, pts, scratchP1, scratchP2, minStepPxSq, buf)
                if (floatLen < 4) {
                    coordScratchLens[tIdx] = 0
                    continue
                }
                coordScratchLens[tIdx] = floatLen
                val bit = resolveVisualColorBit(trail)
                colorBitsScratch[tIdx] = bit

                if (useClusteredLanes) {
                    val vCount = floatLen / 2
                    for (i in 0 until vCount - 1) {
                        val ax = buf[i * 2]
                        val ay = buf[i * 2 + 1]
                        val bx = buf[(i + 1) * 2]
                        val by = buf[(i + 1) * 2 + 1]
                        val dx = bx - ax
                        val dy = by - ay
                        val segLen = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
                        if (segLen < 0.5f) continue
                        val ang = undirectedAngleBucket(dx, dy)
                        val ux = dx / segLen
                        val uy = dy / segLen
                        val sign = canonicalTangentSign(ux, uy, ang)
                        val refUx = ux * sign
                        val refUy = uy * sign
                        val steps = ((segLen / cellPx).toInt()).coerceAtLeast(1).coerceAtMost(32)
                        for (s in 0..steps) {
                            val t = s.toFloat() / steps.toFloat()
                            markCell(ax + dx * t, ay + dy * t, ang, bit, refUx, refUy)
                        }
                    }
                }
            }

            var outIdx = 0
            for (tIdx in 0 until count) {
                val floatLen = coordScratchLens[tIdx]
                if (floatLen < 4) continue
                val coords = coordScratchPool[tIdx]
                val path = pathPool[outIdx]
                path.rewind()
                val vCount = floatLen / 2
                val selfBit = colorBitsScratch[tIdx]
                var prevNx = 0f
                var prevNy = 0f
                var prevCount = 0

                for (i in 0 until vCount) {
                    val vx = coords[i * 2]
                    val vy = coords[i * 2 + 1]
                    val prevIdx = if (i > 0) i - 1 else 0
                    val nextIdx = if (i < vCount - 1) i + 1 else vCount - 1
                    val tx = coords[nextIdx * 2] - coords[prevIdx * 2]
                    val ty = coords[nextIdx * 2 + 1] - coords[prevIdx * 2 + 1]
                    val tLen = kotlin.math.hypot(tx.toDouble(), ty.toDouble()).toFloat()
                    val offsetX: Float
                    val offsetY: Float
                    if (useClusteredLanes && tLen > 0.001f) {
                        val ux = tx / tLen
                        val uy = ty / tLen
                        val ang = undirectedAngleBucket(ux, uy)
                        val cx = kotlin.math.floor(vx / cellPx).toInt()
                        val cy = kotlin.math.floor(vy / cellPx).toInt()
                        val prevAng = (ang + 5) % 6
                        val nextAng = (ang + 1) % 6
                        val sExact = cellGrid.findSlot(cellKey(cx, cy, ang))
                        val sPrev = cellGrid.findSlot(cellKey(cx, cy, prevAng))
                        val sNext = cellGrid.findSlot(cellKey(cx, cy, nextAng))

                        var mask = selfBit
                        if (sExact >= 0) mask = mask or cellGrid.maskAt(sExact)
                        if (sPrev >= 0) mask = mask or cellGrid.maskAt(sPrev)
                        if (sNext >= 0) mask = mask or cellGrid.maskAt(sNext)

                        val refSlot = when {
                            sExact >= 0 -> sExact
                            sPrev >= 0 -> sPrev
                            sNext >= 0 -> sNext
                            else -> -1
                        }
                        val refUx = if (refSlot >= 0) cellGrid.uxAt(refSlot) else ux
                        val refUy = if (refSlot >= 0) cellGrid.uyAt(refSlot) else uy

                        val bundleCount = Integer.bitCount(mask)
                        var nx = -uy
                        var ny = ux
                        if (prevCount > 1 && bundleCount > 1 && (prevNx != 0f || prevNy != 0f)) {
                            if (nx * prevNx + ny * prevNy < 0f) {
                                nx = -nx
                                ny = -ny
                            }
                        } else if (ux * refUx + uy * refUy < 0f) {
                            nx = -nx
                            ny = -ny
                        }
                        prevNx = nx
                        prevNy = ny
                        prevCount = bundleCount
                        val offsetPx = if (bundleCount > 1) {
                            val rank = bitRankInMask(mask, selfBit)
                            computeLaneOffsetPx(rank, bundleCount, strokeWidthPx)
                        } else 0f
                        offsetX = vx + nx * offsetPx
                        offsetY = vy + ny * offsetPx
                    } else {
                        offsetX = vx
                        offsetY = vy
                    }
                    if (i == 0) path.moveTo(offsetX, offsetY) else path.lineTo(offsetX, offsetY)
                }

                val trail = filteredTrailsScratch[tIdx]
                drawColorInts[outIdx] = resolveStrokeColorInt(trail)
                drawColorEnums[outIdx] = trail.color
                drawTrailIds[outIdx] = trail.id
                outIdx++
            }
            drawnCount = outIdx
            lastDrawTrailsVersion = rev
            lastDrawFilterHash = filterHash
            lastDrawStyleHash = styleHash
            lastDrawZoom = zoom
            lastDrawCenterLat = centerLat
            lastDrawCenterLon = centerLon
            lastDrawOrientation = orientation
            lastDrawWidth = mapWidth
            lastDrawHeight = mapHeight
            lastDrawClustered = useClusteredLanes
            if (!useClusteredLanes && !settleRunnableScheduled) {
                settleRunnableScheduled = true
                mapView.postDelayed({
                    settleRunnableScheduled = false
                    mapView.invalidate()
                }, 130L)
            }
        }

        if (drawnCount == 0) return

        // 1. Draw Thin Contrast Halo (stronger for Yellow/White so light blazes stay crisp on pale roads)
        for (i in 0 until drawnCount) {
            val color = drawColorEnums[i]
            haloPaint.alpha = if (color == TrailColor.YELLOW || color == TrailColor.WHITE) lightColorHaloAlpha else darkHaloAlpha
            canvas.drawPath(pathPool[i], haloPaint)
        }

        // 2. Draw Colored Strokes
        val selId = selectedTrailId
        for (i in 0 until drawnCount) {
            trailPaint.color = drawColorInts[i]
            trailPaint.alpha = trailAlpha
            val p = pathPool[i]
            canvas.drawPath(p, trailPaint)

            if (selId != null && drawTrailIds[i] == selId) {
                canvas.drawPath(p, highlightPaint)
            }
        }
    }

    private fun projectDecimatedScreenCoordsInto(
        proj: Projection,
        pts: List<GeoPoint>,
        p1: Point,
        p2: Point,
        minStepPxSq: Float,
        outBuf: FloatArray
    ): Int {
        proj.toPixels(pts[0], p1)
        var lastX = p1.x.toFloat()
        var lastY = p1.y.toFloat()
        outBuf[0] = lastX
        outBuf[1] = lastY
        var count = 1
        val lastIdx = pts.size - 1
        for (i in 1 until lastIdx) {
            proj.toPixels(pts[i], p2)
            val x = p2.x.toFloat()
            val y = p2.y.toFloat()
            val dx = x - lastX
            val dy = y - lastY
            if (dx * dx + dy * dy >= minStepPxSq) {
                outBuf[count * 2] = x
                outBuf[count * 2 + 1] = y
                count++
                lastX = x
                lastY = y
            }
        }
        proj.toPixels(pts[lastIdx], p2)
        val endX = p2.x.toFloat()
        val endY = p2.y.toFloat()
        outBuf[count * 2] = endX
        outBuf[count * 2 + 1] = endY
        count++
        return count * 2
    }

    override fun onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean {
        val proj = mapView.projection ?: return false
        val tapX = e.x
        val tapY = e.y
        val density = context.resources.displayMetrics.density
        val hitRadiusPx = 22.0f * density

        val p1 = scratchP1
        val p2 = scratchP2

        var nearestTrail: TrailVectorSegment? = null
        var minDistancePx = Float.MAX_VALUE

        for (trail in currentTrails) {
            if (!matchesFilters(trail, enabledKinds, enabledColors)) continue
            val pts = trail.points
            for (i in 0 until pts.size - 1) {
                proj.toPixels(pts[i], p1)
                proj.toPixels(pts[i + 1], p2)
                val dist = distanceToSegment(tapX, tapY, p1.x.toFloat(), p1.y.toFloat(), p2.x.toFloat(), p2.y.toFloat())
                if (dist <= hitRadiusPx && dist < minDistancePx) {
                    minDistancePx = dist
                    nearestTrail = trail
                }
            }
        }

        if (nearestTrail != null) {
            selectedTrailId = nearestTrail.id
            if (nearestTrail.name.startsWith("Trail #")) {
                scope.launch(Dispatchers.IO) {
                    val enriched = TrailVectorRepository.resolveTrailDetailsIfMissing(context, nearestTrail)
                    launch(Dispatchers.Main) {
                        onTrailSelected?.invoke(enriched)
                    }
                }
            } else {
                onTrailSelected?.invoke(nearestTrail)
            }
            mapView.invalidate()
            return true
        }

        return false
    }

    private fun distanceToSegment(px: Float, py: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        if (dx == 0f && dy == 0f) {
            val ddx = px - x1
            val ddy = py - y1
            return kotlin.math.sqrt(ddx * ddx + ddy * ddy)
        }
        val t = (((px - x1) * dx + (py - y1) * dy) / (dx * dx + dy * dy)).coerceIn(0f, 1f)
        val projX = x1 + t * dx
        val projY = y1 + t * dy
        val ddx = px - projX
        val ddy = py - projY
        return kotlin.math.sqrt(ddx * ddx + ddy * ddy)
    }
}
