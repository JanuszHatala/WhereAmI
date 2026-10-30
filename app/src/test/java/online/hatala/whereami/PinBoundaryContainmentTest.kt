package online.hatala.whereami

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.osmdroid.util.GeoPoint

/**
 * Pin borders must enclose the pin. Prefer village/sołectwo over gmina.
 * Reject distant wrong name-search hits (e.g. Wielka Puszcza → Andrychów).
 */
class PinBoundaryContainmentTest {

    private val pin = GeoPoint(49.81984, 19.25870)

    /** Square around the pin (Porąbka-like). */
    private fun enclosingSquare(): List<GeoPoint> = listOf(
        GeoPoint(49.77565, 19.19740),
        GeoPoint(49.77565, 19.31651),
        GeoPoint(49.84285, 19.31651),
        GeoPoint(49.84285, 19.19740),
        GeoPoint(49.77565, 19.19740)
    )

    /** Andrychów-like square east of the pin (does not contain it). */
    private fun distantEastSquare(): List<GeoPoint> = listOf(
        GeoPoint(49.76167, 19.28585),
        GeoPoint(49.76167, 19.44587),
        GeoPoint(49.88404, 19.44587),
        GeoPoint(49.88404, 19.28585),
        GeoPoint(49.76167, 19.28585)
    )

    private fun polygonGeoJson(pts: List<GeoPoint>): JSONObject {
        val ring = JSONArray()
        for (p in pts) {
            ring.put(JSONArray().put(p.longitude).put(p.latitude))
        }
        return JSONObject()
            .put("type", "Polygon")
            .put("coordinates", JSONArray().put(ring))
    }

    private fun nominatimHit(
        displayName: String,
        pts: List<GeoPoint>,
        adminLevel: Int?,
        osmClass: String = "boundary",
        osmType: String = "administrative",
        osmEntity: String = "relation",
        placeRank: Int? = null
    ): JSONObject {
        val obj = JSONObject()
            .put("display_name", displayName)
            .put("class", osmClass)
            .put("type", osmType)
            .put("osm_type", osmEntity)
            .put("geojson", polygonGeoJson(pts))
        if (adminLevel != null) {
            obj.put("extratags", JSONObject().put("admin_level", adminLevel.toString()))
        }
        if (placeRank != null) {
            obj.put("place_rank", placeRank)
        }
        return obj
    }

    @Test
    fun `polygonContains reports pin inside Porąbka square and outside Andrychów square`() {
        assertTrue(BoundaryHelper.polygonContains(enclosingSquare(), pin.latitude, pin.longitude))
        assertFalse(BoundaryHelper.polygonContains(distantEastSquare(), pin.latitude, pin.longitude))
    }

    @Test
    fun `selectBestBoundaryPolygon rejects non-containing candidate when geoPoint provided`() {
        val array = JSONArray()
            .put(nominatimHit("gmina Andrychów", distantEastSquare(), adminLevel = 7))
        val poly = BoundaryHelper.selectBestBoundaryPolygon(array, mustContain = pin)
        assertNull(poly)
    }

    @Test
    fun `selectBestBoundaryPolygon prefers tighter village over gmina when both contain pin`() {
        val village = enclosingSquare()
        // Slightly larger square still containing pin (gmina-like)
        val gmina = listOf(
            GeoPoint(49.77000, 19.17500),
            GeoPoint(49.77000, 19.32000),
            GeoPoint(49.87500, 19.32000),
            GeoPoint(49.87500, 19.17500),
            GeoPoint(49.77000, 19.17500)
        )
        val array = JSONArray()
            .put(nominatimHit("gmina Porąbka", gmina, adminLevel = 7, placeRank = 16))
            .put(nominatimHit("Porąbka", village, adminLevel = 10, placeRank = 20))

        val poly = BoundaryHelper.selectBestBoundaryPolygon(array, mustContain = pin)
        assertNotNull(poly)
        // Village square northern edge is 49.84285; gmina extends to 49.875 — pick village (tighter bbox)
        val maxLat = poly!!.maxOf { it.latitude }
        assertEquals(49.84285, maxLat, 0.0001)
    }

    @Test
    fun `selectBestBoundaryPolygon without mustContain still ranks administrative relations`() {
        val array = JSONArray()
            .put(nominatimHit("gmina Andrychów", distantEastSquare(), adminLevel = 7))
        val poly = BoundaryHelper.selectBestBoundaryPolygon(array, mustContain = null)
        assertNotNull(poly)
        assertTrue(BoundaryHelper.polygonContains(poly!!, 49.82, 19.36))
    }

    @Test
    fun `isCachePolygonUsableForPin rejects wrong cached Andrychów under Wielka Puszcza key`() {
        assertFalse(
            BoundaryHelper.isCachePolygonUsableForPin(distantEastSquare(), pin)
        )
        assertTrue(
            BoundaryHelper.isCachePolygonUsableForPin(enclosingSquare(), pin)
        )
    }

    @Test
    fun `adminTightnessScore prefers higher admin_level village over gmina and rejects county`() {
        assertTrue(
            BoundaryHelper.adminTightnessScore(adminLevel = 10, placeRank = 20) >
                BoundaryHelper.adminTightnessScore(adminLevel = 7, placeRank = 16)
        )
        assertTrue(
            BoundaryHelper.adminTightnessScore(adminLevel = 6, placeRank = 12) < 0
        )
    }

    @Test
    fun `live GPS borders use name-first only pin mode uses reverse-first`() {
        assertFalse(BoundaryHelper.shouldResolveReverseFirst(forPin = false, geoPoint = pin))
        assertTrue(BoundaryHelper.shouldResolveReverseFirst(forPin = true, geoPoint = pin))
        assertFalse(BoundaryHelper.shouldResolveReverseFirst(forPin = true, geoPoint = null))
    }

    @Test
    fun `live mode keeps named cache even when GPS is briefly outside polygon`() {
        // GPS just outside Kobiernice-named cache must still be usable for live borders
        assertTrue(
            BoundaryHelper.isCachePolygonUsableForLive(distantEastSquare(), pin)
        )
        assertFalse(
            BoundaryHelper.isCachePolygonUsableForPin(distantEastSquare(), pin)
        )
    }
}
