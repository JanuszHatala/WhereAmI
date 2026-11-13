package online.hatala.whereami

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.osmdroid.util.GeoPoint

class MapProfileConfigTest {

    private class FakeSharedPreferences : SharedPreferences {
        val data = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = data.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (data[key] as? Set<String>)?.toMutableSet() ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = data[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = data[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = data[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val removals = mutableSetOf<String>()
            private var clearAll = false

            override fun putString(key: String?, value: String?) = apply { if (key != null) pending[key] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = apply {
                if (key != null) pending[key] = values?.toSet()
            }
            override fun putInt(key: String?, value: Int) = apply { if (key != null) pending[key] = value }
            override fun putLong(key: String?, value: Long) = apply { if (key != null) pending[key] = value }
            override fun putFloat(key: String?, value: Float) = apply { if (key != null) pending[key] = value }
            override fun putBoolean(key: String?, value: Boolean) = apply { if (key != null) pending[key] = value }
            override fun remove(key: String?) = apply { if (key != null) removals.add(key) }
            override fun clear() = apply { clearAll = true }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (clearAll) data.clear()
                removals.forEach { data.remove(it) }
                pending.forEach { (k, v) -> if (v == null) data.remove(k) else data[k] = v }
            }
        }

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    private fun testContext(prefs: FakeSharedPreferences = FakeSharedPreferences()): Context =
        object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
        }

    @Test
    fun testDefaultProfileMapConfigurations() {
        // Driving (CAR) defaults
        val carConfig = MapProfileConfigHelper.getDefaultConfig(ActivityProfile.CAR)
        assertEquals(MapBaseLayer.STANDARD, carConfig.baseLayer)
        assertEquals(MapOrientationMode.COURSE_UP, carConfig.orientationMode)
        assertFalse(carConfig.showHikingOverlay)
        assertEquals(TrailOverlayMode.VECTOR, carConfig.trailOverlayMode)
        assertEquals(MapFontScale.NORMAL, carConfig.fontScale)
        assertEquals("#EF4444", carConfig.trackColorHex)

        // Cycling defaults
        val cyclingConfig = MapProfileConfigHelper.getDefaultConfig(ActivityProfile.CYCLING)
        assertEquals(MapBaseLayer.STANDARD, cyclingConfig.baseLayer)
        assertEquals(MapOrientationMode.COURSE_UP, cyclingConfig.orientationMode)
        assertFalse(cyclingConfig.showHikingOverlay)
        assertEquals(setOf(TrailRouteKind.CYCLING, TrailRouteKind.MTB), cyclingConfig.enabledTrailKinds)
        assertEquals("#EF4444", cyclingConfig.trackColorHex)

        // MTB defaults: clean Topographic + filterable Vector trails
        val mtbConfig = MapProfileConfigHelper.getDefaultConfig(ActivityProfile.MTB)
        assertEquals(MapBaseLayer.TOPO, mtbConfig.baseLayer)
        assertEquals(MapOrientationMode.COURSE_UP, mtbConfig.orientationMode)
        assertTrue(mtbConfig.showHikingOverlay)
        assertEquals(TrailOverlayMode.VECTOR, mtbConfig.trailOverlayMode)
        assertEquals(setOf(TrailRouteKind.MTB, TrailRouteKind.CYCLING), mtbConfig.enabledTrailKinds)
        assertEquals("#10B981", mtbConfig.trackColorHex)

        // Hiking defaults: clean Topographic + filterable Vector trails
        val hikingConfig = MapProfileConfigHelper.getDefaultConfig(ActivityProfile.HIKING)
        assertEquals(MapBaseLayer.TOPO, hikingConfig.baseLayer)
        assertEquals(MapOrientationMode.COURSE_UP, hikingConfig.orientationMode)
        assertTrue(hikingConfig.showHikingOverlay)
        assertEquals(TrailOverlayMode.VECTOR, hikingConfig.trailOverlayMode)
        assertEquals(setOf(TrailRouteKind.HIKING, TrailRouteKind.EDUCATIONAL), hikingConfig.enabledTrailKinds)
        assertEquals("#F59E0B", hikingConfig.trackColorHex)

        // Running defaults
        val runningConfig = MapProfileConfigHelper.getDefaultConfig(ActivityProfile.RUNNING)
        assertEquals(MapBaseLayer.STANDARD, runningConfig.baseLayer)
        assertEquals(MapOrientationMode.NORTH, runningConfig.orientationMode)
        assertFalse(runningConfig.showHikingOverlay)
        assertEquals("#EF4444", runningConfig.trackColorHex)

        // Walking defaults
        val walkingConfig = MapProfileConfigHelper.getDefaultConfig(ActivityProfile.WALKING)
        assertEquals(MapBaseLayer.STANDARD, walkingConfig.baseLayer)
        assertEquals(MapOrientationMode.NORTH, walkingConfig.orientationMode)
        assertFalse(walkingConfig.showHikingOverlay)
        assertEquals("#EF4444", walkingConfig.trackColorHex)

        // Kayaking defaults: clean Topographic + filterable Vector kayaking routes
        val kayakingConfig = MapProfileConfigHelper.getDefaultConfig(ActivityProfile.KAYAKING)
        assertEquals(MapBaseLayer.TOPO, kayakingConfig.baseLayer)
        assertEquals(MapOrientationMode.COURSE_UP, kayakingConfig.orientationMode)
        assertTrue(kayakingConfig.showHikingOverlay)
        assertEquals(TrailOverlayMode.VECTOR, kayakingConfig.trailOverlayMode)
        assertEquals(setOf(TrailRouteKind.KAYAKING), kayakingConfig.enabledTrailKinds)
    }

    @Test
    fun testAllProfilesCoveredByConfig() {
        for (profile in ActivityProfile.values()) {
            val config = MapProfileConfigHelper.getDefaultConfig(profile)
            assertTrue(config.baseLayer in MapBaseLayer.values())
            assertTrue(config.orientationMode in MapOrientationMode.values())
            assertTrue(config.fontScale in MapFontScale.values())
            assertTrue(config.trailOverlayMode in TrailOverlayMode.values())
        }
    }

    @Test
    fun testPolylinePreferencesConstantsAndDefaults() {
        assertEquals("where_am_i_map_prefs", MapProfileConfigHelper.PREFS_NAME)
        assertEquals("pref_show_filtered_polyline", MapProfileConfigHelper.PREF_SHOW_FILTERED_POLYLINE)
        assertEquals("pref_show_raw_polyline", MapProfileConfigHelper.PREF_SHOW_RAW_POLYLINE)
        assertTrue("Filtered polyline must be enabled by default", MapProfileConfigHelper.DEFAULT_SHOW_FILTERED_POLYLINE)
        assertFalse("Raw polyline must be disabled by default", MapProfileConfigHelper.DEFAULT_SHOW_RAW_POLYLINE)
    }

    @Test
    fun testLegacyFreemapMigratesOnceAndPreservesLaterManualSelection() {
        val prefs = FakeSharedPreferences().apply {
            data["profile_HIKING_base_layer"] = MapBaseLayer.FREEMAP_OUTDOOR.name
            data["profile_MTB_base_layer"] = MapBaseLayer.FREEMAP_OUTDOOR.name
            data["profile_KAYAKING_base_layer"] = MapBaseLayer.FREEMAP_OUTDOOR.name
            data["profile_CAR_base_layer"] = MapBaseLayer.FREEMAP_OUTDOOR.name
        }
        val ctx = testContext(prefs)

        assertEquals(MapBaseLayer.TOPO, MapProfileConfigHelper.getConfig(ctx, ActivityProfile.HIKING).baseLayer)
        assertEquals(MapBaseLayer.TOPO, MapProfileConfigHelper.getConfig(ctx, ActivityProfile.MTB).baseLayer)
        assertEquals(MapBaseLayer.TOPO, MapProfileConfigHelper.getConfig(ctx, ActivityProfile.KAYAKING).baseLayer)
        assertEquals(MapBaseLayer.FREEMAP_OUTDOOR, MapProfileConfigHelper.getConfig(ctx, ActivityProfile.CAR).baseLayer)

        // Manual opt-back-in after one-time migration must stick
        MapProfileConfigHelper.setBaseLayer(ctx, ActivityProfile.HIKING, MapBaseLayer.FREEMAP_OUTDOOR)
        assertEquals(MapBaseLayer.FREEMAP_OUTDOOR, MapProfileConfigHelper.getConfig(ctx, ActivityProfile.HIKING).baseLayer)
    }

    @Test
    fun testSelectiveTrailKindAndColorPersistenceAndFiltering() {
        val ctx = testContext()
        MapProfileConfigHelper.setTrailOverlayMode(ctx, ActivityProfile.HIKING, TrailOverlayMode.WAYMARKED)
        MapProfileConfigHelper.setEnabledTrailKinds(ctx, ActivityProfile.HIKING, setOf(TrailRouteKind.HIKING))
        MapProfileConfigHelper.setEnabledTrailColors(ctx, ActivityProfile.HIKING, setOf(TrailColor.RED, TrailColor.BLUE))

        val loaded = MapProfileConfigHelper.getConfig(ctx, ActivityProfile.HIKING)
        assertEquals(TrailOverlayMode.WAYMARKED, loaded.trailOverlayMode)
        assertEquals(setOf(TrailRouteKind.HIKING), loaded.enabledTrailKinds)
        assertEquals(setOf(TrailColor.RED, TrailColor.BLUE), loaded.enabledTrailColors)

        val redHiking = TrailVectorSegment(
            id = 1L, name = "Red Summit Trail", kind = TrailRouteKind.HIKING, color = TrailColor.RED,
            points = listOf(GeoPoint(50.68, 16.49), GeoPoint(50.69, 16.50))
        )
        val greenHiking = redHiking.copy(id = 2L, color = TrailColor.GREEN)
        val redMtb = redHiking.copy(id = 3L, kind = TrailRouteKind.MTB)

        assertTrue(VectorTrailOverlay.matchesFilters(redHiking, loaded.enabledTrailKinds, loaded.enabledTrailColors))
        assertFalse(VectorTrailOverlay.matchesFilters(greenHiking, loaded.enabledTrailKinds, loaded.enabledTrailColors))
        assertFalse(VectorTrailOverlay.matchesFilters(redMtb, loaded.enabledTrailKinds, loaded.enabledTrailColors))
        assertTrue(VectorTrailOverlay.matchesFilters(greenHiking, emptySet(), emptySet()))

        MapProfileConfigHelper.resetProfileDefaults(ctx, ActivityProfile.HIKING)
        val reset = MapProfileConfigHelper.getConfig(ctx, ActivityProfile.HIKING)
        assertEquals(TrailOverlayMode.VECTOR, reset.trailOverlayMode)
        assertEquals(setOf(TrailRouteKind.HIKING, TrailRouteKind.EDUCATIONAL), reset.enabledTrailKinds)
        assertTrue(reset.enabledTrailColors.isEmpty())
    }
}
