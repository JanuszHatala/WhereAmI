package janush.tech.whereami

import org.osmdroid.util.GeoPoint

enum class PlaceCategory(val displayName: String, val iconEmoji: String) {
    HOME("Home", "🏠"),
    WORK("Work", "💼"),
    FAMILY("Family", "👨‍👩‍👧"),
    SCHOOL("School", "🏫"),
    FAVORITE("Favorite", "⭐"),
    CUSTOM("Custom", "📍")
}

data class SavedPlace(
    val id: Long = 0,
    val name: String,
    val category: PlaceCategory,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float = 100f,
    val locality: String = "",
    val street: String = "",
    val createdAt: Long = System.currentTimeMillis()
) {
    val geoPoint: GeoPoint get() = GeoPoint(latitude, longitude)
}
