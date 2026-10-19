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
 * for the user's position cursor (with dynamic collision avoidance relative to pins).
 * Supports solid border for DEM and dashed border for GPS fallback.
 *
 * The badge is screen-stabilized (counter-rotated by -mapOrientation around its center)
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
    private var isGpsMode: Boolean = false

    @Volatile
    private var currentSpeedKmh: Float = 0f

    @Volatile
    private var fontScaleFactor: Float = 1.0f

    @Volatile
    private var savedPlacesList: List<SavedPlace> = emptyList()

    @Volatile
    private var destGeoPoint: GeoPoint? = null

    private var lastRenderedIntAlt: Long? = null
    private var currentOffsetIndex: Int = 0

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // Semi-transparent dark slate background (~46% opacity / 0x75), allowing underlying polyline
        // and map details to show through clearly while maintaining high contrast for the altitude value
        color = 0x750F172A.toInt()
        style = Paint.Style.FILL
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // Solid black high-contrast border outline for sharp contrast over any terrain or polyline
        color = 0xFF000000.toInt() // Solid Black (#000000)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }

    private val dashedStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // Solid black dashed border for GPS fallback
        color = 0xFF000000.toInt() // Solid Black (#000000)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        pathEffect = DashPathEffect(floatArrayOf(4.5f * density, 3f * density), 0f)
    }

    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF94A3B8.toInt() // Slate 400
        textSize = 10f * density
        textAlign = Paint.Align.LEFT
        setShadowLayer(1.5f * density, 0f, 1f * density, 0xCC000000.toInt())
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF34D399.toInt() // Emerald 400
        textSize = 12f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.LEFT
        setShadowLayer(2f * density, 0f, 1f * density, 0xCC000000.toInt())
    }

    private val screenPoint = Point()
    private val tempPoint = Point()
    private val pillRect = RectF()

    fun updateData(
        geoPoint: GeoPoint?,
        altitude: Double?,
        isGpsFallback: Boolean = false,
        fontScaleFactor: Float = 1.0f,
        currentSpeedKmh: Float = 0f,
        isGpsMode: Boolean = false,
        savedPlaces: List<SavedPlace> = emptyList(),
        destinationPoint: GeoPoint? = null
    ) {
        centerGeoPoint = geoPoint
        altitudeM = altitude
        this.isGpsFallback = isGpsFallback
        this.isGpsMode = isGpsMode
        this.currentSpeedKmh = currentSpeedKmh
        this.fontScaleFactor = fontScaleFactor.coerceIn(0.75f, 2.0f)
        savedPlacesList = savedPlaces
        destGeoPoint = destinationPoint
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow || !isEnabled) return

        val orientationDeg = mapView.mapOrientation
        val rad = Math.toRadians(orientationDeg.toDouble())
        val cosRot = cos(rad).toFloat()
        val sinRot = sin(rad).toFloat()

        // 1. Draw User Cursor Altitude Badge (with collision avoidance)
        drawCursorBadge(canvas, mapView, orientationDeg, cosRot, sinRot)
    }

    private fun drawCursorBadge(
        canvas: Canvas,
        mapView: MapView,
        orientationDeg: Float,
        cosRot: Float,
        sinRot: Float
    ) {
        if (currentSpeedKmh >= 15f) return
        if (!isGpsMode && isGpsFallback) return

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

            val bestIdx = AltitudeBadgePositionEvaluator.selectBestOffsetWithHysteresis(
                cursorX = 0f,
                cursorY = 0f,
                savedPlaceScreenPoints = threatScreenPoints,
                density = density,
                currentOffsetIndex = currentOffsetIndex,
                collisionRadiusDp = 50f,
                hysteresisMarginDp = 12f
            )
            currentOffsetIndex = bestIdx
            val bestOffset = AltitudeBadgePositionEvaluator.CANDIDATE_OFFSETS[bestIdx]

            val screenDx = bestOffset.dxDp * density
            val screenDy = bestOffset.dyDp * density

            val canvasDx = screenDx * cosRot + screenDy * sinRot
            val canvasDy = -screenDx * sinRot + screenDy * cosRot

            val badgeCenterX = cursorCanvasX + canvasDx
            val badgeCenterY = cursorCanvasY + canvasDy

            val curInt = lastRenderedIntAlt
            val roundedAlt = kotlin.math.round(alt).toLong()
            val stableInt = if (curInt == null || kotlin.math.abs(alt - curInt.toDouble()) >= 0.75) {
                lastRenderedIntAlt = roundedAlt
                roundedAlt
            } else {
                curInt
            }
            val altText = "$stableInt m"
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
}
