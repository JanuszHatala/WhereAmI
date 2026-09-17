package janush.tech.whereami

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch

@Composable
fun CacheManagerDialog(
    onDismissRequest: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cacheManager = remember { CacheManager.getInstance(context) }

    var tileBytes by remember { mutableStateOf(0L) }
    var boundaryBytes by remember { mutableStateOf(0L) }
    var boundaryCount by remember { mutableStateOf(0) }
    var spatialBytes by remember { mutableStateOf(0L) }
    var spatialCount by remember { mutableStateOf(0L) }

    var allowOnBattery by remember { mutableStateOf(cacheManager.allowOnBattery) }
    var allowMobileData by remember { mutableStateOf(cacheManager.allowMobileData) }

    var isPreloading by remember { mutableStateOf(false) }
    var preloadProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    fun refreshMetrics() {
        scope.launch {
            tileBytes = cacheManager.getTileCacheBytes()
            boundaryBytes = cacheManager.getBoundaryCacheBytes()
            boundaryCount = cacheManager.getBoundaryCacheCount()
            spatialBytes = cacheManager.getSpatialCacheBytes()
            spatialCount = cacheManager.getSpatialCacheCount()
        }
    }

    LaunchedEffect(Unit) {
        refreshMetrics()
    }

    fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024f * 1024f))
            bytes >= 1024 -> String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024f)
            else -> "$bytes B"
        }
    }

    Dialog(onDismissRequest = onDismissRequest) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFF1E293B),
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Storage,
                            contentDescription = null,
                            tint = Color(0xFF38BDF8)
                        )
                        Text(
                            text = "Offline Data & Cache",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    IconButton(
                        onClick = onDismissRequest,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color(0xFF94A3B8)
                        )
                    }
                }

                // Storage Breakdown Card
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "STORAGE BREAKDOWN",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF64748B),
                            letterSpacing = 1.sp
                        )

                        // 1. Map Tiles
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Map Tiles", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text(formatBytes(tileBytes), color = Color(0xFF94A3B8), fontSize = 12.sp)
                            }
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        cacheManager.clearTileCache()
                                        refreshMetrics()
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Clear", fontSize = 12.sp, color = Color(0xFFF87171))
                            }
                        }

                        Divider(color = Color(0xFF1E293B))

                        // 2. Locality Boundaries
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Boundaries", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text("${formatBytes(boundaryBytes)} • $boundaryCount places", color = Color(0xFF94A3B8), fontSize = 12.sp)
                            }
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        cacheManager.clearBoundaryCache()
                                        refreshMetrics()
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Clear", fontSize = 12.sp, color = Color(0xFFF87171))
                            }
                        }

                        Divider(color = Color(0xFF1E293B))

                        // 3. Persistent Spatial Address & Street DB
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Addresses & Streets", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text("${formatBytes(spatialBytes)} • $spatialCount points", color = Color(0xFF94A3B8), fontSize = 12.sp)
                            }
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        cacheManager.clearSpatialCache()
                                        refreshMetrics()
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Clear", fontSize = 12.sp, color = Color(0xFFF87171))
                            }
                        }

                        Divider(color = Color(0xFF1E293B))

                        // Total
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Total Cached Data", color = Color(0xFF38BDF8), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text(formatBytes(tileBytes + boundaryBytes + spatialBytes), color = Color(0xFF38BDF8), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // Battery & Network Rules Card
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "BATTERY & NETWORK RULES",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF64748B),
                            letterSpacing = 1.sp
                        )

                        // Battery toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text("Allow on battery", color = Color.White, fontSize = 14.sp)
                                Text(
                                    if (allowOnBattery) "Pre-fetching permitted on battery" else "Pre-fetch only runs while charging",
                                    color = Color(0xFF94A3B8),
                                    fontSize = 11.sp
                                )
                            }
                            Switch(
                                checked = allowOnBattery,
                                onCheckedChange = {
                                    allowOnBattery = it
                                    cacheManager.allowOnBattery = it
                                }
                            )
                        }

                        Divider(color = Color(0xFF1E293B))

                        // Mobile Data toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text("Allow on mobile data", color = Color.White, fontSize = 14.sp)
                                Text(
                                    if (allowMobileData) "Pre-fetching permitted on cellular" else "Pre-fetch only runs on Wi-Fi",
                                    color = Color(0xFF94A3B8),
                                    fontSize = 11.sp
                                )
                            }
                            Switch(
                                checked = allowMobileData,
                                onCheckedChange = {
                                    allowMobileData = it
                                    cacheManager.allowMobileData = it
                                }
                            )
                        }
                    }
                }

                // Route Pre-fetch Action Card
                Button(
                    onClick = {
                        if (!isPreloading) {
                            isPreloading = true
                            statusMessage = null
                            scope.launch {
                                val result = cacheManager.prefetchTripCorridors { current, total ->
                                    preloadProgress = current to total
                                }
                                isPreloading = false
                                preloadProgress = null
                                refreshMetrics()
                                statusMessage = when (result) {
                                    is CacheManager.PreloadResult.BlockedByBattery ->
                                        "Blocked: Device is not charging. Connect charger or enable 'Allow on battery'."
                                    is CacheManager.PreloadResult.BlockedByNetwork ->
                                        "Blocked: Not on Wi-Fi. Connect to Wi-Fi or enable 'Allow on mobile data'."
                                    is CacheManager.PreloadResult.Completed ->
                                        "Complete: Cached ${result.addedCount} new points (${result.alreadyCachedCount} already cached)."
                                    is CacheManager.PreloadResult.Failed ->
                                        "Failed: ${result.error}"
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                    shape = RoundedCornerShape(10.dp),
                    enabled = !isPreloading
                ) {
                    if (isPreloading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        val prog = preloadProgress
                        val txt = if (prog != null) "Pre-fetching (${prog.first}/${prog.second})..." else "Pre-fetching..."
                        Text(txt, fontSize = 13.sp)
                    } else {
                        Icon(imageVector = Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Pre-fetch Recorded Route Areas", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }

                statusMessage?.let { msg ->
                    Text(
                        text = msg,
                        color = if (msg.startsWith("Complete")) Color(0xFF4ADE80) else Color(0xFFFBBF24),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}
