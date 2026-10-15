package janush.tech.whereami

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
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
 *    Supports solid border for DEM and dashed border for GPS fallback.
 * 2. Pinned destination locations (with loading state and DEM elevation).
 * 3. Saved place pins (displaying distinct colored callout badges matching pin color).
 *
 * All badges are screen-stabilized (counter-rotated by -mapOrientation around their centers)
 * so text and glyphs remain strictly horizontal and upright on screen.
 * Dimensions scale dynamically with the Map Labels Size setting (fontScaleFactor).
 */
class AltitudeCursorBadgeOverlay(context: Context) : Overlay() {

    private val density = context.resources.displayMetrics.density

    @Volatile
    private var centerGeoPoint: GeoPoint? = null

    @Volatile
    private var altitudeM: Double? = null

    @Volatile
    private var isGpsFallback: Boolean = false

    @Volatile
    private var fontScaleFactor: Float = 1.0f

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

    private val dashedStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF64748B.toInt() // Slate 500
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
        pathEffect = DashPathEffect(floatArrayOf(4.5f * density, 3f * density), 0f)
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

    // Saved Place Badges Paints
    private val spBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        setShadowLayer(3.5f * density, 0f, 1.2f * density, 0x90000000.toInt())
    }

    private val spStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55FFFFFF.toInt() // Translucent white border
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }

    private val spTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE
        textSize = 11.5f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.LEFT
    }

    private val spGlyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE
        textSize = 9.5f * density
        textAlign = Paint.Align.LEFT
    }

    private val screenPoint = Point()
    private val tempPoint = Point()
    private val pillRect = RectF()
    private val calloutPath = Path()

    fun updateData(
        geoPoint: GeoPoint?,
        altitude: Double?,
        isGpsFallback: Boolean = false,
        fontScaleFactor: Float = 1.0f,
        savedPlaces: List<SavedPlace> = emptyList(),
        destinationPoint: GeoPoint? = null,
        destinationAltitude: Double? = null,
        destinationLoading: Boolean = false
    ) {
        centerGeoPoint = geoPoint
        altitudeM = altitude
        this.isGpsFallback = isGpsFallback
        this.fontScaleFactor = fontScaleFactor.coerceIn(0.75f, 2.0f)
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
                textColor = 0xFF34D399.toInt(),
                isDashed = isGpsFallback
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
                    textColor = 0xFF38BDF8.toInt(),
                    isDashed = false
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
                    textColor = 0xFF34D399.toInt(),
                    isDashed = false
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
                // Badge placed ~30dp above center in screen coordinates.
                val screenDx = 0f
                val screenDy = -30f * density

                val canvasDx = screenDx * cosRot + screenDy * sinRot
                val canvasDy = -screenDx * sinRot + screenDy * cosRot

                val badgeCenterX = pinX + canvasDx
                val badgeCenterY = pinY + canvasDy

                val altText = String.format(Locale.getDefault(), "%.0f m", alt)
                drawSavedPlaceCalloutBadge(
                    canvas = canvas,
                    badgeCenterX = badgeCenterX,
                    badgeCenterY = badgeCenterY,
                    orientationDeg = orientationDeg,
                    glyph = "▲",
                    text = altText,
                    colorHex = place.getEffectiveColorHex()
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
        textColor: Int,
        isDashed: Boolean
    ) {
        val effectiveDensity = density * fontScaleFactor
        textPaint.textSize = 12f * effectiveDensity
        glyphPaint.textSize = 10f * effectiveDensity

        val glyphWidth = glyphPaint.measureText(glyph)
        textPaint.color = textColor
        val textWidth = textPaint.measureText(text)

        val padH = 6.5f * effectiveDensity
        val padV = 3.5f * effectiveDensity
        val gap = 3f * effectiveDensity

        val totalContentWidth = glyphWidth + gap + textWidth
        val badgeWidth = totalContentWidth + (padH * 2)
        val badgeHeight = 19f * effectiveDensity
        val cornerRadius = 6f * effectiveDensity

        canvas.save()
        canvas.rotate(-orientationDeg, badgeCenterX, badgeCenterY)

        val left = badgeCenterX - (badgeWidth / 2f)
        val top = badgeCenterY - (badgeHeight / 2f)
        val right = left + badgeWidth
        val bottom = top + badgeHeight

        pillRect.set(left, top, right, bottom)
        canvas.drawRoundRect(pillRect, cornerRadius, cornerRadius, bgPaint)

        val borderPaint = if (isDashed) dashedStrokePaint else strokePaint
        canvas.drawRoundRect(pillRect, cornerRadius, cornerRadius, borderPaint)

        val textY = top + padV + (10.5f * effectiveDensity)
        val glyphY = if (glyph == "▲") textY - (1.5f * effectiveDensity) else textY

        val startX = left + padH
        canvas.drawText(glyph, startX, glyphY, glyphPaint)
        canvas.drawText(text, startX + glyphWidth + gap, textY, textPaint)

        canvas.restore()
    }

    /**
     * Dedicated callout badge for Saved Places:
     * - Uses the saved place pin color as background.
     * - Uses crisp, high-contrast white text (#FFFFFF).
     * - Distinct shape: compact rounded tag with small pointer notch at bottom center.
     */
    private fun drawSavedPlaceCalloutBadge(
        canvas: Canvas,
        badgeCenterX: Float,
        badgeCenterY: Float,
        orientationDeg: Float,
        glyph: String,
        text: String,
        colorHex: String
    ) {
        val effectiveDensity = density * fontScaleFactor
        spTextPaint.textSize = 11f * effectiveDensity
        spGlyphPaint.textSize = 9.5f * effectiveDensity

        val parsedColor = try {
            AndroidColor.parseColor(colorHex)
        } catch (_: Exception) {
            0xFF0284C7.toInt()
        }
        spBgPaint.color = (0xF0000000.toInt() or (parsedColor and 0x00FFFFFF))

        val glyphWidth = spGlyphPaint.measureText(glyph)
        val textWidth = spTextPaint.measureText(text)

        val padH = 6f * effectiveDensity
        val padV = 3f * effectiveDensity
        val gap = 2.5f * effectiveDensity

        val totalContentWidth = glyphWidth + gap + textWidth
        val badgeWidth = totalContentWidth + (padH * 2)
        val badgeHeight = 18f * effectiveDensity
        val cornerRadius = 4f * effectiveDensity
        val pointerHeight = 3.5f * effectiveDensity
        val pointerHalfWidth = 3f * effectiveDensity

        canvas.save()
        canvas.rotate(-orientationDeg, badgeCenterX, badgeCenterY)

        val left = badgeCenterX - (badgeWidth / 2f)
        val top = badgeCenterY - (badgeHeight / 2f) - (pointerHeight / 2f)
        val right = left + badgeWidth
        val bottom = top + badgeHeight

        pillRect.set(left, top, right, bottom)

        // Draw Callout Tag Shape (rounded box + bottom notch pointing towards pin)
        calloutPath.reset()
        calloutPath.addRoundRect(pillRect, cornerRadius, cornerRadius, Path.Direction.CW)
        // Add downward pointer notch
        calloutPath.moveTo(badgeCenterX - pointerHalfWidth, bottom)
        calloutPath.lineTo(badgeCenterX, bottom + pointerHeight)
        calloutPath.lineTo(badgeCenterX + pointerHalfWidth, bottom)
        calloutPath.close()

        canvas.drawPath(calloutPath, spBgPaint)
        canvas.drawPath(calloutPath, spStrokePaint)

        val textY = top + padV + (10f * effectiveDensity)
        val glyphY = textY - (1.2f * effectiveDensity)

        val startX = left + padH
        canvas.drawText(glyph, startX, glyphY, spGlyphPaint)
        canvas.drawText(text, startX + glyphWidth + gap, textY, spTextPaint)

        canvas.restore()
    }
}
