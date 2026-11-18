package online.hatala.whereami

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-height ModalBottomSheet showing a frozen snapshot of the current locality,
 * full administrative hierarchy, road/street details, outdoor areas & trails,
 * GPS coordinates, on-demand OSM/Nominatim codes, and active trip route list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaceDetailsModalSheet(
    snapshot: PlaceDetailsSnapshot,
    fetchExtendedMetadata: suspend (Double, Double) -> NominatimExtendedMetadata?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var extendedMeta by remember(snapshot.capturedAtMs) { mutableStateOf<NominatimExtendedMetadata?>(null) }
    var isLoadingMeta by remember(snapshot.capturedAtMs) { mutableStateOf(snapshot.lat != null && snapshot.lng != null) }

    LaunchedEffect(snapshot.capturedAtMs) {
        val lat = snapshot.lat
        val lng = snapshot.lng
        if (lat != null && lng != null) {
            isLoadingMeta = true
            extendedMeta = fetchExtendedMetadata(lat, lng)
            isLoadingMeta = false
        } else {
            isLoadingMeta = false
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF0F172A),
        contentColor = Color.White,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .width(40.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFF475569))
            )
        }
    ) {
        val primary = snapshot.primaryPlace
        val secondary = snapshot.secondaryPlace
        val capturedTimeStr = remember(snapshot.capturedAtMs) {
            SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(snapshot.capturedAtMs))
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .padding(horizontal = 18.dp)
        ) {
            // Header row with canonical 36dp slate close button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        text = primary?.city?.takeIf { it.isNotBlank() } ?: "Unknown Locality",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (secondary != null && secondary.city.isNotBlank() && !secondary.city.equals(primary?.city, ignoreCase = true)) {
                        Text(
                            text = secondary.city,
                            fontSize = 14.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }
                    Row(
                        modifier = Modifier.padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PlaceDetailBadge(
                            text = "Snapshot $capturedTimeStr",
                            bgColor = Color(0xFF1E293B),
                            textColor = Color(0xFFCBD5E1)
                        )
                        val freshnessLabel = when (snapshot.dataFreshness) {
                            PlaceDataFreshness.FRESH_LIVE -> "LIVE"
                            PlaceDataFreshness.LOCAL_CACHE_HIT -> "CACHED"
                            PlaceDataFreshness.STALE_FALLBACK -> "STALE"
                        }
                        val freshnessColor = when (snapshot.dataFreshness) {
                            PlaceDataFreshness.FRESH_LIVE -> Color(0xFF10B981)
                            PlaceDataFreshness.LOCAL_CACHE_HIT -> Color(0xFF38BDF8)
                            PlaceDataFreshness.STALE_FALLBACK -> Color(0xFFF59E0B)
                        }
                        PlaceDetailBadge(
                            text = freshnessLabel,
                            bgColor = freshnessColor.copy(alpha = 0.2f),
                            textColor = freshnessColor
                        )
                    }
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1E293B))
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            HorizontalDivider(color = Color(0xFF1E293B))

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(top = 14.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 1. Administrative Hierarchy
                item {
                    PlaceDetailSectionCard(title = "Administrative Hierarchy") {
                        if (snapshot.hierarchySubtitle.isNotBlank()) {
                            Text(
                                text = snapshot.hierarchySubtitle,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF38BDF8),
                                modifier = Modifier.padding(bottom = 6.dp)
                            )
                        }
                        PlaceDetailRow("Locality", primary?.city)
                        PlaceDetailRow("Municipality (gm.)", primary?.gmina)
                        PlaceDetailRow("County (pow.)", primary?.powiat)
                        PlaceDetailRow("Voivodeship / Region", primary?.voivodeship)
                        PlaceDetailRow(
                            "Country",
                            listOfNotNull(
                                primary?.country?.takeIf { it.isNotBlank() },
                                primary?.countryCode?.takeIf { it.isNotBlank() }?.uppercase(Locale.ROOT)?.let { "[$it]" }
                            ).joinToString(" ")
                        )
                    }
                }

                // 2. Road & Street
                val formattedStreet = RoadNameNormalizer.formatStreetDisplay(
                    street = primary?.street,
                    roadRef = primary?.roadRef,
                    countryCode = primary?.countryCode,
                    allowHouseNumber = true
                )
                if (!formattedStreet.isNullOrBlank() || !primary?.roadRef.isNullOrBlank() || !primary?.highwayType.isNullOrBlank()) {
                    item {
                        PlaceDetailSectionCard(title = "Road & Street") {
                            PlaceDetailRow("Display Road / Street", formattedStreet)
                            PlaceDetailRow("Raw Street", primary?.street)
                            PlaceDetailRow("Road Ref", primary?.roadRef)
                            PlaceDetailRow("OSM Highway Class", primary?.highwayType)
                        }
                    }
                }

                // 3. Outdoor Areas, Trails & Nearby Features
                val outdoor = snapshot.outdoorContext
                if (outdoor != null && outdoor.hasAnyOutdoor) {
                    item {
                        PlaceDetailSectionCard(title = "Outdoor Context & Trails") {
                            outdoor.mountainRange?.let { area ->
                                PlaceDetailRow("Mountain Range", area.name, area.kind.iconEmoji)
                            }
                            outdoor.valley?.let { area ->
                                PlaceDetailRow("Valley", area.name, area.kind.iconEmoji)
                            }
                            outdoor.protectedAreas.forEach { area ->
                                PlaceDetailRow(area.kind.displayName, area.name, area.kind.iconEmoji)
                            }
                            outdoor.activeTrails.forEach { trail ->
                                val distLabel = if (trail.distanceMeters > 0f) " (${Math.round(trail.distanceMeters)} m)" else ""
                                val refPart = trail.ref?.let { "[$it] " } ?: ""
                                PlaceDetailRow(trail.kind.displayName, "$refPart${trail.name}$distLabel", trail.kind.iconEmoji)
                            }
                            outdoor.waterBody?.let { wb ->
                                val distLabel = if (wb.distanceMeters > 0f) " (${Math.round(wb.distanceMeters)} m)" else ""
                                PlaceDetailRow("Water Body", "${wb.name}$distLabel")
                            }
                            outdoor.nearestFeatures.forEach { feat ->
                                val elev = feat.elevationMeters?.let { " • ${it} m a.s.l." } ?: ""
                                val dist = if (feat.distanceMeters > 0f) " • ${Math.round(feat.distanceMeters)} m" else ""
                                PlaceDetailRow(feat.kind.name.lowercase(Locale.ROOT).replace('_', ' ').replaceFirstChar { it.uppercase() }, "${feat.name}$elev$dist")
                            }
                        }
                    }
                }

                // 4. GPS Coordinates & Telemetry
                item {
                    PlaceDetailSectionCard(title = "Coordinates & Telemetry") {
                        val lat = snapshot.lat
                        val lng = snapshot.lng
                        if (lat != null && lng != null) {
                            val decimalStr = CoordinateFormatHelper.formatDecimal(lat, lng)
                            val dmsStr = CoordinateFormatHelper.formatDms(lat, lng)
                            PlaceDetailRow("Decimal (WGS84)", decimalStr)
                            PlaceDetailRow("DMS", dmsStr)

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        copyToClipboard(context, "Coordinates", decimalStr)
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Copy Coords", color = Color.White, fontSize = 12.sp, maxLines = 1, softWrap = false)
                                }
                                Button(
                                    onClick = {
                                        openInGoogleMaps(context, lat, lng, primary?.city ?: "")
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Map, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Open Maps", color = Color.White, fontSize = 12.sp, maxLines = 1, softWrap = false)
                                }
                            }
                        } else {
                            Text("Waiting for GPS coordinates...", fontSize = 12.sp, color = Color(0xFF94A3B8))
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        snapshot.altitudeM?.let { alt ->
                            PlaceDetailRow("Altitude", "${Math.round(alt)} m a.s.l.")
                        }
                        snapshot.accuracyM?.let { acc ->
                            PlaceDetailRow("Horizontal Accuracy", "±${Math.round(acc)} m")
                        }
                        snapshot.speedMs?.let { spd ->
                            PlaceDetailRow("Speed at Snapshot", String.format(Locale.getDefault(), "%.1f km/h", spd * 3.6f))
                        }
                    }
                }

                // 5. Place Codes & OSM Metadata (On-Demand)
                item {
                    PlaceDetailSectionCard(title = "Codes & OSM Metadata") {
                        when {
                            isLoadingMeta -> {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.padding(vertical = 4.dp)
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = Color(0xFF38BDF8)
                                    )
                                    Text(
                                        text = "Fetching postal, ISO, and OSM metadata...",
                                        fontSize = 12.sp,
                                        color = Color(0xFF94A3B8)
                                    )
                                }
                            }
                            extendedMeta == null -> {
                                Text(
                                    text = "Extended OSM metadata unavailable offline or rate-limited.",
                                    fontSize = 12.sp,
                                    color = Color(0xFF94A3B8)
                                )
                            }
                            else -> {
                                val meta = extendedMeta!!
                                PlaceDetailRow("Postal Code", meta.postcode)
                                PlaceDetailRow("ISO 3166-2 Region", meta.iso3166Lvl4)
                                PlaceDetailRow("TERYT / National ID", meta.terytSimc)
                                PlaceDetailRow("Municipality Code", meta.municipalityCode)
                                val osmObject = if (meta.osmType != null && meta.osmId != null) {
                                    "${meta.osmType} #${meta.osmId}"
                                } else null
                                PlaceDetailRow("OSM Object", osmObject)
                                val osmKind = listOfNotNull(meta.osmClass, meta.osmPlaceType).joinToString(" / ").takeIf { it.isNotBlank() }
                                PlaceDetailRow("OSM Class / Type", osmKind)
                                val rankAdmin = listOfNotNull(
                                    meta.placeRank?.let { "place_rank=$it" },
                                    meta.adminLevel?.let { "admin_level=$it" }
                                ).joinToString(", ").takeIf { it.isNotBlank() }
                                PlaceDetailRow("Hierarchy Rank", rankAdmin)
                                PlaceDetailRow("Population", meta.population)
                                PlaceDetailRow("Wikidata", meta.wikidataId)
                                PlaceDetailRow("Wikipedia", meta.wikipediaTag)
                                if (!meta.displayName.isNullOrBlank()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = meta.displayName,
                                        fontSize = 11.sp,
                                        color = Color(0xFF94A3B8)
                                    )
                                }
                            }
                        }
                    }
                }

                // 6. Current Trip Route List (only while recording)
                val route = snapshot.tripRoute
                if (route != null) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                EmojiText(emoji = "🚩", fontSize = 16.sp, yOffsetDp = 0.dp)
                                Text(
                                    text = "Current Trip Route",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                            Text(
                                text = "${route.placesVisited.size} places • ${String.format(Locale.getDefault(), "%.1f km", route.distanceMeters / 1000.0)}",
                                fontSize = 12.sp,
                                color = Color(0xFF94A3B8)
                            )
                        }
                    }

                    if (route.placesVisited.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF1E293B))
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No intermediate places recorded yet.",
                                    color = Color(0xFF94A3B8),
                                    fontSize = 12.sp
                                )
                            }
                        }
                    } else {
                        items(route.placesVisited) { place ->
                            val isFirst = route.placesVisited.firstOrNull() == place
                            val isLast = route.placesVisited.lastOrNull() == place
                            val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(place.timestamp))
                            val distStr = String.format(Locale.getDefault(), "%.1f km", place.distanceAtEntryMeters / 1000.0)

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF1E293B))
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(if (isLast) Color(0xFF10B981) else if (isFirst) Color(0xFF38BDF8) else Color(0xFFFBBF24))
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = place.placeName,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            modifier = Modifier.weight(1f, fill = false),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = timeStr,
                                            fontSize = 12.sp,
                                            color = Color(0xFF38BDF8),
                                            fontWeight = FontWeight.SemiBold,
                                            softWrap = false
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = place.hierarchySubtitle,
                                            fontSize = 11.sp,
                                            color = Color(0xFF94A3B8),
                                            modifier = Modifier.weight(1f, fill = false),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = distStr,
                                            fontSize = 11.sp,
                                            color = Color(0xFF64748B),
                                            softWrap = false
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaceDetailSectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF1E293B),
        border = BorderStroke(1.dp, Color(0xFF334155)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                text = title.uppercase(Locale.ROOT),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF94A3B8),
                letterSpacing = 0.6.sp
            )
            Spacer(modifier = Modifier.height(2.dp))
            content()
        }
    }
}

@Composable
private fun PlaceDetailRow(
    label: String,
    value: String?,
    leadingEmoji: String? = null
) {
    if (value.isNullOrBlank()) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(0.42f).padding(end = 8.dp)
        ) {
            if (!leadingEmoji.isNullOrBlank()) {
                EmojiText(emoji = leadingEmoji, fontSize = 12.sp, yOffsetDp = 0.dp)
                Spacer(modifier = Modifier.width(5.dp))
            }
            Text(
                text = label,
                fontSize = 12.sp,
                color = Color(0xFF94A3B8),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White,
            modifier = Modifier.weight(0.58f)
        )
    }
}

@Composable
private fun PlaceDetailBadge(
    text: String,
    bgColor: Color,
    textColor: Color
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = bgColor,
        modifier = Modifier.height(20.dp)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 7.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = textColor,
                maxLines = 1,
                softWrap = false,
                style = TextStyle(
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                    lineHeightStyle = LineHeightStyle(
                        alignment = LineHeightStyle.Alignment.Center,
                        trim = LineHeightStyle.Trim.Both
                    ),
                    lineHeight = 10.sp
                )
            )
        }
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
}
