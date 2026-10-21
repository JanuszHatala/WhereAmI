package janush.tech.whereami

import java.util.Locale

/**
 * Utility for normalizing Polish and international road/street names.
 * Standardizes highway numbers (DK52, DW946, A4, S7), strips house numbers
 * from major traffic corridors, and cleans street prefixes.
 */
object RoadNameNormalizer {

    private val DK_REGEX = Regex("""(?i)^(?:Droga\s+)?Krajow(?:a|ej|ą)\s+(?:nr\s+)?(\d+)(.*)$""")
    private val DK_SHORT_REGEX = Regex("""(?i)^DK\s*(\d+)(.*)$""")

    private val DW_REGEX = Regex("""(?i)^(?:Droga\s+)?Wojew[oó]dzk(?:a|ej|ą)\s+(?:nr\s+)?(\d+)(.*)$""")
    private val DW_SHORT_REGEX = Regex("""(?i)^DW\s*(\d+)(.*)$""")

    private val AUTOSTRADA_REGEX = Regex("""(?i)^(?:Autostrada\s+)?A\s*(\d+)(.*)$""")
    private val EKSPRESOWA_REGEX = Regex("""(?i)^(?:Droga\s+)?Ekspresow(?:a|ej|ą)\s+(?:nr\s+)?S\s*(\d+)(.*)$""")
    private val S_SHORT_REGEX = Regex("""(?i)^S\s*(\d+)(.*)$""")
    private val E_ROUTE_REGEX = Regex("""(?i)^(?:Trasa\s+|Droga\s+)?(?:Europejska\s+|Międzynarodowa\s+)?E\s*(\d+)(.*)$""")

    private val TRAILING_HOUSE_NUM = Regex("""\s+\d+(\s*[a-zA-Z]|/\d+)?$""")

    /**
     * Determines whether a given road name represents a high-speed / national / provincial corridor.
     */
    fun isMajorRoad(name: String?, roadRef: String? = null): Boolean {
        if (!roadRef.isNullOrBlank()) {
            val refTrimmed = roadRef.trim().uppercase(Locale.ROOT)
            if (refTrimmed.matches(Regex("""^(DK|DW|A|S|E)\s*\d+.*""")) || refTrimmed.matches(Regex("""^\d{1,3}$"""))) {
                return true
            }
        }
        if (name.isNullOrBlank()) return false
        val trimmed = name.trim().uppercase(Locale.ROOT)
        return trimmed.matches(Regex("""^(DK|DW|A|S|E)\s*\d+.*""")) ||
                trimmed.matches(Regex("""^\d{1,3}$""")) ||
                trimmed.contains(Regex("""\((DK|DW|A|S|E)\s*\d+.*\)""")) ||
                trimmed.startsWith("DROGA KRAJOWA") ||
                trimmed.startsWith("KRAJOWA") ||
                trimmed.startsWith("DROGA WOJEWÓDZKA") ||
                trimmed.startsWith("WOJEWÓDZKA") ||
                trimmed.startsWith("AUTOSTRADA") ||
                trimmed.startsWith("DROGA EKSPRESOWA") ||
                trimmed.startsWith("EKSPRESOWA") ||
                trimmed.contains("NIEPODLEGŁOŚCI") ||
                trimmed.contains("MONTE CASSINO") ||
                trimmed.contains("ANDERSA") ||
                trimmed.startsWith("ALEJA") ||
                trimmed.startsWith("ALEJE") ||
                trimmed.startsWith("AL.") ||
                trimmed.startsWith("TRASA") ||
                trimmed.contains("OBWODNICA")
    }

    /**
     * At speed on a major corridor, a non-major OSRM snap is usually a parallel or crossing street and is
     * ignored. Not when the OSM way at the fix carries a road number ([osmRoadRef]): then the snap is the
     * corridor's own local name (DK52 through Bujaków is "Bielska"), and rejecting it showed the bare
     * number in every village the corridor passes.
     */
    /**
     * Road number of the reverse-geocoded way. Nominatim's `address` has no `ref`; `namedetails` has the
     * result object's own name and ref, which belong to [road] only when that object is the road itself.
     */
    fun wayRef(road: String?, nameDetails: Pair<String?, String?>?): String? {
        val (name, ref) = nameDetails ?: return null
        if (road.isNullOrBlank() || ref.isNullOrBlank() || name == null) return null
        return ref.trim().takeIf { name.trim().equals(road.trim(), ignoreCase = true) }
    }

