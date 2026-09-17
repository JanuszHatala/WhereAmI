# Field Testing (2026-09-17) Multi-Step Implementation Plan

**Date**: 2026-09-17  
**Status**: DRAFT / Awaiting User Feedback & Approval  
**Architecture Reference**: [AGENTS.md](../AGENTS.md) | [FIELD_TEST_2026-09-17_ANALYSIS.md](FIELD_TEST_2026-09-17_ANALYSIS.md)

---

## Plan Overview & Workflow

In strict accordance with `AGENTS.md`:
1. Each phase is developed on a dedicated feature/fix branch created from `master`.
2. All algorithmic changes are covered by automated unit tests (`app/src/test/java/`).
3. Verification gates (`.\gradlew.bat testDebugUnitTest` and `.\gradlew.bat assembleDebug`) must pass before completing each step.
4. After verification and user approval, the branch is pushed to GitHub, merged to `master`, and (if requested) installed on the connected phone.
5. This document is updated with checkboxes and summaries after every phase to allow seamless resumption across sessions.

---

## Progress Dashboard

| Phase | Description | Status | Commit / PR |
|---|---|---|---|
| **Phase 1** | Telemetry Data Audit & Core Kinematic / Geocoding Engine Refactor | ✅ Completed | `fix/field-test-kinematics-and-geocoding` |
| **Phase 2** | Live Sharing Web Viewer & Android Panel Overhaul | ⏳ Planned | - |
| **Phase 3** | Main Location Panel Header Rearrangement & Map Viewport Centering | ⏳ Planned | - |
| **Phase 4** | Map Pin Enhancements, Forest Reverse Geocoding & Pin Boundaries | ⏳ Planned | - |
| **Phase 5** | Trips & Places Overhaul (Edit Places, Collapsed Pauses, Group By, School) | ⏳ Planned | - |

---

## Phase Breakdown

### Phase 1: Core Kinematics, Adaptive Speed Filter, Geocoding SSOT & Storage Migration
- [x] **1.1 Telemetry Download (Point 0)**:
  - Phone connected via ADB, full device dump acquired (`device_dump_20260917/`).
  - Extracted database (`where_i_am_trips.db`), SharedPreferences (`where_i_am_prefs.xml`), and 2.3MB logcat.
  - Root causes confirmed and documented in `FIELD_TEST_2026-09-17_ANALYSIS.md`.
- [x] **1.2 Database & Storage Rename to "Where Am I" (User Request)**:
  - Renamed `where_i_am_trips.db` to `where_am_i_trips.db` in `TripDatabaseHelper.kt`.
  - Added safe automatic file migration (`StorageMigrationHelper.kt`): atomically moves/copies DB and `-wal`/`-shm` files so all trips and saved places are preserved.
  - Migrated legacy SharedPreferences (`where_i_am_prefs`, `where_i_am_trip_prefs`, `where_i_am_live_share_prefs`, `where_i_am_power_prefs`, `where_i_am_ui_prefs`, `whereiam_map_prefs`) to `where_am_i_*`.
  - Renamed `Theme.WhereIAm` -> `Theme.WhereAmI` and root project name in `settings.gradle.kts`.
- [x] **1.3 Industry-Standard Speedometer & Kinematics Engine (Point 1)**:
  - **Doppler Primacy**: Base velocity strictly on GNSS Doppler speed (`location.speed`), ignoring accelerometer variance when speed > 1.2 m/s (~4.3 km/h) or displacement > 3m.
  - **Adaptive Velocity Kalman Filter**: Replaced sluggish 3-second moving-average with an adaptive 1D Kalman filter (fast gain during acceleration/braking for instant dashboard response; low gain during steady speed for rock-solid stability).
  - **Zero-Snap (Anti-Creep)**: Instantly snap to 0.0 km/h when Doppler speed < 0.4 m/s and displacement < 1.5m to prevent sluggish draining at red lights.
- [x] **1.4 Single Source of Truth (SSOT) Location Flow (Point 1)**:
  - Unified location updates into a single shared master pipeline (`masterLocationFlow`) in `LocationManager`.
  - Both `MainActivity`, `AutoMediaService`, and `LiveTrackingService` observe this shared stream, eliminating 4 duplicate FusedLocation callbacks, race conditions, and frozen Android Auto sessions.
- [x] **1.5 Fix Street Blinking & Fast-Path Double Emission (Point 1)**:
  - Updated `committedPlace = stabilizedMultiData` after street hysteresis stabilization so fast and async paths emit identical street names.
  - Strengthened DK/DW corridor inertia (leaving highway requires 7 fixes and 10s; entering highway snaps within 2 fixes).
- [x] **1.6 Bielsko-Biała Municipality Bug (Point 2)**:
  - Removed unsafe `distToLastGood < 1500f` cross-city gmina inheritance.
  - Added `POLISH_COUNTY_CITIES` set covering all 66 Polish county cities; suppressed `gm.` and `pow.` entirely for county cities.
  - Sanitized and purged corrupted `loc_gmina_bielsko-biała` from SharedPreferences.
