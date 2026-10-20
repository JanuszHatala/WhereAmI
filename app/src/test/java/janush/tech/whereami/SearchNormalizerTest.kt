package janush.tech.whereami

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale

class SearchNormalizerTest {

    @Test
    fun testPolishDiacriticsNormalization() {
        val polishLower = "zażółć gęślą jaźń"
        val normalizedLower = SearchNormalizer.normalize(polishLower)
        assertEquals("zazolc gesla jazn", normalizedLower)

        val polishUpper = "ZAŻÓŁĆ GĘŚLĄ JAŹŃ"
        val normalizedUpper = SearchNormalizer.normalize(polishUpper)
        assertEquals("zazolc gesla jazn", normalizedUpper)

        val polishLWithStroke = "Bielsko-Biała Łódź Iłża"
        val normalizedL = SearchNormalizer.normalize(polishLWithStroke)
        assertEquals("bielsko-biala lodz ilza", normalizedL)
    }

    @Test
    fun testInternationalDiacriticsNormalization() {
        // German
        assertEquals("munchner strasse", SearchNormalizer.normalize("Münchner Straße"))
        assertEquals("grosste", SearchNormalizer.normalize("Größte"))

        // French
        assertEquals("cafe au lait et creme brulee", SearchNormalizer.normalize("Café au lait et crème brûlée"))
        assertEquals("coeur garcon", SearchNormalizer.normalize("Cœur garçon"))

        // Spanish
        assertEquals("ano nuevo en espana", SearchNormalizer.normalize("Año nuevo en España"))

        // Czech / Slovak
        assertEquals("ceske budejovice", SearchNormalizer.normalize("České Budějovice"))
        assertEquals("kosice liptovsky mikulas", SearchNormalizer.normalize("Košice Liptovský Mikuláš"))
        assertEquals("o", SearchNormalizer.normalize("ô"))

        // Nordic
        assertEquals("tromso kobenhavn alborg", SearchNormalizer.normalize("Tromsø København Ålborg"))
    }

    @Test
    fun testMatchesQueryCaseAndDiacriticInsensitive() {
        assertTrue(SearchNormalizer.matchesQuery("krakow", "Kraków"))
        assertTrue(SearchNormalizer.matchesQuery("KRAKÓW", "krakow"))
        assertTrue(SearchNormalizer.matchesQuery("kraków", "Kraków"))
        assertTrue(SearchNormalizer.matchesQuery("krakow", "KRAKÓW"))

        assertTrue(SearchNormalizer.matchesQuery("lodz", "Łódź"))
        assertTrue(SearchNormalizer.matchesQuery("ŁÓDŹ", "lodz"))

        assertTrue(SearchNormalizer.matchesQuery("żołądek", "Zoladek"))
        assertTrue(SearchNormalizer.matchesQuery("zoladek", "Żołądek"))
    }

    @Test
    fun testHyphenatedAndPunctuatedNames() {
        // User types with hyphen or space
        assertTrue(SearchNormalizer.matchesQuery("bielsko biala", "Bielsko-Biała"))
        assertTrue(SearchNormalizer.matchesQuery("bielsko-biala", "Bielsko Biała"))
        assertTrue(SearchNormalizer.matchesQuery("bielsko", "Bielsko-Biała"))
        assertTrue(SearchNormalizer.matchesQuery("biala", "Bielsko-Biała"))
    }

