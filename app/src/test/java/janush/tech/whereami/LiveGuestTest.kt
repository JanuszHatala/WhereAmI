package janush.tech.whereami

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.osmdroid.util.GeoPoint

class LiveGuestTest {

    @Test
    fun `test LiveGuest deserialization from JSON`() {
        val json = JSONObject().apply {
            put("id", "guest_123")
            put("name", "Alice")
            put("lat", 49.822)
            put("lng", 19.044)
            put("acc", 12.5)
            put("t", 1700000000000L)
            put("firstSeen", 1699990000000L)
            put("viewCount", 5)
            put("colorIndex", 3)
            put("isInactive", false)
        }

        val guest = LiveGuest.fromJson(json)
        assertNotNull(guest)
        assertEquals("guest_123", guest!!.id)
        assertEquals("Alice", guest.name)
        assertEquals(49.822, guest.lat, 0.0001)
        assertEquals(19.044, guest.lng, 0.0001)
        assertEquals(12.5f, guest.accuracy ?: 0f, 0.1f)
        assertEquals(1700000000000L, guest.lastUpdated)
        assertEquals(1699990000000L, guest.firstSeen)
        assertEquals(5, guest.viewCount)
        assertEquals(3, guest.colorIndex)
        assertFalse(guest.rawIsInactive)
        // Timestamp is in 2023 (> 5 minutes ago relative to now), so it's inactive in real time
        assertTrue(guest.isInactive)
        // But relative to 1 minute after its timestamp, it was active
        assertFalse(guest.isInactiveAt(1700000000000L + 60_000L))
        // And relative to 6 minutes after its timestamp, it was inactive
        assertTrue(guest.isInactiveAt(1700000000000L + 360_000L))
    }

    @Test
    fun `test LiveGuest deserialization with defaults and missing optional fields`() {
        val json = JSONObject().apply {
            put("id", "guest_abc")
            put("lat", 50.061)
            put("lng", 19.937)
        }

        val guest = LiveGuest.fromJson(json)
        assertNotNull(guest)
        assertEquals("guest_abc", guest!!.id)
        assertEquals("Guest", guest.name)
        assertEquals(50.061, guest.lat, 0.0001)
        assertEquals(19.937, guest.lng, 0.0001)
        assertNull(guest.accuracy)
        assertEquals(0, guest.colorIndex)
        assertFalse(guest.isInactive)
    }

    @Test
    fun `test LiveGuest deserialization rejects invalid coordinates or empty id`() {
        val invalidCoord = JSONObject().apply {
            put("id", "guest_bad")
            put("lat", "not_a_number")
            put("lng", 19.0)
        }
        assertNull(LiveGuest.fromJson(invalidCoord))

        val emptyId = JSONObject().apply {
            put("id", "")
            put("lat", 50.0)
            put("lng", 19.0)
        }
        assertNull(LiveGuest.fromJson(emptyId))
    }

    @Test
    fun `test distance formatting`() {
        assertEquals("0 m", LiveGuest.formatDistance(0f))
        assertEquals("350 m", LiveGuest.formatDistance(350.2f))
        assertEquals("999 m", LiveGuest.formatDistance(999.4f))
        assertEquals("1.0 km", LiveGuest.formatDistance(1000f))
        assertEquals("1.5 km", LiveGuest.formatDistance(1520f))
        assertEquals("12.4 km", LiveGuest.formatDistance(12400f))
    }

    @Test
    fun `test color palette assignment and index wrapping`() {
        assertEquals(8, LiveGuest.PALETTE_COLORS.size)
        // Distinct colors
        assertEquals(8, LiveGuest.PALETTE_COLORS.toSet().size)

        // Index wrapping
        for (i in 0 until 8) {
            assertEquals(LiveGuest.PALETTE_COLORS[i], LiveGuest.getColor(i))
            assertEquals(LiveGuest.PALETTE_COLORS[i], LiveGuest.getColor(i + 8))
            assertEquals(LiveGuest.PALETTE_COLORS[i], LiveGuest.getColor(i + 16))
        }
        // Negative index handling
        assertEquals(LiveGuest.PALETTE_COLORS[7], LiveGuest.getColor(-1))
    }

