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
 * for:
 * 1. The user's position cursor (with dynamic collision avoidance relative to pins).
 * 2. Pinned destination locations (with loading state and DEM elevation).
 * 3. Saved place pins (displaying their stored elevation).
 *
 * All badges are screen-stabilized (counter-rotated by -mapOrientation around their centers)
 * so text and glyphs remain strictly horizontal and upright on screen.
 */
class AltitudeCursorBadgeOverlay(context: Context) : Overlay() {

    private val density = context.resources.displayMetrics.density

    @Volatile
    private var centerGeoPoint: GeoPoint? = null

    @Volatile
    private var altitudeM: Double? = null

    @Volatile
    private var savedPlacesList: List<SavedPlace> = emptyList()

    @Volatile
    private var destGeoPoint: GeoPoint? = null

    @Volatile
    private var destAltitudeM: Double? = null

    @Volatile
    private var destLoading: Boolean = false

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

    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
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
        savedPlaces: List<SavedPlace> = emptyList(),
        destinationPoint: GeoPoint? = null,
        destinationAltitude: Double? = null,
        destinationLoading: Boolean = false
    ) {
        centerGeoPoint = geoPoint
        altitudeM = altitude
        savedPlacesList = savedPlaces
        destGeoPoint = destinationPoint
        destAltitudeM = destinationAltitude
        destLoading = destinationLoading
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow || !isEnabled) return

        val orientationDeg = mapView.mapOrientation
        val rad = Math.toRadians(orientationDeg.toDouble())
        val cosRot = cos(rad).toFloat()
        val sinRot = sin(rad).toFloat()

        // 1. Draw User Cursor Altitude Badge (with collision avoidance)
        drawCursorBadge(canvas, mapView, orientationDeg, cosRot, sinRot)

        // 2. Draw Destination Pin Altitude Badge (above pinned location)
        drawDestinationBadge(canvas, mapView, orientationDeg, cosRot, sinRot)

        // 3. Draw Saved Places Altitude Badges (above saved place icons)
        drawSavedPlacesBadges(canvas, mapView, orientationDeg, cosRot, sinRot)
    }

    private fun drawCursorBadge(
        canvas: Canvas,
        mapView: MapView,
        orientationDeg: Float,
        cosRot: Float,
        sinRot: Float
    ) {
        val alt = altitudeM ?: return
        if (alt.isNaN() || alt == 0.0) return
        val pt = centerGeoPoint ?: return

        try {
            mapView.projection.toPixels(pt, screenPoint)
            val cursorCanvasX = screenPoint.x.toFloat()
            val cursorCanvasY = screenPoint.y.toFloat()

            // Gather collision threat points in screen space: all saved places + destination pin
            val threatPoints = ArrayList<GeoPoint>(savedPlacesList.size + 1)
            for (sp in savedPlacesList) {
                threatPoints.add(sp.geoPoint)
            }
            destGeoPoint?.let { threatPoints.add(it) }

            val threatScreenPoints = ArrayList<Pair<Float, Float>>(threatPoints.size)
            for (tp in threatPoints) {
                mapView.projection.toPixels(tp, tempPoint)
                val cDx = tempPoint.x.toFloat() - cursorCanvasX
                val cDy = tempPoint.y.toFloat() - cursorCanvasY
                val sDx = cDx * cosRot - cDy * sinRot
                val sDy = cDx * sinRot + cDy * cosRot
                threatScreenPoints.add(Pair(sDx, sDy))
            }

            val bestOffset = AltitudeBadgePositionEvaluator.selectBestOffset(
                cursorX = 0f,
                cursorY = 0f,
                savedPlaceScreenPoints = threatScreenPoints,
                density = density,
                collisionRadiusDp = 50f
            )

            val screenDx = bestOffset.dxDp * density
            val screenDy = bestOffset.dyDp * density

            val canvasDx = screenDx * cosRot + screenDy * sinRot
            val canvasDy = -screenDx * sinRot + screenDy * cosRot

            val badgeCenterX = cursorCanvasX + canvasDx
            val badgeCenterY = cursorCanvasY + canvasDy

            val altText = String.format(Locale.getDefault(), "%.0f m", alt)
            drawPill(
                canvas = canvas,
                badgeCenterX = badgeCenterX,
                badgeCenterY = badgeCenterY,
                orientationDeg = orientationDeg,
                glyph = "▲",
                text = altText,
                textColor = 0xFF34D399.toInt()
            )
        } catch (_: Exception) {}
    }

    private fun drawDestinationBadge(
        canvas: Canvas,
        mapView: MapView,
        orientationDeg: Float,
        cosRot: Float,
        sinRot: Float
    ) {
        val destPt = destGeoPoint ?: return
        val isLoading = destLoading
        val alt = destAltitudeM

        if (!isLoading && (alt == null || alt == 0.0 || alt.isNaN())) {
            return
        }

        try {
            mapView.projection.toPixels(destPt, screenPoint)
            val pinX = screenPoint.x.toFloat()
            val pinY = screenPoint.y.toFloat()

            // Destination pin anchor is bottom-center, height is 36dp.
            // Badge placed ~46dp above anchor point in screen coordinates.
            val screenDx = 0f
            val screenDy = -46f * density

            val canvasDx = screenDx * cosRot + screenDy * sinRot
            val canvasDy = -screenDx * sinRot + screenDy * cosRot

            val badgeCenterX = pinX + canvasDx
            val badgeCenterY = pinY + canvasDy

            if (isLoading) {
                drawPill(
                    canvas = canvas,
                    badgeCenterX = badgeCenterX,
                    badgeCenterY = badgeCenterY,
                    orientationDeg = orientationDeg,
                    glyph = "⛰️",
                    text = "...",
                    textColor = 0xFF38BDF8.toInt()
                )
            } else if (alt != null) {
                val altText = String.format(Locale.getDefault(), "%.0f m", alt)
                drawPill(
                    canvas = canvas,
                    badgeCenterX = badgeCenterX,
                    badgeCenterY = badgeCenterY,
                    orientationDeg = orientationDeg,
                    glyph = "▲",
                    text = altText,
                    textColor = 0xFF34D399.toInt()
                )
            }
        } catch (_: Exception) {}
    }

    private fun drawSavedPlacesBadges(
        canvas: Canvas,
        mapView: MapView,
        orientationDeg: Float,
        cosRot: Float,
        sinRot: Float
    ) {
        if (savedPlacesList.isEmpty()) return

        for (place in savedPlacesList) {
            val alt = place.altitude ?: continue
            if (alt.isNaN() || alt == 0.0) continue

            try {
                mapView.projection.toPixels(place.geoPoint, screenPoint)
                val pinX = screenPoint.x.toFloat()
                val pinY = screenPoint.y.toFloat()

                // Saved place pin anchor is center, radius is 17dp (height 34dp).
                // Badge placed ~27dp above center in screen coordinates.
                val screenDx = 0f
                val screenDy = -27f * density

                val canvasDx = screenDx * cosRot + screenDy * sinRot
                val canvasDy = -screenDx * sinRot + screenDy * cosRot

                val badgeCenterX = pinX + canvasDx
                val badgeCenterY = pinY + canvasDy

                val altText = String.format(Locale.getDefault(), "%.0f m", alt)
                drawPill(
                    canvas = canvas,
                    badgeCenterX = badgeCenterX,
                    badgeCenterY = badgeCenterY,
                    orientationDeg = orientationDeg,
                    glyph = "▲",
                    text = altText,
                    textColor = 0xFF34D399.toInt()
                )
            } catch (_: Exception) {}
        }
    }

    private fun drawPill(
        canvas: Canvas,
        badgeCenterX: Float,
        badgeCenterY: Float,
        orientationDeg: Float,
        glyph: String,
        text: String,
        textColor: Int
    ) {
        val glyphWidth = glyphPaint.measureText(glyph)
        textPaint.color = textColor
        val textWidth = textPaint.measureText(text)

        val padH = 6f * density
        val padV = 3.5f * density
        val gap = 3f * density

        val totalContentWidth = glyphWidth + gap + textWidth
        val badgeWidth = totalContentWidth + (padH * 2)
        val badgeHeight = 19f * density
        val cornerRadius = 6f * density

        canvas.save()
        canvas.rotate(-orientationDeg, badgeCenterX, badgeCenterY)

        val left = badgeCenterX - (badgeWidth / 2f)
        val top = badgeCenterY - (badgeHeight / 2f)
        val right = left + badgeWidth
        val bottom = top + badgeHeight

        pillRect.set(left, top, right, bottom)
        canvas.drawRoundRect(pillRect, cornerRadius, cornerRadius, bgPaint)
        canvas.drawRoundRect(pillRect, cornerRadius, cornerRadius, strokePaint)

        val textY = top + padV + (10.5f * density)
        val glyphY = if (glyph == "▲") textY - (1.5f * density) else textY

        val startX = left + padH
        canvas.drawText(glyph, startX, glyphY, glyphPaint)
        canvas.drawText(text, startX + glyphWidth + gap, textY, textPaint)

        canvas.restore()
    }
}