    fun shouldShieldMajorCorridor(speedKmh: Float?, isCommittedMajor: Boolean, osrmStreet: String?, osmRoadRef: String?): Boolean =
        speedKmh != null && speedKmh > 35f && isCommittedMajor && osrmStreet != null &&
            !isMajorRoad(osrmStreet, osmRoadRef)

    /**
     * Single line for the locality card / Auto. Never joins a local name with a bare ref via " • "
     * (that produced "Krakowska • 52" instead of "Krakowska (DK52)" or "DK52").
     */
    fun formatStreetDisplay(street: String?, roadRef: String?, countryCode: String? = "PL"): String? {
        val s = street?.trim()?.takeIf { it.isNotEmpty() }
        val r = roadRef?.trim()?.takeIf { it.isNotEmpty() }
        if (s != null && PAREN_HIGHWAY_REF.containsMatchIn(s)) return s
        if (s != null && isHighwayCodeOnly(s)) {
            return normalize(null, r ?: s, null, includeHouseNumber = false, countryCode = countryCode) ?: s
        }
        return normalize(s, r, null, includeHouseNumber = false, countryCode = countryCode)
            ?: s
            ?: normalize(null, r, null, includeHouseNumber = false, countryCode = countryCode)
    }

    /**
     * OSRM `/nearest` often returns only the highway code (`DW948`) on corridors that also have a local
     * OSM name (`Krakowska`). Prefer the local name and keep the code as [rawRef] so normalize yields
     * `Krakowska (DW948)` instead of a bare `DW948`.
     *
     * @return pair of (streetForNormalize, refForNormalize)
     */
    fun coalesceOsrmAndOsmStreet(
        osrmStreet: String?,
        osmStreet: String?,
        osmRoadRef: String?,
        geocoderStreet: String? = null
    ): Pair<String?, String?> {
        val osrm = osrmStreet?.trim()?.takeIf { it.isNotEmpty() }
        val named = sequenceOf(osmStreet, geocoderStreet)
            .mapNotNull { it?.trim()?.takeIf { s -> s.isNotEmpty() && !isHighwayCodeOnly(s) } }
            .firstOrNull()
        val codeFromOsrm = osrm?.takeIf { isHighwayCodeOnly(it) }
        val ref = osmRoadRef?.trim()?.takeIf { it.isNotEmpty() } ?: codeFromOsrm
        return when {
            osrm != null && !isHighwayCodeOnly(osrm) -> osrm to osmRoadRef?.trim()?.takeIf { it.isNotEmpty() }
            named != null -> named to ref
            osrm != null -> osrm to osmRoadRef?.trim()?.takeIf { it.isNotEmpty() }
            else -> null to osmRoadRef?.trim()?.takeIf { it.isNotEmpty() }
        }
    }

    /** True when [incoming] is a bare highway code that must not overwrite a named [existing] cache street. */
    fun shouldPreferExistingStreet(existing: String?, incoming: String?): Boolean {
        if (existing.isNullOrBlank() || isHighwayCodeOnly(existing)) return false
        return incoming.isNullOrBlank() || isHighwayCodeOnly(incoming)
    }

    /** Pulls a canonical highway code from a street line (`DW948` or `Krakowska (DW948)`). */
    fun extractHighwayCode(street: String?): String? {
        if (street.isNullOrBlank()) return null
        val trimmed = street.trim()
        if (isHighwayCodeOnly(trimmed)) {
            return normalize(null, trimmed, null, includeHouseNumber = false) ?: trimmed.uppercase(Locale.ROOT)
        }
        val paren = Regex("""\((DK|DW|A|S|E)\s*(\d+)\)""", RegexOption.IGNORE_CASE).find(trimmed) ?: return null
        return "${paren.groupValues[1].uppercase(Locale.ROOT)}${paren.groupValues[2]}"
    }

    private val POLISH_DK_TOKEN = Regex("""(?i)\bDK\s*(\d+)\b""")
    private val POLISH_DW_TOKEN = Regex("""(?i)\bDW\s*(\d+)\b""")

    /**
     * Rewrites spurious Polish `DK*` / `DW*` tokens for a non-PL [countryCode].
     * Slovakia: 1–2 digit → `I/<n>`, 3-digit → `II/<n>`. Other countries: bare number.
     * No-op in Poland. Used for live normalize leftovers and spatial-cache migration.
     */
    fun sanitizeStreetForCountry(street: String?, countryCode: String?): String? {
        if (street.isNullOrBlank()) return street
        val cc = countryCode?.trim()?.uppercase(Locale.ROOT)
        if (cc.isNullOrBlank() || cc == "PL") return street
        if (!POLISH_DK_TOKEN.containsMatchIn(street) && !POLISH_DW_TOKEN.containsMatchIn(street)) return street

        fun mapNum(num: String): String =
            if (cc == "SK") {
                if (num.length <= 2) "I/$num" else "II/$num"
            } else {
                num
            }

        var out = POLISH_DK_TOKEN.replace(street) { mapNum(it.groupValues[1]) }
        out = POLISH_DW_TOKEN.replace(out) { mapNum(it.groupValues[1]) }
        // Collapse "Name (I/18)" double spaces / empty parens leftovers
        out = out.replace(Regex("""\s{2,}"""), " ").trim()
        return out
    }

