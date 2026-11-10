package online.hatala.whereami

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class PauseRestoreAndLocalitySanityTest {

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * asin(sqrt(a))
    }

    @Test
    fun testStructuralHierarchyConsistencyRejectsDisjointLocality() {
        // Current place in Bielsko-Biała
        val currentCity = "Bielsko-Biała"
        val currentPowiat = "powiat Bielsko-Biała"
        val currentGmina: String? = null

        val candidateName = "Kraków"
        val isConsistent = candidateName.equals(currentCity, ignoreCase = true) ||
                (currentGmina != null && candidateName.equals(currentGmina, ignoreCase = true)) ||
                (currentPowiat != null && candidateName.equals(currentPowiat, ignoreCase = true))

        assertFalse("Kraków candidate must be rejected when current hierarchy is $currentCity, $currentPowiat", isConsistent)

        // Real candidate in Bielsko-Biała
        val validCandidate = "Bielsko-Biała"
        val isValidConsistent = validCandidate.equals(currentCity, ignoreCase = true)
        assertTrue("Bielsko-Biała must be structurally consistent", isValidConsistent)
    }

    @Test
    fun testSandwichedPingPongBlipFilter() {
        val t0 = 1791716670000L
        val places = listOf(
            VisitedPlace(placeName = "Bielsko-Biała", timestamp = t0, latitude = 49.807, longitude = 18.970),
            VisitedPlace(placeName = "Jaworze", timestamp = t0 + 600_000L, latitude = 49.795, longitude = 18.950),
            VisitedPlace(placeName = "Bielsko-Biała", timestamp = t0 + 1200_000L, latitude = 49.807, longitude = 18.970),
            VisitedPlace(placeName = "Kraków", timestamp = t0 + 1272_000L, latitude = 49.813, longitude = 18.975), // false 9s blip
            VisitedPlace(placeName = "Bielsko-Biała", timestamp = t0 + 1281_000L, latitude = 49.814, longitude = 18.975), // 9s after Kraków
            VisitedPlace(placeName = "Rybarzowice", timestamp = t0 + 1488_000L, latitude = 49.835, longitude = 18.983), // 13s sandwiched blip
            VisitedPlace(placeName = "Bielsko-Biała", timestamp = t0 + 1501_000L, latitude = 49.836, longitude = 18.987),
            VisitedPlace(placeName = "Mazańcowice", timestamp = t0 + 1575_000L, latitude = 49.845, longitude = 19.007), // 18s sandwiched blip
            VisitedPlace(placeName = "Bielsko-Biała", timestamp = t0 + 1593_000L, latitude = 49.847, longitude = 19.012),
            VisitedPlace(placeName = "Kozy", timestamp = t0 + 2100_000L, latitude = 49.850, longitude = 19.140),
            VisitedPlace(placeName = "Kęty", timestamp = t0 + 3600_000L, latitude = 49.880, longitude = 19.220)
        )

        val cleaned = mutableListOf<VisitedPlace>()
        for (i in places.indices) {
            val p = places[i]

            // Sandwiched ping-pong blip check (< 30s)
            if (i < places.size - 1 && cleaned.isNotEmpty()) {
                val nextP = places[i + 1]
                val prevP = cleaned.last()
                val durationSec = (nextP.timestamp - p.timestamp) / 1000.0
                if (durationSec < 30.0 && prevP.placeName.equals(nextP.placeName, ignoreCase = true)) {
                    continue // Skip transient glitch
                }
            }

            // 3. Coalesce consecutive duplicates
            if (cleaned.isNotEmpty() && cleaned.last().placeName.equals(p.placeName, ignoreCase = true)) {
                continue
            }

            cleaned.add(p)
        }

        val expectedNames = listOf("Bielsko-Biała", "Jaworze", "Bielsko-Biała", "Kozy", "Kęty")
        val actualNames = cleaned.map { it.placeName }
        assertEquals(expectedNames, actualNames)
    }

    @Test
    fun testCorridorShieldRecognizesExpresswayCorridors() {
        assertTrue(RoadNameNormalizer.isMajorRoad("Bohaterów Monte Cassino", "S52"))
        assertTrue(RoadNameNormalizer.isMajorRoad("ul. Bohaterów Monte Cassino", "S52"))
        assertTrue(RoadNameNormalizer.isMajorRoad("Aleja Generała Władysława Andersa"))
        assertTrue(RoadNameNormalizer.isMajorRoad("Trasa Niepodległości"))
        assertTrue(RoadNameNormalizer.isMajorRoad("S52"))
        assertTrue(RoadNameNormalizer.isMajorRoad("DK52"))
        assertTrue(RoadNameNormalizer.isMajorHighway("motorway"))
        assertTrue(RoadNameNormalizer.isMajorHighway("trunk"))
        assertTrue(RoadNameNormalizer.isMajorHighway("primary"))

        // Residential streets must NOT be major
        assertFalse(RoadNameNormalizer.isMajorRoad("Sabały"))
        assertFalse(RoadNameNormalizer.isMajorRoad("Piechurów"))
        assertFalse(RoadNameNormalizer.isMajorRoad("Poligonowa"))
        assertFalse(RoadNameNormalizer.isMajorRoad("Biblioteczna"))
        assertFalse(RoadNameNormalizer.isMajorRoad("Browarna"))
    }

    @Test
    fun testTripPauseRestorationDataModel() {
        val now = 1791716000000L
        val pause = TripPause(
            startTime = now,
            endTime = null,
            latitude = 49.818,
            longitude = 19.032,
            durationMs = 0L,
            pointIndex = 42,
            distanceMeters = 15300.0
        )

        assertEquals(now, pause.startTime)
        assertEquals(49.818, pause.latitude, 0.0001)
        assertEquals(19.032, pause.longitude, 0.0001)
        assertEquals(42, pause.pointIndex)
        assertEquals(15300.0, pause.distanceMeters, 0.1)

        val restoredDurationMs = 120_000L
        val restoredPause = pause.copy(durationMs = restoredDurationMs)
        assertEquals(120_000L, restoredPause.durationMs)
    }
}
