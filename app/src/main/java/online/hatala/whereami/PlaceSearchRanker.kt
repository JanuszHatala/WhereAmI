package online.hatala.whereami

/**
 * Client-side near-me ranking for place search.
 * Soft bias only: far results stay in the list, just lower when the query is short.
 * Name matching is case- and diacritic-insensitive via [SearchNormalizer].
 */
object PlaceSearchRanker {

    const val DEFAULT_MAX_RESULTS = 12

    data class Candidate(
        val item: SearchResultItem,
        val placeType: String? = null,
        val importance: Double = 0.0,
        val isSaved: Boolean = false,
        val isRecent: Boolean = false
    )

    /**
     * Rank [candidates] for [query] relative to [originLat]/[originLon].
     * Never drops items solely for distance; only reorders and caps.
     */
    fun rank(
        query: String,
        candidates: List<Candidate>,
        originLat: Double?,
        originLon: Double?,
        countryCode: String? = null,
        maxResults: Int = DEFAULT_MAX_RESULTS
    ): List<SearchResultItem> {
        if (candidates.isEmpty()) return emptyList()
        val trimmed = query.trim()
        val queryLen = SearchNormalizer.normalize(trimmed).length
        val originCc = countryCode?.lowercase()?.takeIf { it.isNotBlank() }

        val scored = candidates.map { c ->
            val dist = if (originLat != null && originLon != null) {
                SearchHelper.computeDistanceMeters(
                    originLat, originLon,
                    c.item.geoPoint.latitude, c.item.geoPoint.longitude
                )
            } else {
                null
            }
            val score = score(
                query = trimmed,
                queryLen = queryLen,
                title = c.item.title,
                subtitle = c.item.subtitle,
                placeType = c.placeType ?: c.item.placeType,
                importance = c.importance.takeIf { it > 0 } ?: c.item.importance,
                distanceMeters = dist,
                resultCountryCode = c.item.countryCode,
                originCountryCode = originCc,
                isSaved = c.isSaved,
                isRecent = c.isRecent
            )
            Triple(score, dist, c)
        }

        return scored
            .sortedWith(
                compareByDescending<Triple<Double, Float?, Candidate>> { it.first }
                    .thenBy { it.second ?: Float.MAX_VALUE }
            )
            .distinctBy {
                String.format(
                    java.util.Locale.US,
                    "%.5f,%.5f",
                    it.third.item.geoPoint.latitude,
                    it.third.item.geoPoint.longitude
                )
            }
            .take(maxResults)
            .map { (score, dist, c) ->
                c.item.copy(
                    distanceMeters = dist,
                    placeType = c.placeType ?: c.item.placeType,
                    importance = c.importance.takeIf { it > 0 } ?: c.item.importance,
                    rankScore = score
                )
            }
    }

    fun score(
        query: String,
        queryLen: Int,
        title: String,
        subtitle: String,
        placeType: String?,
        importance: Double,
        distanceMeters: Float?,
        resultCountryCode: String?,
        originCountryCode: String?,
        isSaved: Boolean,
        isRecent: Boolean
    ): Double {
        val distanceWeight = when {
            queryLen <= 2 -> 0.55
            queryLen <= 3 -> 0.45
            queryLen <= 5 -> 0.30
            else -> 0.18
        }
        val nameWeight = 1.0 - distanceWeight

        val nameScore = nameMatchScore(query, title, subtitle)
        var total =
            distanceWeight * distanceScore(distanceMeters, queryLen) +
                nameWeight * nameScore

        total += typeBoost(placeType, queryLen)
        total += (importance.coerceIn(0.0, 1.0) * 0.08)
        // Exact / whole-token hits must beat nearby prefix siblings (Oslo > Oslońska).
        total += exactNameBonus(query, title)

        val resultCc = resultCountryCode?.lowercase()
        if (!originCountryCode.isNullOrBlank() && resultCc == originCountryCode) {
            total += 0.12
        }
        if (isSaved) total += 0.55
        if (isRecent) total += 0.35

        return total
    }

    /** Extra lift when the query equals the title or a whole title token. */
    fun exactNameBonus(query: String, title: String): Double {
        val q = SearchNormalizer.normalize(query)
        if (q.isBlank()) return 0.0
        val t = SearchNormalizer.normalize(title)
        if (t == q) return 0.50
        val tokens = t.split(Regex("[\\s,;\\-_/]+")).filter { it.isNotBlank() }
        if (tokens.any { it == q }) return 0.42
        return 0.0
    }

    /** Higher is better. Short queries compress far distances more aggressively. */
    fun distanceScore(distanceMeters: Float?, queryLen: Int): Double {
        if (distanceMeters == null) return 0.35
        val km = (distanceMeters / 1000.0).coerceAtLeast(0.0)
        val scaleKm = when {
            queryLen <= 2 -> 12.0
            queryLen <= 3 -> 20.0
            queryLen <= 5 -> 45.0
            else -> 90.0
        }
        return 1.0 / (1.0 + km / scaleKm)
    }

    /**
     * Case- and diacritic-insensitive name quality.
     * `an` / `ą` both match Andrychów after [SearchNormalizer].
     */
    fun nameMatchScore(query: String, title: String, subtitle: String = ""): Double {
        val q = SearchNormalizer.normalize(query)
        if (q.isBlank()) return 0.0
        val t = SearchNormalizer.normalize(title)
        val s = SearchNormalizer.normalize(subtitle)
        if (t.isEmpty() && s.isEmpty()) return 0.0

        if (t == q) return 1.0
        if (t.startsWith(q)) return 0.95

        val titleTokens = t.split(Regex("[\\s,;\\-_/]+")).filter { it.isNotBlank() }
        if (titleTokens.any { it.startsWith(q) }) return 0.88
        if (titleTokens.any { it.contains(q) }) return 0.70

        if (t.contains(q)) return 0.55
        if (s.contains(q)) return 0.40
        // Photon already returned the hit; keep a floor so far-but-relevant results survive.
        return 0.15
    }

    fun typeBoost(placeType: String?, queryLen: Int): Double {
        if (placeType.isNullOrBlank()) return 0.0
        val t = placeType.lowercase()
        val shortQuery = queryLen <= 4
        return when {
            t in listOf("city", "town", "village", "municipality", "borough", "suburb") ->
                if (shortQuery) 0.28 else 0.12
            t in listOf("hamlet", "locality", "neighbourhood", "neighborhood") ->
                if (shortQuery) 0.22 else 0.10
            t in listOf("street", "highway", "road", "residential", "pedestrian", "path") ->
                if (shortQuery) 0.24 else 0.14
            t in listOf("house", "building", "address") -> 0.10
            t == "saved" || t == "recent" -> 0.20
            else -> if (shortQuery) 0.0 else 0.05
        }
    }

    fun formatDistance(distanceMeters: Float?): String? {
        if (distanceMeters == null) return null
        return if (distanceMeters < 1000f) {
            "${distanceMeters.toInt()} m"
        } else if (distanceMeters < 10_000f) {
            String.format(java.util.Locale.US, "%.1f km", distanceMeters / 1000f)
        } else {
            "${(distanceMeters / 1000f).toInt()} km"
        }
    }
}
