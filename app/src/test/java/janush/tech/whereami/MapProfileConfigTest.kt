package janush.tech.whereami

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapProfileConfigTest {

    @Test
    fun testDefaultProfileMapConfigurations() {
        // Driving (CAR) defaults
        val carConfig = MapProfileConfigHelper.getDefaultConfig(ActivityProfile.CAR)
        assertEquals(MapBaseLayer.STANDARD, carConfig.baseLayer)
        assertEquals(MapOrientationMode.COURSE_UP, carConfig.orientationMode)
        assertFalse(carConfig.showHikingOverlay)
        assertEquals(MapFontScale.NORMAL, carConfig.fontScale)

        // Cycling defaults
        val cyclingConfig = MapProfileConfigHelper.getDefaultConfig(ActivityProfile.CYCLING)
        assertEquals(MapBaseLayer.STANDARD, cyclingConfig.baseLayer)
        assertEquals(MapOrientationMode.COURSE_UP, cyclingConfig.orientationMode)
        assertFalse(cyclingConfig.showHikingOverlay)

        // MTB defaults
        val mtbConfig = MapProfileConfigHelper.getDefaultConfig(ActivityProfile.MTB)
        assertEquals(MapBaseLayer.FREEMAP_OUTDOOR, mtbConfig.baseLayer)
        assertEquals(MapOrientationMode.COURSE_UP, mtbConfig.orientationMode)
        assertTrue(mtbConfig.showHikingOverlay)

        // Hiking defaults
        val hikingConfig = MapProfileConfigHelper.getDefaultConfig(ActivityProfile.HIKING)
        assertEquals(MapBaseLayer.FREEMAP_OUTDOOR, hikingConfig.baseLayer)
        assertEquals(MapOrientationMode.COURSE_UP, hikingConfig.orientationMode)
        assertTrue(hikingConfig.showHikingOverlay)

        // Running defaults
        val runningConfig = MapProfileConfigHelper.getDefaultConfig(ActivityProfile.RUNNING)
        assertEquals(MapBaseLayer.STANDARD, runningConfig.baseLayer)
        assertEquals(MapOrientationMode.NORTH, runningConfig.orientationMode)
        assertFalse(runningConfig.showHikingOverlay)

        // Walking defaults
        val walkingConfig = MapProfileConfigHelper.getDefaultConfig(ActivityProfile.WALKING)
        assertEquals(MapBaseLayer.STANDARD, walkingConfig.baseLayer)
        assertEquals(MapOrientationMode.NORTH, walkingConfig.orientationMode)
        assertFalse(walkingConfig.showHikingOverlay)
    }

    @Test
    fun testAllProfilesCoveredByConfig() {
        for (profile in ActivityProfile.values()) {
            val config = MapProfileConfigHelper.getDefaultConfig(profile)
            assertTrue(config.baseLayer in MapBaseLayer.values())
            assertTrue(config.orientationMode in MapOrientationMode.values())
            assertTrue(config.fontScale in MapFontScale.values())
        }
    }
}
