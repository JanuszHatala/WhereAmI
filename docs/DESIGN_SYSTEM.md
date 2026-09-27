# WhereAmI Design System & UI Modal Architecture

## 1. Purpose & Core Principles
This document formalizes the interaction patterns, component hierarchy, modal dismiss rules, and visual contrast standards across WhereAmI.

### The 3 Fundamental Tenets:
1. **Unobstructed Situational Awareness**: Locality and canonical road status must remain primary, instantly legible at an arm's length (in vehicle mount or bike handlebar).
2. **Deterministic Navigation**: Every bottom sheet, modal dialog, and full-screen overlay must behave with 100% predictable dismissal logic.
3. **High-Contrast Outdoor Legibility**: Ban low-contrast gray-on-gray or muted elements. Text and iconography must meet WCAG 2.1 AA contrast ratios ($> 4.5:1$ for body, $> 3:1$ for large headings).

---

## 2. Modal & Sheet Dismissal Standards

### Problem Identified
Previous iterations used inconsistent closing patterns: some sheets had a dedicated "Close" button, some had an "X" in the corner, some had "Done" / "Cancel", and some only responded to clicking outside the scrim or Android system back.

### Standardized Modal Hierarchy

| Modal Type | Top-Right Action | Bottom Action Bar | Scrim Click | Back Handler |
|---|---|---|---|---|
| **ModalBottomSheet** (e.g. Map Settings, App Settings, Live Sharing) | Close icon (`Icons.Default.Close`) in header row | Dedicated action buttons (e.g. "Save", "Start Sharing", "Clear Cache") | Dismisses sheet | Dismisses sheet |
| **Full-Screen Dialog** (e.g. Places & Trips History, Statistics) | Close icon button (`Icons.Default.Close`) at top-right | Tab bar or primary export actions | N/A (Full screen) | Dismisses dialog |
| **Confirmation / Input Dialog** (e.g. Rename Trip, Save Place) | None | Standard Dialog buttons: "Cancel" (left/outlined) & "Save" / "Confirm" (right/filled) | Dismisses dialog without changes | Dismisses dialog without changes |

### Unified Modal Header Component Pattern
Every bottom sheet and full-screen screen overlay MUST employ the following standardized header:
```kotlin
Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = headerIcon,
            contentDescription = null,
            tint = Color(0xFF38BDF8),
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = titleText,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
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
            tint = Color(0xFF94A3B8),
            modifier = Modifier.size(18.dp)
        )
    }
}
```

---

## 3. High-Contrast Dark Theme Palette

All cards, chips, and overlays use the following standardized high-contrast color tokens:

- **Surface Dark**: `#0F172A` (Slate 900) - Sheet backgrounds
- **Card Dark**: `#1E293B` (Slate 800) - Elevated cards & containers
- **Primary Text**: `#F8FAFC` (Slate 50) - 100% white-like contrast for maximum sun legibility
- **Secondary Text / Subtitle**: `#E2E8F0` (Slate 200) - Never use muted gray `#64748B` for vital data!
- **Muted Metadata**: `#94A3B8` (Slate 400) - For timestamps and minor units only
- **Primary Accent**: `#0284C7` (Sky 600) / `#38BDF8` (Sky 400)
- **Active / Success**: `#10B981` (Emerald 500)
- **Warning / Paused**: `#F59E0B` (Amber 500)
- **Danger / Destructive**: `#EF4444` (Red 500)

---

## 4. Map Overlay Interaction Principles

- **Pinch-to-Zoom Supremacy**: Digital zoom buttons (`+` / `-`) clutter precious screen space and are removed.
- **Orientation Control**: Map orientation mode (`AUTO` course-up vs `NORTH` fixed-north) belongs in Map Settings, keeping the map canvas minimal and focused.

---

## 5. Single-Line & Multi-Element Alignment Architecture (Mandatory Guidelines)

To permanently prevent UI alignment glitches and unwanted text wrapping across development sessions, every developer and AI agent MUST adhere to these architectural rules:

### A. Emoji & Icon Baseline Alignment Rule
- **Never Concatenate Emoji in Raw Text Strings**: Do not write `Text("${profile.iconEmoji} ${profile.displayName}")`. In Android Compose, Emoji fonts (e.g. *Noto Color Emoji*) have vastly different ascender/descender metrics (`ascent ≈ 3× descent`) compared to alphanumeric fonts. Concatenating them in a single string throws off vertical centering completely.
- **Why PlatformTextStyle Alone Fails**: Setting `PlatformTextStyle(includeFontPadding = false)` removes Android's legacy padding, but it does NOT fix the intrinsic font metric imbalance of Noto Color Emoji. The text bounding box center remains significantly higher than the visual glyph center. Wrapping in a `Box(Modifier.size(...))` also fails because the emoji baseline still floats inside the box.
- **Mandatory Disentanglement & Alignment Standard**: Always use the project's standard `EmojiText` composable, and pair it with a matching `LineHeightStyle(alignment = LineHeightStyle.Alignment.Center, trim = LineHeightStyle.Trim.Both)` and `lineHeight = fontSize` on both the emoji and any adjacent label text:
```kotlin
Row(verticalAlignment = Alignment.CenterVertically) {
    EmojiText(
        emoji = profile.iconEmoji,
        fontSize = 13.sp
    )
    Spacer(modifier = Modifier.width(4.dp))
    Text(
        text = profile.displayName,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        style = TextStyle(
            platformStyle = PlatformTextStyle(includeFontPadding = false),
            lineHeightStyle = LineHeightStyle(
                alignment = LineHeightStyle.Alignment.Center,
                trim = LineHeightStyle.Trim.Both
            ),
            lineHeight = 12.sp
        )
    )
}
```
- **Standard `EmojiText` Definition**:
```kotlin
@Composable
fun EmojiText(
    emoji: String,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    yOffsetDp: Dp = Dp.Unspecified
) {
    // Optical vertical alignment:
    // Android's Noto Color Emoji font places emoji glyphs lower in the font metrics box than Latin capital letters.
    // To align the optical center of emoji glyphs with the cap-height center and baseline of adjacent text across the app,
    // we apply a default upward offset (-maxOf(2.5f, fontSize.value * 0.28f).dp).
    val effectiveOffset = if (yOffsetDp != Dp.Unspecified) {
        yOffsetDp
    } else {
        (-maxOf(2.5f, fontSize.value * 0.28f)).dp
    }
    Text(
        text = emoji,
        fontSize = fontSize,
        modifier = modifier.offset(y = effectiveOffset),
        style = TextStyle(
            platformStyle = PlatformTextStyle(includeFontPadding = false),
            lineHeightStyle = LineHeightStyle(
                alignment = LineHeightStyle.Alignment.Center,
                trim = LineHeightStyle.Trim.Both
            ),
            lineHeight = fontSize
        )
    )
}
```
- **Never Use**:
  - `Box(Modifier.size(18.dp)) { Text(emoji) }` — clips without correcting internal font metric offset.
  - `PlatformTextStyle(includeFontPadding = false)` alone without `LineHeightStyle.Trim.Both` and `lineHeight = fontSize`.

### B. Single-Line Multi-Metric Displays & Overflow Guarding
- **Strict Single-Line Guard**: In metric displays where multiple values are shown together (e.g. `Pace` + `Speed`, or `Distance` + `Max Speed`), every metric `Text` MUST specify:
  - `maxLines = 1`
  - `softWrap = false`
- **Ban Word-Breaking Glitches**: Never allow secondary units to break across whitespace (e.g. `(5.0 km/` wrapping onto `h)`).
- **Subordinate Sizing**: Secondary metrics must always be 2–3sp smaller than the primary metric and use muted color token `Color(0xFF94A3B8)`.
- **Flexible Content Weighting**: When displaying variable-length text (city, street, route names) alongside fixed metrics or buttons, the variable text must use `Modifier.weight(1f, fill = false)` with `overflow = TextOverflow.Ellipsis`.

### C. Landscape Mode Screen Space Conservation
- In landscape mode, vertical height is severely limited (~360–400dp) while horizontal width is abundant (~800–900dp).
- **No Vertical Stacking of Toolbars**: Never stack floating toolbars vertically above each other at `BottomCenter` in landscape.
- **Toolbar Collapse & Side-by-Side Placement**:
  - Secondary toolbars (e.g. Heat Map controls) must collapse to icon-only representations in landscape (hiding textual labels via `LocalConfiguration.current.orientation`).
  - Where possible, place toolbars side-by-side horizontally along the bottom edge rather than vertically stacked.

