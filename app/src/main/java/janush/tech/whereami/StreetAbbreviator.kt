package janush.tech.whereami

import java.util.Locale

/**
 * Modular, locale-aware street name abbreviator.
 * Shortens honorifics, titles, and given names for compact display surfaces
 * (e.g. Android Auto, car head units, small smartwatch or compact mobile screens)
 * without corrupting institutional, historical, or non-person street names.
 */
object StreetAbbreviator {

    // ── Polish Rule Set ──────────────────────────────────────────────────────────

    private val PL_TITLES: Map<String, String> = mapOf(
        "generała" to "Gen.",
        "generał" to "Gen.",
        "gen." to "Gen.",
        "kardynała" to "Kard.",
        "kardynał" to "Kard.",
        "kard." to "Kard.",
        "księdza" to "Ks.",
        "ksiądz" to "Ks.",
        "ks." to "Ks.",
        "biskupa" to "Bp.",
        "biskup" to "Bp.",
        "bp." to "Bp.",
        "arcybiskupa" to "Abp.",
        "arcybiskup" to "Abp.",
        "abp." to "Abp.",
        "świętego" to "Św.",
        "świętej" to "Św.",
        "świętych" to "Św.",
        "święty" to "Św.",
        "św." to "Św.",
        "pułkownika" to "Płk.",
        "pułkownik" to "Płk.",
        "płk." to "Płk.",
        "majora" to "Mjr.",
        "major" to "Mjr.",
        "mjr." to "Mjr.",
        "kapitana" to "Kpt.",
        "kapitan" to "Kpt.",
        "kpt." to "Kpt.",
        "porucznika" to "Por.",
        "porucznik" to "Por.",
        "por." to "Por.",
        "marszałka" to "Marsz.",
        "marszałek" to "Marsz.",
        "marsz." to "Marsz.",
        "profesora" to "Prof.",
        "profesor" to "Prof.",
        "prof." to "Prof.",
        "doktora" to "Dr.",
        "doktor" to "Dr.",
        "dr." to "Dr.",
        "dr" to "Dr.",
        "prezydenta" to "Prez.",
        "prezydent" to "Prez."
    )

    // Curated set of common Polish given names in nominative and genitive forms.
    // Explicitly avoids common institutional / historical words (e.g. Wojska, Armii, Powstańców, Bohaterów).
    private val PL_GIVEN_NAMES: Set<String> = setOf(
        "adam", "adama",
        "aleksander", "aleksandra",
        "andrzej", "andrzeja",
        "antoni", "antoniego",
        "artur", "artura",
        "bartłomiej", "bartłomieja",
        "bartosz", "bartosza",
        "bogdan", "bogdana",
        "bolesław", "bolesława",
        "bronisław", "bronisława",
        "czesław", "czesława",
        "dariusz", "dariusza",
        "edward", "edwarda",
        "emil", "emila",
        "feliks", "feliksa",
        "franciszek", "franciszka",
        "grzegorz", "grzegorza",
        "henryk", "henryka",
        "ignacy", "ignacego",
        "jacek", "jacka",
        "jakub", "jakuba",
        "jan", "jana",
        "janusz", "janusza",
        "jarosław", "jarosława",
        "jerzy", "jerzego",
        "józef", "józefa",
        "juliusz", "juliusza",
        "kamil", "kamila",
        "karol", "karola",
        "kazimierz", "kazimierza",
        "klemens", "klemensa",
        "krzysztof", "krzysztofa",
        "lech", "lecha",
        "leszek", "leszka",
        "ludwik", "ludwika",
        "łukasz", "łukasza",
        "maciej", "macieja",
        "marcin", "marcina",
        "marek", "marka",
        "marian", "mariana",
        "mariusz", "mariusza",
        "mateusz", "mateusza",
        "michał", "michała",
        "mieczysław", "mieczysława",
        "mikołaj", "mikołaja",
        "mirosław", "mirosława",
        "norbert", "norberta",
        "paweł", "pawła",
        "piotr", "piotra",
        "rafał", "rafała",
        "robert", "roberta",
        "roman", "romana",
        "ryszard", "ryszarda",
        "seweryn", "seweryna",
        "stanisław", "stanisława",
        "stefan", "stefana",
        "tadeusz", "tadeusza",
        "tomasz", "tomasza",
        "wacław", "wacława",
        "waldemar", "waldemara",
        "wiesław", "wiesława",
        "wincenty", "wincentego",
        "witold", "witolda",
        "władysław", "władysława",
        "wojciech", "wojciecha",
        "zbigniew", "zbigniewa",
        "zdzisław", "zdzisława",
        "zygmunt", "zygmunta",
        // Common female names
        "agnieszka", "agnieszki",
        "aleksandra", "aleksandry",
        "alicja", "alicji",
        "anna", "anny",
        "barbara", "barbary",
        "beata", "beaty",
        "danuta", "danuty",
        "dorota", "doroty",
        "elżbieta", "elżbiety",
        "emilia", "emilii",
        "ewa", "ewy",
        "grażyna", "grażyny",
        "halina", "haliny",
        "helena", "heleny",
        "irena", "ireny",
        "jadwiga", "jadwigi",
        "janina", "janiny",
        "joanna", "joanny",
        "karolina", "karoliny",
        "katarzyna", "katarzyny",
        "klaudia", "klaudii",
        "krystyna", "krystyny",
        "lucja", "lucji",
        "lucyna", "lucyny",
        "magdalena", "magdaleny",
        "małgorzata", "małgorzaty",
        "maria", "marii",
        "marianna", "marianny",
        "monika", "moniki",
        "natalia", "natalii",
        "patrycja", "patrycji",
        "paulina", "pauliny",
        "stanisława", "stanisławy",
        "stefania", "stefanii",
        "teresa", "teresy",
        "urszula", "urszuli",
        "wanda", "wandy",
        "weronika", "weroniki",
        "wiesława", "wiesławy",
        "wiktoria", "wiktorii",
        "zofia", "zofii"
    )