    @Test
    fun `test inactivity threshold rule calculation`() {
        fun computeInactivityThreshold(syncIntervalMinutes: Int): Long {
            return maxOf(5 * 60_000L, syncIntervalMinutes * 2 * 60_000L)
        }

        // 1 min sync interval -> floor of 5 minutes
        assertEquals(300_000L, computeInactivityThreshold(1))
        // 2 min sync interval -> floor of 5 minutes
        assertEquals(300_000L, computeInactivityThreshold(2))
        // 5 min sync interval -> 10 minutes (2x sync interval)
        assertEquals(600_000L, computeInactivityThreshold(5))
        // 10 min sync interval -> 20 minutes (2x sync interval)
        assertEquals(1_200_000L, computeInactivityThreshold(10))
    }

    @Test
    fun `test allShownPoints inclusion logic for fit to screen`() {
        val hostLoc = Triple(50.0, 20.0, 0f)
        val guest1 = LiveGuest("g1", "Guest 1", 50.05, 20.05, 10f, System.currentTimeMillis(), 0, false)
        val guest2 = LiveGuest("g2", "Guest 2", 49.95, 19.95, 15f, System.currentTimeMillis() - 400_000L, 1, true) // dimmed
        val activeGuests = listOf(guest1, guest2)

        // Case A: Trip is recording with track points
        val trackPoints = listOf(GeoPoint(50.01, 20.01), GeoPoint(50.02, 20.02))
        val shownPointsWithTrack = mutableListOf<GeoPoint>().apply {
            addAll(trackPoints)
            activeGuests.forEach { add(it.geoPoint) }
            if (trackPoints.isEmpty()) {
                add(GeoPoint(hostLoc.first, hostLoc.second))
            }
        }
        assertEquals(4, shownPointsWithTrack.size)
        assertTrue(shownPointsWithTrack.contains(trackPoints[0]))
        assertTrue(shownPointsWithTrack.contains(guest1.geoPoint))
        assertTrue(shownPointsWithTrack.contains(guest2.geoPoint))

        // Case B: No track points yet (standby/live-only) -> include host location + all guests
        val emptyTrack = emptyList<GeoPoint>()
        val shownPointsNoTrack = mutableListOf<GeoPoint>().apply {
            addAll(emptyTrack)
            activeGuests.forEach { add(it.geoPoint) }
            if (emptyTrack.isEmpty()) {
                add(GeoPoint(hostLoc.first, hostLoc.second))
            }
        }
        assertEquals(3, shownPointsNoTrack.size)
        assertTrue(shownPointsNoTrack.contains(GeoPoint(hostLoc.first, hostLoc.second)))
        assertTrue(shownPointsNoTrack.contains(guest1.geoPoint))
        assertTrue(shownPointsNoTrack.contains(guest2.geoPoint))
    }

    @Test
    fun `test bearing and compass direction calculation`() {
        val hostLat = 50.0
        val hostLng = 20.0

        // North: lat increases, lng unchanged
        val northGuest = LiveGuest("gN", "North", 50.1, 20.0)
        val northBearing = northGuest.getBearingFrom(hostLat, hostLng)
        assertEquals(0f, northBearing, 1.0f)
        val (northCardinal, northArrow) = northGuest.getDirectionCompass(hostLat, hostLng)
        assertEquals("N", northCardinal)
        assertEquals("↑", northArrow)

        // East: lat unchanged, lng increases
        val eastGuest = LiveGuest("gE", "East", 50.0, 20.1)
        val eastBearing = eastGuest.getBearingFrom(hostLat, hostLng)
        assertEquals(90f, eastBearing, 1.5f)
        val (eastCardinal, eastArrow) = eastGuest.getDirectionCompass(hostLat, hostLng)
        assertEquals("E", eastCardinal)
        assertEquals("→", eastArrow)

        // South: lat decreases, lng unchanged
        val southGuest = LiveGuest("gS", "South", 49.9, 20.0)
        val southBearing = southGuest.getBearingFrom(hostLat, hostLng)
        assertEquals(180f, southBearing, 1.0f)
        val (southCardinal, southArrow) = southGuest.getDirectionCompass(hostLat, hostLng)
        assertEquals("S", southCardinal)
        assertEquals("↓", southArrow)

        // West: lat unchanged, lng decreases
        val westGuest = LiveGuest("gW", "West", 50.0, 19.9)
        val westBearing = westGuest.getBearingFrom(hostLat, hostLng)
        assertEquals(270f, westBearing, 1.5f)
        val (westCardinal, westArrow) = westGuest.getDirectionCompass(hostLat, hostLng)
        assertEquals("W", westCardinal)
        assertEquals("←", westArrow)
    }

