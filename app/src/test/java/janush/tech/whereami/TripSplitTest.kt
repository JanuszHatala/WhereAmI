package janush.tech.whereami

import org.junit.Assert.assertEquals
import org.junit.Test

class TripSplitTest {

    @Test
    fun testCleanPartSuffix() {
        assertEquals("Kraków -> Zakopane", TripDatabaseHelper.cleanPartSuffix("Kraków -> Zakopane - Part 1"))
        assertEquals("Kraków -> Zakopane", TripDatabaseHelper.cleanPartSuffix("Kraków -> Zakopane - Part 2"))
        assertEquals("Kraków -> Zakopane", TripDatabaseHelper.cleanPartSuffix("Kraków -> Zakopane – Part 3"))
        assertEquals("Kraków -> Zakopane", TripDatabaseHelper.cleanPartSuffix("Kraków -> Zakopane (Part 1)"))
        assertEquals("Morning Ride", TripDatabaseHelper.cleanPartSuffix("Morning Ride"))
        assertEquals("Morning Ride", TripDatabaseHelper.cleanPartSuffix("Morning Ride - Part 1 - Part 2"))
    }
}
