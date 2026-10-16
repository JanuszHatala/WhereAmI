package janush.tech.whereami

import org.junit.Assert.*
import org.junit.Test

class AutoSleepManagerTest {

    @Test
    fun testTimeParseHelper() {
        val (h1, m1) = AutoSleepManager.parseHourMinute("22:30", 0, 0)
        assertEquals(22, h1)
        assertEquals(30, m1)

        val (h2, m2) = AutoSleepManager.parseHourMinute("06:15", 0, 0)
        assertEquals(6, h2)
        assertEquals(15, m2)

        val (h3, m3) = AutoSleepManager.parseHourMinute("invalid", 22, 30)
        assertEquals(22, h3)
        assertEquals(30, m3)

        val (h4, m4) = AutoSleepManager.parseHourMinute("25:99", 22, 30)
        assertEquals(22, h4)
        assertEquals(30, m4)
    }

    @Test
    fun testBedtimeWindowMidnightWrap() {
        // Standard Bedtime Window: 22:30 to 06:30
        val isBedtime = { h: Int, m: Int ->
            AutoSleepManager.isTimeInWindow(h, m, 22, 30, 6, 30)
        }

        assertFalse("22:29 is before bedtime window", isBedtime(22, 29))
        assertTrue("22:30 is start of bedtime window", isBedtime(22, 30))
        assertTrue("23:15 is inside bedtime window", isBedtime(23, 15))
        assertTrue("00:00 midnight is inside bedtime window", isBedtime(0, 0))
        assertTrue("03:45 is inside bedtime window", isBedtime(3, 45))
        assertTrue("06:29 is inside bedtime window", isBedtime(6, 29))
        assertFalse("06:30 is end of bedtime window", isBedtime(6, 30))
        assertFalse("07:00 is outside bedtime window", isBedtime(7, 0))
        assertFalse("14:00 daytime is outside bedtime window", isBedtime(14, 0))
    }

    @Test
    fun testNonWrappingWindow() {
        // Daytime / non-wrapping window: 13:00 to 15:00
        val inWindow = { h: Int, m: Int ->
            AutoSleepManager.isTimeInWindow(h, m, 13, 0, 15, 0)
        }

        assertFalse(inWindow(12, 59))
        assertTrue(inWindow(13, 0))
        assertTrue(inWindow(14, 30))
        assertFalse(inWindow(15, 0))
        assertFalse(inWindow(16, 0))
    }

    @Test
    fun testDndTriggersEarlySleepInEvening() {
        // Evening at 21:15 (before 22:30 bedtime), user enables DND and puts phone down (screen off, stationary)
        val shouldSleep = AutoSleepManager.shouldAutoSleep(
            isAutoSleepEnabled = true,
            isScreenOff = true,
            isStationary = true,
            isTimeInWindow = false, // Not yet 22:30!
            isDndActive = true,
            followDnd = true,
            isNightOrBedtime = true, // 21:15 is night/bedtime window
            hasActiveTrip = false,
            wasManuallyWokenThisCycle = false
        )
        assertTrue("DND active in the evening with screen off must trigger early sleep", shouldSleep)

        // Same DND on in the afternoon (e.g. 14:00 meeting) must NOT trigger overnight sleep
        val shouldSleepAfternoon = AutoSleepManager.shouldAutoSleep(
            isAutoSleepEnabled = true,
            isScreenOff = true,
            isStationary = true,
            isTimeInWindow = false,
            isDndActive = true,
            followDnd = true,
            isNightOrBedtime = false, // Afternoon
            hasActiveTrip = false,
            wasManuallyWokenThisCycle = false
        )
        assertFalse("DND in the afternoon must not trigger sleep mode", shouldSleepAfternoon)
    }

