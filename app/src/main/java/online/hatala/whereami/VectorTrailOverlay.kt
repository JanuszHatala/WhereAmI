package online.hatala.whereami

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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
    var enabledKinds: Set<TrailRouteKind> = emptySet(),
    var enabledColors: Set<TrailColor> = emptySet(),
    var onTrailSelected: ((TrailVectorSegment) -> Unit)? = null
) : Overlay() {

    companion object {
        fun matchesFilters(
            segment: TrailVectorSegment,
            enabledKinds: Set<TrailRouteKind>,
            enabledColors: Set<TrailColor>
        ): Boolean {
            val kindOk = enabledKinds.isEmpty() || segment.kind in enabledKinds
            val colorOk = enabledColors.isEmpty() || segment.color in enabledColors
            return kindOk && colorOk
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
    private var fetchJob: Job? = null
    var selectedTrailId: Long? = null

    fun updateTrailsForViewport(mapView: MapView) {
        if (mapView.zoomLevelDouble < 12.0) {
            currentTrails.clear()
            lastQueriedBbox = null
            return
        }

        val bbox = mapView.boundingBox ?: return
        val prev = lastQueriedBbox
        if (prev != null &&
            bbox.latSouth >= prev.latSouth &&
            bbox.latNorth <= prev.latNorth &&
            bbox.lonWest >= prev.lonWest &&
            bbox.lonEast <= prev.lonEast &&
            currentTrails.isNotEmpty()
        ) {
            return
        }

        if (fetchJob?.isActive == true) return

        // Expand query bbox by 25% to avoid fetching on small pans
        val latSpan = bbox.latNorth - bbox.latSouth
        val lonSpan = bbox.lonEast - bbox.lonWest
        val expanded = BoundingBox(
            bbox.latNorth + latSpan * 0.25,
            bbox.lonEast + lonSpan * 0.25,
            bbox.latSouth - latSpan * 0.25,
            bbox.lonWest - lonSpan * 0.25
        )

        lastQueriedBbox = expanded
        fetchJob = scope.launch(Dispatchers.IO) {
            val trails = TrailVectorRepository.getTrailsForBoundingBox(context, expanded)
            currentTrails.clear()
            currentTrails.addAll(trails)
            launch(Dispatchers.Main) {
                mapView.invalidate()
            }
        }
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val zoom = mapView.zoomLevelDouble
        if (zoom < 12.0) return

        updateTrailsForViewport(mapView)
        val trailsToDraw = currentTrails.filter { matchesFilters(it, enabledKinds, enabledColors) }
        if (trailsToDraw.isEmpty()) return

        val proj = mapView.projection ?: return
        val density = context.resources.displayMetrics.density

        val baseWidthDp = when {
            zoom >= 16.5 -> 7.0f
            zoom >= 15.0 -> 5.5f
            zoom >= 13.5 -> 4.5f
            else -> 3.2f
        }
        val strokeWidthPx = baseWidthDp * trailThickness.widthMultiplier * density
        val haloWidthPx = strokeWidthPx + 3.0f * density

        haloPaint.strokeWidth = haloWidthPx
        trailPaint.strokeWidth = strokeWidthPx
        highlightPaint.strokeWidth = strokeWidthPx + 4.0f * density

        val p1 = Point()
        val p2 = Point()
        val path = Path()

        // 1. Draw Halo Casing
        for (trail in trailsToDraw) {
            val pts = trail.points
            if (pts.size < 2) continue
            path.reset()
            proj.toPixels(pts[0], p1)
            path.moveTo(p1.x.toFloat(), p1.y.toFloat())
            for (i in 1 until pts.size) {
                proj.toPixels(pts[i], p2)
                path.lineTo(p2.x.toFloat(), p2.y.toFloat())
            }
            canvas.drawPath(path, haloPaint)
        }

        // 2. Draw Colored Strokes
        for (trail in trailsToDraw) {
            val pts = trail.points
            if (pts.size < 2) continue
            path.reset()
            proj.toPixels(pts[0], p1)
            path.moveTo(p1.x.toFloat(), p1.y.toFloat())
            for (i in 1 until pts.size) {
                proj.toPixels(pts[i], p2)
                path.lineTo(p2.x.toFloat(), p2.y.toFloat())
            }
            val colorArgb = try {
                Color.parseColor(trail.color.hexColor)
            } catch (_: Exception) {
                Color.parseColor("#EF4444")
            }
            trailPaint.color = colorArgb
            canvas.drawPath(path, trailPaint)

            if (selectedTrailId != null && trail.id == selectedTrailId) {
                canvas.drawPath(path, highlightPaint)
            }
        }
    }

    override fun onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean {
        if (mapView.zoomLevelDouble < 12.0) return false
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
            onTrailSelected?.invoke(nearestTrail)
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
