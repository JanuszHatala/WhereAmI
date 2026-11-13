package online.hatala.whereami

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
            if (refTrimmed.contains(";")) {
                if (refTrimmed.split(";").any { isMajorRoad(null, it) }) return true
            }
            if (refTrimmed.contains(" / ")) {
                if (refTrimmed.split(" / ").any { isMajorRoad(null, it) }) return true
            }
            if (refTrimmed.matches(Regex("""^(DK|DW|A|S|E)\s*\d+.*""")) ||
                refTrimmed.matches(Regex("""^\d{1,3}$""")) ||
                refTrimmed.matches(Regex("""^(I|II|III)/\d+.*"""))
            ) {
                return true
            }
        }
        if (name.isNullOrBlank()) return false
        val trimmed = name.trim().uppercase(Locale.ROOT)
        if (trimmed.contains(";")) {
            if (trimmed.split(";").any { isMajorRoad(it, null) }) return true
        }
        if (trimmed.contains(" / ")) {
            if (trimmed.split(" / ").any { isMajorRoad(it, null) }) return true
        }
        return trimmed.matches(Regex("""^(DK|DW|A|S|E)\s*\d+.*""")) ||
                trimmed.matches(Regex("""^\d{1,3}$""")) ||
                trimmed.matches(Regex("""^(?:I|II|III)/\d+.*""")) ||
                trimmed.contains(Regex("""\((DK|DW|A|S|E)\s*\d+.*\)""")) ||
                trimmed.startsWith("DROGA KRAJOWA") ||
                trimmed.startsWith("KRAJOWA") ||
                trimmed.startsWith("DROGA WOJEWÓDZKA") ||
                trimmed.startsWith("WOJEWÓDZKA") ||
                trimmed.startsWith("AUTOSTRADA") ||
                trimmed.startsWith("DROGA EKSPRESOWA") ||
                trimmed.startsWith("EKSPRESOWA") ||
                trimmed.startsWith("DROGA MIĘDZYNARODOWA") ||
                trimmed.startsWith("TRASA EUROPEJSKA") ||
                trimmed.startsWith("ALEJA") ||
                trimmed.startsWith("ALEJE") ||
                trimmed.startsWith("AL.") ||
                trimmed.startsWith("TRASA") ||
                trimmed.contains("OBWODNICA")
    }

    /**
     * Data-driven highway classification based on standard OpenStreetMap tags.
     * Evaluates highway tags: motorway, trunk, primary, secondary, tertiary (and their links)
     * or explicit national/international highway reference codes.
     */
    fun isMajorHighway(highwayType: String?, roadRef: String? = null): Boolean {
        if (!roadRef.isNullOrBlank()) {
            val refTrimmed = roadRef.trim().uppercase(Locale.ROOT)
            if (refTrimmed.matches(Regex("""^(DK|DW|A|S|E)\s*\d+.*""")) ||
                refTrimmed.matches(Regex("""^\d{1,3}$""")) ||
                refTrimmed.matches(Regex("""^(I|II)/\d+.*"""))
            ) {
                return true
            }
        }
        val ht = highwayType?.trim()?.lowercase(Locale.ROOT)
        if (ht != null) {
            return isMajorHighwayType(ht)
        }
        return false
    }

    /**
     * Determines whether the angle between vehicle trajectory heading and candidate road bearing
     * exceeds 45 degrees relative to the bidirectional axis (crossing/perpendicular road).
     */
    fun isPerpendicularCrossing(vehicleHeadingDeg: Float?, roadBearingDeg: Float?): Boolean {
        if (vehicleHeadingDeg == null || roadBearingDeg == null) return false
        var diff = kotlin.math.abs(vehicleHeadingDeg - roadBearingDeg) % 360f
        if (diff > 180f) diff = 360f - diff
        val lineDiff = if (diff > 90f) 180f - diff else diff
        return lineDiff > 45f
    }

    /**
     * Road number of the reverse-geocoded way. Nominatim's `address` has no `ref`; `namedetails` has the
     * result object's own name and ref, which belong to [road] only when that object is the road itself.
     */
    fun wayRef(road: String?, nameDetails: Pair<String?, String?>?): String? {
        val (name, ref) = nameDetails ?: return null
        if (road.isNullOrBlank() || ref.isNullOrBlank() || name == null) return null
        return ref.trim().takeIf { name.trim().equals(road.trim(), ignoreCase = true) }
    }

    fun shouldShieldMajorCorridor(
        speedKmh: Float?,
        isCommittedMajor: Boolean,
        osrmStreet: String?,
        osmRoadRef: String?,
        osmStreet: String? = null,
        candidateHighwayType: String? = null,
        vehicleHeadingDeg: Float? = null,
        roadBearingDeg: Float? = null
    ): Boolean {
        if (speedKmh == null || speedKmh <= 35f || !isCommittedMajor || osrmStreet == null) return false
        if (isMajorRoad(osrmStreet, null)) return false
        if (isPerpendicularCrossing(vehicleHeadingDeg, roadBearingDeg)) return true

        val isCandidateMajor = isMajorHighway(candidateHighwayType, osmRoadRef)
        if (!isCandidateMajor && candidateHighwayType != null &&
            (candidateHighwayType == "residential" || candidateHighwayType == "service" ||
             candidateHighwayType == "track" || candidateHighwayType == "living_street" ||
             candidateHighwayType == "unclassified" || candidateHighwayType == "path")
        ) {
            return true
        }

        // osmRoadRef comes from Nominatim's osmStreet, NOT from OSRM. It only exempts osrmStreet when
        // osrmStreet is the same road as osmStreet (or osmStreet is absent/bare highway code).
        val osrmMatchesOsm = !osmStreet.isNullOrBlank() && streetsMatch(osrmStreet, osmStreet)
        val refAppliesToOsrm = !osmRoadRef.isNullOrBlank() &&
            (osrmMatchesOsm || osmStreet.isNullOrBlank() || isHighwayCodeOnly(osmStreet))
        return !isMajorRoad(osrmStreet, if (refAppliesToOsrm) osmRoadRef else null)
    }

    /**
     * Single line for the locality card / Auto. Never joins a local name with a bare ref via " • "
     * (that produced "Krakowska • 52" instead of "Krakowska (DK52)" or "DK52").
     */
    fun formatStreetDisplay(
        street: String?,
        roadRef: String?,
        countryCode: String? = null,
        allowHouseNumber: Boolean = false,
        houseNumber: String? = null
    ): String? {
        val s = street?.trim()?.takeIf { it.isNotEmpty() }
        val r = roadRef?.trim()?.takeIf { it.isNotEmpty() }
        if (s == null && r == null) return null
        val extractedH = houseNumber ?: extractHouseNumber(s)
        val highwayCode = extractHighwayCode(s) ?: extractHighwayCode(r)
        val baseStreet = stripParenHighwayRef(extractBaseStreet(s))

        if (baseStreet != null) {
            val refToUse = highwayCode ?: r
            return normalize(
                rawStreet = baseStreet,
                rawRef = refToUse,
                houseNumber = extractedH,
                includeHouseNumber = allowHouseNumber && !extractedH.isNullOrBlank(),
                countryCode = countryCode
            ) ?: s
        }
        if (s != null && isHighwayCodeOnly(s)) {
            return normalize(null, r ?: s, null, includeHouseNumber = false, countryCode = countryCode) ?: s
        }
        return normalize(s, r, extractedH, includeHouseNumber = allowHouseNumber, countryCode = countryCode)
            ?: s
            ?: normalize(null, r, null, includeHouseNumber = false, countryCode = countryCode)
    }

    private val MAJOR_HIGHWAY_TYPES = setOf(
        "motorway", "motorway_link",
        "trunk", "trunk_link",
        "primary", "primary_link",
        "secondary", "secondary_link"
    )

    private val RESIDENTIAL_HIGHWAY_TYPES = setOf(
        "residential", "living_street", "service", "unclassified", "tertiary", "tertiary_link", "track", "footway", "path"
    )

    fun isMajorHighwayType(type: String?): Boolean {
        if (type == null) return false
        return type.trim().lowercase(Locale.ROOT) in MAJOR_HIGHWAY_TYPES
    }

    fun isResidentialHighwayType(type: String?): Boolean {
        if (type == null) return false
        return type.trim().lowercase(Locale.ROOT) in RESIDENTIAL_HIGHWAY_TYPES
    }

    /**
     * OSRM `/nearest` often returns only the highway code (`DW948`) on corridors that also have a local
     * OSM name (`Krakowska`). Prefer the local name; attach the code only when OSM confirms [osmRoadRef]
     * (or when a later bare-code cache coalesce upgrades a committed `DK*`).
     *
     * Purely data-driven: Never glue an OSRM-only code onto an unrelated named way unless OSM explicitly
     * confirms matching roadRef or matching street geometry.
     *
     * @return pair of (streetForNormalize, refForNormalize)
     */
    fun coalesceOsrmAndOsmStreet(
        osrmStreet: String?,
        osmStreet: String?,
        osmRoadRef: String?,
        geocoderStreet: String? = null,
        candidateHighwayType: String? = null
    ): Pair<String?, String?> {
        val osrm = osrmStreet?.trim()?.takeIf { it.isNotEmpty() }
        val named = sequenceOf(osmStreet, geocoderStreet)
            .mapNotNull { it?.trim()?.takeIf { s -> s.isNotEmpty() && !isHighwayCodeOnly(s) } }
            .firstOrNull()
        val osmRef = osmRoadRef?.trim()?.takeIf { it.isNotEmpty() }
        val isOsrmHighway = osrm != null && isHighwayCodeOnly(osrm)
        val isResidential = isResidentialHighwayType(candidateHighwayType)
        val isMajor = isMajorHighwayType(candidateHighwayType)

        return when {
            // 1. If OSM confirmed both a named street and a valid road ref, prefer OSM confirmed name and ref
            // (e.g. OSM has Bielska with ref=52, while OSRM snapped to perpendicular local street Podlesie with no ref)
            !osmStreet.isNullOrBlank() && !isHighwayCodeOnly(osmStreet) && !osmRef.isNullOrBlank() ->
                osmStreet.trim() to osmRef

            // 2. OSRM has a real named street (e.g. "Krakowska", "Stojałowskiego", "Broniewskiego"):
            // Prefer OSRM street because it is filtered by the vehicle's driving trajectory (bearing ±35°).
            // Attach confirmed ref only if candidate is a major corridor and names match or ref is confirmed.
            osrm != null && !isHighwayCodeOnly(osrm) -> {
                val refToAttach = if (!isResidential && osmRef != null && (isMajor || streetsMatch(osrm, osmStreet))) {
                    osmRef
                } else null
                osrm to refToAttach
            }

            // 3. Residential / service / unclassified roads must NEVER carry a highway code
            named != null && isResidential ->
                named to null

            // 4. Named street is present and OSRM is a bare highway code:
            // If OSM confirms major corridor classification, coalesce named street with the OSRM highway code (e.g. "Beskidzka (DW781)")
            named != null && isOsrmHighway && isMajor ->
                named to osrm

            // 5. Named street is present and OSRM is a bare highway code:
            // Prefer the named local street, attach confirmed osmRef only if confirmed by OSM
            named != null && isOsrmHighway ->
                named to osmRef

            // 6. Named street without confirmed highway ref: keep local name, never attach stray highway ref
            named != null && osmRef == null ->
                named to null

            // 7. Fallback
            osrm != null -> osrm to (if (!isResidential) osmRef else null)
            else -> named to (if (!isResidential) osmRef else null)
        }
    }

    /** True when [incoming] is a bare highway code that must not overwrite a named [existing] cache street. */
    fun shouldPreferExistingStreet(existing: String?, incoming: String?): Boolean {
        if (existing.isNullOrBlank() || isHighwayCodeOnly(existing)) return false
        // Residential address with house numbers must NEVER override incoming major road corridor
        if (!extractHouseNumber(existing).isNullOrBlank() && isMajorRoad(incoming)) return false
        return incoming.isNullOrBlank() || isHighwayCodeOnly(incoming)
    }

    /**
     * True when [street] looks like a residential address that must not replace a committed major corridor
     * (house number present, e.g. "Kamieniecka 1"). Plain local names without a house number ("Krakowska")
     * are the corridor's own name and must be coalesced with the highway code instead of shielded away.
     */
    fun isResidentialSideStreet(street: String?): Boolean {
        if (street.isNullOrBlank() || isHighwayCodeOnly(street) || isMajorRoad(street)) return false
        return !extractHouseNumber(street).isNullOrBlank()
    }

    /**
     * When the committed display is a bare highway code (DK52) and the spatial cache has the corridor's
     * local name (Krakowska), prefer `Krakowska (DK52)`.
     *
     * Verification is strictly data-driven: The cached entry MUST confirm connection to this highway
     * corridor via matching roadRef or parenthesized highway code. Never coalesces based on language patterns.
     */
    fun coalesceBareCodeWithCachedName(
        committedStreet: String?,
        committedRoadRef: String?,
        cachedStreet: String?,
        cachedRoadRef: String?
    ): Pair<String?, String?>? {
        val committedCode = when {
            isHighwayCodeOnly(committedStreet) -> extractHighwayCode(committedStreet)
            isHighwayCodeOnly(committedRoadRef) -> extractHighwayCode(committedRoadRef)
            else -> null
        } ?: return null
        val cached = cachedStreet?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (isHighwayCodeOnly(cached) || isResidentialSideStreet(cached)) return null

        val normalizedCommitted = committedCode.replace(" ", "").uppercase(Locale.ROOT)
        val cachedRefNormalized = cachedRoadRef?.trim()?.replace(" ", "")?.uppercase(Locale.ROOT)
        val hasMatchingRef = cachedRefNormalized != null && (
            cachedRefNormalized == normalizedCommitted ||
            cachedRefNormalized == extractHighwayCode(normalizedCommitted)?.uppercase(Locale.ROOT) ||
            normalizedCommitted.endsWith(cachedRefNormalized) ||
            cachedRefNormalized.endsWith(normalizedCommitted)
        )
        val hasParenRef = cached.contains("($committedCode)", ignoreCase = true) ||
            (extractHighwayCode(committedCode)?.let { cached.contains("($it)", ignoreCase = true) } == true)

        // Strict verification: cached record MUST explicitly confirm connection to this highway code
        if (!hasMatchingRef && !hasParenRef) return null

        val local = stripParenHighwayRef(extractBaseStreet(cached))?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null

        val ref = cachedRoadRef?.trim()?.takeIf { it.isNotEmpty() } ?: committedCode
        return local to ref
    }

    /** Removes a trailing `(DK52)` / `(S1)` annotation so normalize does not double-wrap. */
    fun stripParenHighwayRef(street: String?): String? {
        if (street.isNullOrBlank()) return street
        return street.replace(PAREN_HIGHWAY_REF_FULL, "").trim().ifEmpty { null }
    }

    /** Collapse `Name (DK52) (DK52)` / repeated highway paren groups into a single annotation. */
    fun dedupeParenHighwayRef(street: String?): String? {
        if (street.isNullOrBlank()) return street
        var out = street.trim()
        val matches = PAREN_HIGHWAY_REF_FULL.findAll(out).toList()
        if (matches.size <= 1) return out
        val code = matches.last().value
        out = out.replace(PAREN_HIGHWAY_REF_FULL, "").trim()
        return "$out $code".replace(Regex("""\s{2,}"""), " ").trim()
    }

    /** Pulls a canonical highway code from a street line (`DW948`, `Krakowska (DW948)`, or `(DK44 / DW933)`). */
    fun extractHighwayCode(street: String?, countryCode: String? = null): String? {
        if (street.isNullOrBlank()) return null
        val trimmed = street.trim()
        if (isHighwayCodeOnly(trimmed)) {
            return normalizeHighwayRef(trimmed, countryCode) ?: trimmed.uppercase(Locale.ROOT)
        }
        val multiParen = Regex("""\(((?:DK|DW|A|S|E|I|II|III)\s*\d+[\w/ ]*(?:\([^)]*\))?)\)""", RegexOption.IGNORE_CASE).find(trimmed)
        if (multiParen != null) {
            return normalizeHighwayRef(multiParen.groupValues[1], countryCode) ?: multiParen.groupValues[1]
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
    private val PAREN_HIGHWAY_REF_FULL = Regex("""\s*\((?:(?:DK|DW|A|S|E|I|II|III)\s*\d+[\w/ ]*(?:\([^)]*\))?)\)""", RegexOption.IGNORE_CASE)

    /**
     * Determines whether a given string is strictly a highway code (e.g. DK52, S1, DW946)
     * rather than a named local street running along the corridor (e.g. Aleje świętego Jana Pawła II).
     */
    fun isHighwayCodeOnly(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val trimmed = name.trim().uppercase(Locale.ROOT)
        if (trimmed.contains(";")) {
            val parts = trimmed.split(";").map { it.trim() }.filter { it.isNotEmpty() }
            return parts.isNotEmpty() && parts.all { isHighwayCodeOnly(it) }
        }
        if (trimmed.contains(" / ")) {
            val parts = trimmed.split(" / ").map { it.trim() }.filter { it.isNotEmpty() }
            return parts.isNotEmpty() && parts.all { isHighwayCodeOnly(it) }
        }
        if (trimmed.matches(Regex("""^[A-Z0-9/ ]+\s*\([A-Z0-9/ ]+\)$"""))) {
            val paren = Regex("""^([A-Z0-9/ ]+)\s*\(([A-Z0-9/ ]+)\)$""").find(trimmed)
            if (paren != null) {
                val part1 = paren.groupValues[1].trim()
                val part2 = paren.groupValues[2].trim()
                if (isHighwayCodeOnly(part1) && isHighwayCodeOnly(part2)) return true
            }
        }
        return trimmed.matches(Regex("""^(DK|DW|A|S|E)\s*\d+.*""")) ||
                trimmed.matches(Regex("""^\d{1,3}$""")) ||
                trimmed.matches(Regex("""^(?:I|II|III)/\d+$""")) ||
                trimmed.startsWith("DROGA KRAJOWA") ||
                trimmed.startsWith("KRAJOWA") ||
                trimmed.startsWith("DROGA WOJEWÓDZKA") ||
                trimmed.startsWith("WOJEWÓDZKA") ||
                trimmed.startsWith("AUTOSTRADA") ||
                trimmed.startsWith("DROGA EKSPRESOWA") ||
                trimmed.startsWith("EKSPRESOWA") ||
                trimmed.startsWith("DROGA MIĘDZYNARODOWA") ||
                trimmed.startsWith("TRASA EUROPEJSKA")
    }

    /**
     * Determines whether house numbers should be displayed on the main UI.
     * Allowed when moving at slow speed (< 15 km/h) or stationary,
     * with acceptable GPS horizontal accuracy (<= 40m stationary, <= 25m moving).
     * Bare highways (e.g. A4, S7, DK52 without a named street) are suppressed,
     * but named urban corridors (e.g. Bielska, Krakowska carrying DK52) DO show house numbers when stopped/slow.
     */
    fun shouldShowHouseNumber(
        speedKmh: Float?,
        accuracyMeters: Float?,
        isMajorRoad: Boolean,
        isStationary: Boolean = false,
        hasNamedLocalStreet: Boolean = false
    ): Boolean {
        if (isMajorRoad && !hasNamedLocalStreet) return false
        val speed = speedKmh ?: 0f
        val accuracy = accuracyMeters ?: 999.0f
        val maxAcc = when {
            isStationary || speed < 1.0f -> 40.0f
            speed < 5.0f -> 35.0f
            speed < 15.0f -> 25.0f
            else -> 0.0f
        }
        return speed < 15.0f && accuracy <= maxAcc
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
        if (street.isNullOrBlank() || allowHouseNumber) return street
        if (extractHouseNumber(street).isNullOrBlank()) return street
        val highwayCode = extractHighwayCode(street)
        val base = cleanStreetPrefix(extractBaseStreet(street))
        return when {
            highwayCode != null -> "$base ($highwayCode)"
            else -> base
        }
    }

    /**
     * Normalizes a single highway token (e.g. "DK 52" -> "DK52", "44" in PL -> "DK44", "A4" -> "A4", "18" in SK -> "I/18").
     */
    fun normalizeSingleHighwayCode(rawToken: String?, countryCode: String? = null): String? {
        if (rawToken.isNullOrBlank()) return null
        val token = rawToken.trim()
        val isPoland = countryCode.equals("PL", ignoreCase = true)
        val isNonPoland = !countryCode.isNullOrBlank() && !isPoland
        val isSlovakia = countryCode?.equals("SK", ignoreCase = true) == true

        return when {
            !isNonPoland && DK_REGEX.matches(token) -> "DK${DK_REGEX.find(token)!!.groupValues[1]}"
            !isNonPoland && DK_SHORT_REGEX.matches(token) -> "DK${DK_SHORT_REGEX.find(token)!!.groupValues[1]}"
            isNonPoland && DK_SHORT_REGEX.matches(token) -> {
                val num = DK_SHORT_REGEX.find(token)!!.groupValues[1]
                sanitizeStreetForCountry("DK$num", countryCode)
            }
            !isNonPoland && DW_REGEX.matches(token) -> "DW${DW_REGEX.find(token)!!.groupValues[1]}"
            !isNonPoland && DW_SHORT_REGEX.matches(token) -> "DW${DW_SHORT_REGEX.find(token)!!.groupValues[1]}"
            isNonPoland && DW_SHORT_REGEX.matches(token) -> {
                val num = DW_SHORT_REGEX.find(token)!!.groupValues[1]
                sanitizeStreetForCountry("DW$num", countryCode)
            }
            AUTOSTRADA_REGEX.matches(token) -> "A${AUTOSTRADA_REGEX.find(token)!!.groupValues[1]}"
            EKSPRESOWA_REGEX.matches(token) -> "S${EKSPRESOWA_REGEX.find(token)!!.groupValues[1]}"
            S_SHORT_REGEX.matches(token) -> "S${S_SHORT_REGEX.find(token)!!.groupValues[1]}"
            E_ROUTE_REGEX.matches(token) -> "E${E_ROUTE_REGEX.find(token)!!.groupValues[1]}"
            token.matches(Regex("""^(?:I|II|III)/\d+$""")) -> token
            token.matches(Regex("""^\d{1,2}$""")) -> {
                if (isPoland) "DK$token"
                else if (isSlovakia) "I/$token"
                else token
            }
            token.matches(Regex("""^\d{3}$""")) -> {
                if (isPoland) "DW$token"
                else if (isSlovakia) "II/$token"
                else token
            }
            isNonPoland && token.matches(Regex("""(?i)^(DK|DW)\s*(\d+)$""")) -> {
                val match = Regex("""(?i)^(?:DK|DW)\s*(\d+)$""").find(token)!!
                val num = match.groupValues[1]
                if (isSlovakia) {
                    if (num.length <= 2) "I/$num" else "II/$num"
                } else num
            }
            else -> null
        }
    }

    /**
     * Normalizes a potentially multi-reference highway designation string
     * (e.g. "44;933" -> "DK44 / DW933", "A4;E40" -> "A4 (E40)", "DK44;DW933" -> "DK44 / DW933").
     */
    fun normalizeHighwayRef(rawRef: String?, countryCode: String? = null): String? {
        if (rawRef.isNullOrBlank()) return null
        val trimmed = rawRef.trim()
        val tokens = if (trimmed.contains(";")) {
            trimmed.split(";").map { it.trim() }.filter { it.isNotEmpty() }
        } else if (trimmed.contains(" / ")) {
            trimmed.split(" / ").map { it.trim() }.filter { it.isNotEmpty() }
        } else {
            listOf(trimmed)
        }
        val normalized = tokens.mapNotNull { normalizeSingleHighwayCode(it, countryCode) }.distinct()
        if (normalized.isEmpty()) return null
        if (normalized.size == 1) return normalized[0]

        // Split European routes (E*) vs National/Motorway routes
        val eRoutes = normalized.filter { it.startsWith("E") }
        val nationalRoutes = normalized.filter { !it.startsWith("E") }

        return when {
            nationalRoutes.isNotEmpty() && eRoutes.isNotEmpty() -> {
                val nat = nationalRoutes.joinToString(" / ")
                val eur = eRoutes.joinToString(" / ")
                "$nat ($eur)"
            }
            else -> normalized.joinToString(" / ")
        }
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
        countryCode: String? = null
    ): String? {
        if (rawStreet.isNullOrBlank() && rawRef.isNullOrBlank()) return null

        // Strip an existing `(DK52)` annotation before re-normalizing so we never emit
        // `Krakowska (DK52) (DK52)` when street already carries the code and ref is repeated.
        var road = stripParenHighwayRef(rawStreet?.trim()) ?: ""
        val ref = rawRef?.trim() ?: ""

        // Check if road itself is a national or provincial highway designation
        var isRoadSelfHighway = false
        var normalizedHighway: String? = null

        val roadAsHighway = normalizeHighwayRef(road, countryCode)
        if (roadAsHighway != null && (isHighwayCodeOnly(road) || road.contains(";") || normalizeSingleHighwayCode(road, countryCode) != null)) {
            normalizedHighway = roadAsHighway
            isRoadSelfHighway = true
        } else if (ref.isNotEmpty()) {
            normalizedHighway = normalizeHighwayRef(ref, countryCode)
        }

        // Extract house number if available
        val effectiveHouseNumber = houseNumber?.trim()?.takeIf { it.isNotEmpty() }
            ?: extractHouseNumber(road)

        // If it's a major highway:
        if (normalizedHighway != null) {
            // If the road was just the highway designation itself (e.g. "DK 52" or "Krajowa 52"), return canonical highway code
            val shouldAttachRef = !isRoadSelfHighway && road.isNotEmpty() && !isHighwayCodeOnly(road)
            val combined = if (shouldAttachRef) {
                val cleanLocal = cleanStreetPrefix(extractBaseStreet(road), countryCode)
                if (includeHouseNumber && !effectiveHouseNumber.isNullOrBlank()) {
                    "$cleanLocal $effectiveHouseNumber ($normalizedHighway)"
                } else {
                    "$cleanLocal ($normalizedHighway)"
                }
            } else {
                normalizedHighway
            }
            return sanitizeStreetForCountry(combined, countryCode) ?: combined
        }

        // It's a residential or local street (may still carry a Polish DK/DW token outside PL):
        val baseStreet = extractBaseStreet(road)
        val cleanStreet = cleanStreetPrefix(baseStreet, countryCode)

        val local = if (includeHouseNumber && !effectiveHouseNumber.isNullOrBlank()) {
            "$cleanStreet $effectiveHouseNumber"
        } else {
            cleanStreet
        }
        return sanitizeStreetForCountry(local, countryCode) ?: local
    }

    /**
     * Extracts trailing house number if present (e.g. "ul. Zdrojowa 11A" -> "11A", "Krakowska 242 (DK52)" -> "242").
     */
    fun extractHouseNumber(street: String?): String? {
        if (street.isNullOrBlank()) return null
        val stripped = stripParenHighwayRef(street) ?: return null
        val match = TRAILING_HOUSE_NUM.find(stripped) ?: return null
        return match.value.trim()
    }

    /**
     * Extracts base street name by stripping trailing house numbers and parenthesized highway codes
     * (e.g. "ul. Kościuszki 14A" -> "ul. Kościuszki", "Krakowska 242 (DK52)" -> "Krakowska").
     */
    fun extractBaseStreet(street: String?): String {
        if (street.isNullOrBlank()) return ""
        val stripped = stripParenHighwayRef(street) ?: ""
        return stripped.replace(TRAILING_HOUSE_NUM, "").trim()
    }

    /**
     * Cleans common Polish street prefixes into standard abbreviations (strictly gated on PL country code).
     */
    private fun cleanStreetPrefix(name: String, countryCode: String? = null): String {
        var res = name.trim()
        if (!countryCode.equals("PL", ignoreCase = true)) return res
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