    @Test
    fun testDaytimeDndStrictlyForbiddenFromSleeping() {
        // Morning at 08:30 or 09:20 (broad daylight, outside bedtime window)
        // User puts phone on DND or in pocket with screen off and stationary
        val isDaytimeNight = AutoSleepManager.isNightOrBedtimeTime(9, 20, 22, 30, 6, 30)
        assertFalse("09:20 must not be considered night or bedtime", isDaytimeNight)

        val shouldSleepMorning = AutoSleepManager.shouldAutoSleep(
            isAutoSleepEnabled = true,
            isScreenOff = true,
            isStationary = true,
            isTimeInWindow = false,
            isDndActive = true,
            followDnd = true,
            isNightOrBedtime = isDaytimeNight,
            hasActiveTrip = false,
            wasManuallyWokenThisCycle = false
        )
        assertFalse("Daytime DND at 09:20 must strictly forbid entering sleep mode", shouldSleepMorning)
    }

    @Test
    fun testActiveTripStrictlyBlocksAutoSleep() {
        // Even if bedtime window is active, DND is on, and screen is off, an ongoing trip recording blocks sleep
        val shouldSleep = AutoSleepManager.shouldAutoSleep(
            isAutoSleepEnabled = true,
            isScreenOff = true,
            isStationary = true,
            isTimeInWindow = true,
            isDndActive = true,
            followDnd = true,
            isNightOrBedtime = true,
            hasActiveTrip = true, // Active recording in progress!
            wasManuallyWokenThisCycle = false
        )
        assertFalse("Active trip recording must strictly block auto sleep", shouldSleep)
    }

    @Test
    fun testScreenOnSuppressesAutoSleep() {
        // While user actively uses phone (screen on), auto sleep must not trigger
        val shouldSleep = AutoSleepManager.shouldAutoSleep(
            isAutoSleepEnabled = true,
            isScreenOff = false, // Screen is on
            isStationary = true,
            isTimeInWindow = true,
            isDndActive = true,
            followDnd = true,
            isNightOrBedtime = true,
            hasActiveTrip = false,
            wasManuallyWokenThisCycle = false
        )
        assertFalse("Screen on must prevent entering auto sleep", shouldSleep)
    }

    @Test
    fun testDndOffInMorningTriggersEarlyWake() {
        // Morning at 05:45 (before 06:30 bedtime end), user had DND on during sleep and turns off DND
        val shouldWake = AutoSleepManager.shouldAutoWake(
            isAutoSleepEnabled = true,
            isCurrentlySleeping = true,
            isTimeInWindow = true, // Still within 22:30-06:30 schedule
            isDndActive = false, // DND turned off!
            followDnd = true,
            isMorning = true, // 05:45 is morning window
            isAlarmFired = false,
            wasDndActiveInSession = true // Prior DND active during sleep!
        )
        assertTrue("DND turned off in morning must trigger early wake", shouldWake)
    }

    @Test
    fun testNoPriorDndInMorningDoesNotWakeInsideBedtimeWindow() {
        // Morning at 05:45 (before 06:30 bedtime end), user never used DND
        val shouldWake = AutoSleepManager.shouldAutoWake(
            isAutoSleepEnabled = true,
            isCurrentlySleeping = true,
            isTimeInWindow = true, // Still within 22:30-06:30 schedule
            isDndActive = false,
            followDnd = true,
            isMorning = true,
            isAlarmFired = false,
            wasDndActiveInSession = false // No prior DND!
        )
        assertFalse("Without prior DND, phone must stay asleep inside bedtime window", shouldWake)
    }

    @Test
    fun testNoSleepWakeOscillationInMorning() {
        // At 05:30 (inside bedtime window), stationary, screen off, DND never enabled
        val shouldSleep = AutoSleepManager.shouldAutoSleep(
            isAutoSleepEnabled = true,
            isScreenOff = true,
            isStationary = true,
            isTimeInWindow = true,
            isDndActive = false,
            followDnd = true,
            isNightOrBedtime = true,
            hasActiveTrip = false,
            wasManuallyWokenThisCycle = false
        )
        assertTrue("Inside bedtime window, stationary device enters/maintains sleep", shouldSleep)

        val shouldWake = AutoSleepManager.shouldAutoWake(
            isAutoSleepEnabled = true,
            isCurrentlySleeping = true,
            isTimeInWindow = true,
            isDndActive = false,
            followDnd = true,
            isMorning = true,
            isAlarmFired = false,
            wasDndActiveInSession = false
        )
        assertFalse("Device must NOT wake up early if DND was never active", shouldWake)
    }