    // ── English Rule Set ─────────────────────────────────────────────────────────

    private val EN_SUFFIXES: Map<String, String> = mapOf(
        "street" to "St",
        "avenue" to "Ave",
        "road" to "Rd",
        "boulevard" to "Blvd",
        "drive" to "Dr",
        "lane" to "Ln",
        "court" to "Ct",
        "circle" to "Cir",
        "place" to "Pl",
        "terrace" to "Ter",
        "highway" to "Hwy",
        "parkway" to "Pkwy"
    )

    private val EN_TITLES: Map<String, String> = mapOf(
        "saint" to "St.",
        "doctor" to "Dr.",
        "general" to "Gen.",
        "captain" to "Cpt.",
        "colonel" to "Col.",
        "president" to "Pres."
    )

    // ── German Rule Set ──────────────────────────────────────────────────────────

    private val DE_SUFFIX_MAP: List<Pair<Regex, String>> = listOf(
        Regex("""(?i)straße$""") to "str.",
        Regex("""(?i)strasse$""") to "str.",
        Regex("""(?i)platz$""") to "pl.",
        Regex("""(?i)gasse$""") to "g."
    )

    /**
     * Abbreviates a given street name based on the specified country code or language.
     * Preserves major highway codes (e.g. DK52, DW946, A4, S7, E77) and street suffixes.
     */
    fun abbreviate(
        street: String?,
        countryCode: String? = null,
        language: String? = null
    ): String? {
        if (street.isNullOrBlank()) return street

        val trimmed = street.trim()

        // If it's a pure highway code or designation (e.g. "DK52", "A4", "S7"), return as is
        if (RoadNameNormalizer.isMajorRoad(trimmed) && !trimmed.contains("(")) {
            return trimmed
        }

        // If it contains a corridor annotation (e.g. "ul. Krakowska (DK52)"),
        // abbreviate the street portion while preserving the corridor code
        val corridorMatch = Regex("""^(.*?)\s*(\((?:DK|DW|A|S|E)\s*\d+.*\))$""").find(trimmed)
        if (corridorMatch != null) {
            val mainStreet = corridorMatch.groupValues[1]
            val corridorSuffix = corridorMatch.groupValues[2]
            val shortenedMain = abbreviate(mainStreet, countryCode, language)
            return if (!shortenedMain.isNullOrBlank()) "$shortenedMain $corridorSuffix" else trimmed
        }

        val effectiveCountry = countryCode?.uppercase(Locale.ROOT)
            ?: if (language.equals("pl", ignoreCase = true)) "PL" else null

        return when (effectiveCountry) {
            "PL" -> abbreviatePolish(trimmed)
            "US", "GB", "CA", "AU", "NZ", "IE" -> abbreviateEnglish(trimmed)
            "DE", "AT", "CH" -> abbreviateGerman(trimmed)
            else -> {
                // If country code is unspecified, detect if string has typical Polish prefixes
                if (trimmed.startsWith("ul.", ignoreCase = true) ||
                    trimmed.startsWith("ulica ", ignoreCase = true) ||
                    trimmed.startsWith("al.", ignoreCase = true) ||
                    trimmed.startsWith("aleja ", ignoreCase = true) ||
                    trimmed.startsWith("os.", ignoreCase = true) ||
                    trimmed.startsWith("osiedle ", ignoreCase = true) ||
                    trimmed.startsWith("pl.", ignoreCase = true) ||
                    trimmed.startsWith("plac ", ignoreCase = true)
                ) {
                    abbreviatePolish(trimmed)
                } else {
                    // Try English as universal fallback
                    abbreviateEnglish(trimmed)
                }
            }
        }
    }

