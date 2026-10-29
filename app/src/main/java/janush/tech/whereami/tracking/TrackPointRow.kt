package janush.tech.whereami.tracking

import kotlin.math.abs

/**
 * One persisted track sample (`track_points` table). Unlike the legacy `points_json` blob it keeps the
 * fix time, speed, accuracy and heading, which makes GPX export with `<time>`, post-trip analysis and
 * OSRM map matching possible, and allows O(1) incremental appends while a trip is recording.
 */
data class TrackPointRow(
    val tUtcMs: Long?,
    val lat: Double,
    val lng: Double,
    val altitudeM: Double?,
    /** "GPS" or "DEM"; null for legacy points that never had an altitude. */
    val altSource: String?,
    val speedMps: Float?,
    val hAccM: Float?,
    val bearingDeg: Float?,
    val flags: Int = 0,
    val rawLat: Double? = null,
    val rawLng: Double? = null
) {
    val isStationary: Boolean get() = flags and FLAG_STATIONARY != 0

    companion object {
        const val FLAG_STATIONARY = 1
        const val FLAG_MATCHED = 2 // geometry replaced by post-trip road snapping

        fun from(p: SmoothedPoint, altitudeM: Double?, altSource: String?): TrackPointRow = TrackPointRow(
            tUtcMs = p.tUtcMs,
            lat = p.lat,
            lng = p.lng,
            altitudeM = altitudeM,
            altSource = altSource,
            speedMps = p.speedMps.toFloat(),
            hAccM = p.accuracyM,
            bearingDeg = p.headingDeg.toFloat(),
            flags = if (p.isStationary) FLAG_STATIONARY else 0,
            rawLat = p.rawLat,
            rawLng = p.rawLng
        )

        /**
         * Re-bind an edited geometry (e.g. elevation correction, split) to existing per-point metadata.
         * Index-aligned rows with matching coordinates keep time/speed/accuracy/bearing/flags; others
         * start without metadata. [altitudes] / [sources] come from the edited list.
         */
        fun rebind(
            existing: List<TrackPointRow>,
            lats: DoubleArray,
            lngs: DoubleArray,
            altitudes: List<Double?>,
            sources: List<String?>?
        ): List<TrackPointRow> {
            val out = ArrayList<TrackPointRow>(lats.size)
            for (i in lats.indices) {
                val old = existing.getOrNull(i)
                val same = old != null && abs(old.lat - lats[i]) < 1e-7 && abs(old.lng - lngs[i]) < 1e-7
                val alt = altitudes.getOrNull(i)?.takeIf { !it.isNaN() && it != 0.0 }
                val src = if (alt != null) sources?.getOrNull(i) ?: old?.altSource ?: "GPS" else null
                out.add(
                    TrackPointRow(
                        tUtcMs = if (same) old!!.tUtcMs else null,
                        lat = lats[i],
                        lng = lngs[i],
                        altitudeM = alt,
                        altSource = src,
                        speedMps = if (same) old!!.speedMps else null,
                        hAccM = if (same) old!!.hAccM else null,
                        bearingDeg = if (same) old!!.bearingDeg else null,
                        flags = if (same) old!!.flags else 0,
                        rawLat = if (same) old!!.rawLat else null,
                        rawLng = if (same) old!!.rawLng else null
                    )
                )
            }
            return out
        }
    }
}
