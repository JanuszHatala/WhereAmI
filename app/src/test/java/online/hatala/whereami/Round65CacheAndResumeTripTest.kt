package online.hatala.whereami

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.osmdroid.util.GeoPoint
import java.io.File
import java.nio.file.Files

class Round65CacheAndResumeTripTest {

    @Test
    fun `resume saved trip appends pause gap between saved end and resume time`() {
        val existingPause = TripPause(
            startTime = 200_000L,
            endTime = 260_000L,
            latitude = 50.10,
            longitude = 19.10,
            durationMs = 60_000L,
            pointIndex = 2,
            distanceMeters = 500.0
        )
        val savedTrip = TripRecord(
            id = 42L,
            startTime = 100_000L,
            endTime = 400_000L,
            distanceMeters = 2_000.0,
            activityProfile = ActivityProfile.CAR,
            points = listOf(
                GeoPoint(50.00, 19.00),
                GeoPoint(50.05, 19.05),
                GeoPoint(50.10, 19.10),
                GeoPoint(50.15, 19.15)
            ),
            pauses = listOf(existingPause)
        )

        // Resuming 1 hour later appends pause spanning [400_000 .. 4_000_000]
        val resumeNow = 4_000_000L
        val pausesAfterResume = TripManager.computeResumedTripPauses(savedTrip, resumeNow)
        assertEquals(2, pausesAfterResume.size)
        val gap = pausesAfterResume.last()
        assertEquals(400_000L, gap.startTime)
        assertEquals(resumeNow, gap.endTime)
        assertEquals(3_600_000L, gap.durationMs)
        assertEquals(3, gap.pointIndex)
        assertEquals(50.15, gap.latitude, 0.0001)
        assertEquals(19.15, gap.longitude, 0.0001)

        // Short <15s reopen skips creating an artificial micro-pause
        val quickReopen = TripManager.computeResumedTripPauses(savedTrip, 405_000L)
        assertEquals(1, quickReopen.size)
    }

    @Test
    fun `corridor planner generates z13_15 deduplicated tiles for Standard OSM and trip profile base layer`() {
        // Two points ~1.5km apart so intermediate chord densification adds intermediate z15 tiles
        val route = listOf(
            50.0600 to 19.9300,
            50.0720 to 19.9480
        )
        val planned = MapTileCorridorPlanner.planForTrips(
            tripSpecs = listOf(route to MapBaseLayer.TOPO)
        )
        assertTrue(planned.isNotEmpty())
        assertTrue(planned.any { it.layer == MapBaseLayer.STANDARD && it.zoom == 13 })
        assertTrue(planned.any { it.layer == MapBaseLayer.STANDARD && it.zoom == 15 })
        assertTrue(planned.any { it.layer == MapBaseLayer.TOPO && it.zoom == 14 })
        // Duplicate layers collapse cleanly when profile already uses Standard OSM
        val stdOnly = MapTileCorridorPlanner.planForTrips(
            tripSpecs = listOf(route to MapBaseLayer.STANDARD)
        )
        assertTrue(stdOnly.all { it.layer == MapBaseLayer.STANDARD })
        assertEquals(planned.count { it.layer == MapBaseLayer.STANDARD }, stdOnly.size)
    }

    @Test
    fun `osmdroid sqlTileIndex bit packing matches SqlTileWriter schema and missing tile filter detects legacy tile files`() {
        val z = 14
        val x = 9095
        val y = 5567
        val expected = (((z.toLong() shl z) + x.toLong()) shl z) + y.toLong()
        assertEquals(expected, MapTileCorridorPlanner.sqlTileIndex(x, y, z))

        val tmpDir = Files.createTempDirectory("tiles_test").toFile()
        try {
            val cachedTile = TileCoordKey(MapBaseLayer.STANDARD, z, x, y)
            val missingTile = TileCoordKey(MapBaseLayer.STANDARD, z, x + 1, y)
            val topoTile = TileCoordKey(MapBaseLayer.TOPO, z, x, y)

            // Write legacy .tile file for cachedTile (non-empty)
            val legacyPath = File(tmpDir, "Mapnik/$z/$x/${y}.png.tile").apply { parentFile?.mkdirs() }
            legacyPath.writeBytes(byteArrayOf(1, 2, 3, 4))

            val missing = CacheManager.filterMissingMapTilesInDb(
                cacheDbFile = File(tmpDir, "cache.db"), // DB absent -> legacy check still rescues cachedTile
                tileDir = tmpDir,
                plannedTiles = listOf(cachedTile, missingTile, topoTile)
            )
            assertEquals(listOf(missingTile, topoTile), missing)
        } finally {
            tmpDir.deleteRecursively()
        }
    }
}
