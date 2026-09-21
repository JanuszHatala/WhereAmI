package janush.tech.whereami

import org.osmdroid.util.GeoPoint

enum class PlaceCategory(val displayName: String, val iconEmoji: String) {
    FAVORITE("Favorite", "⭐"),
    HOME("Home", "🏠"),
    WORK("Work", "💼"),
    FAMILY("Family", "👨‍👩‍👧"),
    SCHOOL("School", "🏫"),
    CUSTOM("Custom", "📍")
}

data class PinColorOption(
    val label: String,
    val hex: String
)

val PRESET_PIN_COLORS = listOf(
    PinColorOption("Auto", ""),          // Falls back to category default color
    PinColorOption("Red", "#EF4444"),
    PinColorOption("Orange", "#F97316"),
    PinColorOption("Amber", "#F59E0B"),
    PinColorOption("Emerald", "#10B981"),
    PinColorOption("Teal", "#14B8A6"),
    PinColorOption("Cyan", "#06B6D4"),
    PinColorOption("Blue", "#3B82F6"),
    PinColorOption("Purple", "#8B5CF6"),
    PinColorOption("Pink", "#EC4899"),
    PinColorOption("Slate", "#64748B")
)

data class SavedPlace(
    val id: Long = 0,
    val name: String,
    val category: PlaceCategory,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float = 100f,
    val locality: String = "",
    val street: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val colorHex: String = ""
) {
    val geoPoint: GeoPoint get() = GeoPoint(latitude, longitude)

    fun getEffectiveColorHex(): String {
        if (colorHex.isNotBlank()) return colorHex
        return when (category) {
            PlaceCategory.FAVORITE -> "#8B5CF6" // Purple
            PlaceCategory.HOME -> "#10B981"     // Emerald Green
            PlaceCategory.WORK -> "#3B82F6"     // Blue
            PlaceCategory.FAMILY -> "#EC4899"   // Pink
            PlaceCategory.SCHOOL -> "#F59E0B"   // Amber
            PlaceCategory.CUSTOM -> "#06B6D4"   // Cyan
        }
    }
}
