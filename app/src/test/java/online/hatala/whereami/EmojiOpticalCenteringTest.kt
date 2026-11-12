package online.hatala.whereami

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiOpticalCenteringTest {

    @Test
    fun testAllProfilesHaveAppropriateOpticalOffset() {
        assertEquals(-0.20f, ActivityProfile.CAR.opticalYOffsetRatio, 0.001f)
        assertEquals(-0.16f, ActivityProfile.KAYAKING.opticalYOffsetRatio, 0.001f)
        assertEquals(-0.05f, ActivityProfile.HIKING.opticalYOffsetRatio, 0.001f)
        assertEquals(0.0f, ActivityProfile.CYCLING.opticalYOffsetRatio, 0.001f)
        assertEquals(0.0f, ActivityProfile.MTB.opticalYOffsetRatio, 0.001f)
        assertEquals(0.0f, ActivityProfile.RUNNING.opticalYOffsetRatio, 0.001f)
        assertEquals(0.0f, ActivityProfile.WALKING.opticalYOffsetRatio, 0.001f)
    }

    @Test
    fun testOpticalYOffsetDpCalculation() {
        // At 13sp (spatious mode trigger pill)
        assertEquals(-2.6f, ActivityProfile.CAR.opticalYOffsetDp(13f), 0.01f)
        assertEquals(-2.08f, ActivityProfile.KAYAKING.opticalYOffsetDp(13f), 0.01f)
        assertEquals(-0.65f, ActivityProfile.HIKING.opticalYOffsetDp(13f), 0.01f)
        assertEquals(0.0f, ActivityProfile.WALKING.opticalYOffsetDp(13f), 0.001f)
        assertEquals(0.0f, ActivityProfile.RUNNING.opticalYOffsetDp(13f), 0.001f)
        assertEquals(0.0f, ActivityProfile.CYCLING.opticalYOffsetDp(13f), 0.001f)
        assertEquals(0.0f, ActivityProfile.MTB.opticalYOffsetDp(13f), 0.001f)

        // At 11sp (compact mode trigger pill)
        assertEquals(-2.2f, ActivityProfile.CAR.opticalYOffsetDp(11f), 0.01f)
        assertEquals(-1.76f, ActivityProfile.KAYAKING.opticalYOffsetDp(11f), 0.01f)
        assertEquals(0.0f, ActivityProfile.WALKING.opticalYOffsetDp(11f), 0.001f)
    }

    @Test
    fun testEmojiOpticalYOffsetFormula() {
        // Flat horizontal emojis shift upward to match Latin cap-height center (~35%)
        assertEquals(-2.6f, emojiOpticalYOffset("🚗", 13.sp).value, 0.01f)
        assertEquals(-2.08f, emojiOpticalYOffset("🛶", 13.sp).value, 0.01f)
        assertEquals(-0.65f, emojiOpticalYOffset("🥾", 13.sp).value, 0.01f)

        // Upright emojis (already centered at ~35%) stay at 0.0.dp
        assertEquals(0f, emojiOpticalYOffset("🚶", 13.sp).value, 0.001f)
        assertEquals(0f, emojiOpticalYOffset("🏃", 13.sp).value, 0.001f)
        assertEquals(0f, emojiOpticalYOffset("🚴", 13.sp).value, 0.001f)
        assertEquals(0f, emojiOpticalYOffset("🚵", 13.sp).value, 0.001f)
        assertEquals(0f, emojiOpticalYOffset("🚩", 11.sp).value, 0.001f)
        assertEquals(0f, emojiOpticalYOffset("📍", 14.sp).value, 0.001f)
    }
}
