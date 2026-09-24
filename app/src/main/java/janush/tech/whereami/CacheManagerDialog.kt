package janush.tech.whereami

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

@Composable
fun CacheManagerDialog(
    onDismissRequest: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cacheManager = remember { CacheManager.getInstance(context) }
    val prefetchState by cacheManager.prefetchState.collectAsState()
    val cacheDeficit by cacheManager.cacheDeficit.collectAsState()

    var tileBytes by remember { mutableStateOf(0L) }
    var boundaryBytes by remember { mutableStateOf(0L) }
    var boundaryCount by remember { mutableStateOf(0) }
    var spatialBytes by remember { mutableStateOf(0L) }
    var spatialCount by remember { mutableStateOf(0L) }

    var allowOnBattery by remember { mutableStateOf(cacheManager.allowOnBattery) }
    var allowMobileData by remember { mutableStateOf(cacheManager.allowMobileData) }

    var cacheClearConfirmTarget by remember { mutableStateOf<String?>(null) } // "TILES", "BOUNDARIES", "SPATIAL"

    fun refreshMetrics() {
        cacheManager.calculateCacheDeficit()
        scope.launch {
            tileBytes = cacheManager.getTileCacheBytes()
            boundaryBytes = cacheManager.getBoundaryCacheBytes()
            boundaryCount = cacheManager.getBoundaryCacheCount()
            spatialBytes = cacheManager.getSpatialCacheBytes()
            spatialCount = cacheManager.getSpatialCacheCount()
        }
    }

    // Refresh metrics on start and when prefetch finishes
    LaunchedEffect(prefetchState) {
        if (prefetchState is CacheManager.PrefetchState.Idle || prefetchState is CacheManager.PrefetchState.Completed) {
            refreshMetrics()
        }
    }

    fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024f * 1024f))
            bytes >= 1024 -> String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024f)
            else -> "$bytes B"
        }
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFF1E293B),
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth(0.94f)
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
                            tint = Color.White
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
                            color = Color(0xFF38BDF8),
                            letterSpacing = 1.sp
                        )

                        // 1. Map Tiles
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text("Map Tiles", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text(formatBytes(tileBytes), color = Color(0xFFE2E8F0), fontSize = 12.sp)
                            }
                            OutlinedButton(
                                onClick = { cacheClearConfirmTarget = "TILES" },
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
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text("Boundaries", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    val missingBoundaries = cacheDeficit?.missingBoundaries
                                    when {
                                        missingBoundaries == null -> Text("…", color = Color(0xFF94A3B8), fontSize = 11.sp)
                                        missingBoundaries == 0 -> Text("✓ complete", color = Color(0xFF4ADE80), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                        else -> Text("⚠ $missingBoundaries missing", color = Color(0xFFFBBF24), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                                Text("${formatBytes(boundaryBytes)} • $boundaryCount places", color = Color(0xFFE2E8F0), fontSize = 12.sp)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                val missingBoundaries = cacheDeficit?.missingBoundaries
                                if (missingBoundaries != null && missingBoundaries > 0) {
                                    Button(
                                        onClick = { cacheManager.fetchMissingBoundaries() },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
                                    ) {
                                        Text("Fetch", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                    }
                                }
                                OutlinedButton(
                                    onClick = { cacheClearConfirmTarget = "BOUNDARIES" },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("Clear", fontSize = 11.sp, color = Color(0xFFF87171))
                                }
                            }
                        }

                        Divider(color = Color(0xFF1E293B))

                        // 3. Persistent Spatial Address & Street DB
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text("Addresses & Streets", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    val missingRoutes = cacheDeficit?.missingRoutes
                                    when {
                                        missingRoutes == null -> Text("…", color = Color(0xFF94A3B8), fontSize = 11.sp)
                                        missingRoutes == 0 -> Text("✓ complete", color = Color(0xFF4ADE80), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                        else -> Text("⚠ $missingRoutes missing", color = Color(0xFFFBBF24), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                                Text("${formatBytes(spatialBytes)} • $spatialCount points", color = Color(0xFFE2E8F0), fontSize = 12.sp)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                val missingRoutes = cacheDeficit?.missingRoutes
                                if (missingRoutes != null && missingRoutes > 0) {
                                    Button(
                                        onClick = { cacheManager.startPrefetch() },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
                                    ) {
                                        Text("Fetch", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                    }
                                }
                                OutlinedButton(
                                    onClick = { cacheClearConfirmTarget = "SPATIAL" },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("Clear", fontSize = 11.sp, color = Color(0xFFF87171))
                                }
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
                            color = Color(0xFF38BDF8),
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
                                    color = Color(0xFFE2E8F0),
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
                                    color = Color(0xFFE2E8F0),
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

                // Persistent Background Route Pre-fetch Control Card
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "OFFLINE ROUTE ADDRESSES & LOCALITIES",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF38BDF8),
                            letterSpacing = 1.sp
                        )

                        Text(
                            text = "Locations visited during trip recording are automatically cached offline. Pre-fetch scans your trip history and fills in missing address & road data along recorded corridors in 2 passes (Pass 1: ~50m skeleton, Pass 2: ~15m precision). Downloads are rate-limited to 1 req/sec per OpenStreetMap usage policies.",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )

                        when (val state = prefetchState) {
                            is CacheManager.PrefetchState.Idle -> {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    val totalMissing = cacheDeficit?.let { it.missingRoutes + it.missingBoundaries }
                                    val buttonText = when {
                                        totalMissing == null -> "Pre-fetch Missing Data"
                                        totalMissing > 0 -> "Pre-fetch All Missing Data ($totalMissing)"
                                        else -> "Re-scan Cache"
                                    }

                                    Button(
                                        onClick = {
                                            if (totalMissing == 0) {
                                                cacheManager.calculateCacheDeficit()
                                            } else {
                                                cacheManager.startPrefetch()
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Icon(
                                            if (totalMissing == 0) Icons.Default.Refresh else Icons.Default.CloudDownload,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(buttonText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    }

                                    val estimateStr = when (totalMissing) {
                                        null -> "Calculating pending items..."
                                        0 -> "All known routes and boundaries are fully cached offline."
                                        else -> "Found $totalMissing missing items to fetch."
                                    }
                                    val estimateColor = if (totalMissing == 0) Color(0xFF10B981) else Color(0xFFF59E0B)
                                    
                                    Text(
                                        text = estimateStr,
                                        fontSize = 12.sp,
                                        color = estimateColor,
                                        modifier = Modifier.align(Alignment.CenterHorizontally),
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                            is CacheManager.PrefetchState.Running -> {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    val percent = if (state.total > 0) (state.current * 100 / state.total) else 0

                                    // Row 1: Pass Title and Percentage Badge (guaranteed non-wrapping)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = state.passName,
                                            color = Color.White,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.weight(1f, fill = false).padding(end = 8.dp)
                                        )
                                        Surface(
                                            color = Color(0xFF0284C7).copy(alpha = 0.25f),
                                            shape = RoundedCornerShape(6.dp),
                                            border = BorderStroke(1.dp, Color(0xFF38BDF8))
                                        ) {
                                            Text(
                                                text = "$percent%",
                                                color = Color(0xFF38BDF8),
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                                softWrap = false
                                            )
                                        }
                                    }

                                    // Progress Bar
                                    LinearProgressIndicator(
                                        progress = if (state.total > 0) state.current.toFloat() / state.total.toFloat() else 0f,
                                        modifier = Modifier.fillMaxWidth().height(6.dp),
                                        color = Color(0xFF38BDF8),
                                        trackColor = Color(0xFF1E293B)
                                    )

                                    // Row 2: Progress Counts and Added Metric
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "${state.current} / ${state.total} pts",
                                            color = Color(0xFF94A3B8),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            softWrap = false,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        if (state.added > 0) {
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "+${state.added} new",
                                                color = Color(0xFF4ADE80),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                softWrap = false
                                            )
                                        }
                                    }

                                    // Action Buttons
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = { cacheManager.pausePrefetch() },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B)),
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text("⏸ Pause", fontSize = 12.sp, color = Color.White)
                                        }
                                        Button(
                                            onClick = { cacheManager.cancelPrefetch() },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text("🛑 Stop", fontSize = 12.sp, color = Color.White)
                                        }
                                    }
                                }
                            }
                            is CacheManager.PrefetchState.Paused -> {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    val percent = if (state.total > 0) (state.current * 100 / state.total) else 0

                                    // Row 1: Pass Title and Paused Percentage Badge (guaranteed non-wrapping)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = state.passName,
                                            color = Color.White,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.weight(1f, fill = false).padding(end = 8.dp)
                                        )
                                        Surface(
                                            color = Color(0xFFF59E0B).copy(alpha = 0.25f),
                                            shape = RoundedCornerShape(6.dp),
                                            border = BorderStroke(1.dp, Color(0xFFFBBF24))
                                        ) {
                                            Text(
                                                text = "$percent% (Paused)",
                                                color = Color(0xFFFBBF24),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                                softWrap = false
                                            )
                                        }
                                    }

                                    // Progress Bar
                                    LinearProgressIndicator(
                                        progress = if (state.total > 0) state.current.toFloat() / state.total.toFloat() else 0f,
                                        modifier = Modifier.fillMaxWidth().height(6.dp),
                                        color = Color(0xFFFBBF24),
                                        trackColor = Color(0xFF1E293B)
                                    )

                                    // Row 2: Progress Counts and Added Metric
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "${state.current} / ${state.total} pts",
                                            color = Color(0xFF94A3B8),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            softWrap = false,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        if (state.added > 0) {
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "+${state.added} new",
                                                color = Color(0xFF4ADE80),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                softWrap = false
                                            )
                                        }
                                    }

                                    // Action Buttons
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = { cacheManager.resumePrefetch() },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text("▶ Resume", fontSize = 12.sp, color = Color.White)
                                        }
                                        Button(
                                            onClick = { cacheManager.cancelPrefetch() },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text("🛑 Stop", fontSize = 12.sp, color = Color.White)
                                        }
                                    }
                                }
                            }
                            is CacheManager.PrefetchState.Completed -> {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    val statusText = if (state.addedRoutes == 0 && state.addedBoundaries == 0) {
                                        "✓ All recorded routes & boundaries are fully cached!"
                                    } else {
                                        "✓ Pre-fetch Complete! Downloaded: ${state.addedRoutes} route points, ${state.addedBoundaries} boundaries."
                                    }
                                    Text(
                                        text = statusText,
                                        color = Color(0xFF4ADE80),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium
                                    )

                                    val totalMissing = cacheDeficit?.let { it.missingRoutes + it.missingBoundaries } ?: 0
                                    if (totalMissing > 0) {
                                        Button(
                                            onClick = { cacheManager.startPrefetch() },
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Text("Fetch Remaining Missing Data ($totalMissing)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        }
                                    } else {
                                        Button(
                                            onClick = { cacheManager.calculateCacheDeficit() },
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Text("Re-scan Cache", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                            is CacheManager.PrefetchState.Blocked -> {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        text = "⚠️ ${state.reason}",
                                        color = Color(0xFFFBBF24),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Button(
                                        onClick = { cacheManager.startPrefetch() },
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Text("Retry", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            is CacheManager.PrefetchState.Error -> {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        text = "❌ Error: ${state.message}",
                                        color = Color(0xFFF87171),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Button(
                                        onClick = { cacheManager.startPrefetch() },
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Text("Retry", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Safety Confirmation Alert Dialog for Clearing Caches
    cacheClearConfirmTarget?.let { target ->
        val (title, msg) = when (target) {
            "TILES" -> "Clear Map Tiles Cache?" to "This will delete all downloaded offline map tiles. They will be re-downloaded when viewing the map."
            "BOUNDARIES" -> "Clear Administrative Boundaries?" to "This will delete all saved town and city boundary polygons. They will be fetched again on demand."
            "SPATIAL" -> "Clear Addresses & Streets Cache?" to "This will clear all locally cached reverse-geocoded coordinates and street names."
            else -> "Clear Cache?" to "Are you sure you want to clear this cached data?"
        }

        AlertDialog(
            onDismissRequest = { cacheClearConfirmTarget = null },
            containerColor = Color(0xFF0F172A),
            title = {
                Text(title, color = Color.White, fontWeight = FontWeight.Bold)
            },
            text = {
                Text(msg, color = Color(0xFFE2E8F0), fontSize = 14.sp)
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            when (target) {
                                "TILES" -> cacheManager.clearTileCache()
                                "BOUNDARIES" -> cacheManager.clearBoundaryCache()
                                "SPATIAL" -> cacheManager.clearSpatialCache()
                            }
                            cacheClearConfirmTarget = null
                            refreshMetrics()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                ) {
                    Text("Clear", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { cacheClearConfirmTarget = null }) {
                    Text("Cancel", color = Color.LightGray)
                }
            }
        )
    }
}









