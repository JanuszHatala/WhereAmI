package janush.tech.whereami

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import android.graphics.Typeface
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * Lightweight, high-contrast altitude badge overlay rendered on the OSM map canvas
 * beside the user's position cursor.
 *
 * Features:
 * 1. Screen-stabilized upright rendering: Counter-rotates the canvas by -mapView.mapOrientation
 *    around the badge center so text and glyph remain strictly upright on screen.
 * 2. Dynamic collision avoidance: Automatically shifts between quadrants (top-right, bottom-right,
 *    bottom-left, top-left) via [AltitudeBadgePositionEvaluator] when near a saved place map pin.
 * 3. Optical glyph vertical alignment: Centers "▲" centroid with cap-height of altitude text.
 */
class AltitudeCursorBadgeOverlay(context: Context) : Overlay() {

    private val density = context.resources.displayMetrics.density

    @Volatile
    private var centerGeoPoint: GeoPoint? = null

    @Volatile
    private var altitudeM: Double? = null

    @Volatile
    private var savedPlacesGeoPoints: List<GeoPoint> = emptyList()

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xEE0F172A.toInt() // Rich Dark Slate, ~93% opacity
        style = Paint.Style.FILL
        setShadowLayer(3f * density, 0f, 1f * density, 0x80000000.toInt())
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF475569.toInt() // Slate 600
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }

    private val trianglePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF94A3B8.toInt() // Slate 400
        textSize = 10f * density
        textAlign = Paint.Align.LEFT
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF34D399.toInt() // Emerald 400
        textSize = 12f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.LEFT
    }

    private val screenPoint = Point()
    private val tempPoint = Point()
    private val pillRect = RectF()

    fun updateData(
        geoPoint: GeoPoint?,
        altitude: Double?,
        savedPlaces: List<GeoPoint>
    ) {
        centerGeoPoint = geoPoint
        altitudeM = altitude
        savedPlacesGeoPoints = savedPlaces
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow || !isEnabled) return
        val alt = altitudeM ?: return
        if (alt.isNaN() || alt == 0.0) return
        val pt = centerGeoPoint ?: return

        try {
            mapView.projection.toPixels(pt, screenPoint)
            val cursorCanvasX = screenPoint.x.toFloat()
            val cursorCanvasY = screenPoint.y.toFloat()

            // Map orientation in degrees and radians
            val orientationDeg = mapView.mapOrientation
            val rad = Math.toRadians(orientationDeg.toDouble())
            val cosRot = cos(rad).toFloat()
            val sinRot = sin(rad).toFloat()

            // Convert saved places into screen-space displacement relative to cursor
            val threatScreenPoints = ArrayList<Pair<Float, Float>>(savedPlacesGeoPoints.size)
            for (sp in savedPlacesGeoPoints) {
                mapView.projection.toPixels(sp, tempPoint)
                val cDx = tempPoint.x.toFloat() - cursorCanvasX
                val cDy = tempPoint.y.toFloat() - cursorCanvasY
                // Rotate canvas vector into screen space: rotate by +orientationDeg
                val sDx = cDx * cosRot - cDy * sinRot
                val sDy = cDx * sinRot + cDy * cosRot
                threatScreenPoints.add(Pair(sDx, sDy))
            }

            // Select best screen-space offset relative to cursor
            val bestOffset = AltitudeBadgePositionEvaluator.selectBestOffset(
                cursorX = 0f,
                cursorY = 0f,
                savedPlaceScreenPoints = threatScreenPoints,
                density = density,
                collisionRadiusDp = 50f
            )

            val screenDx = bestOffset.dxDp * density
            val screenDy = bestOffset.dyDp * density

            // Convert screen-space offset (screenDx, screenDy) back to canvas space: rotate by -orientationDeg
            val canvasDx = screenDx * cosRot + screenDy * sinRot
            val canvasDy = -screenDx * sinRot + screenDy * cosRot

            val badgeCenterX = cursorCanvasX + canvasDx
            val badgeCenterY = cursorCanvasY + canvasDy

            // Prepare text and measurements
            val triangleStr = "▲"
            val altText = String.format(Locale.getDefault(), "%.0f m", alt)

            val triangleWidth = trianglePaint.measureText(triangleStr)
            val textWidth = textPaint.measureText(altText)

            val padH = 6f * density
            val padV = 3.5f * density
            val gap = 3f * density

            val totalContentWidth = triangleWidth + gap + textWidth
            val badgeWidth = totalContentWidth + (padH * 2)
            val badgeHeight = 19f * density
            val cornerRadius = 6f * density

            // Draw upright badge on canvas:
            // Counter-rotate canvas by -orientationDeg around badge center
            canvas.save()
            canvas.rotate(-orientationDeg, badgeCenterX, badgeCenterY)

            val left = badgeCenterX - (badgeWidth / 2f)
            val top = badgeCenterY - (badgeHeight / 2f)
            val right = left + badgeWidth
            val bottom = top + badgeHeight

            pillRect.set(left, top, right, bottom)
            canvas.drawRoundRect(pillRect, cornerRadius, cornerRadius, bgPaint)
            canvas.drawRoundRect(pillRect, cornerRadius, cornerRadius, strokePaint)

            // Vertical alignment:
            // Center text and triangle vertically inside the pill
            val textY = top + padV + (10.5f * density)
            // Optical offset for triangle "▲" so its centroid aligns with text cap-height
            val triangleY = textY - (1.5f * density)

            val startX = left + padH
            canvas.drawText(triangleStr, startX, triangleY, trianglePaint)
            canvas.drawText(altText, startX + triangleWidth + gap, textY, textPaint)

            canvas.restore()
        } catch (_: Exception) {}
    }
}