    private val PAREN_HIGHWAY_REF = Regex("""\((DK|DW|A|S|E)\s*\d+""", RegexOption.IGNORE_CASE)

    /**
     * Determines whether a given string is strictly a highway code (e.g. DK52, S1, DW946)
     * rather than a named local street running along the corridor (e.g. Aleje świętego Jana Pawła II).
     */
    fun isHighwayCodeOnly(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val trimmed = name.trim().uppercase(Locale.ROOT)
        return trimmed.matches(Regex("""^(DK|DW|A|S|E)\s*\d+.*""")) ||
                trimmed.matches(Regex("""^\d{1,3}$""")) ||
                trimmed.startsWith("DROGA KRAJOWA") ||
                trimmed.startsWith("KRAJOWA") ||
                trimmed.startsWith("DROGA WOJEWÓDZKA") ||
                trimmed.startsWith("WOJEWÓDZKA") ||
                trimmed.startsWith("AUTOSTRADA") ||
                trimmed.startsWith("DROGA EKSPRESOWA") ||
                trimmed.startsWith("EKSPRESOWA")
    }

    /**
     * Determines whether house numbers should be displayed on the main UI.
     * Allowed only when moving at slow speed (< 15 km/h) or stationary,
     * with acceptable GPS horizontal accuracy (<= 20m),
     * on non-major roads.
     */
    fun shouldShowHouseNumber(
        speedKmh: Float?,
        accuracyMeters: Float?,
        isMajorRoad: Boolean
    ): Boolean {
        if (isMajorRoad) return false
        val speed = speedKmh ?: 0f
        val accuracy = accuracyMeters ?: Float.MAX_VALUE
        return speed < 15.0f && accuracy <= 20.0f
    }

    /**
     * Checks if two street names refer to the same street or corridor,
     * ignoring honorifics, prefixes ("ul.", "al."), and house numbers.
     */
    fun streetsMatch(streetA: String?, streetB: String?): Boolean {
        if (streetA.isNullOrBlank() || streetB.isNullOrBlank()) return false
        val cleanA = extractBaseStreet(cleanStreetPrefix(streetA)).lowercase(Locale.ROOT)
        val cleanB = extractBaseStreet(cleanStreetPrefix(streetB)).lowercase(Locale.ROOT)
        return cleanA == cleanB || cleanA.contains(cleanB) || cleanB.contains(cleanA)
    }

    /**
     * Strips house numbers from a given street string if allowHouseNumber is false.
     * Preserves major highway corridors and clean base street names.
     */
    fun sanitizeHouseNumber(street: String?, allowHouseNumber: Boolean): String? {
        if (street.isNullOrBlank()) return street
        if (allowHouseNumber) return street
        if (isMajorRoad(street)) return street
        return extractBaseStreet(street)
    }

