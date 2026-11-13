package online.hatala.whereami

import android.content.Context

enum class TrailOverlayMode(val label: String) {
    VECTOR("Vector (Filterable)"),
    WAYMARKED("Waymarked (Raster)")
}

data class ProfileMapConfig(
    val baseLayer: MapBaseLayer,
    val orientationMode: MapOrientationMode,
    val showHikingOverlay: Boolean,
    val fontScale: MapFontScale,
    val trackColorHex: String = "#EF4444",
    val showOutdoorAreasOverlay: Boolean = false,
    val trailThickness: TrailThickness = TrailThickness.NORMAL,
    val trailOverlayMode: TrailOverlayMode = TrailOverlayMode.VECTOR,
    val enabledTrailKinds: Set<TrailRouteKind> = emptySet(),
    val enabledTrailColors: Set<TrailColor> = emptySet()
)

object MapProfileConfigHelper {
    const val PREFS_NAME = "where_am_i_map_prefs"
    const val PREF_SHOW_FILTERED_POLYLINE = "pref_show_filtered_polyline"
    const val PREF_SHOW_RAW_POLYLINE = "pref_show_raw_polyline"
    const val DEFAULT_SHOW_FILTERED_POLYLINE = true
    const val DEFAULT_SHOW_RAW_POLYLINE = false
    private const val PREF_MIGRATED_CLEAN_MAP_V240 = "pref_migrated_clean_map_views_v240"

    fun getDefaultConfig(profile: ActivityProfile): ProfileMapConfig = when (profile) {
        ActivityProfile.CAR -> ProfileMapConfig(
            baseLayer = MapBaseLayer.STANDARD,
            orientationMode = MapOrientationMode.COURSE_UP,
            showHikingOverlay = false,
            fontScale = MapFontScale.NORMAL,
            trackColorHex = "#EF4444"
        )
        ActivityProfile.CYCLING -> ProfileMapConfig(
            baseLayer = MapBaseLayer.STANDARD,
            orientationMode = MapOrientationMode.COURSE_UP,
            showHikingOverlay = false,
            fontScale = MapFontScale.NORMAL,
            trackColorHex = "#EF4444",
            enabledTrailKinds = setOf(TrailRouteKind.CYCLING, TrailRouteKind.MTB)
        )
        ActivityProfile.MTB -> ProfileMapConfig(
            baseLayer = MapBaseLayer.TOPO,
            orientationMode = MapOrientationMode.COURSE_UP,
            showHikingOverlay = true,
            fontScale = MapFontScale.NORMAL,
            trackColorHex = "#10B981",
            showOutdoorAreasOverlay = true,
            trailThickness = TrailThickness.THICK,
            trailOverlayMode = TrailOverlayMode.VECTOR,
            enabledTrailKinds = setOf(TrailRouteKind.MTB, TrailRouteKind.CYCLING)
        )
        ActivityProfile.HIKING -> ProfileMapConfig(
            baseLayer = MapBaseLayer.TOPO,
            orientationMode = MapOrientationMode.COURSE_UP,
            showHikingOverlay = true,
            fontScale = MapFontScale.NORMAL,
            trackColorHex = "#F59E0B",
            showOutdoorAreasOverlay = true,
            trailThickness = TrailThickness.THICK,
            trailOverlayMode = TrailOverlayMode.VECTOR,
            enabledTrailKinds = setOf(TrailRouteKind.HIKING, TrailRouteKind.EDUCATIONAL)
        )
        ActivityProfile.RUNNING -> ProfileMapConfig(
            baseLayer = MapBaseLayer.STANDARD,
            orientationMode = MapOrientationMode.NORTH,
            showHikingOverlay = false,
            fontScale = MapFontScale.NORMAL,
            trackColorHex = "#EF4444"
        )
        ActivityProfile.WALKING -> ProfileMapConfig(
            baseLayer = MapBaseLayer.STANDARD,
            orientationMode = MapOrientationMode.NORTH,
            showHikingOverlay = false,
            fontScale = MapFontScale.NORMAL,
            trackColorHex = "#EF4444"
        )
        ActivityProfile.KAYAKING -> ProfileMapConfig(
            baseLayer = MapBaseLayer.TOPO,
            orientationMode = MapOrientationMode.COURSE_UP,
            showHikingOverlay = true,
            fontScale = MapFontScale.NORMAL,
            trackColorHex = "#06B6D4",
            showOutdoorAreasOverlay = true,
            trailThickness = TrailThickness.THICK,
            trailOverlayMode = TrailOverlayMode.VECTOR,
            enabledTrailKinds = setOf(TrailRouteKind.KAYAKING)
        )
    }

    private fun migrateLegacyFreemapDefaultsIfNeeded(prefs: android.content.SharedPreferences) {
        if (prefs.getBoolean(PREF_MIGRATED_CLEAN_MAP_V240, false)) return
        val editor = prefs.edit().putBoolean(PREF_MIGRATED_CLEAN_MAP_V240, true)
        listOf(ActivityProfile.MTB, ActivityProfile.HIKING, ActivityProfile.KAYAKING).forEach { prof ->
            val key = "profile_${prof.name}_base_layer"
            if (prefs.getString(key, null) == MapBaseLayer.FREEMAP_OUTDOOR.name) {
                editor.putString(key, MapBaseLayer.TOPO.name)
            }
        }
        editor.apply()
    }

