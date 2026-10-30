package online.hatala.whereami

import java.text.DateFormat
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Universal search normalizer providing case-insensitive and diacritic-agnostic text matching.
 * Converts European and international alphabet-specific characters (such as Polish ą, ć, ę, ł, ń, ó, ś, ź, ż,
 * German ä, ö, ü, ß, French é, è, ê, ç, œ, Spanish ñ, Nordic ø, æ, etc.) into their normalized ASCII base forms.
 *
 * Supports tokenized multi-word search matching across TripRecord metadata (titles, formatted dates,
 * English and localized month names, visited localities, hierarchy subtitles, and activity profiles).
 */
object SearchNormalizer {

    private val DIACRITICS_REGEX = Regex("\\p{InCombiningDiacriticalMarks}+")

    private val ENGLISH_MONTH_NAMES = arrayOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December"
    )
    private val ENGLISH_MONTH_SHORT = arrayOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun",
        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
    )

    /**
     * Normalizes text for case-insensitive and diacritic-insensitive matching.
     * 1. Translates non-decomposing Latin letters with strokes/ligatures (Polish ł/Ł, Nordic ø/Ø, German ß, etc.)
     * 2. Decomposes Unicode diacritical marks via Normalizer.Form.NFD
     * 3. Strips all combining diacritical marks (ogoneks, acute accents, umlauts, carons, tildes, etc.)
     * 4. Converts to lower-case using Locale.ROOT
     * 5. Trims leading and trailing whitespace
     */
    fun normalize(text: String?): String {
        if (text.isNullOrEmpty()) return ""

        val preprocessed = StringBuilder(text.length)
        for (i in 0 until text.length) {
            when (val ch = text[i]) {
                'ł' -> preprocessed.append('l')
                'Ł' -> preprocessed.append('L')
                'ø' -> preprocessed.append('o')
                'Ø' -> preprocessed.append('O')
                'đ' -> preprocessed.append('d')
                'Đ' -> preprocessed.append('D')
                'ð' -> preprocessed.append('d')
                'Ð' -> preprocessed.append('D')
                'ß' -> preprocessed.append("ss")
                'æ' -> preprocessed.append("ae")
                'Æ' -> preprocessed.append("AE")
                'œ' -> preprocessed.append("oe")
                'Œ' -> preprocessed.append("OE")
                'þ' -> preprocessed.append("th")
                'Þ' -> preprocessed.append("TH")
                else -> preprocessed.append(ch)
            }
        }

        val decomposed = Normalizer.normalize(preprocessed, Normalizer.Form.NFD)
        return DIACRITICS_REGEX.replace(decomposed, "")
            .lowercase(Locale.ROOT)
            .trim()
    }

    /**
     * Splits query into whitespace-separated tokens and checks whether ALL tokens
     * are contained within the target strings. Both query and targets are normalized.
     */
    fun matchesQuery(query: String?, vararg targets: String?): Boolean {
        return matchesQuery(query, targets.asIterable())
    }

    /**
     * Splits query into whitespace-separated tokens and checks whether ALL tokens
     * are contained within the target strings collection.
     */
    fun matchesQuery(query: String?, targets: Iterable<String?>): Boolean {
        if (query.isNullOrBlank()) return true
        val normalizedQuery = normalize(query)
        if (normalizedQuery.isBlank()) return true

        val queryTokens = normalizedQuery.split(Regex("[\\s,;\\-_/]+")).filter { it.isNotBlank() }
        if (queryTokens.isEmpty()) return true

        val nonNullTargets = targets.filterNotNull().filter { it.isNotBlank() }
        if (nonNullTargets.isEmpty()) return false

        // Build combined normalized text, plus versions with punctuation converted to spaces
        // and stripped entirely to handle hyphenated or punctuated names seamlessly
        // (e.g. "Bielsko-Biała" vs "Bielsko Biała" vs "Bielskobiala", "DK-52" vs "DK52")
        val sb = StringBuilder()
        for (target in nonNullTargets) {
            val norm = normalize(target)
            sb.append(norm).append(' ')
            val dePunct = norm.replace(Regex("[-._/()]"), " ")
            if (dePunct != norm) {
                sb.append(dePunct).append(' ')
            }
            val noPunct = norm.replace(Regex("[-._/()\\s]"), "")
            if (noPunct.isNotBlank() && noPunct != norm && noPunct != dePunct) {
                sb.append(noPunct).append(' ')
            }
        }
        val combinedTarget = sb.toString()

        return queryTokens.all { token -> combinedTarget.contains(token) }
    }

    /**
     * Extracts searchable string representations for a TripRecord:
     * - Custom title (trip.title)
     * - Display/Default title (e.g. "18 Oct 2026, 14:30")
     * - Date in default locale (medium, long, short)
     * - Localized month name (full and short, e.g. "październik", "paź")
     * - English month name and abbreviation ("October", "Oct")
     * - Year and day of month ("2026", "18")
     * - Visited locality names (e.g. "Czaniec", "Kraków")
     * - Visited locality administrative hierarchy subtitles (e.g. "gm. Porąbka, pow. bielski, woj. śląskie")
     * - Activity profile display name (e.g. "Car", "Cycling")
     */
    fun getSearchableStrings(trip: TripRecord, locale: Locale = Locale.getDefault()): List<String> {
        val strings = mutableListOf<String>()

        if (trip.title.isNotBlank()) {
            strings.add(trip.title)
        }

        val tripDate = Date(trip.startTime)
        val dateStr = DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(tripDate)
        val timeStr = DateFormat.getTimeInstance(DateFormat.SHORT, locale).format(tripDate)
        strings.add("$dateStr, $timeStr")
        strings.add(dateStr)

        try {
            strings.add(DateFormat.getDateInstance(DateFormat.LONG, locale).format(tripDate))
        } catch (_: Exception) {}

        val cal = Calendar.getInstance().apply { timeInMillis = trip.startTime }
        val monthIdx = cal.get(Calendar.MONTH)
        if (monthIdx in 0..11) {
            strings.add(ENGLISH_MONTH_NAMES[monthIdx])
            strings.add(ENGLISH_MONTH_SHORT[monthIdx])
        }
        val year = cal.get(Calendar.YEAR).toString()
        val dayOfMonth = cal.get(Calendar.DAY_OF_MONTH).toString()
        strings.add(year)
        strings.add(dayOfMonth)

        try {
            val monthFormat = SimpleDateFormat("MMMM", locale)
            strings.add(monthFormat.format(tripDate))
            val monthShortFormat = SimpleDateFormat("MMM", locale)
            strings.add(monthShortFormat.format(tripDate))
        } catch (_: Exception) {}

        for (place in trip.placesVisited) {
            if (place.placeName.isNotBlank()) {
                strings.add(place.placeName)
            }
            if (place.hierarchySubtitle.isNotBlank()) {
                strings.add(place.hierarchySubtitle)
            }
        }

        strings.add(trip.activityProfile.displayName)

        return strings
    }

    /**
     * Determines whether a TripRecord matches the user search query.
     */
    fun matchesTrip(query: String?, trip: TripRecord, locale: Locale = Locale.getDefault()): Boolean {
        if (query.isNullOrBlank()) return true
        val searchable = getSearchableStrings(trip, locale)
        return matchesQuery(query, searchable)
    }

    /**
     * Determines whether a SavedPlace matches the user search query.
     */
    fun matchesSavedPlace(query: String?, place: SavedPlace): Boolean {
        if (query.isNullOrBlank()) return true
        return matchesQuery(query, place.name, place.locality, place.street, place.category.displayName)
    }
}
