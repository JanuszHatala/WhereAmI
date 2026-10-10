package janush.tech.whereami

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.cos

/**
 * Google Maps-style dynamic accuracy circle overlay for OSMDroid.
 *
 * Renders a soft translucent sky-blue disk with a thin outer stroke centered on
 * the user's stabilized position. The radius scales dynamically with GPS accuracy in meters,
 * animated smoothly via 60fps exponential interpolation and clamped between min/max screen pixels
 * (preventing indoor multipath washout or disappearance on RTK fixes).
 */
class AccuracyCircleOverlay(context: Context) : Overlay() {

    private val density = context.resources.displayMetrics.density
    private val minRadiusPx = 10f * density
    private val maxRadiusPx = 120f * density

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x1A38BDF8.toInt() // ~10% opacity Sky Blue (#38BDF8)
        style = Paint.Style.FILL
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x4D38BDF8.toInt() // ~30% opacity Sky Blue (#38BDF8)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }

    @Volatile
    private var centerGeoPoint: GeoPoint? = null

    @Volatile
    private var accuracyMeters: Float = 15f

    private var currentDisplayRadiusPx: Float = 0f
    private val screenPoint = Point()

    fun updatePosition(lat: Double, lng: Double, accuracy: Float?) {
        centerGeoPoint = GeoPoint(lat, lng)
        if (accuracy != null && accuracy > 0f) {
            accuracyMeters = accuracy
        }
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val pt = centerGeoPoint ?: return

        try {
            mapView.projection.toPixels(pt, screenPoint)
            val cx = screenPoint.x.toFloat()
            val cy = screenPoint.y.toFloat()

            // Calculate metric radius converted to screen pixels at current zoom level
            val equatorPixels = mapView.projection.metersToEquatorPixels(accuracyMeters)
            val latRad = Math.toRadians(pt.latitude)
            val metricRadiusPx = (equatorPixels * cos(latRad)).toFloat()

            // Enforce visual min/max clamping to preserve aesthetic consistency
            val clampedTargetPx = metricRadiusPx.coerceIn(minRadiusPx, maxRadiusPx)

            // Smooth exponential blend (low-pass filter on radius resizing)
            if (currentDisplayRadiusPx <= 0f) {
                currentDisplayRadiusPx = clampedTargetPx
            } else {
                currentDisplayRadiusPx += 0.25f * (clampedTargetPx - currentDisplayRadiusPx)
            }

            canvas.drawCircle(cx, cy, currentDisplayRadiusPx, fillPaint)
            canvas.drawCircle(cx, cy, currentDisplayRadiusPx, strokePaint)
        } catch (_: Exception) {}
    }
}