    fun getConfig(context: Context, profile: ActivityProfile): ProfileMapConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        migrateLegacyFreemapDefaultsIfNeeded(prefs)
        val default = getDefaultConfig(profile)

        val baseLayerName = prefs.getString("profile_${profile.name}_base_layer", null)
        val orientationName = prefs.getString("profile_${profile.name}_orientation_mode", null)
        val hikingOverlay = if (prefs.contains("profile_${profile.name}_hiking_overlay")) {
            prefs.getBoolean("profile_${profile.name}_hiking_overlay", default.showHikingOverlay)
        } else default.showHikingOverlay
        val fontScaleName = prefs.getString("profile_${profile.name}_font_scale", null)
        val trackColorHex = prefs.getString("profile_${profile.name}_track_color", null) ?: default.trackColorHex
        val outdoorAreasOverlay = if (prefs.contains("profile_${profile.name}_outdoor_areas")) {
            prefs.getBoolean("profile_${profile.name}_outdoor_areas", default.showOutdoorAreasOverlay)
        } else default.showOutdoorAreasOverlay
        val trailThicknessName = prefs.getString("profile_${profile.name}_trail_thickness", null)
        val trailOverlayModeName = prefs.getString("profile_${profile.name}_trail_overlay_mode", null)
        val trailKindsRaw = prefs.getString("profile_${profile.name}_trail_kinds", null)
        val trailColorsRaw = prefs.getString("profile_${profile.name}_trail_colors", null)

        val baseLayer = baseLayerName?.let {
            try { MapBaseLayer.valueOf(it) } catch (_: Exception) { null }
        } ?: default.baseLayer

        val orientation = orientationName?.let {
            try { MapOrientationMode.valueOf(it) } catch (_: Exception) { null }
        } ?: default.orientationMode

        val fontScale = fontScaleName?.let {
            try { MapFontScale.valueOf(it) } catch (_: Exception) { null }
        } ?: default.fontScale

        val trailThickness = trailThicknessName?.let {
            try { TrailThickness.valueOf(it) } catch (_: Exception) { null }
        } ?: default.trailThickness

        val trailOverlayMode = trailOverlayModeName?.let {
            try { TrailOverlayMode.valueOf(it) } catch (_: Exception) { null }
        } ?: default.trailOverlayMode

        val enabledTrailKinds = if (trailKindsRaw == null) {
            default.enabledTrailKinds
        } else if (trailKindsRaw == "ALL" || trailKindsRaw.isBlank()) {
            emptySet()
        } else {
            trailKindsRaw.split(",").mapNotNull { token ->
                try { TrailRouteKind.valueOf(token.trim()) } catch (_: Exception) { null }
            }.toSet()
        }

        val enabledTrailColors = if (trailColorsRaw == null) {
            default.enabledTrailColors
        } else if (trailColorsRaw == "ALL" || trailColorsRaw.isBlank()) {
            emptySet()
        } else {
            trailColorsRaw.split(",").mapNotNull { token ->
                try { TrailColor.valueOf(token.trim()) } catch (_: Exception) { null }
            }.toSet()
        }