- [x] **1.7 Verification**:
  - Added unit test suites `HierarchyResolutionTest.kt` and `SpeedKinematicsTest.kt`.
  - Ran `.\gradlew.bat testDebugUnitTest` (passed) and `.\gradlew.bat assembleDebug` (passed).

---

### Phase 2: Live Sharing Web Viewer & Android Panel Overhaul
- [ ] **2.1 Live Visitors Web Page Polish (`server/public/index.html`) (Point 5)**:
  - Add explicit "Last seen: X" label for last update timestamp.
  - Add a floating "Fit Trip" button on the map to fit full polyline bounds (`map.fitBounds()`).
  - Account for top card height when recentering map on user marker.
  - Optimize header layout: status badge in top-right, elapsed time below, graceful title wrapping.
- [ ] **2.2 Live Location Sharing Panel Reorganization (Point 7)**:
  - Move "Start Live Sharing Session" button to top directly beneath title.
  - When active: replace with horizontal button bar: `[Stop]` (red), `[Pause]` (amber), `[Sync Now]` (blue).
  - Render Streaming Controls (Full Trail / Position Only, Update Frequency) permanently inline below buttons.
  - Place Provider, Duration, and Share Links in lower section.
- [ ] **2.3 Verification**:
  - Test web viewer locally in browser; test Android dialog layout in Compose preview / tests.
  - Run `.\gradlew.bat testDebugUnitTest assembleDebug`.

---

### Phase 3: Main Location Panel Header Rearrangement & Map Viewport Centering
- [ ] **3.1 Main Location Panel Header (Point 3)**:
  - **Left**: Google Maps icon/button, followed by Live Sharing button/status pill.
  - **Right**: Bookmarked place icon/label (clickable to open Places tab in Trips & Places), followed by Expand/Collapse button.
- [ ] **3.2 Viewport-Aware Map Centering on Android (`OsmMapView.kt`) (Point 9)**:
  - Compute optical center offset based on top card height (Spatious vs Condensed) and bottom control bar.
  - Recenter action centers user position in visible map viewport aperture.
- [ ] **3.3 Verification**:
  - Verify layout in both Spatious and Condensed modes.
  - Run `.\gradlew.bat testDebugUnitTest assembleDebug`.

---

### Phase 4: Map Pin Enhancements, Forest Reverse Geocoding & Pin Boundaries
- [ ] **4.1 Forest / Off-Road Address Resolution (`SearchHelper.kt`) (Point 8)**:
  - If dropped pin is $> 150\text{m}$ away from the resolved street/house or on natural terrain, do not invent street/house number.
  - Display coordinates and administrative hierarchy (City/Village, Gmina, Powiat, Voivodeship, Country).
- [ ] **4.2 Pin Card Actions & Distinct Boundary Styling (Point 8)**:
  - Remove redundant "Exit Pin" button (top-right X handles dismissal).
  - Add "Show Borders" button on pin card to display pinned locality boundary.
  - Pinned locality polygon rendered with distinct styling (cyan dashed border with subtle tint) to visually differentiate from current GPS location boundary.
  - Keep both boundaries visible simultaneously: current GPS location boundary in standard styling, and pinned locality in cyan dashed styling.
  - Reset pin boundary on new pin drop or pin card dismissal.
- [ ] **4.3 Verification**:
  - Unit tests for reverse geocoding distance threshold and pin boundary state transitions.
  - Run `.\gradlew.bat testDebugUnitTest assembleDebug`.

---

### Phase 5: Trips & Places Overhaul
- [ ] **5.1 Rename "Places & Trips" to "Trips & Places" (Point 4)**:
  - Update all UI strings, tab labels, headers, and descriptions.
- [ ] **5.2 Edit Saved Places (Point 6)**:
  - Add Edit button to saved place cards.
  - Implement Edit Place Dialog (edit name, category, radius) calling `TripDatabaseHelper.updateSavedPlace`.
- [ ] **5.3 Add "School" Category (Point 10)**:
  - Include `PlaceCategory.SCHOOL` ("School", "🏫") in place category selectors.
- [ ] **5.4 Trips Rest Pauses UI (Point 6)**:
  - Make Rest Pauses collapsed by default in trip cards.
  - Add per-trip expand/collapse toggle for pauses.
  - Add a global "Expand All / Collapse All" button next to "Select All" when 0 trips selected.
- [ ] **5.5 Route Text Display (Point 6)**:
  - Remove 2-line truncation so complete visited locality chain is fully visible.
- [ ] **5.6 Database-Level Filtering & Grouping with Counters (Point 6)**:
  - Implement SQLite dynamic queries filtering across all stored trips (not only loaded in-memory items).
  - Add search by route locality name.
  - Add Group By toggle: by Time (Today, This Week, This Month, Earlier / Year) and by Activity Type (Driving, Hiking, Cycling).
  - Each group header includes item count (e.g. `TODAY (2)`, `THIS WEEK (5)`, `SEPTEMBER 2026 (12)`) and supports collapsible section expansion.
- [ ] **5.7 Verification**:
  - Automated tests for trip filtering, grouping, and place updating.
  - Run `.\gradlew.bat testDebugUnitTest assembleDebug`.
