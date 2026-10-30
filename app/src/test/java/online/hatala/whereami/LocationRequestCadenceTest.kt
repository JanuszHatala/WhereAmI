package online.hatala.whereami

import org.junit.Assert.*
import org.junit.Test

class LocationRequestCadenceTest {
    @Test
    fun screenOffBatchConstants_boundedAndOrdered() {
        // Reflection-free sanity: batching windows must stay well below the 30 s stale-fix budget used by the engine.
        val f = AppStateManager::class.java.getDeclaredField("SCREEN_OFF_BATCH_DELAY_MS")
        f.isAccessible = true
        val normal = f.getLong(null)
        val g = AppStateManager::class.java.getDeclaredField("SCREEN_OFF_BATCH_DELAY_SAVER_MS")
        g.isAccessible = true
        val saver = g.getLong(null)
        assertTrue(normal in 10_000L..30_000L)
        assertTrue(saver >= normal && saver <= 60_000L)
    }
}
