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
 * Renders subtle directional chevrons along trip polyline segments to indicate travel direction.
 * Chevrons are drawn at regular intervals (~80-120m) along the forward vector.
 */
class TripDirectionOverlay(
    private var colorArgb: Int = Color.parseColor("#E2E8F0"),
    private var alpha: Int = 180
) : Overlay() {

    private val chevronPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val chevronPath = Path()
    private val scratchP1 = Point()
    private val scratchP2 = Point()

    private var segments: List<List<GeoPoint>> = emptyList()

    fun updateColor(colorArgb: Int, alpha: Int = 180) {
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

        val chevronArmPx = 6.0f * density
        val chevronHalfAngleRad = Math.toRadians(32.0)
        chevronPaint.color = colorArgb
        chevronPaint.alpha = alpha.coerceIn(0, 255)
        chevronPaint.strokeWidth = 2.0f * density

        val targetIntervalMeters = 90.0
        val minSegmentDistMeters = 8.0

        for (segment in segments) {
            val size = segment.size
            if (size < 2) continue

            var accumulatedMeters = 0.0
            var nextChevronDistance = targetIntervalMeters * 0.5 // initial offset

            for (i in 0 until size - 1) {
                val pA = segment[i]
                val pB = segment[i + 1]

                val segDistM = computeDistanceMeters(pA.latitude, pA.longitude, pB.latitude, pB.longitude)
                if (segDistM < 0.5) continue

                val segStartM = accumulatedMeters
                val segEndM = accumulatedMeters + segDistM

                while (nextChevronDistance in segStartM..segEndM) {
                    val fraction = ((nextChevronDistance - segStartM) / segDistM).coerceIn(0.0, 1.0)
                    val interpLat = pA.latitude + (pB.latitude - pA.latitude) * fraction
                    val interpLon = pA.longitude + (pB.longitude - pA.longitude) * fraction

                    // Convert to screen coordinates
                    proj.toPixels(GeoPoint(interpLat, interpLon), scratchP1)
                    val cx = scratchP1.x.toFloat()
                    val cy = scratchP1.y.toFloat()

                    // Quick bounds check
                    if (cx >= -50f && cx <= mapView.width + 50f && cy >= -50f && cy <= mapView.height + 50f) {
                        // Forward screen direction
                        proj.toPixels(pA, scratchP1)
                        proj.toPixels(pB, scratchP2)
                        val dx = (scratchP2.x - scratchP1.x).toFloat()
                        val dy = (scratchP2.y - scratchP1.y).toFloat()
                        val len = hypot(dx.toDouble(), dy.toDouble()).toFloat()

                        if (len >= 3.0f) {
                            val headingRad = atan2(dy.toDouble(), dx.toDouble())
                            drawChevron(canvas, cx, cy, headingRad, chevronArmPx, chevronHalfAngleRad)
                        }
                    }

                    nextChevronDistance += targetIntervalMeters
                }

                accumulatedMeters = segEndM
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
