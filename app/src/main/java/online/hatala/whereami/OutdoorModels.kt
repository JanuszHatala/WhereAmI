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
    val timestamp: Long = System.currentTimeMillis(),
    val isAuthoritative: Boolean = true
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

    /**
     * Filters outdoor card badges by the active profile's map layer settings:
     * - [showOutdoorAreas] controls ranges, parks/reserves, valleys, peaks/passes/huts and water bodies.
     * - [showHikingTrails] controls active trail pills.
     */
    fun forDisplay(
        showOutdoorAreas: Boolean,
        showHikingTrails: Boolean = true
    ): OutdoorContext {
        val filteredTrails = if (showHikingTrails) activeTrails else emptyList()
        return if (showOutdoorAreas) {
            copy(activeTrails = filteredTrails)
        } else {
            copy(
                mountainRange = null,
                protectedAreas = emptyList(),
                valley = null,
                activeTrails = filteredTrails,
                nearestFeatures = emptyList(),
                waterBody = null
            )
        }
    }

    /**
     * Recompute distanceMeters on cached point-of-interest features (peaks, passes, huts, water bodies)
     * in memory against current GPS coordinates without triggering any DB or network IO.
     */
    fun withUpdatedDistances(lat: Double, lng: Double): OutdoorContext {
        if (nearestFeatures.isEmpty() && waterBody == null) return this
        val updatedFeatures = nearestFeatures.map { feature ->
            if (feature.latitude == 0.0 && feature.longitude == 0.0) feature
            else feature.copy(distanceMeters = haversineDistanceMeters(lat, lng, feature.latitude, feature.longitude))
        }.sortedBy { it.distanceMeters }
        val updatedWater = waterBody?.let { water ->
            if (water.latitude == 0.0 && water.longitude == 0.0) water
            else water.copy(distanceMeters = haversineDistanceMeters(lat, lng, water.latitude, water.longitude))
        }
        return copy(nearestFeatures = updatedFeatures, waterBody = updatedWater)
    }

    private fun haversineDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val earthRadiusM = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val sinDLat = kotlin.math.sin(dLat * 0.5)
        val sinDLon = kotlin.math.sin(dLon * 0.5)
        val a = sinDLat * sinDLat +
            kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) * sinDLon * sinDLon
        val c = 2.0 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1.0 - a))
        return (earthRadiusM * c).toFloat()
    }
}

enum class TrailThickness(val label: String, val widthMultiplier: Float) {
    NORMAL("Standard", 1.0f),
    THICK("Thick", 2.0f),
    EXTRA_THICK("Bold", 2.5f)
}

enum class TrailLineStyle(val label: String) {
    SOLID("Solid"),
    DASHED("Dashed"),
    DOTTED("Dotted")
}

enum class TrailOpacity(val label: String, val alphaFraction: Float) {
    NORMAL("Normal", 1.0f),
    SOFT("Soft", 0.65f),
    SUBTLE("Transparent", 0.35f)
}
