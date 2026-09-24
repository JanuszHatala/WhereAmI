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

    private val TRAILING_HOUSE_NUM = Regex("""\s+\d+([a-zA-Z]|/\d+)?$""")

    /**
     * Determines whether a given road name represents a high-speed / national / provincial corridor.
     */
    fun isMajorRoad(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val trimmed = name.trim().uppercase(Locale.ROOT)
        return trimmed.matches(Regex("""^(DK|DW|A|S|E)\s*\d+.*""")) ||
                trimmed.contains(Regex("""\((DK|DW|A|S|E)\s*\d+.*\)""")) ||
                trimmed.startsWith("DROGA KRAJOWA") ||
                trimmed.startsWith("KRAJOWA") ||
                trimmed.startsWith("DROGA WOJEWÓDZKA") ||
                trimmed.startsWith("WOJEWÓDZKA") ||
                trimmed.startsWith("AUTOSTRADA") ||
                trimmed.startsWith("DROGA EKSPRESOWA") ||
                trimmed.startsWith("EKSPRESOWA")
    }

    /**
     * Normalizes a raw street name, road reference, and optional house number.
     *
     * Rules:
     * 1. Highway numbers are normalized to canonical short form (e.g. DK52, DW946, A4, S7).
     * 2. Major roads have house numbers strictly stripped.
     * 3. Residential streets have prefixes cleaned ("ulica " -> "ul. ") and retain house numbers.
     */
    fun normalize(
        rawStreet: String?,
        rawRef: String? = null,
        houseNumber: String? = null
    ): String? {
        if (rawStreet.isNullOrBlank() && rawRef.isNullOrBlank()) return null

        var road = rawStreet?.trim() ?: ""
        val ref = rawRef?.trim() ?: ""

        // Check if road itself is a national or provincial highway designation
        var isRoadSelfHighway = false
        var normalizedHighway: String? = null

        when {
            DK_REGEX.matches(road) -> {
                val match = DK_REGEX.find(road)!!
                normalizedHighway = "DK${match.groupValues[1]}"
                isRoadSelfHighway = true
            }
            DK_SHORT_REGEX.matches(road) -> {
                val match = DK_SHORT_REGEX.find(road)!!
                normalizedHighway = "DK${match.groupValues[1]}"
                isRoadSelfHighway = true
            }
            DW_REGEX.matches(road) -> {
                val match = DW_REGEX.find(road)!!
                normalizedHighway = "DW${match.groupValues[1]}"
                isRoadSelfHighway = true
            }
            DW_SHORT_REGEX.matches(road) -> {
                val match = DW_SHORT_REGEX.find(road)!!
                normalizedHighway = "DW${match.groupValues[1]}"
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
        }

        // If road was not a highway name, check if rawRef is a highway designation (e.g. ref="52", "DK 52", "A4")
        if (normalizedHighway == null && ref.isNotEmpty()) {
            when {
                ref.matches(Regex("""(?i)^DK\s*(\d+)$""")) -> {
                    val num = Regex("""(?i)^DK\s*(\d+)$""").find(ref)!!.groupValues[1]
                    normalizedHighway = "DK$num"
                }
                ref.matches(Regex("""(?i)^DW\s*(\d+)$""")) -> {
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
                ref.matches(Regex("""^\d{1,2}$""")) -> {
                    // 1-2 digits in Poland designate DK
                    normalizedHighway = "DK$ref"
                }
                ref.matches(Regex("""^\d{3}$""")) -> {
                    // 3 digits in Poland designate DW
                    normalizedHighway = "DW$ref"
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
            return if (!isRoadSelfHighway && road.isNotEmpty() && !isMajorRoad(road)) {
                val cleanLocal = cleanStreetPrefix(extractBaseStreet(road))
                "$cleanLocal ($normalizedHighway)"
            } else {
                normalizedHighway
            }
        }

        // It's a residential or local street:
        // 1. Strip redundant house number from base if we have an explicit houseNumber
        val cleanBase = cleanStreetPrefix(road)
        val cleanHouse = houseNumber?.trim()

        return if (!cleanHouse.isNullOrEmpty() && !cleanBase.endsWith(cleanHouse)) {
            // Only append if cleanBase doesn't already have a house number
            if (TRAILING_HOUSE_NUM.containsMatchIn(cleanBase)) {
                cleanBase
            } else {
                "$cleanBase $cleanHouse"
            }
        } else {
            cleanBase
        }
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
}
