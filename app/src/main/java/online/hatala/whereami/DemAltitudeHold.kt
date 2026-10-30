package online.hatala.whereami

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Keeps the last valid DEM elevation on screen while the user stays near where it was resolved.
 *
 * Terrain elevation changes slowly at walking scale, so a failed or still-pending lookup for the next grid
 * cell must not blank the altitude badge (that was the visible flicker). Only beyond [holdRadiusM] from the
 * last resolved point is the value considered stale.
 */
class DemAltitudeHold(private val holdRadiusM: Double = 150.0) {

    private var value: Double? = null
    private var lat = 0.0
    private var lng = 0.0

    /** Records a lookup result and returns the altitude to display (null = nothing trustworthy). */
    fun accept(lat: Double, lng: Double, dem: Double?): Double? {
        if (dem != null && dem > 0.0 && !dem.isNaN()) {
            value = dem
            this.lat = lat
            this.lng = lng
            return dem
        }
        return held(lat, lng)
    }

    /** Last valid elevation if still within [holdRadiusM] of where it was resolved. */
    fun held(lat: Double, lng: Double): Double? {
        val v = value ?: return null
        return if (distanceM(this.lat, this.lng, lat, lng) <= holdRadiusM) v else null
    }

    fun clear() {
        value = null
    }

    companion object {
        fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
            val p1 = Math.toRadians(lat1)
            val p2 = Math.toRadians(lat2)
            val dp = p2 - p1
            val dl = Math.toRadians(lng2 - lng1)
            val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
            return 2 * 6_371_000.0 * asin(sqrt(a.coerceIn(0.0, 1.0)))
        }
    }
}