    @Test
    fun testUnnamedTripMatchingInOctober() {
        // Construct calendar date in October 2026: 2026-10-18 14:30:00
        val cal = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 18, 14, 30, 0)
        }
        val startTimeMs = cal.timeInMillis

        val trip = TripRecord(
            id = 101L,
            title = "", // Unnamed trip!
            startTime = startTimeMs,
            endTime = startTimeMs + 3600_000L,
            distanceMeters = 25000.0,
            placesVisited = listOf(
                VisitedPlace(
                    placeName = "Czaniec",
                    hierarchySubtitle = "gm. Porąbka, pow. bielski, woj. śląskie",
                    latitude = 49.85,
                    longitude = 19.25,
                    timestamp = startTimeMs
                )
            ),
            activityProfile = ActivityProfile.CAR
        )

        // Case-insensitive query "oct" / "Oct" / "OCT"
        assertTrue("oct should match October trip", SearchNormalizer.matchesTrip("oct", trip, Locale.US))
        assertTrue("Oct should match October trip", SearchNormalizer.matchesTrip("Oct", trip, Locale.US))
        assertTrue("OCT should match October trip", SearchNormalizer.matchesTrip("OCT", trip, Locale.US))
        assertTrue("october should match October trip", SearchNormalizer.matchesTrip("october", trip, Locale.US))
        assertTrue("October should match October trip", SearchNormalizer.matchesTrip("October", trip, Locale.US))

        // Year and day matching
        assertTrue("2026 should match trip", SearchNormalizer.matchesTrip("2026", trip, Locale.US))
        assertTrue("18 should match trip day", SearchNormalizer.matchesTrip("18", trip, Locale.US))

        // Visited locality matching with and without diacritics
        assertTrue("czaniec should match place", SearchNormalizer.matchesTrip("czaniec", trip, Locale.US))
        assertTrue("Czaniec should match place", SearchNormalizer.matchesTrip("Czaniec", trip, Locale.US))

        // Administrative hierarchy matching with and without Polish diacritics
        assertTrue("porabka should match gm. Porąbka", SearchNormalizer.matchesTrip("porabka", trip, Locale.US))
        assertTrue("Porąbka should match gm. Porąbka", SearchNormalizer.matchesTrip("Porąbka", trip, Locale.US))
        assertTrue("bielski should match pow. bielski", SearchNormalizer.matchesTrip("bielski", trip, Locale.US))
        assertTrue("slaskie should match woj. śląskie", SearchNormalizer.matchesTrip("slaskie", trip, Locale.US))
        assertTrue("śląskie should match woj. śląskie", SearchNormalizer.matchesTrip("śląskie", trip, Locale.US))

        // Multi-token query matching
        assertTrue("oct czaniec should match", SearchNormalizer.matchesTrip("oct czaniec", trip, Locale.US))
        assertTrue("2026 porabka should match", SearchNormalizer.matchesTrip("2026 porabka", trip, Locale.US))

        // Non-matching query
        assertFalse("berlin should not match this trip", SearchNormalizer.matchesTrip("berlin", trip, Locale.US))
        assertFalse("oct krakow should not match", SearchNormalizer.matchesTrip("oct krakow", trip, Locale.US))
    }

    @Test
    fun testPolishLocaleMonthMatching() {
        val cal = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 18, 14, 30, 0)
        }
        val trip = TripRecord(
            id = 102L,
            title = "",
            startTime = cal.timeInMillis,
            endTime = cal.timeInMillis + 1800_000L,
            distanceMeters = 10000.0,
            placesVisited = emptyList()
        )

        val polishLocale = Locale.forLanguageTag("pl-PL")
        // "październik" / "pazdziernik" / "paź" / "paz"
        assertTrue("pazdziernik should match Polish October", SearchNormalizer.matchesTrip("pazdziernik", trip, polishLocale))
        assertTrue("październik should match Polish October", SearchNormalizer.matchesTrip("październik", trip, polishLocale))
        assertTrue("paz should match Polish short October", SearchNormalizer.matchesTrip("paz", trip, polishLocale))
        assertTrue("paź should match Polish short October", SearchNormalizer.matchesTrip("paź", trip, polishLocale))

        // Even in Polish locale, English "oct" should also match because of universal month extraction
        assertTrue("oct should match even under pl_PL", SearchNormalizer.matchesTrip("oct", trip, polishLocale))
        assertTrue("Oct should match even under pl_PL", SearchNormalizer.matchesTrip("Oct", trip, polishLocale))
    }

    @Test
    fun testSavedPlaceMatching() {
        val place = SavedPlace(
            id = 1L,
            name = "Dom Rodzinny",
            locality = "Kraków",
            street = "ul. Floriańska 12",
            latitude = 50.0647,
            longitude = 19.9450,
            radiusMeters = 50f,
            category = PlaceCategory.HOME
        )

        assertTrue(SearchNormalizer.matchesSavedPlace("dom", place))
        assertTrue(SearchNormalizer.matchesSavedPlace("rodzinny", place))
        assertTrue(SearchNormalizer.matchesSavedPlace("krakow", place))
        assertTrue(SearchNormalizer.matchesSavedPlace("Kraków", place))
        assertTrue(SearchNormalizer.matchesSavedPlace("florianska", place))
        assertTrue(SearchNormalizer.matchesSavedPlace("Floriańska", place))
        assertTrue(SearchNormalizer.matchesSavedPlace("home", place))

        assertFalse(SearchNormalizer.matchesSavedPlace("warszawa", place))
    }
}
