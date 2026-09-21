package janush.tech.whereami

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationDialogNavigationTest {

    @Test
    fun testPercentageCalculation() {
        fun calcPercent(current: Int, total: Int): Int {
            return if (total > 0) (current.toLong() * 100 / total).toInt().coerceIn(0, 100) else 0
        }

        assertEquals(0, calcPercent(0, 0))
        assertEquals(0, calcPercent(0, 19895))
        assertEquals(15, calcPercent(3000, 20000))
        assertEquals(50, calcPercent(500, 1000))
        assertEquals(100, calcPercent(19895, 19895))
        assertEquals(100, calcPercent(25000, 19895))
    }

    @Test
    fun testDialogTargetEnumValues() {
        val targets = MainViewModel.AppDialogTarget.values()
        assertTrue(targets.contains(MainViewModel.AppDialogTarget.NONE))
        assertTrue(targets.contains(MainViewModel.AppDialogTarget.CACHE_MANAGER))
        assertTrue(targets.contains(MainViewModel.AppDialogTarget.LIVE_SHARING))
    }

    @Test
    fun testIntentActionConstants() {
        assertEquals("janush.tech.whereami.ACTION_OPEN_LIVE_SHARING", LiveTrackingService.ACTION_OPEN_LIVE_SHARING)
        assertEquals("extra_open_live_sharing", LiveTrackingService.EXTRA_OPEN_LIVE_SHARING)
        assertEquals("janush.tech.whereami.ACTION_OPEN_CACHE_MANAGER", CacheManager.ACTION_OPEN_CACHE_MANAGER)
        assertEquals("extra_open_cache_manager", CacheManager.EXTRA_OPEN_CACHE_MANAGER)
    }
}
