package janush.tech.whereami.tracking

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot

/**
 * Soft, display-only pull of the live cursor and polyline toward the road returned by OSRM `/nearest`.
 *
 * Recorded `track_points` stay on the filter/smoother path. Competitors (Wikiloc, hiking maps) look
 * road-aligned because they snap the drawn line; this applies a bounded, EMA-smoothed EN offset so
 * roundabouts and corridor GNSS bias do not leave the red line in the buildings beside the carriageway.
 */
class DisplayRoadSnap(
    val maxOffsetM: Double = 18.0,
    val tauS: Double = 2.5
) {
    private var eastM = 0.0
    private var northM = 0.0

    fun offsetM(): Double = hypot(eastM, northM)

    fun reset() {
        eastM = 0.0
        northM = 0.0
    }

    /**
     * @param distanceM OSRM waypoint distance (raw → road). Snaps beyond [maxOffsetM] are ignored.
     */
    fun observeNearest(
        rawLat: Double,
        rawLng: Double,
        snapLat: Double,
        snapLng: Double,
        distanceM: Double,
        dtSec: Double
    ) {
        if (distanceM < 0.0 || distanceM > maxOffsetM) return
        if (!snapLat.isFinite() || !snapLng.isFinite()) return
        val mLat = LocalFrame.METERS_PER_DEG_LAT
        val mLng = mLat * cos(Math.toRadians(rawLat)).coerceAtLeast(0.01)
        val targetE = (snapLng - rawLng) * mLng
        val targetN = (snapLat - rawLat) * mLat
        if (hypot(targetE, targetN) > maxOffsetM) return
        val step = dtSec.coerceIn(0.0, 1.0)
        val a = 1.0 - exp(-step / tauS)
        eastM += a * (targetE - eastM)
        northM += a * (targetN - northM)
        val mag = hypot(eastM, northM)
        if (mag > maxOffsetM && mag > 1e-6) {
            val s = maxOffsetM / mag
            eastM *= s
            northM *= s
        }
    }

    fun apply(lat: Double, lng: Double): Pair<Double, Double> {
        if (eastM == 0.0 && northM == 0.0) return lat to lng
        val mLat = LocalFrame.METERS_PER_DEG_LAT
        val mLng = mLat * cos(Math.toRadians(lat)).coerceAtLeast(0.01)
        return (lat + northM / mLat) to (lng + eastM / mLng)
    }
}
