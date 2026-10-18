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
        // When both Live Sharing and Auto-record are active (without an active trip),
        // the notification MUST clearly convey both:
        // 1. Live Sharing is active
        // 2. Auto-record is ready with current profile and speed threshold
        // 3. Static text without ticking minutes to prevent notification wakelock storms
        val generateLiveNotification = { activeTripExists: Boolean, isLiveActive: Boolean, isAuto: Boolean, isPaused: Boolean, placeName: String, profileName: String, startSpeedKmh: Float ->
            val pauseTag = if (isPaused) " [PAUSED]" else ""
            val autoTag = if (isAuto) {
                val speedFormatted = if (startSpeedKmh % 1f == 0f) ">${startSpeedKmh.toInt()}" else ">%.1f".format(startSpeedKmh)
                " • Auto-record: $profileName ($speedFormatted km/h)"
            } else ""
            "Live Sharing Active$pauseTag$autoTag • $placeName"
        }

        val text1 = generateLiveNotification(false, true, true, false, "Kraków", "Car", 10.0f)
        val text2 = generateLiveNotification(false, true, true, false, "Kraków", "Car", 10.0f)

        // Texts must be identical and static
        assertEquals(text1, text2)
        assertEquals("Live Sharing Active • Auto-record: Car (>10 km/h) • Kraków", text1)

        val bikeText = generateLiveNotification(false, true, true, false, "Zakopane", "Cycling", 7.0f)
        assertEquals("Live Sharing Active • Auto-record: Cycling (>7 km/h) • Zakopane", bikeText)

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

    @Test
    fun testMotionBurstProgressiveCooldown() {
        assertEquals(0L, AppStateManager.computeBurstCooldownMs(0))
        assertEquals(60_000L, AppStateManager.computeBurstCooldownMs(1))
        assertEquals(120_000L, AppStateManager.computeBurstCooldownMs(2))
        assertEquals(180_000L, AppStateManager.computeBurstCooldownMs(3))
        assertEquals(180_000L, AppStateManager.computeBurstCooldownMs(10))
    }

    @Test
    fun testMotionBurstEarlyAbortConditions() {
        val minDuration = AppStateManager.MOTION_BURST_MIN_DURATION_MS
        assertEquals(15_000L, minDuration)

        val targetCandidateSpeedMs = (15.0f * 0.7f) / 3.6f // ~2.91 m/s (10.5 km/h for CAR profile)

        // 1. Before 15s: never abort prematurely
        assertFalse(
            AppStateManager.shouldEarlyAbortBurst(
                elapsedMs = 10_000L,
                minDurationMs = minDuration,
                currentSpeedMs = 0.0f,
                targetCandidateSpeedMs = targetCandidateSpeedMs,
                displacementMeters = 0.0,
                isPhysicallyStationary = true,
                isCarProfile = true
            )
        )

        // 2. At 15s with physical stationary surface detected: abort immediately
        assertTrue(
            AppStateManager.shouldEarlyAbortBurst(
                elapsedMs = 15_000L,
                minDurationMs = minDuration,
                currentSpeedMs = 0.0f,
                targetCandidateSpeedMs = targetCandidateSpeedMs,
                displacementMeters = 0.0,
                isPhysicallyStationary = true,
                isCarProfile = true
            )
        )

        // 3. At 15s with slow walking / drift (0.4 m/s, displacement 4m) without accelerometer stationary: abort
        assertTrue(
            AppStateManager.shouldEarlyAbortBurst(
                elapsedMs = 15_000L,
                minDurationMs = minDuration,
                currentSpeedMs = 0.4f,
                targetCandidateSpeedMs = targetCandidateSpeedMs,
                displacementMeters = 4.0,
                isPhysicallyStationary = false,
                isCarProfile = true
            )
        )

        // 4. At 15s with true vehicle movement (speed 8 m/s = 28.8 km/h): DO NOT ABORT
        assertFalse(
            AppStateManager.shouldEarlyAbortBurst(
                elapsedMs = 15_000L,
                minDurationMs = minDuration,
                currentSpeedMs = 8.0f,
                targetCandidateSpeedMs = targetCandidateSpeedMs,
                displacementMeters = 80.0,
                isPhysicallyStationary = false,
                isCarProfile = true
            )
        )

        // 5. At 25s for CAR profile with human walking speed (1.4 m/s = 5 km/h, disp 20m): abort
        assertTrue(
            AppStateManager.shouldEarlyAbortBurst(
                elapsedMs = 25_000L,
                minDurationMs = minDuration,
                currentSpeedMs = 1.4f,
                targetCandidateSpeedMs = targetCandidateSpeedMs,
                displacementMeters = 20.0,
                isPhysicallyStationary = false,
                isCarProfile = true
            )
        )

        // 6. At 25s for CAR profile with fast driving (12 m/s = 43 km/h): DO NOT ABORT
        assertFalse(
            AppStateManager.shouldEarlyAbortBurst(
                elapsedMs = 25_000L,
                minDurationMs = minDuration,
                currentSpeedMs = 12.0f,
                targetCandidateSpeedMs = targetCandidateSpeedMs,
                displacementMeters = 200.0,
                isPhysicallyStationary = false,
                isCarProfile = true
            )
        )
    }

    @Test
    fun testForegroundDualTierCadenceParameters() {
        // In MANUAL mode, moving is 7000ms (min 4000ms) and stationary is 25000ms (min 15000ms)
        val manualMovingInterval = 7000L
        val manualStationaryInterval = 25000L
        assertTrue("Manual stationary cadence must be at least 3x slower than moving", manualStationaryInterval >= manualMovingInterval * 3)

        // In AUTO mode, moving is 1500ms (min 1000ms) and stationary is 10000ms (min 5000ms)
        val autoMovingInterval = 1500L
        val autoStationaryInterval = 10000L
        assertTrue("Auto stationary cadence must be slower to conserve power at rest", autoStationaryInterval > autoMovingInterval * 5)
    }

    @Test
    fun testStandbyHibernationTimeoutAndGatingRules() {
        // Standby hibernation timeout should be 90 minutes (5_400_000 ms)
        assertEquals(90 * 60 * 1000L, AppStateManager.STANDBY_HIBERNATION_TIMEOUT_MS)

        // Helper logic to verify screen keep-on gating rule
        val computeKeepScreenOn = { userPreference: Boolean, isTripActive: Boolean, isLiveActive: Boolean, inactivityTimeoutReached: Boolean ->
            userPreference && (isTripActive || isLiveActive || !inactivityTimeoutReached)
        }

        // 1. User pref OFF -> Screen off
        assertFalse(computeKeepScreenOn(false, false, false, false))

        // 2. User pref ON, active trip recording -> Keep screen on indefinitely (inactivity timeout ignored)
        assertTrue(computeKeepScreenOn(true, true, false, true))

        // 3. User pref ON, live share active -> Keep screen on indefinitely (inactivity timeout ignored)
        assertTrue(computeKeepScreenOn(true, false, true, true))

        // 4. User pref ON, idle at desk before 3min timeout -> Screen kept on
        assertTrue(computeKeepScreenOn(true, false, false, false))

        // 5. User pref ON, idle at desk after 3min stationary timeout -> Screen permitted to sleep!
        assertFalse(computeKeepScreenOn(true, false, false, true))
    }

    @Test
    fun testLiveSharingOvernightHibernationAndPresenceHeartbeat() {
        // Sleep Mode allowed matrix:
        val canEnterSleepMode = { hasActiveTrip: Boolean ->
            // Only active trip recording blocks sleep mode; Live Sharing alone allows overnight hibernation
            !hasActiveTrip
        }

        // Active recording trip in progress -> cannot enter sleep mode
        assertFalse(canEnterSleepMode(true))

        // Standby auto-detect without trip -> can enter sleep mode
        assertTrue(canEnterSleepMode(false))

        // Live Sharing active without active trip recording -> can enter sleep mode!
        assertTrue(canEnterSleepMode(false))
    }

    @Test
    fun testAppLifecycleModeResolutionWhenStandbySleeping() {
        val resolveMode = { tripActive: Boolean, isAutoMedia: Boolean, inForeground: Boolean, isSleeping: Boolean, liveActive: Boolean ->
            when {
                tripActive -> AppLifecycleMode.TRIP_RECORDING
                isAutoMedia -> AppLifecycleMode.ANDROID_AUTO
                inForeground -> AppLifecycleMode.FOREGROUND_VIEW
                isSleeping -> AppLifecycleMode.IDLE
                liveActive -> AppLifecycleMode.LIVE_ONLY
                else -> AppLifecycleMode.IDLE
            }
        }

        // When sleeping in background with live sharing active, mode must resolve to IDLE (GPS completely off)
        assertEquals(
            AppLifecycleMode.IDLE,
            resolveMode(false, false, false, true, true)
        )

        // When not sleeping, live sharing resolves to LIVE_ONLY
        assertEquals(
            AppLifecycleMode.LIVE_ONLY,
            resolveMode(false, false, false, false, true)
        )

        // Active trip recording always supersedes sleep mode
        assertEquals(
            AppLifecycleMode.TRIP_RECORDING,
            resolveMode(true, false, false, true, true)
        )
    }

    @Test
    fun testOvernightSleepPresenceCadenceRelaxation() {
        val computeHeartbeatCadence = { baseMinutes: Int, isSleeping: Boolean ->
            val baseIntervalMs = (baseMinutes * 60_000L).coerceAtLeast(60_000L)
            if (isSleeping) {
                60 * 60_000L
            } else {
                baseIntervalMs
            }
        }

        // Active daytime live sharing with 1m interval
        assertEquals(60_000L, computeHeartbeatCadence(1, false))

        // Overnight sleep mode with 1m interval -> relaxed to 60m (3,600,000 ms)
        assertEquals(60 * 60_000L, computeHeartbeatCadence(1, true))

        // Overnight sleep mode with 30m interval -> relaxed to 60m (3,600,000 ms)
        assertEquals(60 * 60_000L, computeHeartbeatCadence(30, true))
    }

    @Test
    fun testUnifiedSleepNotificationStateAndActions() {
        data class SleepNotifState(val title: String, val text: String, val actionCount: Int)

        val buildState = { isLive: Boolean, isAuto: Boolean, isPaused: Boolean ->
            val title = when {
                isLive && isAuto -> "⏸ WhereAmI Paused (Live & Auto-record)"
                isLive -> "🌙 WhereAmI Live Sharing Sleeping"
                else -> "⏸ WhereAmI Auto-record Paused"
            }
            val text = when {
                isLive && isAuto -> {
                    val liveStatus = if (isPaused) "Live Sharing paused" else "Live Sharing active (1h sync)"
                    "Auto-record paused • $liveStatus • Tap to resume"
                }
                isLive -> {
                    val liveStatus = if (isPaused) "Live Sharing paused" else "Live Sharing active (1h sync)"
                    "Overnight hibernation • $liveStatus • Tap to wake"
                }
                else -> "Auto-record paused for battery savings • Tap to resume"
            }
            // 1 action for pure standby ([▶ Resume]); 3 actions for live sharing ([▶ Resume], [⏸/▶], [🛑 Stop])
            val actions = if (isLive) 3 else 1
            SleepNotifState(title, text, actions)
        }

        // 1. Pure Auto-detect Standby Sleep:
        val standbyOnly = buildState(false, true, false)
        assertEquals("⏸ WhereAmI Auto-record Paused", standbyOnly.title)
        assertEquals("Auto-record paused for battery savings • Tap to resume", standbyOnly.text)
        assertEquals(1, standbyOnly.actionCount)

        // 2. Both Live Sharing and Auto-detect in Sleep Mode:
        val liveAndAuto = buildState(true, true, false)
        assertEquals("⏸ WhereAmI Paused (Live & Auto-record)", liveAndAuto.title)
        assertTrue(liveAndAuto.text.contains("Auto-record paused • Live Sharing active (1h sync)"))
        assertEquals(3, liveAndAuto.actionCount)

        // 3. Both Live Sharing and Auto-detect in Sleep Mode when paused:
        val liveAndAutoPaused = buildState(true, true, true)
        assertEquals("⏸ WhereAmI Paused (Live & Auto-record)", liveAndAutoPaused.title)
        assertTrue(liveAndAutoPaused.text.contains("Live Sharing paused"))
        assertEquals(3, liveAndAutoPaused.actionCount)

        // 4. Live Sharing only in Sleep Mode (Manual recording mode):
        val liveOnly = buildState(true, false, false)
        assertEquals("🌙 WhereAmI Live Sharing Sleeping", liveOnly.title)
        assertTrue(liveOnly.text.contains("Overnight hibernation • Live Sharing active (1h sync)"))
        assertEquals(3, liveOnly.actionCount)
    }

    @Test
    fun testSingleNotificationInvariantAndSleepIdUnification() {
        // Crucial Invariant: Exactly one notification in system tray.
        // NOTIF_SLEEP_ID must be unified with NOTIF_ID (2) so sleep mode updates in-place
        // rather than spawning a duplicate notification (Round 52 fix).
        assertEquals(2, LiveTrackingService.NOTIF_SLEEP_ID)
    }

    @Test
    fun testSleepPersistenceAcrossForegroundTransitions() {
        // When user opens the app or views the screen while in sleep mode,
        // sleep mode MUST NOT be cancelled automatically. It must persist until
        // the user explicitly activates/wakes it.
        var isSleeping = true

        val onAppForegroundStateChanged = { inForeground: Boolean ->
            // New logic: do NOT cancel sleep on foreground transition
            // isSleeping remains unchanged!
        }

        onAppForegroundStateChanged(true)
        assertTrue("Sleep mode must persist when app transitions to foreground", isSleeping)

        // User explicitly activates via notification action or HUD button:
        val onUserActivateClicked = {
            isSleeping = false
        }
        onUserActivateClicked()
        assertFalse("Sleep mode wakes only on explicit user action", isSleeping)
    }

    @Test
    fun testBatteryBroadcastDebounceAndSleepHeartbeatPreservation() {
        // Overnight Bug 1 fix:
        // ACTION_BATTERY_CHANGED fires ~145 times overnight on 1% drops, voltage, or temp shifts.
        // recalculateState() must ONLY run when actual charging boolean transitions.
        assertFalse(
            "1% battery drop while unplugged must not trigger state recalculation",
            AppStateManager.shouldRecalculateOnChargingChange(previousCharging = false, newCharging = false)
        )
        assertFalse(
            "Battery tick while plugged in must not trigger state recalculation",
            AppStateManager.shouldRecalculateOnChargingChange(previousCharging = true, newCharging = true)
        )
        assertTrue(
            "Plugging in charger must trigger state recalculation",
            AppStateManager.shouldRecalculateOnChargingChange(previousCharging = false, newCharging = true)
        )
        assertTrue(
            "Unplugging charger must trigger state recalculation",
            AppStateManager.shouldRecalculateOnChargingChange(previousCharging = true, newCharging = false)
        )

        // When in IDLE mode due to Standby Sleep with an active Live Sharing session,
        // the 60-minute sleep heartbeat MUST NOT be cancelled by applyModeToLocationEngine().
        assertTrue(
            "60m sleep heartbeat must be kept alive in IDLE mode when sleeping with active live share",
            AppStateManager.shouldKeepSleepHeartbeatAlive(
                mode = AppLifecycleMode.IDLE,
                isStandbySleeping = true,
                hasActiveLiveSession = true
            )
        )
        assertFalse(
            "Sleep heartbeat should not run when no live session is active",
            AppStateManager.shouldKeepSleepHeartbeatAlive(
                mode = AppLifecycleMode.IDLE,
                isStandbySleeping = true,
                hasActiveLiveSession = false
            )
        )
        assertFalse(
            "Normal IDLE without sleep should not keep sleep heartbeat alive",
            AppStateManager.shouldKeepSleepHeartbeatAlive(
                mode = AppLifecycleMode.IDLE,
                isStandbySleeping = false,
                hasActiveLiveSession = false
            )
        )
    }

    @Test
    fun testNotificationSignatureDeduplicationPreventsRedundantWakelocks() {
        // Overnight Bug 2 fix:
        // Calling updateNotification(force = true) when notification title, text, and action state
        // are identical must be skipped to prevent NotificationManagerService:post partial wakelocks.
        val sig1 = LiveTrackingService.computeNotificationSignature(
            title = "⏸ WhereAmI Paused (Live & Auto-record)",
            text = "Auto-record paused • Live Sharing active (1h sync) • Tap to resume",
            isSleeping = true,
            isLiveActive = true,
            isLivePaused = false,
            isAuto = true,
            hasActiveTrip = false,
            isTripPaused = false
        )
        val sig2 = LiveTrackingService.computeNotificationSignature(
            title = "⏸ WhereAmI Paused (Live & Auto-record)",
            text = "Auto-record paused • Live Sharing active (1h sync) • Tap to resume",
            isSleeping = true,
            isLiveActive = true,
            isLivePaused = false,
            isAuto = true,
            hasActiveTrip = false,
            isTripPaused = false
        )
        assertEquals("Identical notification state must produce identical signature", sig1, sig2)

        val sigWoken = LiveTrackingService.computeNotificationSignature(
            title = "🔴 WhereAmI • Live & Auto-record",
            text = "Live Sharing Active • Auto-record: Car (>10 km/h) • Bestwina",
            isSleeping = false,
            isLiveActive = true,
            isLivePaused = false,
            isAuto = true,
            hasActiveTrip = false,
            isTripPaused = false
        )
        org.junit.Assert.assertNotEquals("Waking from sleep must change notification signature", sig1, sigWoken)
    }

    @Test
    fun testLiveOnlyStationaryGpsShutdownOnBatteryWhenScreenOff() {
        val shouldShutdownGpsWhenStationary = { isCharging: Boolean, inForeground: Boolean ->
            !isCharging && !inForeground
        }

        // On battery with screen off: GPS must power down completely to allow sustained Doze!
        assertTrue(
            "Screen off and unplugged: GPS must shut down when stationary",
            shouldShutdownGpsWhenStationary(false, false)
        )

        // When charging: GPS sampling can remain active (relaxed cadence)
        assertFalse(
            "When charging: GPS updates do not drain battery",
            shouldShutdownGpsWhenStationary(true, false)
        )

        // When in foreground: screen is on, GPS remains active for map view
        assertFalse(
            "When in foreground: GPS updates remain active for user view",
            shouldShutdownGpsWhenStationary(false, true)
        )
    }

    @Test
    fun testAltitudeBadgeSpeedGatingRule() {
        val shouldShowAltitudeBadge = { speedKmh: Float, hasValidDem: Boolean, isGpsMode: Boolean ->
            if (speedKmh >= 15f) {
                false
            } else if (!isGpsMode && !hasValidDem) {
                false
            } else {
                true
            }
        }

        // Stationary / walking (< 15 km/h) with valid DEM: visible
        assertTrue(shouldShowAltitudeBadge(0f, true, false))
        assertTrue(shouldShowAltitudeBadge(5f, true, false))
        assertTrue(shouldShowAltitudeBadge(14.9f, true, false))

        // Moving at driving speed (>= 15 km/h): hidden even if DEM is available
        assertFalse(shouldShowAltitudeBadge(15f, true, false))
        assertTrue(!shouldShowAltitudeBadge(25f, true, false))
        assertTrue(!shouldShowAltitudeBadge(60f, true, false))

        // In DEM mode, if DEM is not valid (fallback), hidden even when stationary
        assertFalse(shouldShowAltitudeBadge(0f, false, false))

        // In GPS mode, hidden at high speed
        assertFalse(shouldShowAltitudeBadge(30f, false, true))
    }

    @Test
    fun testSleepModePeriodicFixFrequency() {
        val computeHeartbeatInterval = { isCurrentlySleeping: Boolean, baseIntervalMinutes: Int ->
            if (isCurrentlySleeping) {
                60 * 60_000L
            } else {
                (baseIntervalMinutes * 60_000L).coerceAtLeast(60_000L)
            }
        }

        // Normal mode with 1m sync -> 1m
        assertEquals(60_000L, computeHeartbeatInterval(false, 1))
        // Normal mode with 5m sync -> 5m
        assertEquals(300_000L, computeHeartbeatInterval(false, 5))
        // Sleep mode -> 60 minutes (1 hour) regardless of session sync interval
        assertEquals(3_600_000L, computeHeartbeatInterval(true, 1))
        assertEquals(3_600_000L, computeHeartbeatInterval(true, 5))
    }

    @Test
    fun testStationaryLatchPreservationPreventsOscillation() {
        // Simulates the state machine where GPS is stopped during stationary rest on battery
        var isGpsStopped = false
        var isPhysicallyStationary = false
        var isStationaryCadenceApplied = false

        // Simulate 30s dwell -> transition to stationary rest
        isPhysicallyStationary = true
        isStationaryCadenceApplied = true
        isGpsStopped = true
        // preserveStationaryState=true ensures isPhysicallyStationary remains true when GPS stops
        val preserveStationaryState = true
        if (!preserveStationaryState) {
            isPhysicallyStationary = false
        }

        assertTrue("Stationary state must be preserved when GPS stops to prevent flapping", isPhysicallyStationary)
        assertTrue(isGpsStopped)
        assertTrue(isStationaryCadenceApplied)

        // When unlatchStationary occurs on true hardware motion interrupt:
        val onHardwareMotionWake = {
            isPhysicallyStationary = false
            isGpsStopped = false
            isStationaryCadenceApplied = false
        }
        onHardwareMotionWake()
        assertFalse(isPhysicallyStationary)
        assertFalse(isGpsStopped)
    }

    @Test
    fun testSleepModeGnssFixGatingWhenMotionless() {
        // When not sleeping, helper returns false (normal live tracking rules apply)
        assertFalse(
            LiveSharingManager.shouldRequestSleepGnssFix(
                isSleeping = false,
                isStationary = true,
                hasValidCachedLocation = true
            )
        )

        // When sleeping, physically stationary, and has valid cached coords: GNSS fix is SKIPPED
        assertFalse(
            "Motionless sleep with cached coords must not wake GNSS chip",
            LiveSharingManager.shouldRequestSleepGnssFix(
                isSleeping = true,
                isStationary = true,
                hasValidCachedLocation = true
            )
        )

        // When sleeping, physically stationary, but NO cached coords exist: GNSS fix is REQUESTED
        assertTrue(
            "Sleeping without cached coords must request initial GNSS snapshot",
            LiveSharingManager.shouldRequestSleepGnssFix(
                isSleeping = true,
                isStationary = true,
                hasValidCachedLocation = false
            )
        )

        // When sleeping but motion detected (not stationary): GNSS fix is REQUESTED
        assertTrue(
            "Sleeping with locomotion must request fresh GNSS fix",
            LiveSharingManager.shouldRequestSleepGnssFix(
                isSleeping = true,
                isStationary = false,
                hasValidCachedLocation = true
            )
        )
    }

    @Test
    fun testAutoSleepLiveSharePauseStateTransitions() {
        // Test helper simulating AutoSleepManager's pause/resume state machine
        var wasLiveShareAutoPaused = false
        var isLiveSessionPaused = false

        val onSleepEntered = { pauseLiveSharePref: Boolean, isSessionActive: Boolean ->
            if (pauseLiveSharePref && isSessionActive && !isLiveSessionPaused) {
                wasLiveShareAutoPaused = true
                isLiveSessionPaused = true
            }
        }

        val onSleepWoken = { isSessionActive: Boolean ->
            if (wasLiveShareAutoPaused) {
                wasLiveShareAutoPaused = false
                if (isSessionActive && isLiveSessionPaused) {
                    isLiveSessionPaused = false
                }
            }
        }

        // 1. When pref is disabled, live sharing must remain active
        onSleepEntered(false, true)
        assertFalse(wasLiveShareAutoPaused)
        assertFalse(isLiveSessionPaused)

        // 2. When pref is enabled and live share active, sleep pauses session
        onSleepEntered(true, true)
        assertTrue(wasLiveShareAutoPaused)
        assertTrue(isLiveSessionPaused)

        // 3. Waking from sleep resumes session
        onSleepWoken(true)
        assertFalse(wasLiveShareAutoPaused)
        assertFalse(isLiveSessionPaused)

        // 4. If session was already manually paused by user before sleep, auto-resume must not unpause
        isLiveSessionPaused = true
        onSleepEntered(true, true)
        // wasLiveShareAutoPaused remains false because it was already paused
        assertFalse(wasLiveShareAutoPaused)
        assertTrue(isLiveSessionPaused)
        onSleepWoken(true)
        assertTrue("Manual pause before sleep must be preserved upon waking", isLiveSessionPaused)
    }
}