    @Test
    fun `test formatTimeAgo and formatDurationElapsed`() {
        val now = 1700000000000L

        assertEquals("just now", LiveGuest.formatTimeAgo(now - 5_000L, now))
        assertEquals("25s ago", LiveGuest.formatTimeAgo(now - 25_000L, now))
        assertEquals("4m ago", LiveGuest.formatTimeAgo(now - 240_000L, now))
        assertEquals("2h 15m ago", LiveGuest.formatTimeAgo(now - 8_100_000L, now))
        assertEquals("3d ago", LiveGuest.formatTimeAgo(now - 3 * 86_400_000L, now))

        assertEquals("45s", LiveGuest.formatDurationElapsed(now - 45_000L, now))
        assertEquals("12m", LiveGuest.formatDurationElapsed(now - 720_000L, now))
        assertEquals("1h 30m", LiveGuest.formatDurationElapsed(now - 5_400_000L, now))
    }

    @Test
    fun `test formatCompactTimeAgo`() {
        val now = 1700000000000L

        assertEquals("now", LiveGuest.formatCompactTimeAgo(now - 5_000L, now))
        assertEquals("now", LiveGuest.formatCompactTimeAgo(now - 14_000L, now))
        assertEquals("15s", LiveGuest.formatCompactTimeAgo(now - 15_000L, now))
        assertEquals("45s", LiveGuest.formatCompactTimeAgo(now - 45_000L, now))
        assertEquals("1m", LiveGuest.formatCompactTimeAgo(now - 60_000L, now))
        assertEquals("12m", LiveGuest.formatCompactTimeAgo(now - 720_000L, now))
        assertEquals("1h", LiveGuest.formatCompactTimeAgo(now - 3_600_000L, now))
        assertEquals("2h", LiveGuest.formatCompactTimeAgo(now - 8_100_000L, now))
        assertEquals("1d", LiveGuest.formatCompactTimeAgo(now - 86_400_000L, now))
        assertEquals("3d", LiveGuest.formatCompactTimeAgo(now - 3 * 86_400_000L, now))
    }

    @Test
    fun `test dismissed guest filtering logic`() {
        val g1 = LiveGuest("g1", "Guest 1", 50.0, 20.0)
        val g2 = LiveGuest("g2", "Guest 2", 50.1, 20.1, rawIsInactive = true)
        val list = listOf(g1, g2)

        val dismissedIds = setOf("g2")
        val filtered = list.filter { it.id !in dismissedIds }

        assertEquals(1, filtered.size)
        assertEquals("g1", filtered[0].id)
    }

    @Test
    fun `test GuestMarkerHelper hasCustomName detection`() {
        assertFalse(GuestMarkerHelper.hasCustomName(null))
        assertFalse(GuestMarkerHelper.hasCustomName(""))
        assertFalse(GuestMarkerHelper.hasCustomName("   "))
        assertFalse(GuestMarkerHelper.hasCustomName("Guest"))
        assertFalse(GuestMarkerHelper.hasCustomName("guest"))
        assertFalse(GuestMarkerHelper.hasCustomName("GUEST"))
        assertFalse(GuestMarkerHelper.hasCustomName("  Guest  "))

        assertTrue(GuestMarkerHelper.hasCustomName("Niki"))
        assertTrue(GuestMarkerHelper.hasCustomName("ja"))
        assertTrue(GuestMarkerHelper.hasCustomName("Aleksander"))
        assertTrue(GuestMarkerHelper.hasCustomName("Guest 1")) // "Guest 1" is distinct from bare "Guest"
    }

    @Test
    fun `test GuestMarkerHelper formatDisplayName and truncation`() {
        assertEquals("", GuestMarkerHelper.formatDisplayName(null))
        assertEquals("", GuestMarkerHelper.formatDisplayName("Guest"))
        assertEquals("", GuestMarkerHelper.formatDisplayName("   "))
        assertEquals("Niki", GuestMarkerHelper.formatDisplayName("Niki"))
        assertEquals("ja", GuestMarkerHelper.formatDisplayName("ja"))
        assertEquals("Aleksander", GuestMarkerHelper.formatDisplayName("Aleksander"))
        assertEquals("123456789012", GuestMarkerHelper.formatDisplayName("123456789012"))
        assertEquals("12345678901…", GuestMarkerHelper.formatDisplayName("1234567890123"))
        assertEquals("Aleksander-…", GuestMarkerHelper.formatDisplayName("Aleksander-Sikora"))
    }

