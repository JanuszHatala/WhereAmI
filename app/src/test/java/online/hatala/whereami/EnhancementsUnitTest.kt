package online.hatala.whereami

import android.view.WindowManager
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnhancementsUnitTest {

    @Test
    fun `ScreenDimController resolves overrides and window brightness`() {
        assertEquals(5, ScreenDimController.clampLevelPercent(0))
        assertEquals(60, ScreenDimController.clampLevelPercent(99))
        assertEquals(20, ScreenDimController.clampLevelPercent(20))

        assertTrue(ScreenDimController.isEffectiveDimEnabled(defaultEnabled = true, sessionOverrideEnabled = null))
        assertFalse(ScreenDimController.isEffectiveDimEnabled(defaultEnabled = true, sessionOverrideEnabled = false))
        assertTrue(ScreenDimController.isEffectiveDimEnabled(defaultEnabled = false, sessionOverrideEnabled = true))

        assertEquals(15, ScreenDimController.effectiveLevelPercent(25, 15))
        assertEquals(25, ScreenDimController.effectiveLevelPercent(25, null))

        // Dimmed when keepScreenOn && effectiveDimEnabled && isDimmed
        assertEquals(
            0.20f,
            ScreenDimController.resolveWindowBrightness(
                shouldKeepScreenOn = true,
                isDimmed = true,
                effectiveDimEnabled = true,
                effectiveLevelPercent = 20
            ),
            0.001f
        )

        // Restored override none when awake or keepScreenOn disabled or dim disabled
        assertEquals(
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE,
            ScreenDimController.resolveWindowBrightness(true, isDimmed = false, effectiveDimEnabled = true, 20),
            0.001f
        )
        assertEquals(
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE,
            ScreenDimController.resolveWindowBrightness(false, isDimmed = true, effectiveDimEnabled = true, 20),
            0.001f
        )
        assertEquals(
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE,
            ScreenDimController.resolveWindowBrightness(true, isDimmed = true, effectiveDimEnabled = false, 20),
            0.001f
        )

        assertEquals("30s", ScreenDimController.formatTimeoutLabel(30))
        assertEquals("2m", ScreenDimController.formatTimeoutLabel(120))
    }

    @Test
    fun `MagnifierState activates centered on tap and clamps edges`() {
        // Center tap -> 0 offset
        val centered = MagnifierState.activateAtTap(
            tapX = 500f,
            tapY = 1000f,
            viewportWidth = 1000f,
            viewportHeight = 2000f,
            initialScale = 2.0f
        )
        assertEquals(MagnifierPhase.ACTIVE, centered.phase)
        assertEquals(2.0f, centered.scale, 0.001f)
        assertEquals(0f, centered.offsetX, 0.01f)
        assertEquals(0f, centered.offsetY, 0.01f)

        // Corner tap -> clamped to maxOffset = W * (S - 1) / 2
        val topLeft = MagnifierState.activateAtTap(
            tapX = 0f,
            tapY = 0f,
            viewportWidth = 1000f,
            viewportHeight = 2000f,
            initialScale = 2.5f
        )
        assertEquals(750f, topLeft.offsetX, 0.01f)
        assertEquals(1500f, topLeft.offsetY, 0.01f)

        // Pan & zoom clamping
        val zoomed = centered.applyTransform(
            panX = 10_000f,
            panY = -10_000f,
            zoomChange = 4.0f, // clamps at MAX_SCALE = 5.0
            viewportWidth = 1000f,
            viewportHeight = 2000f
        )
        assertEquals(MagnifierState.MAX_SCALE, zoomed.scale, 0.001f)
        assertEquals(2000f, zoomed.offsetX, 0.01f) // 1000 * (5 - 1) / 2
        assertEquals(-4000f, zoomed.offsetY, 0.01f) // 2000 * (5 - 1) / 2

        // Non-active state ignores transform
        val off = MagnifierState(phase = MagnifierPhase.AWAITING_TAP)
        assertEquals(off, off.applyTransform(100f, 100f, 2f, 1000f, 2000f))
    }

    @Test
    fun `SpeedPaceFormatter computes moving average excluding pauses and formats metrics`() {
        val t0 = 1_000_000L
        assertNull(SpeedPaceFormatter.computeMovingAvgSpeedKmh(null, t0))

        // Below 50 m distance returns null
        val shortTrip = TripRecord(startTime = t0, distanceMeters = 30.0)
        assertNull(SpeedPaceFormatter.computeMovingAvgSpeedKmh(shortTrip, t0 + 60_000L))

        // 10 km in 60 min total, with 20 min completed pause + 10 min open pause = 30 min moving -> 20.0 km/h
        val tripWithPauses = TripRecord(
            startTime = t0,
            distanceMeters = 10_000.0,
            pauses = listOf(
                TripPause(startTime = t0 + 10 * 60_000L, endTime = t0 + 30 * 60_000L, latitude = 50.0, longitude = 19.0),
                TripPause(startTime = t0 + 50 * 60_000L, endTime = null, latitude = 50.05, longitude = 19.05)
            )
        )
        val avg = SpeedPaceFormatter.computeMovingAvgSpeedKmh(tripWithPauses, t0 + 60 * 60_000L)
        assertEquals(20.0f, avg ?: 0f, 0.05f)

        // Speed formatting with and without avg
        assertEquals("0.0 km/h", SpeedPaceFormatter.formatSpeedOrPace(0f, null, preferPace = false))
        assertTrue(SpeedPaceFormatter.formatSpeedOrPace(10f, 25f, preferPace = false).contains("⌀"))
        // 12 km/h = 3.3333 m/s -> 5:00 min/km
        assertEquals("5:00 min/km", SpeedPaceFormatter.formatSpeedOrPace(12f / 3.6f, null, preferPace = true))
        assertTrue(SpeedPaceFormatter.formatSpeedOrPace(12f / 3.6f, 10f, preferPace = true).contains("⌀ 6:00 /km"))
    }

    @Test
    fun `NominatimExtendedMetadata parses extratags and ISO codes cleanly`() {
        val json = JSONObject().apply {
            put("osm_type", "relation")
            put("osm_id", 2799012L)
            put("class", "boundary")
            put("type", "administrative")
            put("place_rank", 16)
            put("display_name", "Kozy, powiat bielski, województwo śląskie, 43-340, Polska")
            put("address", JSONObject().apply {
                put("postcode", "43-340")
                put("country_code", "pl")
                put("ISO3166-2-lvl4", "PL-24")
            })
            put("extratags", JSONObject().apply {
                put("teryt:simc", "0054408")
                put("wikidata", "Q1778791")
                put("wikipedia", "pl:Kozy")
                put("population", "13142")
                put("admin_level", "8")
            })
        }

        val parsed = NominatimExtendedMetadata.fromNominatimJson(json)
        assertTrue(parsed.hasAnyCode)
        assertEquals("43-340", parsed.postcode)
        assertEquals("PL", parsed.countryCodeIso2)
        assertEquals("PL-24", parsed.iso3166Lvl4)
        assertEquals("0054408", parsed.terytSimc)
        assertEquals("relation", parsed.osmType)
        assertEquals(2799012L, parsed.osmId)
        assertEquals(16, parsed.placeRank)
        assertEquals(8, parsed.adminLevel)
        assertEquals("Q1778791", parsed.wikidataId)
        assertEquals("pl:Kozy", parsed.wikipediaTag)
        assertEquals("13142", parsed.population)
    }

    @Test
    fun `CoordinateFormatHelper formats decimal and DMS hemispheres`() {
        assertEquals("49.84281, 19.13564", CoordinateFormatHelper.formatDecimal(49.842812, 19.135640))
        val dmsNorthEast = CoordinateFormatHelper.formatDms(49.5, 19.25)
        assertTrue(dmsNorthEast.contains("49°30'00.0\"N"))
        assertTrue(dmsNorthEast.contains("19°15'00.0\"E"))

        val dmsSouthWest = CoordinateFormatHelper.formatDms(-33.8688, -151.2093)
        assertTrue(dmsSouthWest.endsWith("W"))
        assertTrue(dmsSouthWest.contains("S "))
    }
}
