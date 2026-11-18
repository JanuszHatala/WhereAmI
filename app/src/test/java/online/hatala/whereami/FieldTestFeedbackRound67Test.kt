package online.hatala.whereami

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldTestFeedbackRound67Test {

    @Test
    fun testSpeedPaceFormatterPartsWithoutAverage() {
        val speedMetric = SpeedPaceFormatter.formatSpeedOrPaceParts(
            currentSpeedMs = 10.0f, // 36.0 km/h
            movingAvgSpeedKmh = null,
            preferPace = false
        )
        assertTrue(speedMetric.primaryText.contains("36.0") || speedMetric.primaryText.contains("36,0"))
        assertTrue(speedMetric.primaryText.endsWith("km/h"))
        assertNull(speedMetric.separator)
        assertNull(speedMetric.secondaryText)
        assertEquals(speedMetric.primaryText, speedMetric.fullText)

        val paceMetric = SpeedPaceFormatter.formatSpeedOrPaceParts(
            currentSpeedMs = 10.0f / 3.6f, // 10.0 km/h -> 6:00 min/km
            movingAvgSpeedKmh = null,
            preferPace = true
        )
        assertEquals("6:00 min/km", paceMetric.primaryText)
        assertNull(paceMetric.separator)
        assertNull(paceMetric.secondaryText)
    }

    @Test
    fun testSpeedPaceFormatterPartsWithAverage() {
        val speedMetric = SpeedPaceFormatter.formatSpeedOrPaceParts(
            currentSpeedMs = 13.333f, // 48.0 km/h
            movingAvgSpeedKmh = 26.1f,
            preferPace = false
        )
        assertTrue(speedMetric.primaryText.contains("48.0") || speedMetric.primaryText.contains("48,0"))
        assertEquals(" · ", speedMetric.separator)
        val secText = speedMetric.secondaryText
        assertNotNull(secText)
        assertTrue(secText!!.contains("⌀"))
        assertTrue(secText.contains("26.1") || secText.contains("26,1"))
        assertTrue(secText.endsWith("km/h"))

        val paceMetric = SpeedPaceFormatter.formatSpeedOrPaceParts(
            currentSpeedMs = 12.0f / 3.6f, // 12 km/h -> 5:00
            movingAvgSpeedKmh = 10.0f,     // 10 km/h -> 6:00
            preferPace = true
        )
        assertEquals("5:00", paceMetric.primaryText)
        assertEquals(" · ", paceMetric.separator)
        assertEquals("⌀ 6:00 /km", paceMetric.secondaryText)
    }

    @Test
    fun testOutdoorContextResolverBuildOverpassQueryProximities() {
        // Uniform proximities
        val uniformMap = mapOf(
            TrailRouteKind.HIKING to 40,
            TrailRouteKind.CYCLING to 40,
            TrailRouteKind.MTB to 40,
            TrailRouteKind.KAYAKING to 40,
            TrailRouteKind.EDUCATIONAL to 40
        )
        val uniformQl = OutdoorContextResolver.buildOverpassQuery(49.8, 19.0, uniformMap)
        assertTrue(uniformQl.contains("way(around:40,49.8,19.0)[highway];rel(bw)[type=route][route~\"^(hiking|foot|mtb|bicycle|canoe|kayak)$\"]"))

        // Divergent custom proximities
        val customMap = mapOf(
            TrailRouteKind.HIKING to 25,
            TrailRouteKind.CYCLING to 80,
            TrailRouteKind.MTB to 60,
            TrailRouteKind.KAYAKING to 120,
            TrailRouteKind.EDUCATIONAL to 30
        )
        val customQl = OutdoorContextResolver.buildOverpassQuery(49.8, 19.0, customMap)
        assertTrue(customQl.contains("way(around:25,49.8,19.0)[highway];rel(bw)[type=route][route~\"^(hiking|foot)$\"]"))
        assertTrue(customQl.contains("way(around:80,49.8,19.0)[highway];rel(bw)[type=route][route~\"^(bicycle|cycling)$\"]"))
        assertTrue(customQl.contains("way(around:60,49.8,19.0)[highway];rel(bw)[type=route][route=\"mtb\"]"))
        assertTrue(customQl.contains("way(around:120,49.8,19.0);rel(bw)[type=route][route~\"^(canoe|kayak)$\"]"))
        assertTrue(customQl.contains("way(around:30,49.8,19.0)[highway]"))
    }

    @Test
    fun testTrailProximityConstants() {
        assertEquals(40, MapProfileConfigHelper.DEFAULT_TRAIL_PROXIMITY_METERS)
        assertEquals(10, MapProfileConfigHelper.MIN_TRAIL_PROXIMITY_METERS)
        assertEquals(150, MapProfileConfigHelper.MAX_TRAIL_PROXIMITY_METERS)
    }
}
