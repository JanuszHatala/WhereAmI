package janush.tech.whereami

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.hypot

/**
 * Data class representing an active spiderfy displacement for a guest marker.
 */
data class GuestSpiderfyLine(
    val realPos: GeoPoint,
    val displayPos: GeoPoint,
    val colorArgb: Int,
    val isInactive: Boolean
)

/**
 * Renders subtle, high-contrast connecting leader lines (stems) and anchor junction dots
 * between a guest's true geographical position on the map and their offset (spiderfied) avatar badge.
 * Guarantees spatial awareness so users can always see the exact location on the map even when
 * avatars are displaced to avoid occluding saved place pins, host cursor, or altitude badges.
 */
class GuestLeaderLinesOverlay(context: Context) : Overlay() {

    private val density = context.resources.displayMetrics.density

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        strokeCap = Paint.Cap.ROUND
    }

    private val lineShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.0f * density
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(120, 15, 23, 42) // Slate shadow for delineation over any map layer
    }

    private val anchorDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val anchorBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
        color = Color.WHITE
    }

    @Volatile
    private var spiderfyLines: List<GuestSpiderfyLine> = emptyList()

    private val startPoint = Point()
    private val endPoint = Point()

    fun updateLines(lines: List<GuestSpiderfyLine>) {
        spiderfyLines = lines
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val lines = spiderfyLines
        if (lines.isEmpty()) return

        val minDisplacementPx = 8f * density

        for (item in lines) {
            try {
                mapView.projection.toPixels(item.realPos, startPoint)
                mapView.projection.toPixels(item.displayPos, endPoint)

                val dx = endPoint.x.toFloat() - startPoint.x.toFloat()
                val dy = endPoint.y.toFloat() - startPoint.y.toFloat()
                val distPx = hypot(dx, dy)

                // Only render leader line if marker is visibly offset from true position
                if (distPx >= minDisplacementPx) {
                    val baseColor = item.colorArgb
                    val r = Color.red(baseColor)
                    val g = Color.green(baseColor)
                    val b = Color.blue(baseColor)

                    val lineAlpha = if (item.isInactive) 130 else 220
                    linePaint.color = Color.argb(lineAlpha, r, g, b)

                    val sx = startPoint.x.toFloat()
                    val sy = startPoint.y.toFloat()
                    val ex = endPoint.x.toFloat()
                    val ey = endPoint.y.toFloat()

                    // 1. Subtle dark halo behind line for high contrast
                    canvas.drawLine(sx, sy, ex, ey, lineShadowPaint)

                    // 2. Colored leader line
                    canvas.drawLine(sx, sy, ex, ey, linePaint)

                    // 3. Anchor junction dot at true geographical coordinate
                    val dotRadius = 3.5f * density
                    anchorDotPaint.color = if (item.isInactive) Color.argb(lineAlpha, 148, 163, 184) else baseColor
                    canvas.drawCircle(sx, sy, dotRadius, anchorDotPaint)
                    canvas.drawCircle(sx, sy, dotRadius, anchorBorderPaint)
                }
            } catch (_: Exception) {}
        }
    }
}
