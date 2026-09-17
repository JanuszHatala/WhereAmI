package janush.tech.whereami

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class TripGroupingHelperTest {

    private fun createDummyTrip(id: Long, startTime: Long, profile: ActivityProfile = ActivityProfile.CAR): TripRecord {
        return TripRecord(
            id = id,
            title = "Trip $id",
            startTime = startTime,
            endTime = startTime + 3600_000L,
            distanceMeters = 5000.0,
            maxSpeedKmh = 60f,
            avgSpeedKmh = 40f,
            isAutoDetected = false,
            activityProfile = profile,
            points = emptyList(),
            placesVisited = emptyList(),
            pauses = emptyList()
        )
    }

    @Test
    fun testEmptyTripsReturnsEmpty() {
        val groups = TripGroupingHelper.groupTrips(emptyList(), TripGroupBy.DATE)
        assertTrue(groups.isEmpty())
    }

    @Test
    fun testGroupByNoneReturnsSingleGroup() {
        val trips = listOf(
            createDummyTrip(1, 1000L),
            createDummyTrip(2, 2000L)
        )
        val groups = TripGroupingHelper.groupTrips(trips, TripGroupBy.NONE)
        assertEquals(1, groups.size)
        assertEquals("All Trips", groups[0].title)
        assertEquals(2, groups[0].count)
        assertEquals(trips, groups[0].trips)
    }

    @Test
    fun testDateGroupingIsStrictlyMutuallyExclusive() {
        // Set reference time to Thursday, Sep 17, 2026, 14:00:00
        val cal = Calendar.getInstance().apply {
            set(2026, Calendar.SEPTEMBER, 17, 14, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val refTime = cal.timeInMillis

        // 1. Today (Sep 17, 10:00)
        cal.set(2026, Calendar.SEPTEMBER, 17, 10, 0, 0)
        val tToday1 = cal.timeInMillis
        cal.set(2026, Calendar.SEPTEMBER, 17, 8, 0, 0)
        val tToday2 = cal.timeInMillis

        // 2. This week, but not today (Wednesday, Sep 16, 15:00)
        cal.set(2026, Calendar.SEPTEMBER, 16, 15, 0, 0)
        val tThisWeek1 = cal.timeInMillis
        // (Tuesday, Sep 15, 12:00)
        cal.set(2026, Calendar.SEPTEMBER, 15, 12, 0, 0)
        val tThisWeek2 = cal.timeInMillis

        // 3. This month, but before this week (Sep 5, 2026)
        cal.set(2026, Calendar.SEPTEMBER, 5, 10, 0, 0)
        val tThisMonth = cal.timeInMillis

        // 4. Previous month (August 2026)
        cal.set(2026, Calendar.AUGUST, 20, 10, 0, 0)
        val tAug = cal.timeInMillis

        // 5. Earlier year (June 2025)
        cal.set(2025, Calendar.JUNE, 10, 10, 0, 0)
        val tJune2025 = cal.timeInMillis

        val trips = listOf(
            createDummyTrip(1, tToday1),
            createDummyTrip(2, tToday2),
            createDummyTrip(3, tThisWeek1),
            createDummyTrip(4, tThisWeek2),
            createDummyTrip(5, tThisMonth),
            createDummyTrip(6, tAug),
            createDummyTrip(7, tJune2025)
        )

        val groups = TripGroupingHelper.groupTrips(trips, TripGroupBy.DATE, refTime)

        // Verify mutual exclusivity: every trip ID must appear exactly once across all groups
        val allGroupTripIds = groups.flatMap { it.trips.map { t -> t.id } }
        assertEquals("Total trip count across groups must equal input trips count", trips.size, allGroupTripIds.size)
        assertEquals("No trip ID can be duplicated across groups", trips.size, allGroupTripIds.toSet().size)

        // Verify group count matches sum
        val sumCounts = groups.sumOf { it.count }
        assertEquals(trips.size, sumCounts)

        // Verify expected groups exist with correct items
        val todayGroup = groups.firstOrNull { it.id == "today" }
        assertEquals(2, todayGroup?.count)
        assertEquals(listOf(1L, 2L), todayGroup?.trips?.map { it.id })

        val thisWeekGroup = groups.firstOrNull { it.id == "this_week" }
        assertEquals(2, thisWeekGroup?.count)
        assertEquals(listOf(3L, 4L), thisWeekGroup?.trips?.map { it.id })

        val thisMonthGroup = groups.firstOrNull { it.id == "this_month" }
        assertEquals(1, thisMonthGroup?.count)
        assertEquals(listOf(5L), thisMonthGroup?.trips?.map { it.id })

        val augGroup = groups.firstOrNull { it.id.contains("August 2026", ignoreCase = true) }
        assertEquals(1, augGroup?.count)
        assertEquals(listOf(6L), augGroup?.trips?.map { it.id })

        val june2025Group = groups.firstOrNull { it.id.contains("June 2025", ignoreCase = true) }
        assertEquals(1, june2025Group?.count)
        assertEquals(listOf(7L), june2025Group?.trips?.map { it.id })
    }

    @Test
    fun testActivityGrouping() {
        val trips = listOf(
            createDummyTrip(1, 1000L, ActivityProfile.CAR),
            createDummyTrip(2, 2000L, ActivityProfile.CAR),
            createDummyTrip(3, 3000L, ActivityProfile.CYCLING),
            createDummyTrip(4, 4000L, ActivityProfile.HIKING)
        )

        val groups = TripGroupingHelper.groupTrips(trips, TripGroupBy.ACTIVITY)

        assertEquals(3, groups.size)
        val carGroup = groups.firstOrNull { it.id == "activity_CAR" }
        assertEquals(2, carGroup?.count)
        assertTrue(carGroup?.title?.contains("Driving") == true)

        val cyclingGroup = groups.firstOrNull { it.id == "activity_CYCLING" }
        assertEquals(1, cyclingGroup?.count)
        assertTrue(cyclingGroup?.title?.contains("Cycling") == true)

        val hikingGroup = groups.firstOrNull { it.id == "activity_HIKING" }
        assertEquals(1, hikingGroup?.count)
        assertTrue(hikingGroup?.title?.contains("Hiking") == true)
    }
}
