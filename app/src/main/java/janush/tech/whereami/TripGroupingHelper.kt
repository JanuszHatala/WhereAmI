package janush.tech.whereami

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class TripGroupBy(val displayName: String) {
    NONE("None"),
    DATE("Date"),
    ACTIVITY("Activity")
}

data class TripGroup(
    val id: String,
    val title: String,
    val count: Int,
    val trips: List<TripRecord>
)

object TripGroupingHelper {

    /**
     * Groups trips according to the selected strategy.
     * Guarantees:
     * 1. Mutually exclusive partitions: Each trip is assigned to exactly one group.
     * 2. Sum of group counts exactly equals input trips count.
     * 3. No empty groups are returned.
     */
    fun groupTrips(
        trips: List<TripRecord>,
        groupBy: TripGroupBy,
        referenceTimeMs: Long = System.currentTimeMillis()
    ): List<TripGroup> {
        if (trips.isEmpty()) return emptyList()

        return when (groupBy) {
            TripGroupBy.NONE -> {
                listOf(TripGroup(id = "all", title = "All Trips", count = trips.size, trips = trips))
            }
            TripGroupBy.DATE -> {
                groupByDate(trips, referenceTimeMs)
            }
            TripGroupBy.ACTIVITY -> {
                groupByActivity(trips)
            }
        }
    }

    private fun groupByDate(trips: List<TripRecord>, referenceTimeMs: Long): List<TripGroup> {
        val cal = Calendar.getInstance().apply {
            timeInMillis = referenceTimeMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startOfTodayMs = cal.timeInMillis

        // Start of this week
        cal.set(Calendar.DAY_OF_WEEK, cal.firstDayOfWeek)
        val startOfWeekMs = cal.timeInMillis

        // Start of this month
        cal.timeInMillis = referenceTimeMs
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val startOfMonthMs = cal.timeInMillis

        // Effective threshold for "This month" excluding anything captured by week
        val effectiveStartOfWeek = minOf(startOfWeekMs, startOfTodayMs)
        val effectiveStartOfMonth = minOf(startOfMonthMs, effectiveStartOfWeek)

        val monthYearFormat = SimpleDateFormat("MMMM yyyy", Locale.getDefault())

        val todayList = mutableListOf<TripRecord>()
        val thisWeekList = mutableListOf<TripRecord>()
        val thisMonthList = mutableListOf<TripRecord>()
        val earlierBuckets = LinkedHashMap<String, MutableList<TripRecord>>()

        for (trip in trips) {
            val t = trip.startTime
            when {
                t >= startOfTodayMs -> todayList.add(trip)
                t >= effectiveStartOfWeek -> thisWeekList.add(trip)
                t >= effectiveStartOfMonth -> thisMonthList.add(trip)
                else -> {
                    val key = monthYearFormat.format(Date(t))
                    earlierBuckets.getOrPut(key) { mutableListOf() }.add(trip)
                }
            }
        }

        val result = mutableListOf<TripGroup>()
        if (todayList.isNotEmpty()) {
            result.add(TripGroup(id = "today", title = "Today", count = todayList.size, trips = todayList))
        }
        if (thisWeekList.isNotEmpty()) {
            result.add(TripGroup(id = "this_week", title = "This week", count = thisWeekList.size, trips = thisWeekList))
        }
        if (thisMonthList.isNotEmpty()) {
            result.add(TripGroup(id = "this_month", title = "This month", count = thisMonthList.size, trips = thisMonthList))
        }
        for ((monthKey, bucket) in earlierBuckets) {
            result.add(TripGroup(id = "earlier_$monthKey", title = monthKey, count = bucket.size, trips = bucket))
        }

        return result
    }

    private fun groupByActivity(trips: List<TripRecord>): List<TripGroup> {
        val groupsByProfile = trips.groupBy { it.activityProfile }
        val result = mutableListOf<TripGroup>()

        for (profile in ActivityProfile.values()) {
            val list = groupsByProfile[profile]
            if (!list.isNullOrEmpty()) {
                val title = "${profile.iconEmoji} ${profile.displayName}"
                result.add(TripGroup(id = "activity_${profile.name}", title = title, count = list.size, trips = list))
            }
        }
        return result
    }
}
