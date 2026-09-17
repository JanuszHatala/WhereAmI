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
| **Phase 2** | Live Sharing Web Viewer & Android Panel Overhaul | ✅ Completed | `feat/live-sharing-overhaul` |
| **Phase 3** | Main Location Panel Header Rearrangement & Map Viewport Centering | ✅ Completed | `feat/main-panel-and-map-centering` |
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
- [x] **2.1 Live Visitors Web Page Polish (`server/public/index.html`) (Point 5)**:
  - Add explicit "Last seen: X" label for last update timestamp.
  - Add a floating "Fit Trip" button on the map to fit full polyline bounds (`map.fitBounds()`).
  - Account for top card height when recentering map on user marker.
  - Optimize header layout: status badge in top-right, elapsed time below, graceful title wrapping.
- [x] **2.2 Live Location Sharing Panel Reorganization (Point 7)**:
  - Move "Start Live Sharing Session" button to top directly beneath title.
  - When active: replace with horizontal button bar: `[Stop]` (red), `[Pause]` (amber), `[Sync Now]` (blue).
  - Render Streaming Controls (Full Trail / Position Only, Update Frequency) permanently inline below buttons.
  - Place Provider, Duration, and Share Links in lower section.
- [x] **2.3 Verification**:
  - Verified compilation and test suite: `.\gradlew.bat testDebugUnitTest assembleDebug` (passed in 1m 53s).
  - Merged to `master` locally via `feat/live-sharing-overhaul`.

---

### Phase 3: Main Location Panel Header Rearrangement & Map Viewport Centering
- [x] **3.1 Main Location Panel Header (Point 3)**:
  - **Left**: Google Maps icon/button, followed by Live Sharing button/status pill.
  - **Right**: Bookmarked place icon/label (clickable to open Places tab in Trips & Places), followed by Expand/Collapse button.
  - Cleaned up both Spatious and Compact modes so button orders and shortcuts are consistent.
- [x] **3.2 Viewport-Aware Map Centering on Android (`OsmMapView.kt`) (Point 9)**:
  - Implemented `getOpticalCenter(mapView, target, offsetPixelsY)` using osmdroid canvas projection.
  - Dynamically computes optical offset based on screen orientation, top card style (Compact vs Spatious), and bottom bar height.
  - Applied to follow mode, manual recenter button, floating recenter pill, and search destination pins.
- [x] **3.3 Verification**:
  - Verified compilation and test suite: `.\gradlew.bat testDebugUnitTest assembleDebug` (passed in 1m 44s).

---

### Phase 4: Map Pin Enhancements, Forest Reverse Geocoding & Pin Boundaries
- [x] **4.1 Forest / Off-Road Address Resolution (`SearchHelper.kt`) (Point 8)**:
  - If dropped pin is $> 150\text{m}$ away from the resolved street/house or on natural terrain, do not invent street/house number.
  - Display coordinates and administrative hierarchy (City/Village, Gmina, Powiat, Voivodeship, Country).
- [x] **4.2 Pin Card Actions & Distinct Boundary Styling (Point 8)**:
  - Remove redundant "Exit Pin" button (top-right X handles dismissal).
  - Add "Show Borders" button on pin card to display pinned locality boundary.
  - Pinned locality polygon rendered with distinct styling (cyan dashed border with subtle tint) to visually differentiate from current GPS location boundary.
  - Keep both boundaries visible simultaneously: current GPS location boundary in standard styling, and pinned locality in cyan dashed styling.
  - Reset pin boundary on new pin drop or pin card dismissal.
- [x] **4.3 Verification**:
  - Unit tests for reverse geocoding distance threshold and pin boundary state transitions in `MapPinReverseGeocodingTest.kt`.
  - Run `.\gradlew.bat testDebugUnitTest assembleDebug` (passed).

---

### Phase 5: Trips & Places Overhaul
- [x] **5.1 Rename "Places & Trips" to "Trips & Places" (Point 4)**:
  - Update all UI strings, tab labels, headers, and descriptions.
- [x] **5.2 Edit Saved Places (Point 6)**:
  - Add Edit button to saved place cards and map marker details dialog.
  - Implement Edit Saved Place Dialog (edit name, category, street, locality, radius) calling `MainViewModel.updateSavedPlace`.
- [x] **5.3 Add "School" Category (Point 10)**:
  - Included `PlaceCategory.SCHOOL` ("School", "🏫") in place category selectors and dialogs.
- [x] **5.4 Trips Rest Pauses UI (Point 6)**:
  - Rest Pauses collapsed by default in trip cards.
  - Per-trip expand/collapse toggle for pauses (`▶/▼ ⏸️ Rest Pauses (N)`).
  - Global "Expand Pauses / Collapse Pauses" toggle next to "Select All" when 0 trips selected.
- [x] **5.5 Route Text Display (Point 6)**:
  - Removed 2-line truncation so complete visited locality chain is fully visible.
- [x] **5.6 Database-Level Filtering & Grouping with Counters (Point 6)**:
  - Implemented `TripDatabaseHelper.queryTrips` and `TripGroupingHelper`.
  - Added Group By toggle row (`Group by: [Date] [Activity] [None]`).
  - Implemented strictly mutually exclusive Date grouping (Today, This week, This month, Earlier) with item counts (e.g. `TODAY (2)`, `THIS WEEK (5)`).
  - Collapsible section headers with item counts and expand/collapse support.
- [x] **5.7 Verification**:
  - Automated tests for mutual exclusivity and activity grouping in `TripGroupingHelperTest.kt`.
  - Verification build gate `.\gradlew.bat testDebugUnitTest assembleDebug` passed (33 tests, 0 failures).
