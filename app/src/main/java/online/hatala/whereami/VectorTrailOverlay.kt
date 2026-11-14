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
    var onTrailSelected: ((TrailVectorSegment) -> Unit)? = null
) : Overlay() {

    companion object {
        fun computeStrokeWidthDp(zoom: Double, thickness: TrailThickness): Float {
            val baseWidthDp = when {
                zoom >= 16.5 -> 2.8f
                zoom >= 15.0 -> 2.2f
                zoom >= 13.5 -> 1.8f
                zoom >= 11.5 -> 1.35f
                zoom >= 9.0 -> 1.05f
                else -> 0.85f
            }
            return baseWidthDp * thickness.widthMultiplier
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
    private var lastQueriedBbox: BoundingBox? = null
    private var lastFetchedKinds: Set<TrailRouteKind>? = null
    private var lastFailedAtMs: Long = 0L
    private var lastFailedBbox: BoundingBox? = null
    private var fetchJob: Job? = null
    private var cachedEffectStyle: TrailLineStyle? = null
    private var cachedEffectWidthBucket: Int = -1
    private var cachedPathEffect: PathEffect? = null
    var selectedTrailId: Long? = null

    private fun resolvePathEffect(style: TrailLineStyle, strokeWidthPx: Float, density: Float): PathEffect? {
        val bucket = (strokeWidthPx * 2f).toInt().coerceAtLeast(1)
        if (cachedEffectStyle == style && cachedEffectWidthBucket == bucket) {
            return cachedPathEffect
        }
        val effect = when (style) {
            TrailLineStyle.SOLID -> null
            TrailLineStyle.DASHED -> DashPathEffect(
                floatArrayOf((strokeWidthPx * 3.2f).coerceAtLeast(6f * density), (strokeWidthPx * 2.0f).coerceAtLeast(4f * density)),
                0f
            )
            TrailLineStyle.DOTTED -> DashPathEffect(
                floatArrayOf((0.6f * density).coerceAtLeast(1f), (strokeWidthPx * 2.1f).coerceAtLeast(4f * density)),
                0f
            )
        }
        cachedEffectStyle = style
        cachedEffectWidthBucket = bucket
        cachedPathEffect = effect
        return effect
    }

    fun onFiltersUpdated(mapView: MapView) {
        updateTrailsForViewport(mapView)
        mapView.invalidate()
    }

    fun updateTrailsForViewport(mapView: MapView) {
        val bbox = mapView.boundingBox ?: return
        val kindsScope = enabledKinds.toSet()
        val kindsCovered = lastFetchedKinds?.let { prevKinds ->
            prevKinds.isEmpty() || (kindsScope.isNotEmpty() && kindsScope.all { it in prevKinds })
        } == true

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
            val trails = TrailVectorRepository.getTrailsForBoundingBox(
                context = context,
                bbox = expanded,
                requestedKinds = kindsScope
            )
            if (trails.isNotEmpty()) {
                currentTrails.clear()
                currentTrails.addAll(trails)
                lastQueriedBbox = expanded
                lastFetchedKinds = kindsScope
                lastFailedBbox = null
            } else {
                val centerInPrev = prev != null &&
                    bbox.centerLatitude >= prev.latSouth && bbox.centerLatitude <= prev.latNorth &&
                    bbox.centerLongitude >= prev.lonWest && bbox.centerLongitude <= prev.lonEast
                if (!centerInPrev) {
                    currentTrails.clear()
                }
                lastQueriedBbox = null
                lastFetchedKinds = kindsScope
                lastFailedAtMs = android.os.SystemClock.elapsedRealtime()
                lastFailedBbox = bbox
            }
            launch(Dispatchers.Main) {
                mapView.invalidate()
            }
        }
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val zoom = mapView.zoomLevelDouble

        updateTrailsForViewport(mapView)
        val trailsToDraw = currentTrails.filter { matchesFilters(it, enabledKinds, enabledColors) }
        if (trailsToDraw.isEmpty()) return

        val proj = mapView.projection ?: return
        val density = context.resources.displayMetrics.density

        val widthDp = computeStrokeWidthDp(zoom, trailThickness)
        val strokeWidthPx = widthDp * density
        val haloWidthPx = strokeWidthPx + (2.4f * density * (widthDp / 2.8f).coerceIn(0.45f, 1.35f))

        val pathEffect = resolvePathEffect(trailLineStyle, strokeWidthPx, density)
        val trailAlpha = (255f * trailOpacity.alphaFraction).toInt().coerceIn(0, 255)
        val haloAlpha = (0xCC * trailOpacity.alphaFraction).toInt().coerceIn(0, 255)

        haloPaint.strokeWidth = haloWidthPx
        haloPaint.alpha = haloAlpha
        haloPaint.pathEffect = pathEffect

        trailPaint.strokeWidth = strokeWidthPx
        trailPaint.pathEffect = pathEffect

        highlightPaint.strokeWidth = strokeWidthPx + 4.0f * density

        val minStepPxSq = (2.0f * density) * (2.0f * density)
        val p1 = Point()
        val p2 = Point()
        val path = Path()

        // 1. Draw Halo Casing
        for (trail in trailsToDraw) {
            val pts = trail.points
            if (pts.size < 2) continue
            buildScreenPath(proj, pts, p1, p2, minStepPxSq, path)
            canvas.drawPath(path, haloPaint)
        }

        // 2. Draw Colored Strokes
        for (trail in trailsToDraw) {
            val pts = trail.points
            if (pts.size < 2) continue
            buildScreenPath(proj, pts, p1, p2, minStepPxSq, path)
            val colorArgb = try {
                Color.parseColor(resolveStrokeColorHex(trail))
            } catch (_: Exception) {
                Color.parseColor("#EF4444")
            }
            trailPaint.color = colorArgb
            trailPaint.alpha = trailAlpha
            canvas.drawPath(path, trailPaint)

            if (selectedTrailId != null && trail.id == selectedTrailId) {
                canvas.drawPath(path, highlightPaint)
            }
        }
    }

    private fun buildScreenPath(
        proj: Projection,
        pts: List<GeoPoint>,
        p1: Point,
        p2: Point,
        minStepPxSq: Float,
        path: Path
    ) {
        path.reset()
        proj.toPixels(pts[0], p1)
        var lastX = p1.x.toFloat()
        var lastY = p1.y.toFloat()
        path.moveTo(lastX, lastY)
        val lastIdx = pts.size - 1
        for (i in 1 until lastIdx) {
            proj.toPixels(pts[i], p2)
            val x = p2.x.toFloat()
            val y = p2.y.toFloat()
            val dx = x - lastX
            val dy = y - lastY
            if (dx * dx + dy * dy >= minStepPxSq) {
                path.lineTo(x, y)
                lastX = x
                lastY = y
            }
        }
        proj.toPixels(pts[lastIdx], p2)
        path.lineTo(p2.x.toFloat(), p2.y.toFloat())
    }

    override fun onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean {
        val proj = mapView.projection ?: return false
        val tapX = e.x
        val tapY = e.y
        val density = context.resources.displayMetrics.density
        val hitRadiusPx = 22.0f * density

        val p1 = Point()
        val p2 = Point()

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