    @Test
    fun testEarlyWakeSuppressesReenteringSleepInSameWindow() {
        // User woke up early via morning alarm at 06:00 (inside 22:30-06:30 window)
        // wasManuallyWokenThisCycle is set to true to prevent immediate oscillation back into sleep
        val shouldSleepAfterEarlyWake = AutoSleepManager.shouldAutoSleep(
            isAutoSleepEnabled = true,
            isScreenOff = true,
            isStationary = true,
            isTimeInWindow = true,
            isDndActive = false,
            followDnd = true,
            isNightOrBedtime = true,
            hasActiveTrip = false,
            wasManuallyWokenThisCycle = true // Early wake override!
        )
        assertFalse("After early wake, auto-sleep must be suppressed for remainder of bedtime window", shouldSleepAfterEarlyWake)
    }

    @Test
    fun testAlarmFiredInMorningTriggersWake() {
        // Morning alarm fired at 06:00
        val shouldWake = AutoSleepManager.shouldAutoWake(
            isAutoSleepEnabled = true,
            isCurrentlySleeping = true,
            isTimeInWindow = true,
            isDndActive = true, // DND still on
            followDnd = true,
            isMorning = true,
            isAlarmFired = true // Alarm fired!
        )
        assertTrue("Morning alarm fired must wake from sleep mode", shouldWake)
    }

    @Test
    fun testBedtimeEndTriggersWakeWhenDndOff() {
        // At 06:31 (bedtime ended), DND is off
        val shouldWake = AutoSleepManager.shouldAutoWake(
            isAutoSleepEnabled = true,
            isCurrentlySleeping = true,
            isTimeInWindow = false, // Outside window
            isDndActive = false,
            followDnd = true,
            isMorning = true,
            isAlarmFired = false
        )
        assertTrue("Bedtime schedule end must wake from sleep mode", shouldWake)
    }

    @Test
    fun testExplicitUserWakeSuppressesAutoSleep() {
        // User woke app manually via notification [▶ Activate]
        val shouldSleep = AutoSleepManager.shouldAutoSleep(
            isAutoSleepEnabled = true,
            isScreenOff = true,
            isStationary = true,
            isTimeInWindow = true,
            isDndActive = true,
            followDnd = true,
            isNightOrBedtime = true,
            hasActiveTrip = false,
            wasManuallyWokenThisCycle = true // Explicitly woken by user
        )
        assertFalse("Manual user wake override must suppress auto sleep for the cycle", shouldSleep)
    }

    @Test
    fun testManualSleepNeverAutoWakesOnBedtimeWindowEnd() {
        // User manually entered sleep mode (e.g. afternoon or outside bedtime window).
        // It must NOT auto-wake simply because the bedtime window ended.
        val shouldWake = AutoSleepManager.shouldAutoWake(
            isAutoSleepEnabled = true,
            isCurrentlySleeping = true,
            isTimeInWindow = false, // Outside window
            isDndActive = false,
            followDnd = true,
            isMorning = false,
            isAlarmFired = false,
            isManualSleep = true // Manually entered sleep
        )
        assertFalse("Manually entered sleep must strictly ignore bedtime window expiration", shouldWake)
    }

    @Test
    fun testManualSleepWakesOnMorningAlarm() {
        // User manually entered sleep mode, and in the morning an alarm fires.
        val shouldWake = AutoSleepManager.shouldAutoWake(
            isAutoSleepEnabled = true,
            isCurrentlySleeping = true,
            isTimeInWindow = false,
            isDndActive = true,
            followDnd = true,
            isMorning = true,
            isAlarmFired = true,
            isManualSleep = true
        )
        assertTrue("Morning alarm should still wake manual sleep", shouldWake)
    }

