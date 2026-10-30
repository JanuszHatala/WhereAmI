package online.hatala.whereami

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
    var autoStartOnWifi by remember { mutableStateOf(cacheManager.autoStartOnWifi) }
    var autoStartOnCharger by remember { mutableStateOf(cacheManager.autoStartOnCharger) }
    var notifyWhenAvailable by remember { mutableStateOf(cacheManager.notifyWhenAvailable) }
    var maxCacheSizeMb by remember { mutableStateOf(cacheManager.maxCacheSizeMb) }
    val snackbarHostState = remember { SnackbarHostState() }

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
        Box(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFF1E293B),
                tonalElevation = 6.dp,
                modifier = Modifier.fillMaxWidth()
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
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF334155))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
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
                            letterSpacing = 1.sp,
                            style = TextStyle(
                                platformStyle = PlatformTextStyle(includeFontPadding = false),
                                lineHeight = 14.sp
                            )
                        )

                        // 1. Map Tiles
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(
                                    text = "Map Tiles",
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    style = TextStyle(
                                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                                        lineHeight = 15.sp
                                    )
                                )
                                Text(
                                    text = formatBytes(tileBytes),
                                    color = Color(0xFFCBD5E1),
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(top = 1.dp),
                                    style = TextStyle(
                                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                                        lineHeight = 14.sp
                                    )
                                )
                            }
                            OutlinedButton(
                                onClick = { cacheClearConfirmTarget = "TILES" },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.6f))
                            ) {
                                Text(
                                    text = "Clear",
                                    fontSize = 12.sp,
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold,
                                    style = TextStyle(
                                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                                        lineHeight = 13.sp
                                    )
                                )
                            }
                        }

                        Divider(color = Color(0xFF1E293B))

                        // 2. Locality Boundaries
                        val isRunning = prefetchState is CacheManager.PrefetchState.Running
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(
                                    text = "Boundaries",
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    style = TextStyle(
                                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                                        lineHeight = 15.sp
                                    )
                                )
                                val missingBoundaries = cacheDeficit?.missingBoundaries
                                when {
                                    missingBoundaries == null -> Text(
                                        text = "…",
                                        color = Color(0xFF94A3B8),
                                        fontSize = 11.sp,
                                        style = TextStyle(
                                            platformStyle = PlatformTextStyle(includeFontPadding = false),
                                            lineHeight = 13.sp
                                        )
                                    )
                                    missingBoundaries == 0 -> Text(
                                        text = "✓ complete",
                                        color = Color(0xFF4ADE80),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        style = TextStyle(
                                            platformStyle = PlatformTextStyle(includeFontPadding = false),
                                            lineHeight = 13.sp
                                        )
                                    )
                                    else -> Text(
                                        text = "⚠ $missingBoundaries missing",
                                        color = Color(0xFFFBBF24),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        style = TextStyle(
                                            platformStyle = PlatformTextStyle(includeFontPadding = false),
                                            lineHeight = 13.sp
                                        )
                                    )
                                }
                                Text(
                                    text = "${formatBytes(boundaryBytes)} • $boundaryCount places",
                                    color = Color(0xFFCBD5E1),
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(top = 1.dp),
                                    style = TextStyle(
                                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                                        lineHeight = 14.sp
                                    )
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                val missingBoundaries = cacheDeficit?.missingBoundaries
                                if (missingBoundaries != null && missingBoundaries > 0) {
                                    Button(
                                        onClick = { if (!isRunning) cacheManager.fetchMissingBoundaries() },
                                        enabled = !isRunning,
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
                                    ) {
                                        if (isRunning) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(12.dp),
                                                strokeWidth = 2.dp,
                                                color = Color.White
                                            )
                                        } else {
                                            Text(
                                                text = "Fetch",
                                                fontSize = 11.sp,
                                                color = Color.White,
                                                fontWeight = FontWeight.Bold,
                                                style = TextStyle(
                                                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                                                    lineHeight = 13.sp
                                                )
                                            )
                                        }
                                    }
                                }
                                OutlinedButton(
                                    onClick = { cacheClearConfirmTarget = "BOUNDARIES" },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.6f))
                                ) {
                                    Text(
                                        text = "Clear",
                                        fontSize = 11.sp,
                                        color = Color.White,
                                        fontWeight = FontWeight.SemiBold,
                                        style = TextStyle(
                                            platformStyle = PlatformTextStyle(includeFontPadding = false),
                                            lineHeight = 13.sp
                                        )
                                    )
                                }
                            }
                        }

                        Divider(color = Color(0xFF1E293B))

                        // 3. Persistent Spatial Address & Street DB
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(
                                    text = "Addresses & Streets",
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    style = TextStyle(
                                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                                        lineHeight = 15.sp
                                    )
                                )
                                val missingRoutes = cacheDeficit?.missingRoutes
                                when {
                                    missingRoutes == null -> Text(
                                        text = "…",
                                        color = Color(0xFF94A3B8),
                                        fontSize = 11.sp,
                                        style = TextStyle(
                                            platformStyle = PlatformTextStyle(includeFontPadding = false),
                                            lineHeight = 13.sp
                                        )
                                    )
                                    missingRoutes == 0 -> Text(
                                        text = "✓ complete",
                                        color = Color(0xFF4ADE80),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        style = TextStyle(
                                            platformStyle = PlatformTextStyle(includeFontPadding = false),
                                            lineHeight = 13.sp
                                        )
                                    )
                                    else -> Text(
                                        text = "⚠ $missingRoutes missing",
                                        color = Color(0xFFFBBF24),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        style = TextStyle(
                                            platformStyle = PlatformTextStyle(includeFontPadding = false),
                                            lineHeight = 13.sp
                                        )
                                    )
                                }
                                Text(
                                    text = "${formatBytes(spatialBytes)} • $spatialCount points",
                                    color = Color(0xFFCBD5E1),
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(top = 1.dp),
                                    style = TextStyle(
                                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                                        lineHeight = 14.sp
                                    )
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                val missingRoutes = cacheDeficit?.missingRoutes
                                if (missingRoutes != null && missingRoutes > 0) {
                                    Button(
                                        onClick = { if (!isRunning) cacheManager.startPrefetch() },
                                        enabled = !isRunning,
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
                                    ) {
                                        if (isRunning) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(12.dp),
                                                strokeWidth = 2.dp,
                                                color = Color.White
                                            )
                                        } else {
                                            Text(
                                                text = "Fetch",
                                                fontSize = 11.sp,
                                                color = Color.White,
                                                fontWeight = FontWeight.Bold,
                                                style = TextStyle(
                                                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                                                    lineHeight = 13.sp
                                                )
                                            )
                                        }
                                    }
                                }
                                OutlinedButton(
                                    onClick = { cacheClearConfirmTarget = "SPATIAL" },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.6f))
                                ) {
                                    Text(
                                        text = "Clear",
                                        fontSize = 11.sp,
                                        color = Color.White,
                                        fontWeight = FontWeight.SemiBold,
                                        style = TextStyle(
                                            platformStyle = PlatformTextStyle(includeFontPadding = false),
                                            lineHeight = 13.sp
                                        )
                                    )
                                }
                            }
                        }

                        Divider(color = Color(0xFF1E293B))

                        // Total
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Total Cached Data",
                                color = Color(0xFF38BDF8),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                style = TextStyle(
                                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                                    lineHeight = 15.sp
                                )
                            )
                            Text(
                                text = formatBytes(tileBytes + boundaryBytes + spatialBytes),
                                color = Color(0xFF38BDF8),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                style = TextStyle(
                                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                                    lineHeight = 15.sp
                                )
                            )
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
                            letterSpacing = 1.sp,
                            style = TextStyle(
                                platformStyle = PlatformTextStyle(includeFontPadding = false),
                                lineHeight = 14.sp
                            )
                        )

                        // Battery toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(
                                    text = "Allow on battery",
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    style = TextStyle(
                                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                                        lineHeight = 15.sp
                                    )
                                )
                                Text(
                                    text = if (allowOnBattery) "Pre-fetching permitted on battery" else "Pre-fetch only runs while charging",
                                    color = Color(0xFF94A3B8),
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(top = 2.dp),
                                    style = TextStyle(
                                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                                        lineHeight = 13.5.sp
                                    )
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

                        HorizontalDivider(color = Color(0xFF1E293B))

                        // Mobile Data toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(
                                    text = "Allow on mobile data",
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    style = TextStyle(
                                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                                        lineHeight = 15.sp
                                    )
                                )
                                Text(
                                    text = if (allowMobileData) "Pre-fetching permitted on cellular" else "Pre-fetch only runs on Wi-Fi",
                                    color = Color(0xFF94A3B8),
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(top = 2.dp),
                                    style = TextStyle(
                                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                                        lineHeight = 13.5.sp
                                    )
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

                        HorizontalDivider(color = Color(0xFF1E293B))

                        Text(
                            text = "AUTO-UPDATE & NOTIFICATIONS",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF64748B),
                            letterSpacing = 0.8.sp,
                            style = TextStyle(
                                platformStyle = PlatformTextStyle(includeFontPadding = false),
                                lineHeight = 12.sp
                            )
                        )

                        // Auto-start on WiFi
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(
                                    text = "Auto-start on Wi-Fi",
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    style = TextStyle(
                                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                                        lineHeight = 15.sp
                                    )
                                )
                                Text(
                                    text = if (autoStartOnWifi) "Downloads begin automatically when on Wi-Fi" else "Manual start only",
                                    color = Color(0xFF94A3B8),
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(top = 2.dp),
                                    style = TextStyle(
                                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                                        lineHeight = 13.5.sp
                                    )
                                )
                            }
                            Switch(
                                checked = autoStartOnWifi,
                                onCheckedChange = {
                                    autoStartOnWifi = it
                                    cacheManager.autoStartOnWifi = it
                                }
                            )
                        }

                        HorizontalDivider(color = Color(0xFF1E293B))

                        // Auto-start on charger
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(
                                    text = "Auto-start when charging",
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    style = TextStyle(
                                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                                        lineHeight = 15.sp
                                    )
                                )
                                Text(
                                    text = if (autoStartOnCharger) "Downloads begin automatically when plugged into power" else "Manual start only",
                                    color = Color(0xFF94A3B8),
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(top = 2.dp),
                                    style = TextStyle(
                                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                                        lineHeight = 13.5.sp
                                    )
                                )
                            }
                            Switch(
                                checked = autoStartOnCharger,
                                onCheckedChange = {
                                    autoStartOnCharger = it
                                    cacheManager.autoStartOnCharger = it
                                }
                            )
                        }

                        HorizontalDivider(color = Color(0xFF1E293B))

                        // Notify when available
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(
                                    text = "Notify when updates available",
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    style = TextStyle(
                                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                                        lineHeight = 15.sp
                                    )
                                )
                                Text(
                                    text = if (notifyWhenAvailable) "System notification with Start/Pause/Stop actions shown when items need updating" else "Silent — no notification",
                                    color = Color(0xFF94A3B8),
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(top = 2.dp),
                                    style = TextStyle(
                                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                                        lineHeight = 13.5.sp
                                    )
                                )
                            }
                            Switch(
                                checked = notifyWhenAvailable,
                                onCheckedChange = {
                                    notifyWhenAvailable = it
                                    cacheManager.notifyWhenAvailable = it
                                }
                            )
                        }

                        HorizontalDivider(color = Color(0xFF1E293B))

                        // Max Cache Storage Budget
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "Max Storage Limit",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                style = TextStyle(
                                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                                    lineHeight = 15.sp
                                )
                            )
                            Text(
                                text = "Entries older than 1 year are evicted via LRU only when cache exceeds this limit",
                                color = Color(0xFF94A3B8),
                                fontSize = 11.sp,
                                modifier = Modifier.padding(top = 2.dp),
                                style = TextStyle(
                                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                                    lineHeight = 13.5.sp
                                )
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(500 to "500 MB", 1024 to "1 GB", 2048 to "2 GB", 5120 to "5 GB").forEach { (sizeMb, label) ->
                                    val isSelected = maxCacheSizeMb == sizeMb
                                    Surface(
                                        onClick = {
                                            maxCacheSizeMb = sizeMb
                                            cacheManager.maxCacheSizeMb = sizeMb
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (isSelected) Color(0xFF0284C7) else Color(0xFF1E293B),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            text = label,
                                            color = Color.White,
                                            fontSize = 11.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.padding(vertical = 6.dp),
                                            style = TextStyle(
                                                platformStyle = PlatformTextStyle(includeFontPadding = false),
                                                lineHeight = 13.sp
                                            )
                                        )
                                    }
                                }
                            }
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
                            letterSpacing = 1.sp,
                            style = TextStyle(
                                platformStyle = PlatformTextStyle(includeFontPadding = false),
                                lineHeight = 14.sp
                            )
                        )

                        Text(
                            text = "Locations visited during trip recording are automatically cached offline. Pre-fetch scans your trip history and fills in missing address & road data along recorded corridors in 2 passes (Pass 1: ~50m skeleton, Pass 2: ~15m precision). Downloads are rate-limited to 1 req/sec per OpenStreetMap usage policies.",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp,
                            lineHeight = 14.5.sp,
                            style = TextStyle(
                                platformStyle = PlatformTextStyle(includeFontPadding = false),
                                lineHeight = 14.5.sp
                            )
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
                                                scope.launch {
                                                    snackbarHostState.showSnackbar(
                                                        "✓ Cache is up to date. All routes and boundaries are cached.",
                                                        duration = SnackbarDuration.Short
                                                    )
                                                }
                                            } else {
                                                cacheManager.startPrefetch()
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Color(0xFF0284C7),
                                            contentColor = Color.White
                                        ),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Icon(
                                            if (totalMissing == 0) Icons.Default.Refresh else Icons.Default.CloudDownload,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = buttonText,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            style = TextStyle(
                                                platformStyle = PlatformTextStyle(includeFontPadding = false),
                                                lineHeight = 14.sp
                                            )
                                        )
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
                                        fontWeight = FontWeight.SemiBold,
                                        style = TextStyle(
                                            platformStyle = PlatformTextStyle(includeFontPadding = false),
                                            lineHeight = 14.sp
                                        )
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
                                            modifier = Modifier.weight(1f, fill = false).padding(end = 8.dp),
                                            style = TextStyle(
                                                platformStyle = PlatformTextStyle(includeFontPadding = false),
                                                lineHeight = 15.sp
                                            )
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
                                                softWrap = false,
                                                style = TextStyle(
                                                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                                                    lineHeight = 14.sp
                                                )
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
                                            modifier = Modifier.weight(1f, fill = false),
                                            style = TextStyle(
                                                platformStyle = PlatformTextStyle(includeFontPadding = false),
                                                lineHeight = 14.sp
                                            )
                                        )
                                        if (state.added > 0) {
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "+${state.added} new",
                                                color = Color(0xFF4ADE80),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                softWrap = false,
                                                style = TextStyle(
                                                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                                                    lineHeight = 14.sp
                                                )
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
                                            Text(
                                                text = "⏸ Pause",
                                                fontSize = 12.sp,
                                                color = Color.White,
                                                fontWeight = FontWeight.SemiBold,
                                                style = TextStyle(
                                                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                                                    lineHeight = 13.sp
                                                )
                                            )
                                        }
                                        Button(
                                            onClick = { cacheManager.cancelPrefetch() },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text(
                                                text = "🛑 Stop",
                                                fontSize = 12.sp,
                                                color = Color.White,
                                                fontWeight = FontWeight.SemiBold,
                                                style = TextStyle(
                                                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                                                    lineHeight = 13.sp
                                                )
                                            )
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
                                            modifier = Modifier.weight(1f, fill = false).padding(end = 8.dp),
                                            style = TextStyle(
                                                platformStyle = PlatformTextStyle(includeFontPadding = false),
                                                lineHeight = 15.sp
                                            )
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
                                                softWrap = false,
                                                style = TextStyle(
                                                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                                                    lineHeight = 13.sp
                                                )
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
                                            modifier = Modifier.weight(1f, fill = false),
                                            style = TextStyle(
                                                platformStyle = PlatformTextStyle(includeFontPadding = false),
                                                lineHeight = 14.sp
                                            )
                                        )
                                        if (state.added > 0) {
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "+${state.added} new",
                                                color = Color(0xFF4ADE80),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                softWrap = false,
                                                style = TextStyle(
                                                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                                                    lineHeight = 14.sp
                                                )
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
                                            Text(
                                                text = "▶ Resume",
                                                fontSize = 12.sp,
                                                color = Color.White,
                                                fontWeight = FontWeight.SemiBold,
                                                style = TextStyle(
                                                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                                                    lineHeight = 13.sp
                                                )
                                            )
                                        }
                                        Button(
                                            onClick = { cacheManager.cancelPrefetch() },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text(
                                                text = "🛑 Stop",
                                                fontSize = 12.sp,
                                                color = Color.White,
                                                fontWeight = FontWeight.SemiBold,
                                                style = TextStyle(
                                                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                                                    lineHeight = 13.sp
                                                )
                                            )
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
                                        fontWeight = FontWeight.Medium,
                                        style = TextStyle(
                                            platformStyle = PlatformTextStyle(includeFontPadding = false),
                                            lineHeight = 15.sp
                                        )
                                    )

                                    val totalMissing = cacheDeficit?.let { it.missingRoutes + it.missingBoundaries } ?: 0
                                    if (totalMissing > 0) {
                                        Button(
                                            onClick = { cacheManager.startPrefetch() },
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = Color(0xFF0284C7),
                                                contentColor = Color.White
                                            ),
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Text(
                                                text = "Fetch Remaining Missing Data ($totalMissing)",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White,
                                                style = TextStyle(
                                                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                                                    lineHeight = 14.sp
                                                )
                                            )
                                        }
                                    } else {
                                        Button(
                                            onClick = { cacheManager.calculateCacheDeficit() },
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = Color(0xFF0284C7),
                                                contentColor = Color.White
                                            ),
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Text(
                                                text = "Re-scan Cache",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White,
                                                style = TextStyle(
                                                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                                                    lineHeight = 14.sp
                                                )
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(2.dp))
                                    OutlinedButton(
                                        onClick = {
                                            cacheManager.validateAndCleanCache { count ->
                                                android.widget.Toast.makeText(context, "Cache validated. Cleaned $count stale entries.", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = Color.White
                                        ),
                                        border = BorderStroke(1.dp, Color(0xFF334155)),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Text(
                                            text = "Validate & Clean Cache",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            style = TextStyle(
                                                platformStyle = PlatformTextStyle(includeFontPadding = false),
                                                lineHeight = 14.sp
                                            )
                                        )
                                    }
                                }
                            }
                            is CacheManager.PrefetchState.Blocked -> {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        text = "⚠️ ${state.reason}",
                                        color = Color(0xFFFBBF24),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        style = TextStyle(
                                            platformStyle = PlatformTextStyle(includeFontPadding = false),
                                            lineHeight = 14.sp
                                        )
                                    )
                                    Button(
                                        onClick = { cacheManager.startPrefetch() },
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Color(0xFF0284C7),
                                            contentColor = Color.White
                                        ),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Text(
                                            text = "Retry",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            style = TextStyle(
                                                platformStyle = PlatformTextStyle(includeFontPadding = false),
                                                lineHeight = 14.sp
                                            )
                                        )
                                    }
                                }
                            }
                            is CacheManager.PrefetchState.Error -> {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        text = "❌ Error: ${state.message}",
                                        color = Color(0xFFF87171),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        style = TextStyle(
                                            platformStyle = PlatformTextStyle(includeFontPadding = false),
                                            lineHeight = 14.sp
                                        )
                                    )
                                    Button(
                                        onClick = { cacheManager.startPrefetch() },
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Color(0xFF0284C7),
                                            contentColor = Color.White
                                        ),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Text(
                                            text = "Retry",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            style = TextStyle(
                                                platformStyle = PlatformTextStyle(includeFontPadding = false),
                                                lineHeight = 14.sp
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            } // end Surface
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp)
            ) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = Color(0xFF0F172A),
                    contentColor = Color(0xFF10B981)
                )
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
                Text(
                    text = title,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    style = TextStyle(
                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                        lineHeight = 18.sp
                    )
                )
            },
            text = {
                Text(
                    text = msg,
                    color = Color(0xFFCBD5E1),
                    fontSize = 13.sp,
                    style = TextStyle(
                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                        lineHeight = 16.sp
                    )
                )
            },
            confirmButton = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { cacheClearConfirmTarget = null }) {
                        Text(
                            text = "Cancel",
                            color = Color.White,
                            style = TextStyle(
                                platformStyle = PlatformTextStyle(includeFontPadding = false),
                                lineHeight = 14.sp
                            )
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
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
                        Text(
                            text = "Clear",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            style = TextStyle(
                                platformStyle = PlatformTextStyle(includeFontPadding = false),
                                lineHeight = 14.sp
                            )
                        )
                    }
                }
            },
            dismissButton = null
        )
    }
}









