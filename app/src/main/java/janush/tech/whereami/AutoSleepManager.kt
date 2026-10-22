package janush.tech.whereami

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Calendar

class AutoSleepManager private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: AutoSleepManager? = null

        fun getInstance(context: Context): AutoSleepManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AutoSleepManager(context.applicationContext).also { INSTANCE = it }
            }
        }

        private const val PREFS_NAME = "where_am_i_power_prefs"
        const val KEY_AUTO_SLEEP_ENABLED = "pref_auto_sleep_enabled"
        const val KEY_AUTO_SLEEP_START_TIME = "pref_auto_sleep_start_time"
        const val KEY_AUTO_SLEEP_END_TIME = "pref_auto_sleep_end_time"
        const val KEY_AUTO_SLEEP_FOLLOW_DND = "pref_auto_sleep_follow_dnd"
        const val KEY_AUTO_SLEEP_FOLLOW_ALARM = "pref_auto_sleep_follow_alarm"
        const val KEY_AUTO_SLEEP_PAUSE_LIVE_SHARE = "pref_auto_sleep_pause_live_share"
        private const val KEY_UPCOMING_ALARM_MS = "auto_sleep_upcoming_alarm_ms"
        private const val KEY_LAST_FIRED_ALARM_MS = "auto_sleep_last_fired_alarm_ms"

        const val DEFAULT_START_TIME = "22:30"
        const val DEFAULT_END_TIME = "06:30"

        const val ACTION_BEDTIME_TRANSITION = "janush.tech.whereami.ACTION_BEDTIME_TRANSITION"
        private const val REQUEST_CODE_BEDTIME_ALARM = 8821

        fun computeNextBoundaryTriggerMillis(
            nowMillis: Long,
            isCurrentlySleeping: Boolean,
            startH: Int,
            startM: Int,
            endH: Int,
            endM: Int
        ): Long {
            val (targetH, targetM) = if (isCurrentlySleeping) {
                Pair(endH, endM)
            } else {
                Pair(startH, startM)
            }
            val cal = Calendar.getInstance().apply {
                timeInMillis = nowMillis
                set(Calendar.HOUR_OF_DAY, targetH)
                set(Calendar.MINUTE, targetM)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (cal.timeInMillis <= nowMillis) {
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
            return cal.timeInMillis
        }

        fun parseHourMinute(timeStr: String, defaultHour: Int, defaultMinute: Int): Pair<Int, Int> {
            val parts = timeStr.trim().split(":")
            if (parts.size == 2) {
                val h = parts[0].toIntOrNull() ?: defaultHour
                val m = parts[1].toIntOrNull() ?: defaultMinute
                if (h in 0..23 && m in 0..59) {
                    return Pair(h, m)
                }
            }
            return Pair(defaultHour, defaultMinute)
        }

        fun isTimeInWindow(
            nowHour: Int,
            nowMinute: Int,
            startHour: Int,
            startMinute: Int,
            endHour: Int,
            endMinute: Int
        ): Boolean {
            val nowTotal = nowHour * 60 + nowMinute
            val startTotal = startHour * 60 + startMinute
            val endTotal = endHour * 60 + endMinute

            return if (startTotal <= endTotal) {
                nowTotal in startTotal until endTotal
            } else {
                // Window wraps midnight (e.g. 22:30 -> 06:30)
                nowTotal >= startTotal || nowTotal < endTotal
            }
        }

        fun isNightOrBedtimeTime(
            nowHour: Int,
            nowMinute: Int,
            startHour: Int,
            startMinute: Int,
            endHour: Int,
            endMinute: Int
        ): Boolean {
            // Bedtime window itself OR up to 60 minutes before scheduled start time
            val startMinus60 = (startHour * 60 + startMinute - 60 + 1440) % 1440
            val startMinusH = startMinus60 / 60
            val startMinusM = startMinus60 % 60
            return isTimeInWindow(nowHour, nowMinute, startMinusH, startMinusM, endHour, endMinute)
        }

        fun isEveningOrNightTime(hour: Int): Boolean {
            // Strict night hours from 21:00 until 06:00 (never daylight morning)
            return hour >= 21 || hour < 6
        }

        fun isMorningTime(hour: Int): Boolean {
            // Morning wake window from 04:00 until 11:00
            return hour in 4..11
        }

        fun shouldAutoSleep(
            isAutoSleepEnabled: Boolean,
            isScreenOff: Boolean,
            isStationary: Boolean,
            isTimeInWindow: Boolean,
            isDndActive: Boolean,
            followDnd: Boolean,
            isNightOrBedtime: Boolean,
            hasActiveTrip: Boolean,
            wasManuallyWokenThisCycle: Boolean
        ): Boolean {
            if (!isAutoSleepEnabled) return false
            if (hasActiveTrip) return false // Trip recording strictly blocks sleep
            if (wasManuallyWokenThisCycle) return false // Explicit user wake honored
            if (!isScreenOff) return false // Screen on does not auto-enter sleep

            // Daytime protection: Outside night/bedtime, auto-sleep is strictly forbidden
            if (!isNightOrBedtime && !isTimeInWindow) return false

            // Trigger 1: DND enabled in the evening/night with screen off
            if (followDnd && isDndActive && isNightOrBedtime) {
                return true
            }

            // Trigger 2: Bedtime schedule window with screen off and stationary
            if (isTimeInWindow && isStationary) {
                return true
            }

            return false
        }

        /** Alarm may wake sleep only at/after bedtime end, or within this many minutes of it. */
        const val ALARM_WAKE_GRACE_BEFORE_END_MIN = 5

        /**
         * Minutes from [nowTotalMin] until the bedtime window end (0 if already past end).
         * Handles midnight-wrapping windows (e.g. 21:30 → 06:30).
         */
        fun minutesUntilWindowEnd(nowTotalMin: Int, endTotalMin: Int, startTotalMin: Int): Int {
            if (startTotalMin <= endTotalMin) {
                // Same-day window
                return (endTotalMin - nowTotalMin).coerceAtLeast(0)
            }
            // Wraps midnight: end is next calendar morning
            return if (nowTotalMin >= startTotalMin) {
                (1440 - nowTotalMin) + endTotalMin
            } else {
                (endTotalMin - nowTotalMin).coerceAtLeast(0)
            }
        }

        fun shouldAutoWake(
            isAutoSleepEnabled: Boolean,
            isCurrentlySleeping: Boolean,
            isTimeInWindow: Boolean,
            isDndActive: Boolean,
            followDnd: Boolean,
            isMorning: Boolean,
            isAlarmFired: Boolean,
            isManualSleep: Boolean = false,
            wasDndActiveInSession: Boolean = false,
            minutesUntilWindowEnd: Int = Int.MAX_VALUE
        ): Boolean {
            if (!isAutoSleepEnabled || !isCurrentlySleeping) return false

            val alarmEligible = isAlarmFired && isMorning &&
                (!isTimeInWindow || minutesUntilWindowEnd <= ALARM_WAKE_GRACE_BEFORE_END_MIN)

            // Manually entered sleep persists until explicit user wake or eligible morning alarm
            if (isManualSleep) {
                return alarmEligible
            }

            // Morning alarm: honor only near/after bedtime end so a 06:00 alarm does not end a
            // 06:30 sleep window and leave Live Sharing GPS hot for the remaining half hour
            // (field 2026-10-22).
            if (alarmEligible) return true

            // DND was active during sleep, and user turned it off in the morning
            if (followDnd && wasDndActiveInSession && !isDndActive && isMorning) return true

            // Bedtime window completely ended and DND is not holding it
            if (!isTimeInWindow && (!followDnd || !isDndActive)) return true

            return false
        }
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _autoSleepEnabled = MutableStateFlow(prefs.getBoolean(KEY_AUTO_SLEEP_ENABLED, true))
    val autoSleepEnabled: StateFlow<Boolean> = _autoSleepEnabled.asStateFlow()

    private val _startTime = MutableStateFlow(prefs.getString(KEY_AUTO_SLEEP_START_TIME, DEFAULT_START_TIME) ?: DEFAULT_START_TIME)
    val startTime: StateFlow<String> = _startTime.asStateFlow()

    private val _endTime = MutableStateFlow(prefs.getString(KEY_AUTO_SLEEP_END_TIME, DEFAULT_END_TIME) ?: DEFAULT_END_TIME)
    val endTime: StateFlow<String> = _endTime.asStateFlow()

    private val _followDnd = MutableStateFlow(prefs.getBoolean(KEY_AUTO_SLEEP_FOLLOW_DND, true))
    val followDnd: StateFlow<Boolean> = _followDnd.asStateFlow()

    private val _followAlarm = MutableStateFlow(prefs.getBoolean(KEY_AUTO_SLEEP_FOLLOW_ALARM, true))
    val followAlarm: StateFlow<Boolean> = _followAlarm.asStateFlow()

    private val _pauseLiveShare = MutableStateFlow(resolvePauseLiveSharePref())
    val pauseLiveShare: StateFlow<Boolean> = _pauseLiveShare.asStateFlow()

    /** One-time: default pause-live-on-sleep to true (was false; overnight visitors sessions drained GPS). */
    private fun resolvePauseLiveSharePref(): Boolean {
        val migrationKey = "pref_auto_sleep_pause_live_share_migrated_v2"
        if (!prefs.getBoolean(migrationKey, false)) {
            prefs.edit()
                .putBoolean(KEY_AUTO_SLEEP_PAUSE_LIVE_SHARE, true)
                .putBoolean(migrationKey, true)
                .apply()
            return true
        }
        return prefs.getBoolean(KEY_AUTO_SLEEP_PAUSE_LIVE_SHARE, true)
    }

    private var wasManuallyWokenThisCycle = false
    private var lastEvaluatedCycleDate = -1
    private var isManualSleep = false
    private var wasDndActiveInSession = false
    private var wasLiveShareAutoPaused = false
    private val alarmTracker = AlarmFireTracker(
        upcomingTriggerMs = prefs.getLong(KEY_UPCOMING_ALARM_MS, 0L),
        lastFiredMs = prefs.getLong(KEY_LAST_FIRED_ALARM_MS, 0L)
    )

    init {
        INSTANCE = this
        registerSystemReceivers()
        checkAndRecordAlarmState()
    }

    fun onManualSleepEntered() {
        isManualSleep = true
        wasDndActiveInSession = isDndActive()
        handleSleepEnteredLiveSharePause()
        TelemetryLogger.log("POWER", "AutoSleepManager: Manual sleep registered (will not auto-wake on bedtime window end)")
    }

    fun onSleepEntered() {
        handleSleepEnteredLiveSharePause()
    }

    private fun handleSleepEnteredLiveSharePause() {
        if (_pauseLiveShare.value) {
            val liveMgr = LiveSharingManager.getInstance(context)
            val session = liveMgr.currentSession.value
            if (session != null && session.isActive && !session.isPaused) {
                wasLiveShareAutoPaused = true
                liveMgr.pauseSession()
                TelemetryLogger.log("POWER", "AutoSleep: Paused Live Sharing session (${session.id}) during sleep.")
            }
        }
    }

    fun onSleepWoken() {
        isManualSleep = false
        wasDndActiveInSession = false
        if (wasLiveShareAutoPaused) {
            wasLiveShareAutoPaused = false
            val liveMgr = LiveSharingManager.getInstance(context)
            val session = liveMgr.currentSession.value
            if (session != null && session.isActive && session.isPaused) {
                liveMgr.resumeSession()
                TelemetryLogger.log("POWER", "AutoSleep: Resumed Live Sharing session (${session.id}) on sleep wake.")
            }
        }
    }

    fun isLiveShareAutoPaused(): Boolean = wasLiveShareAutoPaused

    fun isManualSleepActive(): Boolean = isManualSleep

    fun setPauseLiveShare(enabled: Boolean) {
        _pauseLiveShare.value = enabled
        prefs.edit().putBoolean(KEY_AUTO_SLEEP_PAUSE_LIVE_SHARE, enabled).apply()
        TelemetryLogger.log("SETTINGS", "AutoSleep pauseLiveShare set to $enabled")
    }

    fun setAutoSleepEnabled(enabled: Boolean) {
        _autoSleepEnabled.value = enabled
        prefs.edit().putBoolean(KEY_AUTO_SLEEP_ENABLED, enabled).apply()
        TelemetryLogger.log("SETTINGS", "AutoSleepEnabled set to $enabled")
        if (!enabled) {
            cancelBoundaryAlarm()
        }
        evaluateAutoSleep()
    }

    fun setStartTime(time: String) {
        _startTime.value = time
        prefs.edit().putString(KEY_AUTO_SLEEP_START_TIME, time).apply()
        TelemetryLogger.log("SETTINGS", "AutoSleep start time set to $time")
        evaluateAutoSleep()
    }

    fun setEndTime(time: String) {
        _endTime.value = time
        prefs.edit().putString(KEY_AUTO_SLEEP_END_TIME, time).apply()
        TelemetryLogger.log("SETTINGS", "AutoSleep end time set to $time")
        evaluateAutoSleep()
    }

    fun setFollowDnd(follow: Boolean) {
        _followDnd.value = follow
        prefs.edit().putBoolean(KEY_AUTO_SLEEP_FOLLOW_DND, follow).apply()
        TelemetryLogger.log("SETTINGS", "AutoSleep followDnd set to $follow")
        evaluateAutoSleep()
    }

    fun setFollowAlarm(follow: Boolean) {
        _followAlarm.value = follow
        prefs.edit().putBoolean(KEY_AUTO_SLEEP_FOLLOW_ALARM, follow).apply()
        TelemetryLogger.log("SETTINGS", "AutoSleep followAlarm set to $follow")
        evaluateAutoSleep()
    }

    fun onUserExplicitWake() {
        wasManuallyWokenThisCycle = true
        TelemetryLogger.log("POWER", "User explicitly woke app: AutoSleep suppressed for current night cycle")
    }

    fun isDndActive(): Boolean {
        return try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val filter = nm?.currentInterruptionFilter ?: NotificationManager.INTERRUPTION_FILTER_ALL
            filter != NotificationManager.INTERRUPTION_FILTER_ALL
        } catch (_: Exception) {
            false
        }
    }

    private var lastScheduledBoundaryTriggerMs = 0L

    fun checkAndRecordAlarmState() {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val firedBefore = alarmTracker.lastFiredMs
        alarmTracker.update(System.currentTimeMillis(), am.nextAlarmClock?.triggerTime)
        prefs.edit()
            .putLong(KEY_UPCOMING_ALARM_MS, alarmTracker.upcomingTriggerMs)
            .putLong(KEY_LAST_FIRED_ALARM_MS, alarmTracker.lastFiredMs)
            .apply()
        if (alarmTracker.lastFiredMs != firedBefore) {
            TelemetryLogger.log("POWER", "AutoSleep: alarm clock fired at ${alarmTracker.lastFiredMs}")
        }
    }

    fun isAlarmFiredRecently(): Boolean {
        if (!_followAlarm.value) return false
        checkAndRecordAlarmState()
        return alarmTracker.firedWithin(System.currentTimeMillis())
    }

    fun scheduleBoundaryAlarm() {
        if (!_autoSleepEnabled.value) return
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(ACTION_BEDTIME_TRANSITION).apply {
            setPackage(context.packageName)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pi = PendingIntent.getBroadcast(context, REQUEST_CODE_BEDTIME_ALARM, intent, flags)

        val (startH, startM) = parseHourMinute(_startTime.value, 22, 30)
        val (endH, endM) = parseHourMinute(_endTime.value, 6, 30)
        val isCurrentlySleeping = AppStateManager.getInstance(context).isStandbySleeping.value
        val triggerAtMs = computeNextBoundaryTriggerMillis(
            nowMillis = System.currentTimeMillis(),
            isCurrentlySleeping = isCurrentlySleeping,
            startH = startH,
            startM = startM,
            endH = endH,
            endM = endM
        )

        if (triggerAtMs == lastScheduledBoundaryTriggerMs) return
        lastScheduledBoundaryTriggerMs = triggerAtMs

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
            } else {
                am.set(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
            }
            val cal = Calendar.getInstance().apply { timeInMillis = triggerAtMs }
            TelemetryLogger.log("POWER", "Scheduled bedtime boundary alarm for ${String.format("%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))}")
        } catch (e: Exception) {
            TelemetryLogger.log("ERROR", "Failed to schedule bedtime boundary alarm: ${e.message}")
        }
    }

    fun cancelBoundaryAlarm() {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(ACTION_BEDTIME_TRANSITION).apply {
            setPackage(context.packageName)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_NO_CREATE
        }
        val pi = PendingIntent.getBroadcast(context, REQUEST_CODE_BEDTIME_ALARM, intent, flags)
        if (pi != null) {
            am.cancel(pi)
            pi.cancel()
        }
        lastScheduledBoundaryTriggerMs = 0L
    }

    private fun registerSystemReceivers() {
        val filter = IntentFilter().apply {
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(ACTION_BEDTIME_TRANSITION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                addAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)
            }
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action == AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED) {
                    checkAndRecordAlarmState()
                }
                evaluateAutoSleep()
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
        } catch (e: Exception) {
            TelemetryLogger.log("ERROR", "Failed to register AutoSleep receiver: ${e.message}")
        }
        scheduleBoundaryAlarm()
    }

    fun isCurrentTimeInBedtimeWindow(): Boolean {
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        val (startH, startM) = parseHourMinute(_startTime.value, 22, 30)
        val (endH, endM) = parseHourMinute(_endTime.value, 6, 30)
        return isTimeInWindow(hour, minute, startH, startM, endH, endM)
    }

    fun evaluateAutoSleep() {
        if (!_autoSleepEnabled.value) return

        val appStateMgr = AppStateManager.getInstance(context)
        val tripManager = TripManager.getInstance(context)
        val hasTrip = tripManager.activeTrip.value != null
        val isScreenOff = !appStateMgr.isAppInForegroundFlow.value
        val isStationary = StationaryDetector.getInstance(context).isPhysicallyStationary.value ||
                LocationManager.getInstance(context).getLastValidSpeedMs() < 0.35f
        val isCurrentlySleeping = appStateMgr.isStandbySleeping.value

        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        val dayOfYear = cal.get(Calendar.DAY_OF_YEAR)

        // Reset manual wake override when moving into daytime (after 12:00 PM)
        if (hour in 12..18 && wasManuallyWokenThisCycle) {
            wasManuallyWokenThisCycle = false
        }

        val (startH, startM) = parseHourMinute(_startTime.value, 22, 30)
        val (endH, endM) = parseHourMinute(_endTime.value, 6, 30)

        val inWindow = isTimeInWindow(hour, minute, startH, startM, endH, endM)
        val isNightOrBedtime = isNightOrBedtimeTime(hour, minute, startH, startM, endH, endM)
        val dndActive = isDndActive()
        val isMorning = isMorningTime(hour)
        val alarmFired = isAlarmFiredRecently()

        if (!isCurrentlySleeping) {
            val shouldSleep = shouldAutoSleep(
                isAutoSleepEnabled = _autoSleepEnabled.value,
                isScreenOff = isScreenOff,
                isStationary = isStationary,
                isTimeInWindow = inWindow,
                isDndActive = dndActive,
                followDnd = _followDnd.value,
                isNightOrBedtime = isNightOrBedtime,
                hasActiveTrip = hasTrip,
                wasManuallyWokenThisCycle = wasManuallyWokenThisCycle
            )
            if (shouldSleep) {
                wasDndActiveInSession = dndActive
                val reason = if (dndActive && _followDnd.value) "DND active in night/bedtime window" else "Bedtime schedule ($startH:$startM-$endH:$endM)"
                TelemetryLogger.log("POWER", "AutoSleep triggered: $reason. Entering standby sleep mode.")
                appStateMgr.enterStandbySleep()
            }
        } else {
            if (dndActive) {
                wasDndActiveInSession = true
            }
            val nowTotal = hour * 60 + minute
            val endTotal = endH * 60 + endM
            val startTotal = startH * 60 + startM
            val minsToEnd = minutesUntilWindowEnd(nowTotal, endTotal, startTotal)
            val shouldWake = shouldAutoWake(
                isAutoSleepEnabled = _autoSleepEnabled.value,
                isCurrentlySleeping = true,
                isTimeInWindow = inWindow,
                isDndActive = dndActive,
                followDnd = _followDnd.value,
                isMorning = isMorning,
                isAlarmFired = alarmFired && _followAlarm.value,
                isManualSleep = isManualSleep,
                wasDndActiveInSession = wasDndActiveInSession,
                minutesUntilWindowEnd = minsToEnd
            )
            if (shouldWake) {
                val isEarlyWake = inWindow && (alarmFired || (!dndActive && wasDndActiveInSession))
                // DND early wake: suppress re-sleep for the remainder of the window (user is up).
                // Alarm early wake is no longer granted while deep inside the window (see shouldAutoWake).
                if (isEarlyWake && !alarmFired) {
                    wasManuallyWokenThisCycle = true
                    TelemetryLogger.log("POWER", "AutoSleep early wake (DND): suppressed auto-sleep for remainder of bedtime window")
                }
                val reason = when {
                    alarmFired -> "Morning alarm fired (within ${minsToEnd}m of bedtime end)"
                    !dndActive && isMorning && _followDnd.value && wasDndActiveInSession -> "DND turned off in morning"
                    else -> "Bedtime window ended"
                }
                TelemetryLogger.log("POWER", "AutoSleep waking up: $reason.")
                appStateMgr.wakeStandbySleep()
            }
        }
        scheduleBoundaryAlarm()
    }
}
