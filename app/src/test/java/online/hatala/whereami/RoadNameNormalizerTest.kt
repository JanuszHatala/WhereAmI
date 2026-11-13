package online.hatala.whereami

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class RoadNameNormalizerTest {

    @Test
    fun testNationalRoadsNormalization() {
        assertEquals("DK52", RoadNameNormalizer.normalize("Droga Krajowa 52", countryCode = "PL"))
        assertEquals("DK52", RoadNameNormalizer.normalize("Droga Krajowa nr 52", countryCode = "PL"))
        assertEquals("DK52", RoadNameNormalizer.normalize("Krajowa 52", countryCode = "PL"))
        assertEquals("DK52", RoadNameNormalizer.normalize("DK 52", countryCode = "PL"))
        assertEquals("DK52", RoadNameNormalizer.normalize("DK52", countryCode = "PL"))
        assertEquals("DK28", RoadNameNormalizer.normalize("Droga Krajowa 28", countryCode = "PL"))
        assertEquals("DK28", RoadNameNormalizer.normalize("28", countryCode = "PL"))
        assertEquals("DK47", RoadNameNormalizer.normalize("47", countryCode = "PL"))
    }

    @Test
    fun testProvincialRoadsNormalization() {
        assertEquals("DW946", RoadNameNormalizer.normalize("Droga Wojewódzka 946", countryCode = "PL"))
        assertEquals("DW946", RoadNameNormalizer.normalize("Wojewódzka 946", countryCode = "PL"))
        assertEquals("DW946", RoadNameNormalizer.normalize("DW 946", countryCode = "PL"))
        assertEquals("DW948", RoadNameNormalizer.normalize("Droga Wojewódzka nr 948", countryCode = "PL"))
        assertEquals("DW946", RoadNameNormalizer.normalize("946", countryCode = "PL"))
    }

    @Test
    fun testHighwaysAndExpressways() {
        assertEquals("A4", RoadNameNormalizer.normalize("Autostrada A4"))
        assertEquals("A4", RoadNameNormalizer.normalize("A 4"))
        assertEquals("S7", RoadNameNormalizer.normalize("Droga Ekspresowa S7"))
        assertEquals("S7", RoadNameNormalizer.normalize("S 7"))
        assertEquals("E77", RoadNameNormalizer.normalize("Droga Międzynarodowa E77"))
    }

    @Test
    fun testMajorRoadsStripHouseNumbers() {
        // Major roads must NOT have house numbers appended
        assertEquals("DK52", RoadNameNormalizer.normalize("DK 52", houseNumber = "142", countryCode = "PL"))
        assertEquals("DW946", RoadNameNormalizer.normalize("Droga Wojewódzka 946", houseNumber = "55B", countryCode = "PL"))
    }

    @Test
    fun testResidentialStreetsStripHouseNumbersAndCleanPrefix() {
        assertEquals("ul. Mickiewicza", RoadNameNormalizer.normalize("ulica Mickiewicza", houseNumber = "12", countryCode = "PL"))
        assertEquals("ul. Mickiewicza", RoadNameNormalizer.normalize("ulica Mickiewicza 12", countryCode = "PL"))
        assertEquals("al. Wolności", RoadNameNormalizer.normalize("aleja Wolności", houseNumber = "4A", countryCode = "PL"))
        assertEquals("os. Tysiąclecia", RoadNameNormalizer.normalize("osiedle Tysiąclecia", houseNumber = "5", countryCode = "PL"))
        assertEquals("pl. Grunwaldzki", RoadNameNormalizer.normalize("plac Grunwaldzki", countryCode = "PL"))
        assertEquals("Zagłębocze", RoadNameNormalizer.normalize("Zagłębocze 45", countryCode = "PL"))
        assertEquals("Górska", RoadNameNormalizer.normalize("Górska 92", countryCode = "PL"))
        assertEquals("Zielona", RoadNameNormalizer.normalize("Zielona 18", countryCode = "PL"))
    }

    @Test
    fun testNamedStreetWithHighwayRef() {
        assertEquals("ul. Wadowicka (DK52)", RoadNameNormalizer.normalize("ulica Wadowicka", rawRef = "DK52", countryCode = "PL"))
        assertEquals("ul. Żywiecka (DW946)", RoadNameNormalizer.normalize("ulica Żywiecka", rawRef = "946", countryCode = "PL"))
        assertEquals("al. świętego Jana Pawła II (S1)", RoadNameNormalizer.normalize("Aleje świętego Jana Pawła II", rawRef = "S1", countryCode = "PL"))
    }

    @Test
    fun testIsMajorRoad() {
        assertTrue(RoadNameNormalizer.isMajorRoad("DK52"))
        assertTrue(RoadNameNormalizer.isMajorRoad("DW946"))
        assertTrue(RoadNameNormalizer.isMajorRoad("A4"))
        assertTrue(RoadNameNormalizer.isMajorRoad("S7"))
        assertTrue(RoadNameNormalizer.isMajorRoad("S1"))
        assertTrue(RoadNameNormalizer.isMajorRoad("28"))
        assertTrue(RoadNameNormalizer.isMajorRoad("946"))
        assertTrue(RoadNameNormalizer.isMajorRoad("Aleje świętego Jana Pawła II (S1)"))
        assertTrue(RoadNameNormalizer.isMajorRoad("ul. Wadowicka (DK52)"))
        assertTrue(RoadNameNormalizer.isMajorRoad("Droga Krajowa 28"))
        assertTrue(RoadNameNormalizer.isMajorRoad("Trasa Niepodległości"))
        assertTrue(RoadNameNormalizer.isMajorRoad("ul. Niepodległości (S52)"))
        assertTrue(RoadNameNormalizer.isMajorRoad("Aleja Generała Władysława Andersa"))
        assertTrue(RoadNameNormalizer.isMajorRoad("Trasa Niepodległości"))
        assertTrue(RoadNameNormalizer.isMajorRoad("Północno-Wschodnia Obwodnica Bielska-Białej"))
        assertTrue(RoadNameNormalizer.isMajorRoad("Droga Bez Nazwy", roadRef = "S52"))
        assertTrue(RoadNameNormalizer.isMajorRoad("Bohaterów Monte Cassino", roadRef = "DK52"))
        assertTrue(RoadNameNormalizer.isMajorRoad("Aleja gen. Władysława Andersa"))
        assertFalse(RoadNameNormalizer.isMajorRoad("ul. Kościuszki"))
        assertFalse(RoadNameNormalizer.isMajorRoad("Rynek"))
        assertFalse(RoadNameNormalizer.isMajorRoad("ul. Braterska"))
        assertFalse(RoadNameNormalizer.isMajorRoad("Generała Józefa Chłopickiego"))
    }

    @Test
    fun testShouldShowHouseNumberGating() {
        // High accuracy (<= 20m) and low speed (< 15 km/h) on residential road -> allowed
        assertTrue(RoadNameNormalizer.shouldShowHouseNumber(speedKmh = 5.0f, accuracyMeters = 8.0f, isMajorRoad = false))
        assertTrue(RoadNameNormalizer.shouldShowHouseNumber(speedKmh = 0.0f, accuracyMeters = 4.0f, isMajorRoad = false))
        assertTrue(RoadNameNormalizer.shouldShowHouseNumber(speedKmh = null, accuracyMeters = 10.0f, isMajorRoad = false))
        assertTrue(RoadNameNormalizer.shouldShowHouseNumber(speedKmh = 12.0f, accuracyMeters = 15.0f, isMajorRoad = false))
        assertTrue(RoadNameNormalizer.shouldShowHouseNumber(speedKmh = 0.0f, accuracyMeters = 20.0f, isMajorRoad = false))

        // Velocity >= 15 km/h -> stripped
        assertFalse(RoadNameNormalizer.shouldShowHouseNumber(speedKmh = 15.0f, accuracyMeters = 8.0f, isMajorRoad = false))
        assertFalse(RoadNameNormalizer.shouldShowHouseNumber(speedKmh = 45.0f, accuracyMeters = 5.0f, isMajorRoad = false))

        // Stationary / indoor dwell (speed < 1 km/h): allowed up to 40m accuracy
        assertTrue(RoadNameNormalizer.shouldShowHouseNumber(speedKmh = 0.0f, accuracyMeters = 25.0f, isMajorRoad = false))
        assertTrue(RoadNameNormalizer.shouldShowHouseNumber(speedKmh = 0.0f, accuracyMeters = 35.0f, isMajorRoad = false))
        assertFalse(RoadNameNormalizer.shouldShowHouseNumber(speedKmh = 0.0f, accuracyMeters = 42.0f, isMajorRoad = false))
        assertFalse(RoadNameNormalizer.shouldShowHouseNumber(speedKmh = 0.0f, accuracyMeters = null, isMajorRoad = false))

        // Slow walking (speed < 5 km/h): allowed up to 35m accuracy
        assertTrue(RoadNameNormalizer.shouldShowHouseNumber(speedKmh = 3.5f, accuracyMeters = 30.0f, isMajorRoad = false))
        assertFalse(RoadNameNormalizer.shouldShowHouseNumber(speedKmh = 3.5f, accuracyMeters = 36.0f, isMajorRoad = false))

        // Moving (5 - 15 km/h): allowed up to 25m accuracy
        assertTrue(RoadNameNormalizer.shouldShowHouseNumber(speedKmh = 10.0f, accuracyMeters = 20.0f, isMajorRoad = false))
        assertFalse(RoadNameNormalizer.shouldShowHouseNumber(speedKmh = 10.0f, accuracyMeters = 28.0f, isMajorRoad = false))

        // Major road / corridor -> strictly stripped regardless of speed/accuracy
        assertFalse(RoadNameNormalizer.shouldShowHouseNumber(speedKmh = 0.0f, accuracyMeters = 3.0f, isMajorRoad = true))
    }

    @Test
    fun testCoalesceBareCodeSideStreetShielding() {
        // Graniczna is a side street without matching roadRef - must NOT coalesce with DK52
        val resultGraniczna = RoadNameNormalizer.coalesceBareCodeWithCachedName(
            committedStreet = "DK52",
            committedRoadRef = "DK52",
            cachedStreet = "Graniczna",
            cachedRoadRef = null
        )
        org.junit.Assert.assertNull(resultGraniczna)

        // Krakowska with matching roadRef DK52 - coalesces cleanly
        val resultKrakowska = RoadNameNormalizer.coalesceBareCodeWithCachedName(
            committedStreet = "DK52",
            committedRoadRef = "DK52",
            cachedStreet = "Krakowska",
            cachedRoadRef = "DK52"
        )
        org.junit.Assert.assertNotNull(resultKrakowska)
        org.junit.Assert.assertEquals("Krakowska", resultKrakowska?.first)
        org.junit.Assert.assertEquals("DK52", resultKrakowska?.second)
    }

    @Test
    fun testStreetsMatch() {
        assertTrue(RoadNameNormalizer.streetsMatch("ul. Mickiewicza", "Mickiewicza"))
        assertTrue(RoadNameNormalizer.streetsMatch("Lipnicka", "ulica Lipnicka"))
        assertTrue(RoadNameNormalizer.streetsMatch("ul. Stefanii Sempołowskiej", "Sempołowskiej"))
        assertTrue(RoadNameNormalizer.streetsMatch("Zielona", "Zielona 92"))
        assertFalse(RoadNameNormalizer.streetsMatch("Stojałowskiego", "Lipnicka"))
        assertFalse(RoadNameNormalizer.streetsMatch("ul. Główna", "Boczna"))
    }

    @Test
    fun testSanitizeHouseNumber() {
        assertEquals("ul. Zdrojowa", RoadNameNormalizer.sanitizeHouseNumber("ul. Zdrojowa 11A", allowHouseNumber = false))
        assertEquals("ul. Zdrojowa 11A", RoadNameNormalizer.sanitizeHouseNumber("ul. Zdrojowa 11A", allowHouseNumber = true))
        assertEquals("DK52", RoadNameNormalizer.sanitizeHouseNumber("DK52", allowHouseNumber = false))
        assertEquals("ul. Krakowska (DK52)", RoadNameNormalizer.sanitizeHouseNumber("ul. Krakowska (DK52)", allowHouseNumber = false))
        assertEquals("ul. Bielska", RoadNameNormalizer.sanitizeHouseNumber("ul. Bielska", allowHouseNumber = false))
        assertEquals("os. Młocki Dolne", RoadNameNormalizer.sanitizeHouseNumber("os. Młocki Dolne", allowHouseNumber = false))
    }

    @Test
    fun testInternationalAndSlovakRoadNormalization() {
        // In Slovakia, numeric refs should never be prefixed with Polish DK or DW
        assertEquals("I/18", RoadNameNormalizer.normalize("18", countryCode = "SK"))
        assertEquals("Cesta slobody (I/18)", RoadNameNormalizer.normalize("Cesta slobody", rawRef = "18", countryCode = "SK"))
        assertEquals("I/78", RoadNameNormalizer.normalize("78", countryCode = "SK"))
        assertEquals("II/537", RoadNameNormalizer.normalize("537", countryCode = "SK"))
        assertEquals("Cesta slobody (II/537)", RoadNameNormalizer.normalize("Cesta slobody", rawRef = "537", countryCode = "SK"))
        assertEquals("I/18", RoadNameNormalizer.normalize("I/18", countryCode = "SK"))
        assertEquals("II/537", RoadNameNormalizer.normalize("II/537", countryCode = "SK"))

        // Even if rawRef mistakenly has DK18 or DW537 outside Poland, strip the Polish prefix
        assertEquals("I/18", RoadNameNormalizer.normalize(null, rawRef = "DK18", countryCode = "SK"))
        assertEquals("II/537", RoadNameNormalizer.normalize(null, rawRef = "DW537", countryCode = "SK"))

        // Generic other country (e.g. CZ, DE) & unknown null/blank countryCode
        assertEquals("18", RoadNameNormalizer.normalize("18", countryCode = "CZ"))
        assertEquals("537", RoadNameNormalizer.normalize("537", countryCode = "DE"))
        assertEquals("12", RoadNameNormalizer.normalize("12", countryCode = null))
        assertEquals("66", RoadNameNormalizer.normalize("66", countryCode = ""))
        assertEquals("902", RoadNameNormalizer.normalize("902", countryCode = null))
        assertEquals("Main St (66)", RoadNameNormalizer.normalize("Main St", rawRef = "66", countryCode = "US"))
        assertEquals("Plac Espanya", RoadNameNormalizer.normalize("Plac Espanya", countryCode = "ES"))
        assertEquals("Ulica Heroja", RoadNameNormalizer.normalize("Ulica Heroja", countryCode = "HR"))
    }

    @Test
    fun corridorLocalName_isNotShieldedWhenTheWayCarriesTheRoadNumber() {
        // DK52 through Bujaków: OSRM snaps "Bielska", Nominatim's way has ref 52.
        assertFalse(RoadNameNormalizer.shouldShieldMajorCorridor(54f, true, "Bielska", "52"))
        assertEquals("Bielska (DK52)", RoadNameNormalizer.normalize("Bielska", "52", countryCode = "PL"))
        // A parallel / crossing local street without a road number stays shielded at speed.
        assertTrue(RoadNameNormalizer.shouldShieldMajorCorridor(54f, true, "Polna", null))
        assertFalse(RoadNameNormalizer.shouldShieldMajorCorridor(30f, true, "Polna", null))
        assertFalse(RoadNameNormalizer.shouldShieldMajorCorridor(54f, false, "Polna", null))
    }

    @Test
    fun wayRef_onlyFromTheRoadObjectItself() {
        assertEquals("52", RoadNameNormalizer.wayRef("Bielska", "Bielska" to "52"))
        assertEquals(null, RoadNameNormalizer.wayRef("Bielska", "Bujaków" to "52"))
        assertEquals(null, RoadNameNormalizer.wayRef("Bielska", "Bielska" to null))
        assertEquals(null, RoadNameNormalizer.wayRef(null, "Bielska" to "52"))
        assertEquals(null, RoadNameNormalizer.wayRef("Bielska", null))
    }

    @Test
    fun testShouldShowHouseNumberOnMajorCorridorWithLocalName() {
        // Stationary or slow speed on a major road WITH a local street name: ALLOW house numbers
        assertTrue(
            RoadNameNormalizer.shouldShowHouseNumber(
                speedKmh = 0f,
                accuracyMeters = 5f,
                isMajorRoad = true,
                isStationary = true,
                hasNamedLocalStreet = true
            )
        )
        assertTrue(
            RoadNameNormalizer.shouldShowHouseNumber(
                speedKmh = 4f,
                accuracyMeters = 8f,
                isMajorRoad = true,
                isStationary = false,
                hasNamedLocalStreet = true
            )
        )

        // Bare highway (e.g. A4, S7, DK52 without a local street name): DO NOT allow house numbers
        assertFalse(
            RoadNameNormalizer.shouldShowHouseNumber(
                speedKmh = 0f,
                accuracyMeters = 5f,
                isMajorRoad = true,
                isStationary = true,
                hasNamedLocalStreet = false
            )
        )

        // Driving fast (> 15 km/h) on major corridor: DO NOT allow house numbers
        assertFalse(
            RoadNameNormalizer.shouldShowHouseNumber(
                speedKmh = 45f,
                accuracyMeters = 5f,
                isMajorRoad = true,
                isStationary = false,
                hasNamedLocalStreet = true
            )
        )
    }

    @Test
    fun testFormatStreetDisplayWithHouseNumber() {
        assertEquals("ul. Zielona 92", RoadNameNormalizer.formatStreetDisplay("ul. Zielona 92", null, "PL", allowHouseNumber = true))
        assertEquals("ul. Zielona", RoadNameNormalizer.formatStreetDisplay("ul. Zielona 92", null, "PL", allowHouseNumber = false))
        assertEquals("DK52", RoadNameNormalizer.formatStreetDisplay("DK52", "52", "PL", allowHouseNumber = true))
        assertEquals("Krakowska 5 (DK52)", RoadNameNormalizer.formatStreetDisplay("Krakowska 5", "52", "PL", allowHouseNumber = true))
        assertEquals("Krakowska (DK52)", RoadNameNormalizer.formatStreetDisplay("Krakowska 5", "52", "PL", allowHouseNumber = false))
        assertEquals("Krakowska (DK52)", RoadNameNormalizer.formatStreetDisplay("Krakowska (DK52)", null, "PL", allowHouseNumber = true))
    }

    @Test
    fun testCoalesceConfirmedCorridorStreets() {
        // In Chocznia: OSM way has name="Tadeusza Kościuszki" and ref="52", OSRM returns "52"
        val (choczniaStreet, choczniaRef) = RoadNameNormalizer.coalesceOsrmAndOsmStreet(
            osrmStreet = "52",
            osmStreet = "Tadeusza Kościuszki",
            osmRoadRef = "52"
        )
        assertEquals("Tadeusza Kościuszki", choczniaStreet)
        assertEquals("52", choczniaRef)
        assertEquals("Tadeusza Kościuszki (DK52)", RoadNameNormalizer.formatStreetDisplay(choczniaStreet, choczniaRef, "PL"))

        // In Inwałd: OSM way has name="Wadowicka" and ref="52", OSRM returns "52"
        val (inwaldStreet, inwaldRef) = RoadNameNormalizer.coalesceOsrmAndOsmStreet(
            osrmStreet = "52",
            osmStreet = "Wadowicka",
            osmRoadRef = "52"
        )
        assertEquals("Wadowicka", inwaldStreet)
        assertEquals("52", inwaldRef)
        assertEquals("Wadowicka (DK52)", RoadNameNormalizer.formatStreetDisplay(inwaldStreet, inwaldRef, "PL"))

        // In Bujaków: OSRM snaps perpendicular side street "Podlesie" (no ref), while OSM confirms "Bielska" + ref "52"
        val (bujakowStreet, bujakowRef) = RoadNameNormalizer.coalesceOsrmAndOsmStreet(
            osrmStreet = "Podlesie",
            osmStreet = "Bielska",
            osmRoadRef = "52"
        )
        assertEquals("Bielska", bujakowStreet)
        assertEquals("52", bujakowRef)
        assertEquals("Bielska (DK52)", RoadNameNormalizer.formatStreetDisplay(bujakowStreet, bujakowRef, "PL"))

        // And shouldShieldMajorCorridor must shield "Bielska (DK52)" against OSRM side street "Podlesie" even when osmRoadRef="52"
        assertTrue(
            RoadNameNormalizer.shouldShieldMajorCorridor(
                speedKmh = 68f,
                isCommittedMajor = true,
                osrmStreet = "Podlesie",
                osmRoadRef = "52",
                osmStreet = "Bielska"
            )
        )
        // Verify data-driven major highway helper
        assertTrue(RoadNameNormalizer.isMajorHighway("primary", null))
        assertTrue(RoadNameNormalizer.isMajorHighway("secondary", null))
        assertFalse(RoadNameNormalizer.isMajorHighway("residential", null))
    }

    @Test
    fun testCoalesceOsrmWithHighwayClassificationGating() {
        // Residential side street in Roczyny: OSRM erroneously snaps to DW781, OSM has "Bielska" residential
        val (bielskaStreet, bielskaRef) = RoadNameNormalizer.coalesceOsrmAndOsmStreet(
            osrmStreet = "DW781",
            osmStreet = "Bielska",
            osmRoadRef = null,
            candidateHighwayType = "residential"
        )
        assertEquals("Bielska", bielskaStreet)
        assertNull("Residential street must never receive DW781 code", bielskaRef)

        // Wiedeńska in Roczyny: unclassified side street must never receive DW781 code
        val (wiedenskaStreet, wiedenskaRef) = RoadNameNormalizer.coalesceOsrmAndOsmStreet(
            osrmStreet = "DW781",
            osmStreet = "Wiedeńska",
            osmRoadRef = null,
            candidateHighwayType = "unclassified"
        )
        assertEquals("Wiedeńska", wiedenskaStreet)
        assertNull("Unclassified street must never receive DW781 code", wiedenskaRef)

        // Beskidzka in Andrychów (actual DW781): OSM has "Beskidzka" secondary, Nominatim has no ref, OSRM has DW781
        val (beskidzkaStreet, beskidzkaRef) = RoadNameNormalizer.coalesceOsrmAndOsmStreet(
            osrmStreet = "DW781",
            osmStreet = "Beskidzka",
            osmRoadRef = null,
            candidateHighwayType = "secondary"
        )
        assertEquals("Beskidzka", beskidzkaStreet)
        assertEquals("DW781", beskidzkaRef)
        assertEquals("Beskidzka (DW781)", RoadNameNormalizer.formatStreetDisplay(beskidzkaStreet, beskidzkaRef, "PL"))
    }

    @Test
    fun testMultiRefSemicolonNormalization() {
        assertEquals("DK44 / DW933", RoadNameNormalizer.normalize(null, "44;933", countryCode = "PL"))
        assertEquals("DK44 / DW933", RoadNameNormalizer.normalize("44;933", countryCode = "PL"))
        assertEquals("A4 (E40)", RoadNameNormalizer.normalize(null, "A4;E40", countryCode = "PL"))
        assertEquals("A4 (E40)", RoadNameNormalizer.normalize("A4;E40", countryCode = "PL"))
        assertEquals("ul. Krakowska (DK44 / DW933)", RoadNameNormalizer.normalize("ul. Krakowska", "44;933", countryCode = "PL"))
        assertEquals("44 / 933", RoadNameNormalizer.normalize(null, "44;933", countryCode = null))
        assertTrue(RoadNameNormalizer.isHighwayCodeOnly("DK44 / DW933"))
        assertTrue(RoadNameNormalizer.isHighwayCodeOnly("A4 (E40)"))
        assertTrue(RoadNameNormalizer.isHighwayCodeOnly("44;933"))
        assertEquals("DK44 / DW933", RoadNameNormalizer.extractHighwayCode("ul. Krakowska (DK44 / DW933)"))
    }

    @Test
    fun testMotorwayProtectionAgainstHouseNumbers() {
        assertFalse(RoadNameNormalizer.shouldPreferExistingStreet("Ignacego Daszyńskiego 638", "A4"))
        assertFalse(RoadNameNormalizer.shouldPreferExistingStreet("Ignacego Daszyńskiego 638", "A4 (E40)"))
        assertTrue(RoadNameNormalizer.shouldPreferExistingStreet("ul. Krakowska", "DW948"))
    }
}

