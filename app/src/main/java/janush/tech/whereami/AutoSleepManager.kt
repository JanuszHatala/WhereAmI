package janush.tech.whereami

import android.app.AlarmManager
import android.app.NotificationManager
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

        const val DEFAULT_START_TIME = "22:30"
        const val DEFAULT_END_TIME = "06:30"

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

        fun isEveningOrNightTime(hour: Int): Boolean {
            // Evening hours from 20:00 (8 PM) until morning 10:00 (10 AM)
            return hour >= 20 || hour < 10
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
            isEveningOrNight: Boolean,
            hasActiveTrip: Boolean,
            wasManuallyWokenThisCycle: Boolean
        ): Boolean {
            if (!isAutoSleepEnabled) return false
            if (hasActiveTrip) return false // Trip recording strictly blocks sleep
            if (wasManuallyWokenThisCycle) return false // Explicit user wake honored
            if (!isScreenOff) return false // Screen on does not auto-enter sleep

            // Trigger 1: DND enabled in the evening/night with screen off
            if (followDnd && isDndActive && isEveningOrNight) {
                return true
            }

            // Trigger 2: Bedtime schedule window with screen off and stationary
            if (isTimeInWindow && isStationary) {
                return true
            }

            return false
        }

        fun shouldAutoWake(
            isAutoSleepEnabled: Boolean,
            isCurrentlySleeping: Boolean,
            isTimeInWindow: Boolean,
            isDndActive: Boolean,
            followDnd: Boolean,
            isMorning: Boolean,
            isAlarmFired: Boolean
        ): Boolean {
            if (!isAutoSleepEnabled || !isCurrentlySleeping) return false

            // Morning alarm fired
            if (isAlarmFired && isMorning) return true

            // DND turned off in the morning
            if (followDnd && !isDndActive && isMorning) return true

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

    private var wasManuallyWokenThisCycle = false
    private var lastEvaluatedCycleDate = -1

    init {
        INSTANCE = this
        registerSystemReceivers()
    }

    fun setAutoSleepEnabled(enabled: Boolean) {
        _autoSleepEnabled.value = enabled
        prefs.edit().putBoolean(KEY_AUTO_SLEEP_ENABLED, enabled).apply()
        TelemetryLogger.log("SETTINGS", "AutoSleepEnabled set to $enabled")
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

    fun isAlarmFiredRecently(): Boolean {
        if (!_followAlarm.value) return false
        return try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            val nextAlarm = am?.nextAlarmClock
            if (nextAlarm != null) {
                val now = System.currentTimeMillis()
                val trigger = nextAlarm.triggerTime
                // Fired in the last 15 minutes
                trigger in (now - 15 * 60_000L)..now
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun registerSystemReceivers() {
        val filter = IntentFilter().apply {
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                addAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)
            }
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                evaluateAutoSleep()
            }
        }
        try {
            context.registerReceiver(receiver, filter)
        } catch (e: Exception) {
            TelemetryLogger.log("ERROR", "Failed to register AutoSleep receiver: ${e.message}")
        }
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
        val dndActive = isDndActive()
        val isEveningNight = isEveningOrNightTime(hour)
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
                isEveningOrNight = isEveningNight,
                hasActiveTrip = hasTrip,
                wasManuallyWokenThisCycle = wasManuallyWokenThisCycle
            )
            if (shouldSleep) {
                val reason = if (dndActive && _followDnd.value) "DND active in evening/night" else "Bedtime schedule ($startH:$startM-$endH:$endM)"
                TelemetryLogger.log("POWER", "AutoSleep triggered: $reason. Entering standby sleep mode.")
                appStateMgr.enterStandbySleep()
            }
        } else {
            val shouldWake = shouldAutoWake(
                isAutoSleepEnabled = _autoSleepEnabled.value,
                isCurrentlySleeping = true,
                isTimeInWindow = inWindow,
                isDndActive = dndActive,
                followDnd = _followDnd.value,
                isMorning = isMorning,
                isAlarmFired = alarmFired
            )
            if (shouldWake) {
                val reason = when {
                    alarmFired -> "Morning alarm fired"
                    !dndActive && isMorning && _followDnd.value -> "DND turned off in morning"
                    else -> "Bedtime window ended"
                }
                TelemetryLogger.log("POWER", "AutoSleep waking up: $reason.")
                appStateMgr.wakeStandbySleep()
            }
        }
    }
}