    /**
     * Normalizes a raw street name, road reference, and optional house number.
     *
     * Rules:
     * 1. Highway numbers are normalized to canonical short form (e.g. DK52, DW946, A4, S7).
     * 2. Major roads have house numbers strictly stripped.
     * 3. Residential streets have prefixes cleaned ("ulica " -> "ul. ") and retain house numbers
     *    only when includeHouseNumber is explicitly true.
     */
    fun normalize(
        rawStreet: String?,
        rawRef: String? = null,
        houseNumber: String? = null,
        includeHouseNumber: Boolean = false,
        countryCode: String? = "PL"
    ): String? {
        if (rawStreet.isNullOrBlank() && rawRef.isNullOrBlank()) return null

        val isPoland = countryCode.isNullOrBlank() || countryCode.equals("PL", ignoreCase = true)
        val isSlovakia = countryCode?.equals("SK", ignoreCase = true) == true

        var road = rawStreet?.trim() ?: ""
        val ref = rawRef?.trim() ?: ""

        // Check if road itself is a national or provincial highway designation
        var isRoadSelfHighway = false
        var normalizedHighway: String? = null

        when {
            isPoland && DK_REGEX.matches(road) -> {
                val match = DK_REGEX.find(road)!!
                normalizedHighway = "DK${match.groupValues[1]}"
                isRoadSelfHighway = true
            }
            isPoland && DK_SHORT_REGEX.matches(road) -> {
                val match = DK_SHORT_REGEX.find(road)!!
                normalizedHighway = "DK${match.groupValues[1]}"
                isRoadSelfHighway = true
            }
            // Spurious Polish DK/DW labels outside PL (legacy cache / OSM tags) → local form
            !isPoland && DK_SHORT_REGEX.matches(road) -> {
                val num = DK_SHORT_REGEX.find(road)!!.groupValues[1]
                normalizedHighway = sanitizeStreetForCountry("DK$num", countryCode)
                isRoadSelfHighway = true
            }
            isPoland && DW_REGEX.matches(road) -> {
                val match = DW_REGEX.find(road)!!
                normalizedHighway = "DW${match.groupValues[1]}"
                isRoadSelfHighway = true
            }
            isPoland && DW_SHORT_REGEX.matches(road) -> {
                val match = DW_SHORT_REGEX.find(road)!!
                normalizedHighway = "DW${match.groupValues[1]}"
                isRoadSelfHighway = true
            }
            !isPoland && DW_SHORT_REGEX.matches(road) -> {
                val num = DW_SHORT_REGEX.find(road)!!.groupValues[1]
                normalizedHighway = sanitizeStreetForCountry("DW$num", countryCode)
                isRoadSelfHighway = true
            }
            AUTOSTRADA_REGEX.matches(road) -> {
                val match = AUTOSTRADA_REGEX.find(road)!!
                normalizedHighway = "A${match.groupValues[1]}"
                isRoadSelfHighway = true
            }
            EKSPRESOWA_REGEX.matches(road) -> {
                val match = EKSPRESOWA_REGEX.find(road)!!
                normalizedHighway = "S${match.groupValues[1]}"
                isRoadSelfHighway = true
            }
            S_SHORT_REGEX.matches(road) -> {
                val match = S_SHORT_REGEX.find(road)!!
                normalizedHighway = "S${match.groupValues[1]}"
                isRoadSelfHighway = true
            }
            E_ROUTE_REGEX.matches(road) -> {
                val match = E_ROUTE_REGEX.find(road)!!
                normalizedHighway = "E${match.groupValues[1]}"
                isRoadSelfHighway = true
            }
            road.matches(Regex("""^\d{1,2}$""")) -> {
                if (isPoland) {
                    // 1-2 digits in Poland designate DK (e.g. "28" -> DK28, "47" -> DK47)
                    normalizedHighway = "DK$road"
                } else if (isSlovakia) {
                    // 1-2 digits in Slovakia designate 1st class road (Cesta I. triedy, e.g. "18" -> "I/18" or "18")
                    normalizedHighway = "I/$road"
                } else {
                    normalizedHighway = road
                }
                isRoadSelfHighway = true
            }
            road.matches(Regex("""^\d{3}$""")) -> {
                if (isPoland) {
                    // 3 digits in Poland designate DW (e.g. "946" -> DW946)
                    normalizedHighway = "DW$road"
                } else if (isSlovakia) {
                    // 3 digits in Slovakia designate 2nd class road (Cesta II. triedy, e.g. "537" -> "II/537")
                    normalizedHighway = "II/$road"
                } else {
                    normalizedHighway = road
                }
                isRoadSelfHighway = true
            }
            road.matches(Regex("""^(?:I|II|III)/\d+$""")) -> {
                normalizedHighway = road
                isRoadSelfHighway = true
            }
        }

        // If road was not a highway name, check if rawRef is a highway designation (e.g. ref="52", "DK 52", "A4")
        if (normalizedHighway == null && ref.isNotEmpty()) {
            when {
                isPoland && ref.matches(Regex("""(?i)^DK\s*(\d+)$""")) -> {
                    val num = Regex("""(?i)^DK\s*(\d+)$""").find(ref)!!.groupValues[1]
                    normalizedHighway = "DK$num"
                }
                isPoland && ref.matches(Regex("""(?i)^DW\s*(\d+)$""")) -> {
                    val num = Regex("""(?i)^DW\s*(\d+)$""").find(ref)!!.groupValues[1]
                    normalizedHighway = "DW$num"
                }
                ref.matches(Regex("""(?i)^A\s*(\d+)$""")) -> {
                    val num = Regex("""(?i)^A\s*(\d+)$""").find(ref)!!.groupValues[1]
                    normalizedHighway = "A$num"
                }
                ref.matches(Regex("""(?i)^S\s*(\d+)$""")) -> {
                    val num = Regex("""(?i)^S\s*(\d+)$""").find(ref)!!.groupValues[1]
                    normalizedHighway = "S$num"
                }
                ref.matches(Regex("""^(?:I|II|III)/\d+$""")) -> {
                    normalizedHighway = ref
                }
                ref.matches(Regex("""^\d{1,2}$""")) -> {
                    if (isPoland) {
                        normalizedHighway = "DK$ref"
                    } else if (isSlovakia) {
                        normalizedHighway = "I/$ref"
                    } else {
                        normalizedHighway = ref
                    }
                }
                ref.matches(Regex("""^\d{3}$""")) -> {
                    if (isPoland) {
                        normalizedHighway = "DW$ref"
                    } else if (isSlovakia) {
                        normalizedHighway = "II/$ref"
                    } else {
                        normalizedHighway = ref
                    }
                }
                !isPoland && ref.matches(Regex("""(?i)^(DK|DW)\s*(\d+)$""")) -> {
                    // If OSM has a spurious DK/DW tag outside Poland, strip the Polish prefix
                    val match = Regex("""(?i)^(?:DK|DW)\s*(\d+)$""").find(ref)!!
                    val num = match.groupValues[1]
                    normalizedHighway = if (isSlovakia) {
                        if (num.length <= 2) "I/$num" else "II/$num"
                    } else num
                }
            }
        }

        // Special handling for key Polish expressway / national highway urban corridors where road name is used
        if (normalizedHighway == null && road.isNotEmpty()) {
            val upper = road.uppercase(Locale.ROOT)
            if (upper.contains("ŚWIĘTEGO JANA PAWŁA II") || upper.contains("SWIETEGO JANA PAWLA II")) {
                normalizedHighway = "S1"
            }
        }

        // If it's a major highway:
        if (normalizedHighway != null) {
            // If the road was just the highway designation itself (e.g. "DK 52" or "Krajowa 52"), return canonical highway code
            val combined = if (!isRoadSelfHighway && road.isNotEmpty() && !isHighwayCodeOnly(road)) {
                val cleanLocal = cleanStreetPrefix(extractBaseStreet(road))
                "$cleanLocal ($normalizedHighway)"
            } else {
                normalizedHighway
            }
            return sanitizeStreetForCountry(combined, countryCode) ?: combined
        }

        // It's a residential or local street (may still carry a Polish DK/DW token outside PL):
        val baseStreet = extractBaseStreet(road)
        val cleanStreet = cleanStreetPrefix(baseStreet)
        val effectiveHouseNumber = houseNumber?.trim()?.takeIf { it.isNotEmpty() }
            ?: extractHouseNumber(road)

        val local = if (includeHouseNumber && !effectiveHouseNumber.isNullOrBlank()) {
            "$cleanStreet $effectiveHouseNumber"
        } else {
            cleanStreet
        }
        return sanitizeStreetForCountry(local, countryCode) ?: local
    }