        return ProfileMapConfig(
            baseLayer = baseLayer,
            orientationMode = orientation,
            showHikingOverlay = hikingOverlay,
            fontScale = fontScale,
            trackColorHex = trackColorHex,
            showOutdoorAreasOverlay = outdoorAreasOverlay,
            trailThickness = trailThickness,
            trailOverlayMode = trailOverlayMode,
            enabledTrailKinds = enabledTrailKinds,
            enabledTrailColors = enabledTrailColors
        )
    }

    fun setBaseLayer(context: Context, profile: ActivityProfile, layer: MapBaseLayer) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString("profile_${profile.name}_base_layer", layer.name).apply()
    }

    fun setOrientationMode(context: Context, profile: ActivityProfile, mode: MapOrientationMode) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString("profile_${profile.name}_orientation_mode", mode.name).apply()
    }

    fun setHikingOverlay(context: Context, profile: ActivityProfile, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean("profile_${profile.name}_hiking_overlay", enabled).apply()
    }

    fun setOutdoorAreasOverlay(context: Context, profile: ActivityProfile, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean("profile_${profile.name}_outdoor_areas", enabled).apply()
    }

    fun setTrailThickness(context: Context, profile: ActivityProfile, thickness: TrailThickness) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString("profile_${profile.name}_trail_thickness", thickness.name).apply()
    }

    fun setTrailOverlayMode(context: Context, profile: ActivityProfile, mode: TrailOverlayMode) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString("profile_${profile.name}_trail_overlay_mode", mode.name).apply()
    }

    fun setEnabledTrailKinds(context: Context, profile: ActivityProfile, kinds: Set<TrailRouteKind>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val encoded = if (kinds.isEmpty()) "ALL" else kinds.joinToString(",") { it.name }
        prefs.edit().putString("profile_${profile.name}_trail_kinds", encoded).apply()
    }

    fun setEnabledTrailColors(context: Context, profile: ActivityProfile, colors: Set<TrailColor>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val encoded = if (colors.isEmpty()) "ALL" else colors.joinToString(",") { it.name }
        prefs.edit().putString("profile_${profile.name}_trail_colors", encoded).apply()
    }

    fun setFontScale(context: Context, profile: ActivityProfile, scale: MapFontScale) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString("profile_${profile.name}_font_scale", scale.name).apply()
    }

    fun setTrackColor(context: Context, profile: ActivityProfile, colorHex: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString("profile_${profile.name}_track_color", colorHex).apply()
    }

    fun resetProfileDefaults(context: Context, profile: ActivityProfile) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .remove("profile_${profile.name}_base_layer")
            .remove("profile_${profile.name}_orientation_mode")
            .remove("profile_${profile.name}_hiking_overlay")
            .remove("profile_${profile.name}_outdoor_areas")
            .remove("profile_${profile.name}_trail_thickness")
            .remove("profile_${profile.name}_trail_overlay_mode")
            .remove("profile_${profile.name}_trail_kinds")
            .remove("profile_${profile.name}_trail_colors")
            .remove("profile_${profile.name}_font_scale")
            .remove("profile_${profile.name}_track_color")
            .apply()
    }

    fun resetAllProfileDefaults(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        ActivityProfile.values().forEach { profile ->
            editor.remove("profile_${profile.name}_base_layer")
            editor.remove("profile_${profile.name}_orientation_mode")
            editor.remove("profile_${profile.name}_hiking_overlay")
            editor.remove("profile_${profile.name}_outdoor_areas")
            editor.remove("profile_${profile.name}_trail_thickness")
            editor.remove("profile_${profile.name}_trail_overlay_mode")
            editor.remove("profile_${profile.name}_trail_kinds")
            editor.remove("profile_${profile.name}_trail_colors")
            editor.remove("profile_${profile.name}_font_scale")
            editor.remove("profile_${profile.name}_track_color")
        }
        editor.remove("base_layer")
            .remove("hiking_overlay")
            .remove("font_scale")
        editor.apply()
    }

    fun getCompassArrow(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean("pref_compass_arrow", true)
    }

    fun setCompassArrow(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean("pref_compass_arrow", enabled).apply()
    }

    fun getShowAltitudeOnMap(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean("pref_show_altitude_on_map", true)
    }

    fun setShowAltitudeOnMap(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean("pref_show_altitude_on_map", enabled).apply()
    }

    fun getAltitudeSourceMode(context: Context): AltitudeSourceMode {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val name = prefs.getString("pref_altitude_source", AltitudeSourceMode.DEM.name) ?: AltitudeSourceMode.DEM.name
        return try {
            AltitudeSourceMode.valueOf(name)
        } catch (_: Exception) {
            AltitudeSourceMode.DEM
        }
    }

    fun setAltitudeSourceMode(context: Context, mode: AltitudeSourceMode) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString("pref_altitude_source", mode.name).apply()
    }

    fun getShowInactiveGuests(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean("pref_show_inactive_guests", true)
    }

    fun setShowInactiveGuests(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean("pref_show_inactive_guests", enabled).apply()
    }

    fun getShowFilteredPolyline(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(PREF_SHOW_FILTERED_POLYLINE, DEFAULT_SHOW_FILTERED_POLYLINE)
    }

    fun setShowFilteredPolyline(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(PREF_SHOW_FILTERED_POLYLINE, enabled).apply()
    }

    fun getShowRawPolyline(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(PREF_SHOW_RAW_POLYLINE, DEFAULT_SHOW_RAW_POLYLINE)
    }

    fun setShowRawPolyline(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(PREF_SHOW_RAW_POLYLINE, enabled).apply()
    }

    const val PREF_AUTO_RECENTER_ENABLED = "pref_auto_recenter_enabled"
    const val PREF_AUTO_RECENTER_TIMEOUT_SEC = "pref_auto_recenter_timeout_sec"
    const val DEFAULT_AUTO_RECENTER_ENABLED = true
    const val DEFAULT_AUTO_RECENTER_TIMEOUT_SEC = 60

    fun getAutoRecenterEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(PREF_AUTO_RECENTER_ENABLED, DEFAULT_AUTO_RECENTER_ENABLED)
    }

    fun setAutoRecenterEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(PREF_AUTO_RECENTER_ENABLED, enabled).apply()
    }

    fun getAutoRecenterTimeoutSec(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(PREF_AUTO_RECENTER_TIMEOUT_SEC, DEFAULT_AUTO_RECENTER_TIMEOUT_SEC)
    }

    fun setAutoRecenterTimeoutSec(context: Context, seconds: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(PREF_AUTO_RECENTER_TIMEOUT_SEC, seconds).apply()
    }
}

enum class AltitudeSourceMode(val label: String, val description: String) {
    DEM("DEM (Online & Cache)", "Digital Elevation Model with fallback to GPS"),
    GPS("GPS Direct", "Raw device GNSS / barometric sensor altitude")
}
