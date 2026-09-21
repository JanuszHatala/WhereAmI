package janush.tech.whereami

import org.junit.Assert.assertEquals
import org.junit.Test

class SavedPlaceColorTest {

    @Test
    fun testExplicitColorTakesPrecedenceOverCategoryDefault() {
        val place = SavedPlace(
            id = 1,
            name = "My Office",
            category = PlaceCategory.WORK,
            latitude = 49.822,
            longitude = 19.245,
            colorHex = "#EF4444" // Explicit Red
        )

        assertEquals("#EF4444", place.getEffectiveColorHex())
    }

    @Test
    fun testBlankColorFallsBackToCategoryDefaults() {
        val homePlace = SavedPlace(
            id = 1,
            name = "Home",
            category = PlaceCategory.HOME,
            latitude = 49.822,
            longitude = 19.245,
            colorHex = ""
        )
        val schoolPlace = SavedPlace(
            id = 2,
            name = "School",
            category = PlaceCategory.SCHOOL,
            latitude = 49.822,
            longitude = 19.245,
            colorHex = ""
        )
        val favoritePlace = SavedPlace(
            id = 3,
            name = "Park",
            category = PlaceCategory.FAVORITE,
            latitude = 49.822,
            longitude = 19.245,
            colorHex = ""
        )

        assertEquals("#10B981", homePlace.getEffectiveColorHex())
        assertEquals("#F59E0B", schoolPlace.getEffectiveColorHex())
        assertEquals("#8B5CF6", favoritePlace.getEffectiveColorHex())
    }

    @Test
    fun testPresetColorsListContainsAutoOption() {
        assertEquals("Auto", PRESET_PIN_COLORS.first().label)
        assertEquals("", PRESET_PIN_COLORS.first().hex)
    }
}
