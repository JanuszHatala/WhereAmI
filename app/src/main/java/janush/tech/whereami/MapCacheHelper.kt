package janush.tech.whereami

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.floor
import kotlin.math.tan

sealed class CacheDownloadState {
    object Idle : CacheDownloadState()
    data class Downloading(val current: Int, val total: Int, val percent: Int) : CacheDownloadState()
    data class Completed(val totalTiles: Int, val addedMb: Double) : CacheDownloadState()
    data class AlreadyCached(val totalTiles: Int) : CacheDownloadState()
    data class Failed(val error: String) : CacheDownloadState()
}

object MapCacheHelper {

    private val _downloadState = MutableStateFlow<CacheDownloadState>(CacheDownloadState.Idle)
    val downloadState: StateFlow<CacheDownloadState> = _downloadState.asStateFlow()

    private var downloadJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    fun isDownloading(): Boolean = _downloadState.value is CacheDownloadState.Downloading

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _downloadState.value = CacheDownloadState.Idle
    }

    fun startCachingRegion(
        context: Context,
        centerLat: Double,
        centerLng: Double,
        radiusDegrees: Double = 0.035,
        zoomLevels: IntRange = 13..15,
        isFreemapOutdoor: Boolean = false
    ) {
        if (isDownloading()) return

        downloadJob = scope.launch {
            try {
                val tileDir = Configuration.getInstance().osmdroidTileCache
                val initialSize = getDirectorySize(tileDir)

                val north = centerLat + radiusDegrees
                val south = centerLat - radiusDegrees
                val east = centerLng + (radiusDegrees * 1.5)
                val west = centerLng - (radiusDegrees * 1.5)

                data class TileCoord(val z: Int, val x: Int, val y: Int)
                val tilesToDownload = mutableListOf<TileCoord>()

                for (z in zoomLevels) {
                    val xMin = lonToTileX(west, z)
                    val xMax = lonToTileX(east, z)
                    val yMin = latToTileY(north, z)
                    val yMax = latToTileY(south, z)

                    val startX = minOf(xMin, xMax)
                    val endX = maxOf(xMin, xMax)
                    val startY = minOf(yMin, yMax)
                    val endY = maxOf(yMin, yMax)

                    for (x in startX..endX) {
                        for (y in startY..endY) {
                            tilesToDownload.add(TileCoord(z, x, y))
                        }
                    }
                }

                val total = tilesToDownload.size
                if (total == 0) {
                    _downloadState.value = CacheDownloadState.Failed("No tiles identified in area")
                    return@launch
                }

                _downloadState.value = CacheDownloadState.Downloading(0, total, 0)

                                val userAgent = Configuration.getInstance().userAgentValue.ifBlank { "WhereAmI/1.3.1 (Android)" }

                val folderName = if (isFreemapOutdoor) "FreemapOutdoor" else "OpenStreetMapMapnik"
                val targetFolder = File(tileDir, "tiles/$folderName")
                val fallbackFolder = File(tileDir, folderName)

                val channel = kotlinx.coroutines.channels.Channel<TileCoord>(kotlinx.coroutines.channels.Channel.UNLIMITED)
                tilesToDownload.forEach { channel.trySend(it) }
                channel.close()

                val count = java.util.concurrent.atomic.AtomicInteger(0)
                val skippedCount = java.util.concurrent.atomic.AtomicInteger(0)
                val concurrency = 6 // MAP-R08: Parallel downloading with reasonable bounds

                val workers = (1..concurrency).map {
                    launch {
                        for (tile in channel) {
                            if (!isActive) break

                            val file1 = File(targetFolder, "${tile.z}/${tile.x}/${tile.y}.png.tile")
                            val file2 = File(fallbackFolder, "${tile.z}/${tile.x}/${tile.y}.png.tile")

                            if (!file1.exists() && !file2.exists()) {
                                val urlStr = if (isFreemapOutdoor) {
                                    "https://outdoor.tiles.freemap.sk/${tile.z}/${tile.x}/${tile.y}@2x"
                                } else {
                                    "https://tile.openstreetmap.org/${tile.z}/${tile.x}/${tile.y}.png"
                                }
                                try {
                                    val conn = URL(urlStr).openConnection() as HttpURLConnection
                                    conn.setRequestProperty("User-Agent", userAgent)
                                    conn.connectTimeout = 3000
                                    conn.readTimeout = 4000
                                    if (conn.responseCode in 200..299) {
                                        val bytes = conn.inputStream.use { it.readBytes() }
                                        file1.parentFile?.mkdirs()
                                        file1.outputStream().use { it.write(bytes) }

                                        file2.parentFile?.mkdirs()
                                        file2.outputStream().use { it.write(bytes) }
                                    }
                                } catch (_: Exception) {
                                }
                            } else {
                                skippedCount.incrementAndGet()
                            }

                            val current = count.incrementAndGet()
                            val pct = ((current * 100) / total)
                            _downloadState.value = CacheDownloadState.Downloading(current, total, pct)
                        }
                    }
                }
                
                kotlinx.coroutines.joinAll(*workers.toTypedArray())

                if (!isActive) {
                    _downloadState.value = CacheDownloadState.Idle
                    return@launch
                }

                val finalSkipped = skippedCount.get()
                val finalCount = count.get()

                // If every tile was already on disk, report "already cached"
                if (finalSkipped == total) {
                    _downloadState.value = CacheDownloadState.AlreadyCached(total)
                    return@launch
                }

                val finalSize = getDirectorySize(tileDir)
                val deltaMb = ((finalSize - initialSize).coerceAtLeast(0L)).toDouble() / (1024.0 * 1024.0)

                _downloadState.value = CacheDownloadState.Completed(finalCount, deltaMb)
            } catch (e: Exception) {
                if (e is CancellationException) {
                    _downloadState.value = CacheDownloadState.Idle
                } else {
                    _downloadState.value = CacheDownloadState.Failed(e.message ?: "Download failed")
                }
            }
        }
    }

    private fun lonToTileX(lon: Double, zoom: Int): Int {
        return floor((lon + 180.0) / 360.0 * (1 shl zoom)).toInt()
    }

    private fun latToTileY(lat: Double, zoom: Int): Int {
        val latRad = Math.toRadians(lat)
        return floor((1.0 - asinh(tan(latRad)) / PI) / 2.0 * (1 shl zoom)).toInt()
    }

    fun getDirectorySize(dir: File): Long {
        return try {
            var size = 0L
            dir.walkTopDown().forEach { if (it.isFile) size += it.length() }
            size
        } catch (_: Exception) {
            0L
        }
    }
}