    /**
     * Extracts trailing house number if present (e.g. "ul. Zdrojowa 11A" -> "11A").
     */
    fun extractHouseNumber(street: String?): String? {
        if (street.isNullOrBlank()) return null
        val match = TRAILING_HOUSE_NUM.find(street) ?: return null
        return match.value.trim()
    }

    /**
     * Extracts base street name by stripping trailing house numbers (e.g. "ul. Kościuszki 14A" -> "ul. Kościuszki").
     */
    fun extractBaseStreet(street: String?): String {
        if (street.isNullOrBlank()) return ""
        return street.replace(TRAILING_HOUSE_NUM, "").trim()
    }

    /**
     * Cleans common Polish street prefixes into standard abbreviations.
     */
    private fun cleanStreetPrefix(name: String): String {
        var res = name.trim()
        if (res.startsWith("ulica ", ignoreCase = true)) {
            res = "ul. " + res.substring(6).trim()
        } else if (res.startsWith("aleja ", ignoreCase = true)) {
            res = "al. " + res.substring(6).trim()
        } else if (res.startsWith("aleje ", ignoreCase = true)) {
            res = "al. " + res.substring(6).trim()
        } else if (res.startsWith("osiedle ", ignoreCase = true)) {
            res = "os. " + res.substring(8).trim()
        } else if (res.startsWith("plac ", ignoreCase = true)) {
            res = "pl. " + res.substring(5).trim()
        }
        return res
    }

    /**
     * Abbreviates a street name using the modular StreetAbbreviator engine.
     */
    fun shortenStreetName(street: String?, countryCode: String? = null, language: String? = null): String? {
        return StreetAbbreviator.abbreviate(street, countryCode, language)
    }
}

