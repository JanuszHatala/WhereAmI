package online.hatala.whereami

import org.osmdroid.util.GeoPoint

enum class OutdoorAreaKind(val displayName: String, val iconEmoji: String) {
    MOUNTAIN_RANGE("Mountain Range", "⛰️"),
    NATIONAL_PARK("National Park", "🌲"),
    LANDSCAPE_PARK("Landscape Park", "🏞️"),
    NATURE_RESERVE("Nature Reserve", "🦌"),
    VALLEY("Valley", "🌾")
}

data class OutdoorArea(
    val id: String,
    val name: String,
    val nameEn: String? = null,
    val kind: OutdoorAreaKind,
    val osmRelationId: Long? = null,
    val centerLat: Double = 0.0,
    val centerLon: Double = 0.0
)

data class OutdoorAreaWithPolygon(
    val area: OutdoorArea,
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double,
    val polygon: List<GeoPoint>
) {
    fun contains(lat: Double, lon: Double): Boolean {
        if (lat < minLat || lat > maxLat || lon < minLon || lon > maxLon) return false
        return BoundaryHelper.polygonContains(polygon, lat, lon)
    }
}

enum class TrailRouteKind(val displayName: String, val iconEmoji: String) {
    HIKING("Hiking Trail", "🥾"),
    EDUCATIONAL("Educational Path", "📖"),
    CYCLING("Cycling Route", "🚴"),
    MTB("MTB Trail", "🚵"),
    KAYAKING("Waterway / Kayak Trail", "🛶")
}

enum class TrailColor(val hexColor: String, val displayName: String) {
    RED("#EF4444", "Red"),
    BLUE("#3B82F6", "Blue"),
    YELLOW("#EAB308", "Yellow"),
    GREEN("#22C55E", "Green"),
    BLACK("#1E293B", "Black"),
    WHITE("#F8FAFC", "White"),
    PURPLE("#A855F7", "Purple"),
    ORANGE("#F97316", "Orange"),
    CYAN("#06B6D4", "Cyan"),
    NONE("#94A3B8", "Unmarked")
}

data class ActiveTrailInfo(
    val osmId: Long,
    val name: String,
    val ref: String? = null,
    val kind: TrailRouteKind = TrailRouteKind.HIKING,
    val color: TrailColor = TrailColor.NONE,
    val network: String? = null,
    val distanceMeters: Float = 0f
)

data class OutdoorFeatureInfo(
    val osmId: Long,
    val name: String,
    val kind: PlaceKind,
    val elevationMeters: Int? = null,
    val distanceMeters: Float = 0f,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0
)

data class OutdoorContext(
    val mountainRange: OutdoorArea? = null,
    val protectedAreas: List<OutdoorArea> = emptyList(),
    val valley: OutdoorArea? = null,
    val activeTrails: List<ActiveTrailInfo> = emptyList(),
    val nearestFeatures: List<OutdoorFeatureInfo> = emptyList(),
    val waterBody: OutdoorFeatureInfo? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    val hasAnyOutdoor: Boolean
        get() = mountainRange != null || protectedAreas.isNotEmpty() || valley != null ||
                activeTrails.isNotEmpty() || nearestFeatures.isNotEmpty() || waterBody != null

    val primaryRange: OutdoorArea? get() = mountainRange
    val parkOrReserve: OutdoorArea? get() = protectedAreas.firstOrNull()
    val otherAreas: List<OutdoorArea> get() = if (protectedAreas.size > 1) protectedAreas.drop(1) else emptyList()
    val activeTrail: ActiveTrailInfo? get() = activeTrails.firstOrNull()
    val nearestPeak: OutdoorFeatureInfo? get() = nearestFeatures.firstOrNull { it.kind == PlaceKind.PEAK }
    val nearestPass: OutdoorFeatureInfo? get() = nearestFeatures.firstOrNull { it.kind == PlaceKind.MOUNTAIN_PASS }
    val nearestHut: OutdoorFeatureInfo? get() = nearestFeatures.firstOrNull { it.kind == PlaceKind.MOUNTAIN_HUT }
    val nearestWater: OutdoorFeatureInfo? get() = waterBody ?: nearestFeatures.firstOrNull { it.kind == PlaceKind.WATER_BODY }
}

enum class TrailThickness(val label: String, val widthMultiplier: Float) {
    NORMAL("Normal", 1.0f),
    THICK("Thick", 1.6f),
    EXTRA_THICK("Extra Thick", 2.4f)
}
