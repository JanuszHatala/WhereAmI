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
