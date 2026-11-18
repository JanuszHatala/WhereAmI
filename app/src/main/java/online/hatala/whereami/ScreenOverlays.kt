package online.hatala.whereami

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessLow
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Unscaled overlay sitting above the magnified root content box.
 * - In AWAITING_TAP: shows hint banner and captures a single tap to focus magnification.
 * - In ACTIVE: intercepts all touch input (view-only underneath), supports 1-finger pan and
 *   2-finger pinch (1.5x..5.0x), and renders a fixed corner close control.
 */
@Composable
fun BoxScope.MagnifierInteractionOverlay(
    state: MagnifierState,
    viewportWidthPx: Float,
    viewportHeightPx: Float,
    onUpdateState: (MagnifierState) -> Unit,
    onDismiss: () -> Unit
) {
    when (state.phase) {
        MagnifierPhase.OFF -> Unit
        MagnifierPhase.AWAITING_TAP -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x220F172A))
                    .pointerInput(viewportWidthPx, viewportHeightPx) {
                        detectTapGestures { offset ->
                            onUpdateState(
                                MagnifierState.activateAtTap(
                                    tapX = offset.x,
                                    tapY = offset.y,
                                    viewportWidth = viewportWidthPx,
                                    viewportHeight = viewportHeightPx
                                )
                            )
                        }
                    }
            ) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = Color(0xF00F172A),
                    border = BorderStroke(1.5.dp, Color(0xFF38BDF8)),
                    shadowElevation = 8.dp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 56.dp, start = 16.dp, end = 16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ZoomIn,
                            contentDescription = null,
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Tap any area to magnify",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                            style = TextStyle(
                                platformStyle = PlatformTextStyle(includeFontPadding = false),
                                lineHeightStyle = LineHeightStyle(
                                    alignment = LineHeightStyle.Alignment.Center,
                                    trim = LineHeightStyle.Trim.Both
                                ),
                                lineHeight = 13.sp
                            )
                        )
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF1E293B))
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Cancel Magnifier",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
        MagnifierPhase.ACTIVE -> {
            val currentState by rememberUpdatedState(state)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(2.dp, Color(0x8838BDF8))
                    .pointerInput(viewportWidthPx, viewportHeightPx) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            onUpdateState(
                                currentState.applyTransform(
                                    panX = pan.x,
                                    panY = pan.y,
                                    zoomChange = zoom,
                                    viewportWidth = viewportWidthPx,
                                    viewportHeight = viewportHeightPx
                                )
                            )
                        }
                    }
            ) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = Color(0xEC0F172A),
                    border = BorderStroke(1.dp, Color(0xFF38BDF8)),
                    shadowElevation = 8.dp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 48.dp, end = 16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ZoomIn,
                            contentDescription = null,
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = String.format(Locale.US, "%.1f×", state.scale),
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false,
                            style = TextStyle(
                                platformStyle = PlatformTextStyle(includeFontPadding = false),
                                lineHeightStyle = LineHeightStyle(
                                    alignment = LineHeightStyle.Alignment.Center,
                                    trim = LineHeightStyle.Trim.Both
                                ),
                                lineHeight = 13.sp
                            )
                        )
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF1E293B))
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close Magnifier",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Compact per-trip/session dimming override dialog triggered by long-pressing Keep Screen On.
 */