    @Test
    fun `test GuestMarkerHelper formatInitial`() {
        assertEquals("", GuestMarkerHelper.formatInitial(null))
        assertEquals("", GuestMarkerHelper.formatInitial(""))
        assertEquals("", GuestMarkerHelper.formatInitial("Guest"))
        assertEquals("N", GuestMarkerHelper.formatInitial("Niki"))
        assertEquals("N", GuestMarkerHelper.formatInitial("niki"))
        assertEquals("J", GuestMarkerHelper.formatInitial("ja"))
        assertEquals("K", GuestMarkerHelper.formatInitial("  Kamil  "))
    }

    @Test
    fun `test GuestMarkerHelper formatBadgeText permutations`() {
        val now = 1700000000000L
        val unnamed = LiveGuest(id = "g1", name = "Guest", lat = 50.0, lng = 20.0, lastUpdated = now)
        val named = LiveGuest(id = "g2", name = "Niki", lat = 50.0, lng = 20.0, lastUpdated = now)
        val longNamed = LiveGuest(id = "g3", name = "Aleksander-Sikora", lat = 50.0, lng = 20.0, lastUpdated = now)

        // Unnamed permutations
        assertEquals("1.1 km • now", GuestMarkerHelper.formatBadgeText(unnamed, 1120f, showDistance = true, now = now))
        assertEquals("now", GuestMarkerHelper.formatBadgeText(unnamed, null, showDistance = true, now = now))
        assertEquals("", GuestMarkerHelper.formatBadgeText(unnamed, 1120f, showDistance = false, now = now))

        // Named permutations
        assertEquals("Niki • 1.1 km • now", GuestMarkerHelper.formatBadgeText(named, 1120f, showDistance = true, now = now))
        assertEquals("Niki • now", GuestMarkerHelper.formatBadgeText(named, null, showDistance = true, now = now))
        assertEquals("Niki", GuestMarkerHelper.formatBadgeText(named, 1120f, showDistance = false, now = now))

        // Truncated name permutation
        assertEquals("Aleksander-… • 1.1 km • now", GuestMarkerHelper.formatBadgeText(longNamed, 1120f, showDistance = true, now = now))
    }

    @Test
    fun `test filtering active vs inactive guests`() {
        val now = System.currentTimeMillis()
        val activeGuest1 = LiveGuest("g1", "Alice", 50.0, 19.0, lastUpdated = now, rawIsInactive = false)
        val activeGuest2 = LiveGuest("g2", "Bob", 50.1, 19.1, lastUpdated = now, rawIsInactive = false)
        val inactiveGuest = LiveGuest("g3", "Charlie", 50.2, 19.2, lastUpdated = now, rawIsInactive = true)
        val allGuests = listOf(activeGuest1, activeGuest2, inactiveGuest)

        // When showInactiveGuests is true
        val withInactive = allGuests
        assertEquals(3, withInactive.size)

        // When showInactiveGuests is false
        val withoutInactive = allGuests.filter { !it.isInactive }
        assertEquals(2, withoutInactive.size)
        assertTrue(withoutInactive.contains(activeGuest1))
        assertTrue(withoutInactive.contains(activeGuest2))
        assertFalse(withoutInactive.contains(inactiveGuest))
    }

