package janush.tech.whereami

import org.junit.Assert.assertEquals
import org.junit.Test

class StreetAbbreviatorTest {

    @Test
    fun testPolishHonorificsAndFirstNames() {
        assertEquals("J. Lompy", StreetAbbreviator.abbreviate("Józefa Lompy", countryCode = "PL"))
        assertEquals("ul. J. Lompy", StreetAbbreviator.abbreviate("ul. Józefa Lompy", countryCode = "PL"))
        assertEquals("ul. J. Lompy", StreetAbbreviator.abbreviate("ulica Józefa Lompy", countryCode = "PL"))
        assertEquals("Gen. W. Sikorskiego", StreetAbbreviator.abbreviate("Generała Władysława Sikorskiego", countryCode = "PL"))
        assertEquals("ul. Gen. W. Sikorskiego", StreetAbbreviator.abbreviate("ul. Generała Władysława Sikorskiego", countryCode = "PL"))
        assertEquals("Kard. K. Wojtyły", StreetAbbreviator.abbreviate("Kardynała Karola Wojtyły", countryCode = "PL"))
        assertEquals("Ks. S. Staszica", StreetAbbreviator.abbreviate("Księdza Stanisława Staszica", countryCode = "PL"))
        assertEquals("Św. J. Kantego", StreetAbbreviator.abbreviate("Świętego Jana Kantego", countryCode = "PL"))
        assertEquals("A. Mickiewicza", StreetAbbreviator.abbreviate("Adama Mickiewicza", countryCode = "PL"))
        assertEquals("J. Słowackiego", StreetAbbreviator.abbreviate("Juliusza Słowackiego", countryCode = "PL"))
        assertEquals("M. Konopnickiej", StreetAbbreviator.abbreviate("Marii Konopnickiej", countryCode = "PL"))
        assertEquals("Płk. S. Dąbka", StreetAbbreviator.abbreviate("Pułkownika Stanisława Dąbka", countryCode = "PL"))
        assertEquals("Prof. Z. Religi", StreetAbbreviator.abbreviate("Profesora Zbigniewa Religi", countryCode = "PL"))
        assertEquals("S. Sempołowskiej", StreetAbbreviator.abbreviate("Stefanii Sempołowskiej", countryCode = "PL"))
        assertEquals("ul. S. Sempołowskiej", StreetAbbreviator.abbreviate("ul. Stefanii Sempołowskiej", countryCode = "PL"))
    }

    @Test
    fun testPolishInstitutionsAndHistoricalStreetsPreserved() {
        // Institutional & historical terms must NOT be abbreviated
        assertEquals("Wojska Polskiego", StreetAbbreviator.abbreviate("Wojska Polskiego", countryCode = "PL"))
        assertEquals("ul. Wojska Polskiego", StreetAbbreviator.abbreviate("ul. Wojska Polskiego", countryCode = "PL"))
        assertEquals("Powstańców Śląskich", StreetAbbreviator.abbreviate("Powstańców Śląskich", countryCode = "PL"))
        assertEquals("Armii Krajowej", StreetAbbreviator.abbreviate("Armii Krajowej", countryCode = "PL"))
        assertEquals("Bohaterów Monte Cassino", StreetAbbreviator.abbreviate("Bohaterów Monte Cassino", countryCode = "PL"))
        assertEquals("3 Maja", StreetAbbreviator.abbreviate("3 Maja", countryCode = "PL"))
        assertEquals("11 Listopada", StreetAbbreviator.abbreviate("11 Listopada", countryCode = "PL"))
        assertEquals("Jana III Sobieskiego", StreetAbbreviator.abbreviate("Jana III Sobieskiego", countryCode = "PL"))
    }

    @Test
    fun testSingleWordAndPlainStreetsPreserved() {
        assertEquals("Polna", StreetAbbreviator.abbreviate("Polna", countryCode = "PL"))
        assertEquals("Krakowska", StreetAbbreviator.abbreviate("Krakowska", countryCode = "PL"))
        assertEquals("Bielska", StreetAbbreviator.abbreviate("Bielska", countryCode = "PL"))
        assertEquals("ul. Ogrodowa", StreetAbbreviator.abbreviate("ul. Ogrodowa", countryCode = "PL"))
    }

    @Test
    fun testMajorCorridorsPreservedWithShortenedStreet() {
        assertEquals("DK52", StreetAbbreviator.abbreviate("DK52", countryCode = "PL"))
        assertEquals("DW946", StreetAbbreviator.abbreviate("DW946", countryCode = "PL"))
        assertEquals("A4", StreetAbbreviator.abbreviate("A4", countryCode = "PL"))
        assertEquals("ul. Krakowska (DK52)", StreetAbbreviator.abbreviate("ul. Krakowska (DK52)", countryCode = "PL"))
        assertEquals("ul. J. Lompy (DW946)", StreetAbbreviator.abbreviate("ul. Józefa Lompy (DW946)", countryCode = "PL"))
    }

    @Test
    fun testEnglishStreetAbbreviations() {
        assertEquals("Main St", StreetAbbreviator.abbreviate("Main Street", countryCode = "US"))
        assertEquals("5th Ave", StreetAbbreviator.abbreviate("5th Avenue", countryCode = "US"))
        assertEquals("Ocean Blvd", StreetAbbreviator.abbreviate("Ocean Boulevard", countryCode = "US"))
        assertEquals("Country Club Rd", StreetAbbreviator.abbreviate("Country Club Road", countryCode = "US"))
        assertEquals("St. Charles St", StreetAbbreviator.abbreviate("Saint Charles Street", countryCode = "US"))
        assertEquals("Dr. Martin Luther King Jr. Blvd", StreetAbbreviator.abbreviate("Doctor Martin Luther King Jr. Boulevard", countryCode = "US"))
    }

    @Test
    fun testGermanStreetAbbreviations() {
        assertEquals("Goethestr.", StreetAbbreviator.abbreviate("Goethestraße", countryCode = "DE"))
        assertEquals("Alexanderpl.", StreetAbbreviator.abbreviate("Alexanderplatz", countryCode = "DE"))
    }

    @Test
    fun testRoadNameNormalizerDelegate() {
        assertEquals("ul. J. Lompy", RoadNameNormalizer.shortenStreetName("ul. Józefa Lompy", "PL"))
        assertEquals("Main St", RoadNameNormalizer.shortenStreetName("Main Street", "US"))
    }
}
