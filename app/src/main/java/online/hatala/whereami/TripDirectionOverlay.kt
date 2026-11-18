package online.hatala.whereami

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Renders high-contrast directional chevrons along trip polyline segments to indicate travel direction.
 * Chevrons use a dual-tone design (crisp white chevron with subtle dark slate outline) and are spaced
 * at fixed screen intervals (~100-120 dp) so they remain clean, legible, and un-crowded at every zoom level.
 */
class TripDirectionOverlay(
    private var colorArgb: Int = Color.WHITE,
    private var alpha: Int = 255,
    private var haloColorArgb: Int = Color.parseColor("#0F172A"),
    private var haloAlpha: Int = 220
) : Overlay() {

    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val chevronPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val chevronPath = Path()
    private val scratchP1 = Point()
    private val scratchP2 = Point()

    private var segments: List<List<GeoPoint>> = emptyList()

    fun updateColor(colorArgb: Int, alpha: Int = 255) {
        this.colorArgb = colorArgb
        this.alpha = alpha
    }

    fun setSegments(segments: List<List<GeoPoint>>) {
        this.segments = segments
    }

    fun setSingleTrack(points: List<GeoPoint>) {
        this.segments = if (points.size >= 2) listOf(points) else emptyList()
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow || segments.isEmpty()) return
        val proj = mapView.projection ?: return
        val density = mapView.context.resources.displayMetrics.density

        val chevronArmPx = 7.0f * density
        val chevronHalfAngleRad = Math.toRadians(32.0)
        val targetIntervalPx = 110.0f * density

        haloPaint.color = haloColorArgb
        haloPaint.alpha = haloAlpha.coerceIn(0, 255)
        haloPaint.strokeWidth = 4.2f * density

        chevronPaint.color = colorArgb
        chevronPaint.alpha = alpha.coerceIn(0, 255)
        chevronPaint.strokeWidth = 2.2f * density

        val mapWidth = mapView.width.toFloat()
        val mapHeight = mapView.height.toFloat()
        val boundsMargin = 60.0f * density
        val minX = -boundsMargin
        val maxX = mapWidth + boundsMargin
        val minY = -boundsMargin
        val maxY = mapHeight + boundsMargin

        for (segment in segments) {
            val size = segment.size
            if (size < 2) continue

            var accumulatedScreenPx = 0.0
            var nextChevronPx = targetIntervalPx * 0.5 // initial offset along segment

            proj.toPixels(segment[0], scratchP1)
            var prevX = scratchP1.x.toFloat()
            var prevY = scratchP1.y.toFloat()

            for (i in 1 until size) {
                proj.toPixels(segment[i], scratchP2)
                val currX = scratchP2.x.toFloat()
                val currY = scratchP2.y.toFloat()

                val dx = currX - prevX
                val dy = currY - prevY
                val segLenPx = hypot(dx.toDouble(), dy.toDouble())

                if (segLenPx < 1.0) {
                    prevX = currX
                    prevY = currY
                    continue
                }

                // Culling: check if both endpoints are outside the same viewport border
                val bothLeft = prevX < minX && currX < minX
                val bothRight = prevX > maxX && currX > maxX
                val bothTop = prevY < minY && currY < minY
                val bothBottom = prevY > maxY && currY > maxY
                val outsideViewport = bothLeft || bothRight || bothTop || bothBottom

                val segStartPx = accumulatedScreenPx
                val segEndPx = accumulatedScreenPx + segLenPx

                while (nextChevronPx in segStartPx..segEndPx) {
                    if (!outsideViewport) {
                        val fraction = ((nextChevronPx - segStartPx) / segLenPx).coerceIn(0.0, 1.0).toFloat()
                        val cx = prevX + dx * fraction
                        val cy = prevY + dy * fraction

                        if (cx in minX..maxX && cy in minY..maxY) {
                            val headingRad = atan2(dy.toDouble(), dx.toDouble())
                            drawChevron(canvas, cx, cy, headingRad, chevronArmPx, chevronHalfAngleRad)
                        }
                    }
                    nextChevronPx += targetIntervalPx
                }

                accumulatedScreenPx = segEndPx
                prevX = currX
                prevY = currY
            }
        }
    }

    private fun drawChevron(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        headingRad: Double,
        armLen: Float,
        halfAngleRad: Double
    ) {
        val angleLeft = headingRad + Math.PI - halfAngleRad
        val angleRight = headingRad + Math.PI + halfAngleRad

        val lx = cx + (armLen * cos(angleLeft)).toFloat()
        val ly = cy + (armLen * sin(angleLeft)).toFloat()

        val rx = cx + (armLen * cos(angleRight)).toFloat()
        val ry = cy + (armLen * sin(angleRight)).toFloat()

        chevronPath.reset()
        chevronPath.moveTo(lx, ly)
        chevronPath.lineTo(cx, cy)
        chevronPath.lineTo(rx, ry)

        // Draw dark outline halo first for sharp contrast over any underlying polyline color
        canvas.drawPath(chevronPath, haloPaint)
        // Draw crisp white chevron stroke
        canvas.drawPath(chevronPath, chevronPaint)
    }

    companion object {
        fun computeDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2.0) * sin(dLat / 2.0) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2.0) * sin(dLon / 2.0)
            val c = 2.0 * atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1.0 - a))
            return 6371000.0 * c
        }
    }
}
