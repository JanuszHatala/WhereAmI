package janush.tech.whereami

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.osmdroid.util.GeoPoint

class PlaceSearchRankerTest {

    // Near Bielsko-Biała / Andrychów area
    private val originLat = 49.8225
    private val originLon = 19.0444

    private fun item(
        title: String,
        lat: Double,
        lon: Double,
        subtitle: String = "",
        countryCode: String? = "pl",
        placeType: String? = null
    ) = SearchResultItem(
        title = title,
        subtitle = subtitle,
        geoPoint = GeoPoint(lat, lon),
        countryCode = countryCode,
        placeType = placeType
    )

    @Test
    fun nameMatch_isCaseAndDiacriticInsensitive() {
        assertTrue(PlaceSearchRanker.nameMatchScore("an", "Andrychów") >= 0.9)
        assertTrue(PlaceSearchRanker.nameMatchScore("AN", "Andrychów") >= 0.9)
        assertTrue(PlaceSearchRanker.nameMatchScore("ą", "Andrychów") >= 0.9)
        assertTrue(PlaceSearchRanker.nameMatchScore("lodz", "Łódź") >= 0.9)
        assertTrue(PlaceSearchRanker.nameMatchScore("Łódź", "lodz") >= 0.9)
        assertTrue(PlaceSearchRanker.nameMatchScore("krakow", "Kraków") >= 0.9)
    }

    @Test
    fun shortPrefix_prefersNearbyAndrychowOverDistantAnPlaces() {
        val andrychow = PlaceSearchRanker.Candidate(
            item("Andrychów", 49.8550, 19.3383, "pow. wadowicki, Polska", "pl", "town"),
            placeType = "town",
            importance = 0.45
        )
        val annapolis = PlaceSearchRanker.Candidate(
            item("Annapolis", 38.9784, -76.4922, "Maryland, USA", "us", "city"),
            placeType = "city",
            importance = 0.7
        )
        val ankara = PlaceSearchRanker.Candidate(
            item("Ankara", 39.9334, 32.8597, "Turkey", "tr", "city"),
            placeType = "city",
            importance = 0.85
        )
        val ranked = PlaceSearchRanker.rank(
            query = "an",
            candidates = listOf(ankara, annapolis, andrychow),
            originLat = originLat,
            originLon = originLon,
            countryCode = "pl"
        )
        assertEquals("Andrychów", ranked.first().title)
        assertTrue(ranked.first().distanceMeters != null && ranked.first().distanceMeters!! < 50_000f)
    }

    @Test
    fun longerQuery_stillSurfacesFarCity() {
        val oslo = PlaceSearchRanker.Candidate(
            item("Oslo", 59.9139, 10.7522, "Norway", "no", "city"),
            placeType = "city",
            importance = 0.9
        )
        val localStreet = PlaceSearchRanker.Candidate(
            item("Oslońska", 49.82, 19.05, "Bielsko-Biała", "pl", "street"),
            placeType = "street",
            importance = 0.1
        )
        val ranked = PlaceSearchRanker.rank(
            query = "Oslo",
            candidates = listOf(localStreet, oslo),
            originLat = originLat,
            originLon = originLon,
            countryCode = "pl"
        )
        assertEquals("Oslo", ranked.first().title)
    }

    @Test
    fun savedPlace_boostsAboveSimilarRemoteHit() {
        val saved = PlaceSearchRanker.Candidate(
            item("Andrzejówka", 49.83, 19.10, "Saved", "pl", "saved"),
            placeType = "saved",
            isSaved = true
        )
        val remote = PlaceSearchRanker.Candidate(
            item("Andrzejówka", 50.5, 20.0, "Somewhere", "pl", "village"),
            placeType = "village",
            importance = 0.3
        )
        val ranked = PlaceSearchRanker.rank(
            query = "andr",
            candidates = listOf(remote, saved),
            originLat = originLat,
            originLon = originLon,
            countryCode = "pl"
        )
        assertEquals(saved.item.geoPoint.latitude, ranked.first().geoPoint.latitude, 1e-6)
    }

    @Test
    fun neverDropsFarResults_onlyReorders() {
        val far = PlaceSearchRanker.Candidate(
            item("Antananarivo", -18.8792, 47.5079, "Madagascar", "mg", "city"),
            placeType = "city",
            importance = 0.8
        )
        val near = PlaceSearchRanker.Candidate(
            item("Andrychów", 49.8550, 19.3383, "Polska", "pl", "town"),
            placeType = "town"
        )
        val ranked = PlaceSearchRanker.rank(
            query = "an",
            candidates = listOf(far, near),
            originLat = originLat,
            originLon = originLon,
            countryCode = "pl"
        )
        assertEquals(2, ranked.size)
        assertEquals("Andrychów", ranked[0].title)
        assertEquals("Antananarivo", ranked[1].title)
    }

    @Test
    fun formatDistance_humanReadable() {
        assertEquals("450 m", PlaceSearchRanker.formatDistance(450f))
        assertEquals("3.2 km", PlaceSearchRanker.formatDistance(3200f))
        assertEquals("180 km", PlaceSearchRanker.formatDistance(180_000f))
    }

    @Test
    fun distanceScore_shortQueryPenalizesFarMore() {
        val near = PlaceSearchRanker.distanceScore(15_000f, queryLen = 2)
        val far = PlaceSearchRanker.distanceScore(1_500_000f, queryLen = 2)
        assertTrue(near > far)
        val farLongQuery = PlaceSearchRanker.distanceScore(1_500_000f, queryLen = 8)
        assertTrue(farLongQuery > far) // longer queries soften distance penalty
    }
}
