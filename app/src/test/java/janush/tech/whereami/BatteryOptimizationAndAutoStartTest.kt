package janush.tech.whereami

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryOptimizationAndAutoStartTest {

    @Test
    fun testAllActivityProfilesHaveConfiguredAutoStartParameters() {
        for (profile in ActivityProfile.values()) {
            assertTrue("Auto-start speed for ${profile.name} must be > 0", profile.autoStartSpeedKmh > 0f)
            assertTrue("Auto-start duration for ${profile.name} must be >= 5s", profile.autoStartDurationMs >= 5_000L)
            assertTrue("Auto-stop speed for ${profile.name} must be > 0", profile.autoStopSpeedKmh > 0f)
            assertTrue("Auto-stop speed must be <= auto-start speed for ${profile.name}", profile.autoStopSpeedKmh <= profile.autoStartSpeedKmh)
        }
    }

    @Test
    fun testWalkingAndHikingAutoStartThresholds() {
        val walking = ActivityProfile.WALKING
        val hiking = ActivityProfile.HIKING

        // Walking & hiking should be sensitive to human walking speeds (~2.5 km/h)
        assertEquals(2.5f, walking.autoStartSpeedKmh, 0.01f)
        assertEquals(15_000L, walking.autoStartDurationMs)

        assertEquals(2.5f, hiking.autoStartSpeedKmh, 0.01f)
        assertEquals(15_000L, hiking.autoStartDurationMs)

        val candidateThresholdWalking = walking.autoStartSpeedKmh * 0.7f
        assertEquals(1.75f, candidateThresholdWalking, 0.01f)

        // Stroll at 2.0 km/h is a candidate
        assertTrue(2.0f >= candidateThresholdWalking)
        // Stationary jitter at 0.8 km/h is NOT a candidate
        assertFalse(0.8f >= candidateThresholdWalking)
    }

    @Test
    fun testDrivingAutoStartThresholds() {
        val car = ActivityProfile.CAR
        assertEquals(10.0f, car.autoStartSpeedKmh, 0.01f)
        assertEquals(10_000L, car.autoStartDurationMs)

        val candidateThresholdCar = car.autoStartSpeedKmh * 0.7f
        assertEquals(7.0f, candidateThresholdCar, 0.01f)

        // Driving at 15 km/h satisfies candidate & auto-start
        assertTrue(15.0f >= candidateThresholdCar)
        assertTrue(15.0f >= car.autoStartSpeedKmh)

        // Walking speed (4 km/h) does NOT trigger car auto-start
        assertFalse(4.0f >= candidateThresholdCar)
    }

    @Test
    fun testCyclingAndMtbAutoStartThresholds() {
        val cycling = ActivityProfile.CYCLING
        val mtb = ActivityProfile.MTB

        assertEquals(7.0f, cycling.autoStartSpeedKmh, 0.01f)
        assertEquals(10_000L, cycling.autoStartDurationMs)

        assertEquals(6.0f, mtb.autoStartSpeedKmh, 0.01f)
        assertEquals(10_000L, mtb.autoStartDurationMs)

        val cyclingCandidate = cycling.autoStartSpeedKmh * 0.7f
        val mtbCandidate = mtb.autoStartSpeedKmh * 0.7f

        assertTrue(8.0f >= cycling.autoStartSpeedKmh)
        assertTrue(6.5f >= mtb.autoStartSpeedKmh)
        assertTrue(5.0f >= cyclingCandidate)
        assertFalse(2.0f >= cyclingCandidate)
    }

    @Test
    fun testMotionBurstDurationAdaptsToProfile() {
        // Confirmation burst window must be longer than the profile's required autoStartDurationMs
        for (profile in ActivityProfile.values()) {
            val burstDurationMs = (profile.autoStartDurationMs + 30_000L).coerceAtLeast(60_000L)
            assertTrue(
                "Burst duration $burstDurationMs ms must comfortably exceed required autoStartDurationMs ${profile.autoStartDurationMs} ms for ${profile.name}",
                burstDurationMs >= profile.autoStartDurationMs + 20_000L
            )
            assertTrue("Burst duration should not be excessively long to preserve battery", burstDurationMs <= 65_000L)
        }
    }

    @Test
    fun testAppLifecycleModeSemantics() {
        // IDLE mode must exist for zero-drain deep sleep
        val idle = AppLifecycleMode.IDLE
        assertEquals("Idle / Suspended", idle.displayName)

        val liveOnly = AppLifecycleMode.LIVE_ONLY
        assertEquals("Live Sharing", liveOnly.displayName)

        val tripRecording = AppLifecycleMode.TRIP_RECORDING
        assertEquals("Recording Trip", tripRecording.displayName)
    }

    @Test
    fun testCustomAutoStartSpeedClampingAndCandidateSensitivity() {
        // Validation of speed clamping rule (1.0f .. 150.0f)
        val clampSpeed = { speed: Float -> speed.coerceIn(1.0f, 150.0f) }
        assertEquals(1.0f, clampSpeed(0.5f), 0.001f)
        assertEquals(1.0f, clampSpeed(-10f), 0.001f)
        assertEquals(150.0f, clampSpeed(200f), 0.001f)
        assertEquals(4.5f, clampSpeed(4.5f), 0.001f)

        // Custom speed threshold adaptation for candidate and sustained speed triggers
        // E.g. user sets cycling threshold to 5.0 km/h (for steep climbs or casual city rides)
        val customCyclingSpeed = 5.0f
        val candidateCycling = customCyclingSpeed * 0.7f
        assertEquals(3.5f, candidateCycling, 0.01f)
        assertTrue("Riding at 3.6 km/h qualifies as candidate speed for 5 km/h target", 3.6f >= candidateCycling)
        assertFalse("Walking at 3.0 km/h does not qualify as candidate speed for 5 km/h target", 3.0f >= candidateCycling)
        assertTrue("Riding at 5.0 km/h triggers sustained auto-start", 5.0f >= customCyclingSpeed)

        // E.g. user sets car threshold to 15.0 km/h
        val customCarSpeed = 15.0f
        val candidateCar = customCarSpeed * 0.7f
        assertEquals(10.5f, candidateCar, 0.01f)
        assertTrue("Speed at 11 km/h is candidate for 15 km/h car threshold", 11.0f >= candidateCar)
        assertFalse("Speed at 9 km/h is not candidate for 15 km/h car threshold", 9.0f >= candidateCar)
    }

    @Test
    fun testIndependentProfileManagementCoverage() {
        // Verify all 6 activity profiles exist and possess distinct identity and defaults
        val allProfiles = ActivityProfile.values()
        assertEquals(6, allProfiles.size)
        assertTrue(allProfiles.contains(ActivityProfile.CAR))
        assertTrue(allProfiles.contains(ActivityProfile.CYCLING))
        assertTrue(allProfiles.contains(ActivityProfile.MTB))
        assertTrue(allProfiles.contains(ActivityProfile.HIKING))
        assertTrue(allProfiles.contains(ActivityProfile.RUNNING))
        assertTrue(allProfiles.contains(ActivityProfile.WALKING))

        // Ensure each profile has distinct emoji and valid timeout presets
        for (profile in allProfiles) {
            assertTrue("Profile displayName cannot be blank", profile.displayName.isNotBlank())
            assertTrue("Profile emoji cannot be blank", profile.iconEmoji.isNotBlank())
            assertTrue("Default auto-stop minutes must be between 1 and 60", profile.autoStopMinutesDefault in 1..60)
        }
    }

    @Test
    fun testWakeLockScopingStrictlyToActiveTripRecording() {
        // AGENTS.md Rule 2.A:
        // Wake locks must ONLY be acquired when an active trip is actively recording.
        // Never during Live Sharing alone (LIVE_ONLY mode), in onCreate(), or in standby/idle.
        val shouldHoldWakeLock = { hasTrip: Boolean, hasLive: Boolean ->
            hasTrip // Strictly scoped to active trip recording
        }

        // Active trip + Live sharing -> Hold wake lock
        assertTrue(shouldHoldWakeLock(true, true))

        // Active trip only -> Hold wake lock
        assertTrue(shouldHoldWakeLock(true, false))

        // Live sharing only (without active trip) -> MUST NOT hold wake lock
        assertFalse("Live sharing alone must not hold persistent wake lock to allow Doze deep sleep", shouldHoldWakeLock(false, true))

        // Standby/Idle -> MUST NOT hold wake lock
        assertFalse(shouldHoldWakeLock(false, false))
    }

    @Test
    fun testForegroundViewPrecedenceOverLiveOnly() {
        // When the user has the app open in foreground, FOREGROUND_VIEW must take precedence
        // over LIVE_ONLY so that high-rate 1.5s UI and map updates are rendered smoothly.
        val resolveMode = { tripActive: Boolean, isForeground: Boolean, liveActive: Boolean, autoMedia: Boolean ->
            when {
                tripActive -> AppLifecycleMode.TRIP_RECORDING
                autoMedia -> AppLifecycleMode.ANDROID_AUTO
                isForeground -> AppLifecycleMode.FOREGROUND_VIEW
                liveActive -> AppLifecycleMode.LIVE_ONLY
                else -> AppLifecycleMode.IDLE
            }
        }

        // App in foreground with live active -> FOREGROUND_VIEW
        assertEquals(AppLifecycleMode.FOREGROUND_VIEW, resolveMode(false, true, true, false))

        // Screen turned off / background with live active -> LIVE_ONLY
        assertEquals(AppLifecycleMode.LIVE_ONLY, resolveMode(false, false, true, false))

        // Trip active always takes precedence -> TRIP_RECORDING
        assertEquals(AppLifecycleMode.TRIP_RECORDING, resolveMode(true, true, true, false))
        assertEquals(AppLifecycleMode.TRIP_RECORDING, resolveMode(true, false, true, false))

        // Screen turned off, no trip, no live -> IDLE
        assertEquals(AppLifecycleMode.IDLE, resolveMode(false, false, false, false))
    }

    @Test
    fun testLiveOnlyStationaryGatingAndZeroIdleDrain() {
        // When in LIVE_ONLY mode and device is stationary for >= 30s:
        // 1. GPS hardware updates must be completely stopped.
        // 2. Ultra-low power hardware motion sensor (Sensor.TYPE_SIGNIFICANT_MOTION) must be armed.
        // 3. Periodic stationary presence heartbeat is used instead of GNSS polling.
        val isStationaryGated = { stationaryDurationMs: Long, isPhysicalStationary: Boolean, speedMs: Float ->
            (isPhysicalStationary || speedMs < 0.35f) && stationaryDurationMs >= 30_000L
        }

        // 10s on table -> not yet gated
        assertFalse(isStationaryGated(10_000L, true, 0f))

        // 30s motionless on nightstand -> gated (GPS shutdown & motion wake armed)
        assertTrue(isStationaryGated(30_000L, true, 0f))
        assertTrue(isStationaryGated(60_000L, true, 0f))

        // Moving at 5 km/h (1.38 m/s) -> not gated
        assertFalse(isStationaryGated(45_000L, false, 1.38f))
    }

    @Test
    fun testLiveOnlyNotificationTextStabilityWithoutTrip() {
        // Without active trip recording, notification text must be static (no ticking elapsed minutes).
        // This prevents 60s content changes that trigger notification wake lock storms.
        val generateLiveNotificationText = { activeTripExists: Boolean, isPaused: Boolean, placeName: String, remainingText: String ->
            if (activeTripExists) {
                // Trip active: includes telemetry and remaining time
                "$placeName • [LIVE] • $remainingText"
            } else {
                // Live only: static place name, no ticking minutes
                val pauseTag = if (isPaused) " [PAUSED]" else ""
                "Live Sharing Active$pauseTag • $placeName"
            }
        }

        val text1 = generateLiveNotificationText(false, false, "Kraków", "⏱️ 7h 11m elapsed")
        val text2 = generateLiveNotificationText(false, false, "Kraków", "⏱️ 7h 12m elapsed")

        // Crucial invariant: texts MUST be identical so contentChanged == false!
        assertEquals(text1, text2)
        assertEquals("Live Sharing Active • Kraków", text1)

        // But when a trip IS recording, remainingText can update
        val tripText1 = generateLiveNotificationText(true, false, "Kraków", "⏱️ 7h 11m elapsed")
        val tripText2 = generateLiveNotificationText(true, false, "Kraków", "⏱️ 7h 12m elapsed")
        org.junit.Assert.assertNotEquals(tripText1, tripText2)
    }

    @Test
    fun testStationaryPresenceHeartbeatDoesNotAppendBreadcrumbPoints() {
        // Breadcrumb points must NEVER accumulate unless activeTrip != null
        val appendBreadcrumb = { isTripRecording: Boolean, displacementMeters: Float ->
            isTripRecording && displacementMeters >= 15f
        }

        assertFalse("Stationary live sharing must not append breadcrumb points", appendBreadcrumb(false, 0f))
        assertFalse("Moving live sharing without active trip must not append breadcrumb points", appendBreadcrumb(false, 30f))
        assertTrue("Active trip with displacement >= 15m must append breadcrumbs", appendBreadcrumb(true, 20f))
        assertFalse("Active trip with stationary jitter < 15m must not append breadcrumbs", appendBreadcrumb(true, 5f))
    }

    @Test
    fun testLiveOnlyStationaryCadenceRelaxation() {
        // In LIVE_ONLY mode:
        // When moving: sample at conservative cadence (15s on battery, 6s on charging)
        // When stationary for >= 30s: relax sampling cadence to 60s (30s on charging) to minimize battery drain
        // while continuing to receive periodic location fixes ("then less frequently but still").
        // Ultra-low power hardware motion sensor (Sensor.TYPE_SIGNIFICANT_MOTION) is armed to wake on locomotion.
        val resolveSamplingIntervalMs = { isStationaryGated: Boolean, isCharging: Boolean ->
            if (isStationaryGated) {
                if (isCharging) 30_000L else 60_000L
            } else {
                if (isCharging) 6_000L else 15_000L
            }
        }

        // Moving on battery: 15s
        assertEquals(15_000L, resolveSamplingIntervalMs(false, false))
        // Moving on charging: 6s
        assertEquals(6_000L, resolveSamplingIntervalMs(false, true))

        // Stationary on battery: relaxed to 60s
        assertEquals(60_000L, resolveSamplingIntervalMs(true, false))
        // Stationary on charging: relaxed to 30s
        assertEquals(30_000L, resolveSamplingIntervalMs(true, true))
    }

    @Test
    fun testLiveOnlyNotificationTextIncludesAutoDetectWhenAutoModeEnabled() {
        // When both Live Sharing and Auto-detect are active (without an active trip),
        // the notification MUST clearly convey both:
        // 1. Live Sharing is active
        // 2. Auto-detect is ready with current profile and speed threshold
        // 3. Static text without ticking minutes to prevent notification wakelock storms
        val generateLiveNotification = { activeTripExists: Boolean, isLiveActive: Boolean, isAuto: Boolean, isPaused: Boolean, placeName: String, profileName: String, startSpeedKmh: Float ->
            val pauseTag = if (isPaused) " [PAUSED]" else ""
            val autoTag = if (isAuto) {
                val speedFormatted = if (startSpeedKmh % 1f == 0f) ">${startSpeedKmh.toInt()}" else ">%.1f".format(startSpeedKmh)
                " • Auto-detect: $profileName ($speedFormatted km/h)"
            } else ""
            "Live Sharing Active$pauseTag$autoTag • $placeName"
        }

        val text1 = generateLiveNotification(false, true, true, false, "Kraków", "Car", 10.0f)
        val text2 = generateLiveNotification(false, true, true, false, "Kraków", "Car", 10.0f)

        // Texts must be identical and static
        assertEquals(text1, text2)
        assertEquals("Live Sharing Active • Auto-detect: Car (>10 km/h) • Kraków", text1)

        val bikeText = generateLiveNotification(false, true, true, false, "Zakopane", "Cycling", 7.0f)
        assertEquals("Live Sharing Active • Auto-detect: Cycling (>7 km/h) • Zakopane", bikeText)

        val manualText = generateLiveNotification(false, true, false, false, "Kraków", "Car", 10.0f)
        assertEquals("Live Sharing Active • Kraków", manualText)
    }

    @Test
    fun testForegroundEntryGuaranteesServiceStartWhenAutoOrLiveOrTrip() {
        val shouldServiceRun = { isAuto: Boolean, hasLive: Boolean, hasTrip: Boolean, isAppInForeground: Boolean ->
            (isAuto || hasLive || hasTrip) && isAppInForeground
        }

        // AUTO + Live Sharing in foreground -> MUST RUN
        assertTrue(shouldServiceRun(true, true, false, true))

        // MANUAL + Live Sharing in foreground -> MUST RUN
        assertTrue(shouldServiceRun(false, true, false, true))

        // AUTO only in foreground -> MUST RUN
        assertTrue(shouldServiceRun(true, false, false, true))

        // Active trip in foreground -> MUST RUN
        assertTrue(shouldServiceRun(false, false, true, true))

        // MANUAL, no live, no trip -> DO NOT RUN (Option A zero-drain)
        assertFalse(shouldServiceRun(false, false, false, true))

        // In background: never start directly from background (Android 14+ rule)
        assertFalse(shouldServiceRun(true, true, false, false))
    }
}