    @Test
    fun testComputeNextBoundaryTriggerMillisWhenAwake() {
        // At 15:00 (afternoon), next boundary when awake is 22:30 today
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 15)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val triggerAt = AutoSleepManager.computeNextBoundaryTriggerMillis(
            nowMillis = cal.timeInMillis,
            isCurrentlySleeping = false,
            startH = 22,
            startM = 30,
            endH = 6,
            endM = 30
        )
        val triggerCal = java.util.Calendar.getInstance().apply { timeInMillis = triggerAt }
        assertEquals(22, triggerCal.get(java.util.Calendar.HOUR_OF_DAY))
        assertEquals(30, triggerCal.get(java.util.Calendar.MINUTE))
        assertEquals(cal.get(java.util.Calendar.DAY_OF_YEAR), triggerCal.get(java.util.Calendar.DAY_OF_YEAR))
    }

    @Test
    fun testComputeNextBoundaryTriggerMillisWhenSleeping() {
        // At 23:45 (night), next boundary when sleeping is 06:30 tomorrow
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 23)
            set(java.util.Calendar.MINUTE, 45)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val triggerAt = AutoSleepManager.computeNextBoundaryTriggerMillis(
            nowMillis = cal.timeInMillis,
            isCurrentlySleeping = true,
            startH = 22,
            startM = 30,
            endH = 6,
            endM = 30
        )
        val triggerCal = java.util.Calendar.getInstance().apply { timeInMillis = triggerAt }
        assertEquals(6, triggerCal.get(java.util.Calendar.HOUR_OF_DAY))
        assertEquals(30, triggerCal.get(java.util.Calendar.MINUTE))
        val expectedDay = cal.get(java.util.Calendar.DAY_OF_YEAR) + 1
        assertEquals(expectedDay, triggerCal.get(java.util.Calendar.DAY_OF_YEAR))
    }

    @Test
    fun testLiveSharingEligibleForOvernightSleepWhenStationary() {
        // In LIVE_ONLY mode, live sharing is active without active trip.
        // When stationary on nightstand during bedtime window (23:40) with screen off:
        val shouldSleep = AutoSleepManager.shouldAutoSleep(
            isAutoSleepEnabled = true,
            isScreenOff = true,
            isStationary = true,
            isTimeInWindow = true, // 23:40 is within 22:30-06:30
            isDndActive = false,
            followDnd = true,
            isNightOrBedtime = true,
            hasActiveTrip = false,
            wasManuallyWokenThisCycle = false
        )
        assertTrue("Live sharing stationary dwell during bedtime MUST trigger auto sleep", shouldSleep)
    }

    @Test
    fun testStandbyInactivityWatcherActiveForLiveSharing() {
        // Evaluates watcher condition: !isAppInForeground && !hasTrip && !_isStandbySleeping && (isAuto || hasLive)
        val shouldWatch = { inForeground: Boolean, hasTrip: Boolean, isSleeping: Boolean, isAuto: Boolean, hasLive: Boolean ->
            !inForeground && !hasTrip && !isSleeping && (isAuto || hasLive)
        }

        // 1. Live sharing in background without trip: watcher MUST run to evaluate auto sleep and hibernation!
        assertTrue("Watcher must be active for live sharing in background", shouldWatch(false, false, false, false, true))

        // 2. Auto-detect standby in background: watcher MUST run
        assertTrue("Watcher must be active for auto-detect standby", shouldWatch(false, false, false, true, false))

        // 3. Active trip recording: watcher must NOT run
        assertFalse("Watcher must not run during active trip", shouldWatch(false, true, false, true, true))

        // 4. App in foreground: watcher must NOT run
        assertFalse("Watcher must not run in foreground", shouldWatch(true, false, false, true, true))

        // 5. Already sleeping: watcher must NOT run
        assertFalse("Watcher must not run when already sleeping", shouldWatch(false, false, true, true, true))
    }
}