@Composable
fun DimSessionOverrideDialog(
    defaultEnabled: Boolean,
    defaultLevelPercent: Int,
    overrideEnabled: Boolean?,
    overrideLevelPercent: Int?,
    hasActiveTrip: Boolean,
    onSetOverrideEnabled: (Boolean?) -> Unit,
    onSetOverrideLevelPercent: (Int?) -> Unit,
    onResetOverride: () -> Unit,
    onDismiss: () -> Unit
) {
    val effectiveEnabled = ScreenDimController.isEffectiveDimEnabled(defaultEnabled, overrideEnabled)
    val effectiveLevel = ScreenDimController.effectiveLevelPercent(defaultLevelPercent, overrideLevelPercent)
    val hasAnyOverride = overrideEnabled != null || overrideLevelPercent != null

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
            border = BorderStroke(1.dp, Color(0xFF334155)),
            modifier = Modifier
                .widthIn(max = 400.dp)
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.BrightnessLow,
                            contentDescription = null,
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(20.dp)
                        )
                        Column {
                            Text(
                                text = "Screen Dimming Override",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = if (hasActiveTrip) "Applies until current trip ends" else "Applies for this app session",
                                fontSize = 11.sp,
                                color = Color(0xFF94A3B8)
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

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF1E293B))
                        .clickable { onSetOverrideEnabled(!effectiveEnabled) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "No dimming for this session",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                        Text(
                            text = "Keep full brightness while Keep Screen On is active",
                            fontSize = 11.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = !effectiveEnabled,
                        onCheckedChange = { noDim -> onSetOverrideEnabled(!noDim) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFF0284C7),
                            uncheckedThumbColor = Color(0xFF94A3B8),
                            uncheckedTrackColor = Color(0xFF334155)
                        )
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF1E293B))
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Minimum dim brightness",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (effectiveEnabled) Color.White else Color(0xFF64748B)
                        )
                        Text(
                            text = "$effectiveLevel%",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (effectiveEnabled) Color(0xFF38BDF8) else Color(0xFF64748B)
                        )
                    }
                    Slider(
                        value = effectiveLevel.toFloat(),
                        onValueChange = { onSetOverrideLevelPercent(it.roundToInt()) },
                        enabled = effectiveEnabled,
                        valueRange = ScreenDimController.MIN_DIM_LEVEL_PERCENT.toFloat()..ScreenDimController.MAX_DIM_LEVEL_PERCENT.toFloat(),
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF38BDF8),
                            activeTrackColor = Color(0xFF0284C7),
                            inactiveTrackColor = Color(0xFF334155)
                        )
                    )
                }

                if (hasAnyOverride) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Button(
                            onClick = {
                                onResetOverride()
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Reset to Default ($defaultLevelPercent%)", color = Color.White, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Settings card for configuring Keep Screen On idle dimming timeout and minimum brightness level.
 */
@Composable
fun ScreenDimSettingsCard(
    dimEnabled: Boolean,
    dimTimeoutSec: Int,
    dimLevelPercent: Int,
    onToggleDimEnabled: (Boolean) -> Unit,
    onSelectTimeoutSec: (Int) -> Unit,
    onChangeLevelPercent: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1E293B))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggleDimEnabled(!dimEnabled) },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Idle Screen Dimming",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    style = TextStyle(
                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                        lineHeight = 15.sp
                    )
                )
                Text(
                    text = "Lowers display brightness after inactivity while Keep Screen On is active; tap screen to wake.",
                    fontSize = 12.sp,
                    color = Color(0xFF94A3B8),
                    modifier = Modifier.padding(top = 2.dp),
                    style = TextStyle(
                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                        lineHeight = 15.sp
                    )
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Switch(
                checked = dimEnabled,
                onCheckedChange = onToggleDimEnabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Color(0xFF0284C7),
                    uncheckedThumbColor = Color(0xFF94A3B8),
                    uncheckedTrackColor = Color(0xFF334155)
                )
            )
        }

        if (dimEnabled) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Dim after inactivity",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFCBD5E1)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ScreenDimController.TIMEOUT_OPTIONS_SEC.forEach { sec ->
                        val selected = dimTimeoutSec == sec
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (selected) Color(0xFF0284C7) else Color(0xFF0F172A),
                            border = BorderStroke(1.dp, if (selected) Color(0xFF38BDF8) else Color(0xFF334155)),
                            modifier = Modifier
                                .weight(1f)
                                .height(32.dp)
                                .clickable { onSelectTimeoutSec(sec) }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = ScreenDimController.formatTimeoutLabel(sec),
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }
            }

            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Minimum dim brightness",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFCBD5E1)
                    )
                    Text(
                        text = "$dimLevelPercent%",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF38BDF8)
                    )
                }
                Slider(
                    value = dimLevelPercent.toFloat(),
                    onValueChange = { onChangeLevelPercent(it.roundToInt()) },
                    valueRange = ScreenDimController.MIN_DIM_LEVEL_PERCENT.toFloat()..ScreenDimController.MAX_DIM_LEVEL_PERCENT.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF38BDF8),
                        activeTrackColor = Color(0xFF0284C7),
                        inactiveTrackColor = Color(0xFF334155)
                    )
                )
                Text(
                    text = "Tip: Long-press the eye button on the map to adjust or disable dimming for one trip.",
                    fontSize = 11.sp,
                    color = Color(0xFF94A3B8)
                )
            }
        }
    }
}
