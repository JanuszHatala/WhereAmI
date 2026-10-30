package online.hatala.whereami

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationIntentEvaluatorTest {

    @Test
    fun testShouldNotHandleIntentOnActivityRecreation() {
        // When savedInstanceState is present (non-null), the activity is being recreated (e.g. rotation).
        // It must NOT replay the notification intent.
        val fakeSavedState = Any()
        val shouldHandle = NotificationIntentEvaluator.shouldHandleOnCreate(fakeSavedState, null)
        assertFalse("Must not handle intent when savedInstanceState is present", shouldHandle)
    }

    @Test
    fun testShouldHandleIntentOnFreshLaunchWithValidIntent() {
        val shouldHandle = NotificationIntentEvaluator.shouldHandleOnCreate(
            savedInstanceState = null,
            intent = null // testing null check
        )
        assertFalse("Must not handle when intent is null", shouldHandle)
    }

    @Test
    fun testParseElevationAction() {
        val parsed = NotificationIntentEvaluator.parseActions(
            action = MainActivity.ACTION_SHOW_ELEVATION_SETTINGS
        )
        assertTrue(parsed.isElevation)
        assertFalse(parsed.isCache)
        assertFalse(parsed.isLiveSharing)
        assertTrue(parsed.hasTarget)
    }

    @Test
    fun testParseElevationExtra() {
        val parsed = NotificationIntentEvaluator.parseActions(
            action = null,
            extraElevation = true
        )
        assertTrue(parsed.isElevation)
        assertFalse(parsed.isCache)
        assertFalse(parsed.isLiveSharing)
        assertTrue(parsed.hasTarget)
    }

    @Test
    fun testParseCacheAction() {
        val parsed = NotificationIntentEvaluator.parseActions(
            action = CacheManager.ACTION_OPEN_CACHE_MANAGER
        )
        assertFalse(parsed.isElevation)
        assertTrue(parsed.isCache)
        assertFalse(parsed.isLiveSharing)
        assertTrue(parsed.hasTarget)
    }

    @Test
    fun testParseLiveSharingAction() {
        val parsed = NotificationIntentEvaluator.parseActions(
            action = LiveTrackingService.ACTION_OPEN_LIVE_SHARING
        )
        assertFalse(parsed.isElevation)
        assertFalse(parsed.isCache)
        assertTrue(parsed.isLiveSharing)
        assertTrue(parsed.hasTarget)
    }

    @Test
    fun testParseNormalLaunchHasNoTarget() {
        val parsed = NotificationIntentEvaluator.parseActions(
            action = "android.intent.action.MAIN"
        )
        assertFalse(parsed.isElevation)
        assertFalse(parsed.isCache)
        assertFalse(parsed.isLiveSharing)
        assertFalse(parsed.hasTarget)
    }
}
