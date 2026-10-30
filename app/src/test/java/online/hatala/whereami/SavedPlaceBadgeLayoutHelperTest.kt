package online.hatala.whereami

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedPlaceBadgeLayoutHelperTest {

    // Simple mock text measurer where each character is 10px wide, and "..." is 20px wide
    private val mockMeasurer: (String) -> Float = { text ->
        if (text.endsWith("...")) {
            val prefix = text.removeSuffix("...")
            prefix.length * 10f + 20f
        } else {
            text.length * 10f
        }
    }

    @Test
    fun `test name longer than 18 characters collapses to icon only`() {
        val longName = "Very Long Saved Place Name"
        // Even with huge available width, > 18 chars triggers icon only
        assertTrue(SavedPlaceBadgeLayoutHelper.shouldShowIconOnly(longName, 1000f, mockMeasurer))
    }

    @Test
    fun `test full name fits completely displays text`() {
        val name = "Timebook" // 8 chars = 80px
        // Available width = 100px >= 80px
        assertFalse(SavedPlaceBadgeLayoutHelper.shouldShowIconOnly(name, 100f, mockMeasurer))
        // Exact fit
        assertFalse(SavedPlaceBadgeLayoutHelper.shouldShowIconOnly(name, 80f, mockMeasurer))
    }

    @Test
    fun `test full name does not fit but at least 4 letters plus ellipsis fits`() {
        val name = "Timebook" // 8 chars = 80px
        // Min 4 letters + "..." = 4 * 10 + 20 = 60px
        // Available width = 70px: 80px doesn't fit, but 70px >= 60px
        assertFalse(SavedPlaceBadgeLayoutHelper.shouldShowIconOnly(name, 70f, mockMeasurer))
        // Exact 60px fits 4 letters + ellipsis ("Time...")
        assertFalse(SavedPlaceBadgeLayoutHelper.shouldShowIconOnly(name, 60f, mockMeasurer))
    }

    @Test
    fun `test fewer than 4 letters plus ellipsis fits collapses to icon only`() {
        val name = "Timebook"
        // Min 4 letters + "..." requires 60px
        // Available width = 55px (could only fit "Tim..." which is 3 letters)
        assertTrue(SavedPlaceBadgeLayoutHelper.shouldShowIconOnly(name, 55f, mockMeasurer))
        assertTrue(SavedPlaceBadgeLayoutHelper.shouldShowIconOnly(name, 40f, mockMeasurer))
        assertTrue(SavedPlaceBadgeLayoutHelper.shouldShowIconOnly(name, 10f, mockMeasurer))
        assertTrue(SavedPlaceBadgeLayoutHelper.shouldShowIconOnly(name, 0f, mockMeasurer))
    }

    @Test
    fun `test short name under 4 letters collapses to icon only if full name does not fit`() {
        val name = "Dom" // 3 chars = 30px
        // Fits full name (30px) -> show text
        assertFalse(SavedPlaceBadgeLayoutHelper.shouldShowIconOnly(name, 30f, mockMeasurer))
        assertFalse(SavedPlaceBadgeLayoutHelper.shouldShowIconOnly(name, 35f, mockMeasurer))

        // Does not fit full name (25px < 30px) -> cannot show 4 letters + ellipsis, so icon only
        assertTrue(SavedPlaceBadgeLayoutHelper.shouldShowIconOnly(name, 25f, mockMeasurer))
    }

    @Test
    fun `test exactly 4 letter name`() {
        val name = "Home" // 4 chars = 40px
        // Fits full name -> show text
        assertFalse(SavedPlaceBadgeLayoutHelper.shouldShowIconOnly(name, 40f, mockMeasurer))

        // Does not fit full name (35px < 40px) -> min 4 with "..." requires 40 + 20 = 60px -> icon only
        assertTrue(SavedPlaceBadgeLayoutHelper.shouldShowIconOnly(name, 35f, mockMeasurer))
    }
}
