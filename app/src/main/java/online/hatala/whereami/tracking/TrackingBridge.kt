package online.hatala.whereami.tracking

import android.location.Location
import android.os.Build
import online.hatala.whereami.ActivityProfile

/** Android <-> tracking core adapters. Kept thin so the core stays JVM-testable. */
object TrackingBridge {

    fun profileFor(profile: ActivityProfile): TrackingProfile = when (profile) {
        ActivityProfile.CAR -> TrackingProfile.CAR
        ActivityProfile.CYCLING -> TrackingProfile.CYCLING
        ActivityProfile.MTB -> TrackingProfile.MTB
        ActivityProfile.RUNNING -> TrackingProfile.RUNNING
        ActivityProfile.HIKING -> TrackingProfile.HIKING
        ActivityProfile.WALKING -> TrackingProfile.WALKING
    }

    /**
     * Single time base: [Location.getElapsedRealtimeNanos] (monotonic, same clock as sensor events and
     * Choreographer-driven rendering). UTC [Location.getTime] is carried only for persistence.
     */
    fun toRawFix(loc: Location): RawFix = RawFix(
        tElapsedMs = loc.elapsedRealtimeNanos / 1_000_000L,
        tUtcMs = if (loc.time > 0L) loc.time else System.currentTimeMillis(),
        lat = loc.latitude,
        lng = loc.longitude,
        accuracyM = if (loc.hasAccuracy()) loc.accuracy else null,
        speedMps = if (loc.hasSpeed()) loc.speed else null,
        speedAccMps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && loc.hasSpeedAccuracy()) loc.speedAccuracyMetersPerSecond else null,
        bearingDeg = if (loc.hasBearing()) loc.bearing else null,
        bearingAccDeg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && loc.hasBearingAccuracy()) loc.bearingAccuracyDegrees else null,
        altitudeM = if (loc.hasAltitude()) loc.altitude else null
    )
}

/** Process-wide hub shared by the location pipeline and the (foreground-only) gyro heading sensor. */
object TrackingRuntime {
    val gyro = GyroYawTracker()
    val compass = CompassTracker()
    val states = TrackStateLog()
    /** Soft display-only pull of the live cursor/line toward OSRM `/nearest` (not persisted). */
    val displaySnap = DisplayRoadSnap()
}