    /**
     * Abbreviates Polish street names according to standard cartographic and postal conventions.
     */
    private fun abbreviatePolish(street: String): String {
        var prefix = ""
        var remainder = street.trim()

        // Extract and standardize prefix
        when {
            remainder.startsWith("ulica ", ignoreCase = true) -> {
                prefix = "ul. "
                remainder = remainder.substring(6).trim()
            }
            remainder.startsWith("ul. ", ignoreCase = true) -> {
                prefix = "ul. "
                remainder = remainder.substring(4).trim()
            }
            remainder.startsWith("aleja ", ignoreCase = true) || remainder.startsWith("aleje ", ignoreCase = true) -> {
                prefix = "al. "
                remainder = remainder.substring(remainder.indexOf(' ') + 1).trim()
            }
            remainder.startsWith("al. ", ignoreCase = true) -> {
                prefix = "al. "
                remainder = remainder.substring(4).trim()
            }
            remainder.startsWith("plac ", ignoreCase = true) -> {
                prefix = "pl. "
                remainder = remainder.substring(5).trim()
            }
            remainder.startsWith("pl. ", ignoreCase = true) -> {
                prefix = "pl. "
                remainder = remainder.substring(4).trim()
            }
            remainder.startsWith("osiedle ", ignoreCase = true) -> {
                prefix = "os. "
                remainder = remainder.substring(8).trim()
            }
            remainder.startsWith("os. ", ignoreCase = true) -> {
                prefix = "os. "
                remainder = remainder.substring(4).trim()
            }
        }

        if (remainder.isEmpty()) return street

        // Split tokens
        val tokens = remainder.split(Regex("""\s+""")).toMutableList()
        if (tokens.isEmpty()) return street

        // Token 0: Check for honorific / military / clerical title
        var titleIndex = -1
        val firstLower = tokens[0].lowercase(Locale.ROOT)
        if (PL_TITLES.containsKey(firstLower)) {
            tokens[0] = PL_TITLES[firstLower]!!
            titleIndex = 0
        }

        // Check if subsequent tokens are known Polish first names that precede a surname.
        // We only abbreviate a given name if there is at least ONE remaining token following it (the surname).
        val startIndex = if (titleIndex != -1) 1 else 0

        for (i in startIndex until tokens.size - 1) {
            val token = tokens[i]
            val lower = token.lowercase(Locale.ROOT).replace(Regex("""[.,]"""), "")

            // Special check: do not abbreviate Roman numerals (e.g. "Jana III Sobieskiego" -> "Jana III Sobieskiego")
            if (i + 1 < tokens.size && tokens[i + 1].matches(Regex("""^(I|II|III|IV|V|VI|VII|VIII|IX|X)+$"""))) {
                break
            }

            if (PL_GIVEN_NAMES.contains(lower)) {
                // Abbreviate to first letter uppercase + period
                val initial = token.first().uppercaseChar()
                tokens[i] = "$initial."
            }
        }

        val resultBody = tokens.joinToString(" ")
        return "$prefix$resultBody".trim()
    }

    /**
     * Abbreviates English street names.
     */
    private fun abbreviateEnglish(street: String): String {
        val tokens = street.trim().split(Regex("""\s+""")).toMutableList()
        if (tokens.isEmpty()) return street

        // Check first token for title
        val firstLower = tokens[0].lowercase(Locale.ROOT)
        if (EN_TITLES.containsKey(firstLower)) {
            tokens[0] = EN_TITLES[firstLower]!!
        }

        // Check last token for suffix
        val lastIdx = tokens.size - 1
        val lastLower = tokens[lastIdx].lowercase(Locale.ROOT).replace(".", "")
        if (EN_SUFFIXES.containsKey(lastLower)) {
            tokens[lastIdx] = EN_SUFFIXES[lastLower]!!
        }

        return tokens.joinToString(" ")
    }

    /**
     * Abbreviates German street names.
     */
    private fun abbreviateGerman(street: String): String {
        var res = street.trim()
        for ((regex, replacement) in DE_SUFFIX_MAP) {
            if (regex.containsMatchIn(res)) {
                res = res.replace(regex, replacement)
                break
            }
        }
        return res
    }
}
