package janush.tech.whereami

import android.content.Context

data class ProfileMapConfig(
    val baseLayer: MapBaseLayer,
    val orientationMode: MapOrientationMode,
    val showHikingOverlay: Boolean,
    val fontScale: MapFontScale,
    val trackColorHex: String = "#EF4444"
)

object MapProfileConfigHelper {
    private const val PREFS_NAME = "where_am_i_map_prefs"

    fun getDefaultConfig(profile: ActivityProfile): ProfileMapConfig = when (profile) {
        ActivityProfile.CAR -> ProfileMapConfig(
            baseLayer = MapBaseLayer.STANDARD,
            orientationMode = MapOrientationMode.COURSE_UP,
            showHikingOverlay = false,
            fontScale = MapFontScale.NORMAL,
            trackColorHex = "#38BDF8"
        )
        ActivityProfile.CYCLING -> ProfileMapConfig(
            baseLayer = MapBaseLayer.STANDARD,
            orientationMode = MapOrientationMode.COURSE_UP,
            showHikingOverlay = false,
            fontScale = MapFontScale.NORMAL,
            trackColorHex = "#06B6D4"
        )
        ActivityProfile.MTB -> ProfileMapConfig(
            baseLayer = MapBaseLayer.FREEMAP_OUTDOOR,
            orientationMode = MapOrientationMode.COURSE_UP,
            showHikingOverlay = true,
            fontScale = MapFontScale.NORMAL,
            trackColorHex = "#10B981"
        )
        ActivityProfile.HIKING -> ProfileMapConfig(
            baseLayer = MapBaseLayer.FREEMAP_OUTDOOR,
            orientationMode = MapOrientationMode.COURSE_UP,
            showHikingOverlay = true,
            fontScale = MapFontScale.NORMAL,
            trackColorHex = "#F59E0B"
        )
        ActivityProfile.RUNNING -> ProfileMapConfig(
            baseLayer = MapBaseLayer.STANDARD,
            orientationMode = MapOrientationMode.NORTH,
            showHikingOverlay = false,
            fontScale = MapFontScale.NORMAL,
            trackColorHex = "#EC4899"
        )
        ActivityProfile.WALKING -> ProfileMapConfig(
            baseLayer = MapBaseLayer.STANDARD,
            orientationMode = MapOrientationMode.NORTH,
            showHikingOverlay = false,
            fontScale = MapFontScale.NORMAL,
            trackColorHex = "#8B5CF6"
        )
    }

    fun getConfig(context: Context, profile: ActivityProfile): ProfileMapConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val default = getDefaultConfig(profile)

        val baseLayerName = prefs.getString("profile_${profile.name}_base_layer", null)
        val orientationName = prefs.getString("profile_${profile.name}_orientation_mode", null)
        val hikingOverlay = if (prefs.contains("profile_${profile.name}_hiking_overlay")) {
            prefs.getBoolean("profile_${profile.name}_hiking_overlay", default.showHikingOverlay)
        } else default.showHikingOverlay
        val fontScaleName = prefs.getString("profile_${profile.name}_font_scale", null)
        val trackColorHex = prefs.getString("profile_${profile.name}_track_color", null) ?: default.trackColorHex

        val baseLayer = baseLayerName?.let {
            try { MapBaseLayer.valueOf(it) } catch (_: Exception) { null }
        } ?: default.baseLayer

        val orientation = orientationName?.let {
            try { MapOrientationMode.valueOf(it) } catch (_: Exception) { null }
        } ?: default.orientationMode

        val fontScale = fontScaleName?.let {
            try { MapFontScale.valueOf(it) } catch (_: Exception) { null }
        } ?: default.fontScale

        return ProfileMapConfig(baseLayer, orientation, hikingOverlay, fontScale, trackColorHex)
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
            editor.remove("profile_${profile.name}_font_scale")
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
}

enum class AltitudeSourceMode(val label: String, val description: String) {
    DEM("DEM (Online & Cache)", "Digital Elevation Model with fallback to GPS"),
    GPS("GPS Direct", "Raw device GNSS / barometric sensor altitude")
}