    @Test
    fun `test dynamic 5-minute inactivity evaluation and JSON deserialization`() {
        val now = 1700000000000L

        // Active guest (updated 2 minutes ago, server says false)
        val activeGuest = LiveGuest(
            id = "g_act",
            name = "Active",
            lat = 50.0,
            lng = 20.0,
            lastUpdated = now - 120_000L,
            rawIsInactive = false
        )
        assertFalse(activeGuest.isInactiveAt(now))

        // Inactive guest due to age (> 5 min, server still said false)
        val oldGuest = LiveGuest(
            id = "g_old",
            name = "Old",
            lat = 50.0,
            lng = 20.0,
            lastUpdated = now - 360_000L, // 6 minutes ago
            rawIsInactive = false
        )
        assertTrue(oldGuest.isInactiveAt(now))

        // Inactive guest flagged by server explicitly even if recent
        val serverInactiveGuest = LiveGuest(
            id = "g_srv",
            name = "SrvInactive",
            lat = 50.0,
            lng = 20.0,
            lastUpdated = now - 10_000L,
            rawIsInactive = true
        )
        assertTrue(serverInactiveGuest.isInactiveAt(now))

        // Boundary test: exactly 5 minutes (300_000 ms)
        val t0 = 1000_000L
        val boundaryGuest = LiveGuest(
            id = "g_bnd",
            name = "Boundary",
            lat = 50.0,
            lng = 20.0,
            lastUpdated = t0,
            rawIsInactive = false
        )
        assertFalse(boundaryGuest.isInactiveAt(t0 + 300_000L)) // exactly 5 min -> active
        assertTrue(boundaryGuest.isInactiveAt(t0 + 300_001L))  // > 5 min -> inactive

        // JSON deserialization test: server JSON says isInactive = false, but timestamp is 6 minutes ago
        val json = JSONObject().apply {
            put("id", "g_json")
            put("name", "JsonGuest")
            put("lat", 50.0)
            put("lng", 20.0)
            put("t", System.currentTimeMillis() - 360_000L) // 6 min ago
            put("isInactive", false)
        }
        val deserialized = LiveGuest.fromJson(json)
        assertNotNull(deserialized)
        assertFalse(deserialized!!.rawIsInactive)
        assertTrue(deserialized.isInactive) // Client evaluates as inactive!
    }

    @Test
    fun `test computeSpiderfyOffsets separates overlapping guests radially`() {
        // Two guests at the exact same location
        val g1 = LiveGuest(id = "g1", name = "Alice", lat = 49.8000, lng = 19.5000, lastUpdated = 1000L)
        val g2 = LiveGuest(id = "g2", name = "Bob", lat = 49.8000, lng = 19.5000, lastUpdated = 1000L)

        val offsets = computeSpiderfyOffsets(listOf(g1, g2))
        assertEquals(2, offsets.size)
        val p1 = offsets["g1"]!!
        val p2 = offsets["g2"]!!

        // They must no longer be at identical coordinates
        val dLat = Math.abs(p1.latitude - p2.latitude)
        val dLng = Math.abs(p1.longitude - p2.longitude)
        assertTrue("Overlapping guests must be separated radially by spiderfy", dLat > 0.00001 || dLng > 0.00001)

        // Single isolated guest should not be offset
        val g3 = LiveGuest(id = "g3", name = "Charlie", lat = 50.0000, lng = 20.0000, lastUpdated = 1000L)
        val singleOffset = computeSpiderfyOffsets(listOf(g3))
        assertEquals(g3.geoPoint.latitude, singleOffset["g3"]!!.latitude, 0.000001)
        assertEquals(g3.geoPoint.longitude, singleOffset["g3"]!!.longitude, 0.000001)
    }

    @Test
    fun `test computeSpiderfyOffsets separates single guest co-located with saved place obstacle`() {
        val g = LiveGuest(id = "ja", name = "ja", lat = 49.8512, lng = 19.2852, lastUpdated = 1000L)
        val homePin = org.osmdroid.util.GeoPoint(49.8512, 19.2852)

        val offsets = computeSpiderfyOffsets(
            guests = listOf(g),
            hostPos = homePin,
            obstaclePoints = listOf(homePin),
            zoomLevel = 17.5,
            density = 2.75f
        )
        assertEquals(1, offsets.size)
        val p = offsets["ja"]!!

        // Must be offset from the homePin
        val dLat = Math.abs(p.latitude - homePin.latitude)
        val dLng = Math.abs(p.longitude - homePin.longitude)
        assertTrue("Guest co-located with obstacle must be separated by spiderfy: dLat=$dLat, dLng=$dLng", dLat > 0.0001 || dLng > 0.0001)

        // Must be shifted toward the West (longitude strictly less than obstacle longitude)
        assertTrue("Guest must be shifted West away from right-side controls and altitude badge: pLng=${p.longitude}, homeLng=${homePin.longitude}", p.longitude < homePin.longitude)

        // Must be tightly clamped (16m - 45m), never jumping 100m+ away
        val mLat = 111_132.92
        val mLng = 111_412.84 * Math.cos(Math.toRadians(homePin.latitude))
        val distM = Math.hypot((p.latitude - homePin.latitude) * mLat, (p.longitude - homePin.longitude) * mLng)
        assertTrue("Displacement distance should be tightly bounded (16m..45m), was $distM m", distM in 15.0..46.0)
    }
}

