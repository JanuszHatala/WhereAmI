package janush.tech.whereami

import android.content.Context

data class ProfileMapConfig(
    val baseLayer: MapBaseLayer,
    val orientationMode: MapOrientationMode,
    val showHikingOverlay: Boolean,
    val fontScale: MapFontScale
)

object MapProfileConfigHelper {
    private const val PREFS_NAME = "where_am_i_map_prefs"

    fun getDefaultConfig(profile: ActivityProfile): ProfileMapConfig = when (profile) {
        ActivityProfile.CAR -> ProfileMapConfig(
            baseLayer = MapBaseLayer.STANDARD,
            orientationMode = MapOrientationMode.COURSE_UP,
            showHikingOverlay = false,
            fontScale = MapFontScale.NORMAL
        )
        ActivityProfile.CYCLING -> ProfileMapConfig(
            baseLayer = MapBaseLayer.STANDARD,
            orientationMode = MapOrientationMode.COURSE_UP,
            showHikingOverlay = false,
            fontScale = MapFontScale.NORMAL
        )
        ActivityProfile.MTB -> ProfileMapConfig(
            baseLayer = MapBaseLayer.FREEMAP_OUTDOOR,
            orientationMode = MapOrientationMode.COURSE_UP,
            showHikingOverlay = true,
            fontScale = MapFontScale.NORMAL
        )
        ActivityProfile.HIKING -> ProfileMapConfig(
            baseLayer = MapBaseLayer.FREEMAP_OUTDOOR,
            orientationMode = MapOrientationMode.COURSE_UP,
            showHikingOverlay = true,
            fontScale = MapFontScale.NORMAL
        )
        ActivityProfile.RUNNING -> ProfileMapConfig(
            baseLayer = MapBaseLayer.STANDARD,
            orientationMode = MapOrientationMode.NORTH,
            showHikingOverlay = false,
            fontScale = MapFontScale.NORMAL
        )
        ActivityProfile.WALKING -> ProfileMapConfig(
            baseLayer = MapBaseLayer.STANDARD,
            orientationMode = MapOrientationMode.NORTH,
            showHikingOverlay = false,
            fontScale = MapFontScale.NORMAL
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

        val baseLayer = baseLayerName?.let {
            try { MapBaseLayer.valueOf(it) } catch (_: Exception) { null }
        } ?: default.baseLayer

        val orientation = orientationName?.let {
            try { MapOrientationMode.valueOf(it) } catch (_: Exception) { null }
        } ?: default.orientationMode

        val fontScale = fontScaleName?.let {
            try { MapFontScale.valueOf(it) } catch (_: Exception) { null }
        } ?: default.fontScale

        return ProfileMapConfig(baseLayer, orientation, hikingOverlay, fontScale)
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

    fun resetProfileDefaults(context: Context, profile: ActivityProfile) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .remove("profile_${profile.name}_base_layer")
            .remove("profile_${profile.name}_orientation_mode")
            .remove("profile_${profile.name}_hiking_overlay")
            .remove("profile_${profile.name}_font_scale")
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
}
