# WhereAmI - Master Feature Inventory & Evolution Register

This document is the authoritative, comprehensive historical and current register for **ALL** features requested, designed, implemented, evolved, or retired from the inception of the **WhereAmI** project (`janush.tech.whereami`).

It tracks the workflow and architectural decisions across all development sessions, aggregating features logically rather than repeating duplicate entries, and documents all encountered bugs, trade-offs, and solutions.

---

## 1. Executive Summary & Status Matrix

| Category | Total Features | Implemented & Verified | In Progress | Planned / Future | Abandoned / Retired |
| :--- | :---: | :---: | :---: | :---: | :---: |
| **1. Core Location, Spatial Awareness & Kinematics** | 10 | 10 | 0 | 0 | 0 |
| **2. Map Engine, Layers & Viewport Navigation** | 8 | 8 | 0 | 0 | 2 |
| **3. Trip Recording, History & Analytics** | 7 | 7 | 0 | 0 | 0 |
| **4. Live Location Sharing (Self-Hosted Platform)** | 8 | 8 | 0 | 3 | 2 |
| **5. Saved Places ("My Places") & Search** | 5 | 5 | 0 | 0 | 0 |
| **6. User Interface, Responsive Layout & UX Polish** | 8 | 8 | 0 | 0 | 1 |
| **7. Android System Integration & Power Architecture** | 7 | 6 | 0 | 0 | 1 |
| **8. Diagnostics, Telemetry & CI/CD Pipeline** | 5 | 5 | 0 | 0 | 0 |
| **Total** | **58** | **52** | **0** | **3** | **6** |

---

## 2. Category 1: Core Location, Spatial Awareness & Kinematics

### LOC-01: Multi-Tier Reverse Geocoding & Structural Administrative Hierarchy
- **Core Value**: Ultra-fast, legible display of current place name (City, Town, Village, Settlement) with complete, unambiguous administrative hierarchy (`gm.` commune, `pow.` county, `woj.` voivodeship, Country).
- **Status**: **Implemented & Verified**
- **Architecture & Implementation**:
  - **Tier 1**: Android native `Geocoder` (on-device, zero network latency).
  - **Tier 2**: OpenStreetMap Nominatim enrichment (resolves missing `gmina`, canonical road refs, and OSM tags).
  - **Fallback**: Persistent location state preserves last confirmed place during signal loss.
- **Decision History & Evolution**:
  - *Initial Implementation*: Basic Android Geocoder locality string. Often omitted `gmina` (commune) and repeated city names redundantly.
  - *User Feedback*: Missing `gmina` in places like Czaniec; redundant formatting like `Maków Podhalański, pow. suski, gm. Maków Podhalański`.
  - *Resolution*: Implemented `formatHierarchy()` with intelligent deduplication: suppresses `gm. X` when locality is already X, deduplicates powiat/voivodeship duplicates.
  - *Field Test Issue (Czaniec Jumping)*: In field tests, Czaniec occasionally dropped `gm. Porąbka` and jumped back and forth while stationary. Root cause: Nominatim 429 rate-limiting during rapid re-queries, causing silent fallbacks to base geocoder.
  - *Final Fix*: Introduced persistent SharedPreferences caching (`loc_gmina_<city>`), unified multi-language geocoding into a single query to eliminate HTTP 429s, and broadened the spatial LRU cache cell resolution to ~100m.

### LOC-02: Canonical Road & Street Designation Normalizer
- **Core Value**: Instantly displays Polish canonical road designations (`DK52`, `DW946`, `A4`, `S7`, `E77`) without house numbers on major corridors, or formatted residential streets with house numbers (`ul. Mickiewicza 12`).
- **Status**: **Implemented & Verified**
- **Architecture & Implementation**:
  - `RoadNameNormalizer.kt`: Regex and token normalization engine.
  - Unit test suite: `RoadNameNormalizerTest.kt` (7 test cases).
- **Decision History & Evolution**:
  - *Initial Implementation*: Displayed raw reverse geocoded street strings (e.g. `Droga Krajowa nr 52 45` or `Krakowska 120`).
  - *User Feedback*: Major highways should show clean canonical codes (`DK52`) without house numbers; residential streets must preserve house numbers and Polish `ul.` prefixes.
  - *Resolution*: Canonical abbreviations: `DK*` (Droga Krajowa), `DW*` (Droga Wojewódzka), `A*` (Autostrada), `S*` (Droga Ekspresowa), `E*` (Trasa Europejska). Strips house numbers strictly from arterial routes while retaining them for local/urban streets.

### LOC-03: Viaduct & Side-Street Trajectory Inertia (Anti-Jumping)
- **Core Value**: Prevents vehicle street identity from flickering between highways and cross-streets when driving over viaducts, underpasses, or past intersections.
- **Status**: **Implemented & Verified**
- **Architecture & Implementation**:
  - `LocationManager.kt` (`applyStreetHysteresis`): Velocity-aware candidate streak filter.
- **Decision History & Evolution**:
  - *User Feedback*: When driving on DK52/DW946 at 50–90 km/h, the app momentarily jumped to parallel side streets (`Bukietowa` vs `Wyzwolenia`) or roads below viaducts.
  - *Evolution*:
    1. *Stage 1*: Added basic candidate count check ($N \ge 3$). Proved insufficient at highway speeds.
    2. *Stage 2*: Enforced dual gate: at speed $> 35\text{ km/h}$ on a major corridor, requires at least 5 consecutive candidate readings AND $\ge 7.0\text{s}$ of sustained fixes before switching road names.
    3. *Stage 3 (Strict Low-Speed Inertia)*: Extended inertia down to 1.2 km/h; requires BOTH count and time duration thresholds to be satisfied before accepting a street switch.

### LOC-04: 15-Second Decay Grace Period for Disappearing Streets
- **Core Value**: Eliminates screen blanking/flicker when driving through tunnels, underpasses, or transient GPS cutouts.
- **Status**: **Implemented & Verified**
- **Architecture & Implementation**:
  - `LocationManager.kt`: Tracks `lastStreetSeenTimestamp`.
- **Decision History & Evolution**:
  - *Issue*: During brief reverse-geocoding drops, the street line would disappear, causing layout jumping.
  - *Resolution*: Enforces a 15-second decay grace period. If reverse geocoding returns an empty street, the app retains the last confirmed road name for 15s before clearing.

### LOC-05: Kinematic GPS Filter Engine (`GpsFilterEngine`)
- **Core Value**: Filters multipath noise, satellite loss jumps, and cell-tower fallbacks ($1.5\text{km}-5\text{km}$ errors) before dispatching to UI, trip recording, or live telemetry.
- **Status**: **Implemented & Verified**
- **Architecture & Implementation**:
  - 4-Stage Filtering Pipeline:
    1. *Horizontal Accuracy Gate*: Hiking $\le 40\text{m}$, Cycling $\le 55\text{m}$, Driving $\le 75\text{m}$.
    2. *Kinematic Velocity Gate*: Maximum displacement speed: Hiking $\le 22\text{ km/h}$, Cycling $\le 90\text{ km/h}$, Driving $\le 230\text{ km/h}$.
    3. *Consecutive Anomaly Recovery*: Re-syncs after 3 consecutive agreeing fixes (e.g. emerging from long tunnels).
    4. *Stationary Jitter Dampener*: Gating displacement $< 4\text{m}$ and speed $< 0.35\text{ m/s}$.

### LOC-06: Stationary Bearing Freeze
- **Core Value**: Locks orientation arrow and map course-up heading when stopped, preventing erratic spinning at traffic lights or rest stops.
- **Status**: **Implemented & Verified**
- **Architecture & Implementation**:
  - `LocationManager.kt` & `OsmMapView.kt`: Velocity threshold gating at $1.2\text{ m/s}$ ($4.3\text{ km/h}$).
- **Decision History & Evolution**:
  - *Issue*: When vehicle stopped at intersections, GPS noise caused random bearing calculations ($0^\circ-360^\circ$), causing the map to spin dizzyingly.
  - *Resolution*: Captures `lastValidBearing` when speed $\ge 1.2\text{ m/s}$. When speed drops below $1.2\text{ m/s}$, bearing updates are frozen. Cursor arrow and Course-Up map remain locked to the vehicle's actual arrival heading.

### LOC-07: Stationary Accelerometer Sensor Fusion (Phantom Drift Elimination)
- **Core Value**: Completely stops GPS calculation, map jitter, and phantom speeds (6–14 km/h) when the device is resting indoors on a desk or nightstand.
- **Status**: **Implemented & Verified**
- **Architecture & Implementation**:
  - `StationaryDetector.kt`: Calculates rolling variance of tri-axial accelerometer data ($\sigma^2 < 0.045$).
  - Unit test suite: `StationaryDetectorTest.kt`.
- **Decision History & Evolution**:
  - *Issue*: Phone sitting on desk recorded phantom speeds of 6–14 km/h and phantom trips due to indoor GPS multi-path reflection.
  - *Resolution*: When accelerometer detects zero physical motion for $\ge 30\text{s}$, `hybridSpeedUpdate` forces speed to 0.0 km/h and suppresses geocoding and trip start triggers.

### LOC-08: Intentional Locality Visit Filter
- **Core Value**: Qualifies a town or settlement as "visited" only if the user genuinely enters it, preventing false visit statistics along border highways.
- **Status**: **Implemented & Verified**
- **Architecture & Implementation**:
  - `TripManager.kt` (`PendingPlaceCandidate`): Spatial penetration gate ($\ge 150\text{m}$) OR temporal duration gate ($\ge 45\text{s}$).
- **Decision History & Evolution**:
  - *Issue*: Driving on a highway bordering two municipalities triggered alternating visit logs for both towns every few hundred meters.
  - *Resolution*: Transient boundary skimming is rejected. Candidate localities are queued and only committed to trip history once displacement exceeds 150m into the polygon or residence exceeds 45s.

### LOC-09: Activity Profiles & Kinematic Adaptive Profiles
- **Core Value**: Tailors UI, sampling rates, and kinematic limits across 6 dedicated profiles: Car, Cycling, MTB, Hiking, Run, Walk.
- **Status**: **Implemented & Verified**
- **Architecture & Implementation**:
  - Car/Cycling/MTB: Speed-first display (km/h), high kinematic thresholds.
  - Hiking/Run/Walk: Pace-first display (min/km), elevation contour prominence, Waymarked Trails overlay.

### LOC-10: 60fps Dead-Reckoning Position Interpolation & Kinematic Decoupling
- **Core Value**: Completely eliminates map marker teleportation, backward snapping, and camera animation jerks, delivering fluid 60fps tracking.
- **Status**: **Implemented & Verified**
- **Architecture & Implementation**:
  - `PositionInterpolator.kt`: Flat-earth trigonometric dead-reckoning ($v \times \Delta t$) capped at 2.5s forward projection, with 250ms ease-out blending on new GPS fixes and stationary dampening ($< 0.35\text{ m/s}$).
  - `LocationManager.kt`: Decouples asynchronous geocoding from kinematics; `getLocationRaw()` emits monotonic `LocationFix` with `.distinctUntilChanged` on coordinates and timestamps.
  - `OsmMapView.kt`: Camera centering runs on display frame clock (`withFrameNanos`) using immediate `setCenter(centerGp)`.
  - Unit tests: `PositionInterpolatorTest.kt` (6 unit tests).
- **Decision History & Evolution**:
  - *User Feedback (Field Test 2026-09-25)*: Heading icon was jumping back and forth and map repositioning was jerky.
  - *Diagnostic Root Cause*: `enrichedSnapshot` was re-emitting past coordinates from asynchronous network closures (1-3s old) back into `masterLocationFlow`, which `getLocationRaw()` routed to map updates, while `animateTo(centerGp, 400L)` calls aborted each other mid-flight.
  - *Resolution*: Decoupled geocoding enrichments from raw kinematic fixes and built `PositionInterpolator`.

---

## 3. Category 2: Map Engine, Layers & Viewport Navigation

### MAP-01: OpenStreetMap Rendering & Hardware Acceleration
- **Core Value**: Native OpenStreetMap raster rendering with hardware acceleration and 60fps pan/zoom.
- **Status**: **Implemented & Verified**
- **Architecture & Implementation**:
  - `OsmMapView.kt`: OSMDroid map view with full GPU hardware acceleration.
- **Decision History & Evolution**:
  - *Issue*: Driving localization felt sluggish and map pan had visual stutter.
  - *Resolution*: Discovered and removed legacy `LAYER_TYPE_SOFTWARE` flag. Panning and tile blitting now run on GPU hardware acceleration. Explored vector rendering (MapLibre Native Android) in `docs/VECTOR_MAP_ENGINE_ANALYSIS.md`.

### MAP-02: Freemap Outdoor Hi-DPI (@2x) PTTK Trail Layer
- **Core Value**: Official Polish PTTK colored hiking trails (Red, Blue, Green, Yellow, Black) with native shaded terrain relief, elevation contours, and Polish peak labels.
- **Status**: **Implemented & Verified**
- **Architecture & Implementation**:
  - `OsmMapView.kt`: `FREEMAP_OUTDOOR` base layer using 512x512 `@2x` tiles from `outdoor.tiles.freemap.sk`.
  - Tile caching in `MapCacheHelper.kt` under `tiles/FreemapOutdoor`.
- **Decision History & Evolution**:
  - *User Request*: "I don't see the PTTK tourist trail marker line in their color, they're always yellow/orange... Do you have access to any PTTK map overlays or data or other resources that we could use especially in the Hiking mode?"
  - *Resolution*: Integrated Freemap Slovakia/Poland Outdoor layer rendering official PTTK markers in crisp high-DPI resolution. Auto-activated when selecting Hiking profile.

### MAP-03: Waymarked Trails Hiking Overlay
- **Core Value**: Transparent route overlay rendering hiking routes, difficulty markers, and trail colors on top of standard OSM or satellite imagery.
- **Status**: **Implemented & Verified**
- **Architecture & Implementation**:
  - `OsmMapView.kt`: Transparent `TilesOverlay` pointing to `tile.waymarkedtrails.org/hiking`.

### MAP-04: Map Recenter & Floating Recenter Pill
- **Core Value**: Allows free map browsing without permanent loss of location tracking, with an intuitive 1-tap recenter control.
- **Status**: **Implemented & Verified**
- **Architecture & Implementation**:
  - `OsmMapView.kt`: Distinguishes between programmatic map moves and user gestures via `isUserDragging`.
- **Decision History & Evolution**:
  - *Original*: Map had an 8-second timer that automatically snapped back to GPS location.
  - *User Feedback*: Snapping back while inspecting nearby terrain was frustrating.
  - *Evolution*: Changed to Option B: manual pan/zoom indefinitely pauses auto-follow and reveals a floating "📍 Recenter" pill at bottom-center.
  - *Subsequent Issue*: Tapping the recenter button recentered the map, but the "Recenter" pill remained visible because scroll listeners fired during animation.
  - *Final Fix*: Added `isUserDragging` touch listener. The pill is dismissed immediately upon tapping either the pill or the refresh/recenter FAB.

### MAP-05: Unified Map Refresh & Location FAB
- **Core Value**: Combines GPS centering, tile memory cache flushing, and redrawing into a single clean button.
- **Status**: **Implemented & Verified**
- **Architecture & Implementation**:
  - `OsmMapView.kt`: Floating action button replacing duplicate "My Location" and "Refresh Map" controls.

### MAP-06: Calibrated Map Font & Tile Scaling, Orientation Modes & Offline Pre-Cache
- **Core Value**: Legible map labels on high-density mobile screens without bitmap blur; 5 cardinal map orientation modes; full offline pre-download with progress tracking.
- **Status**: **Implemented & Verified**
- **Architecture & Implementation**:
  - `OsmMapView.kt`: `tilesScaleFactor` dynamically applied and persisted in `whereiam_map_prefs.xml`.
  - `MapOrientationMode` enum: `COURSE_UP` (auto / gyroscope-based), `NORTH`, `EAST`, `SOUTH`, `WEST` — all 5 modes rendered in 2 responsive rows in Map Settings.
  - `MapCacheHelper.kt`: Pre-downloads 5km radius tile set in background coroutine; reports `Downloading(N, total, %)`, `Completed`, `AlreadyCached(N)`, `Failed` states. Download loop checks `isActive` on every tile iteration for cooperative cancellation.
- **Decision History & Evolution**:
  - *Font Scale Initial Attempt*: Added font scale selector with presets up to 340% (2.0x on top of 1.7x).
  - *User Feedback*: "The Map Label Font Scaling is completely useless, it doesn't scale the fonts but does some zooming. Having the setting e.g. Huge (340%) the map is not sharp, it's like blurred".
  - *Diagnosis*: OSM raster tiles are pre-rendered bitmap PNGs. Scaling above 1.7x magnifies bitmap pixels.
  - *Resolution*: Recalibrated presets to crisp, realistic factors (0.90x, 1.0x, 1.25x, 1.50x, 1.75x) and introduced Hi-DPI Freemap Outdoor 512x512 tiles for native larger labels.
  - *Orientation Regression (Round 2)*: During refactoring, the orientation UI was accidentally reduced to only `COURSE_UP` and `NORTH`. All 5 modes restored in 2 responsive button rows.
  - *Cache Cancel Bug (Round 2)*: Cancel button was calling `MapCacheHelper.cancelDownload()` correctly, but the download coroutine loop didn't check `isActive`, so it continued until the last queued HTTP request completed. Fixed with per-tile `isActive` check.
  - *Already-Cached Feedback (Round 2)*: When all tiles existed on disk, the downloader completed instantly with no feedback. Added `AlreadyCached` state: "✅ Map region already fully cached (N tiles on disk)".

### MAP-07: Territorial Administrative Boundary Polygons
- **Core Value**: Visualizes municipal borders directly on the map.
- **Status**: **Implemented & Verified**
- **Architecture & Implementation**:
  - `BoundaryHelper.kt`: Overpass API polygon fetcher with 2-tier caching (in-memory `ConcurrentHashMap` + persistent on-disk JSON in `cacheDir/boundaries`). Rate-limit mutex (1.5s delay) prevents 429 throttling.

### MAP-08: Traversal Road Heat Map
- **Core Value**: Displays coverage of all historical trips as glowing polyline corridors across the map.
- **Status**: **Implemented & Verified**
- **Architecture & Implementation**:
  - `OsmMapView.kt`: Aggregates recorded database coordinates into persistent heatmap polylines.

---

## 4. Category 3: Trip Recording, History & Analytics

### TRP-01: Manual Trip Recording Pill
- **Core Value**: 1-tap start/stop from bottom action bar with live elapsed distance and duration.
- **Status**: **Implemented & Verified**
- **Architecture & Implementation**:
  - `MainActivity.kt` & `TripManager.kt`: Compact `● REC` / `■ STOP` pill.

### TRP-02: Automatic Trip Detection & Stop Engine
- **Core Value**: Automatically starts recording when speed exceeds profile threshold, and auto-stops when stationary.
- **Status**: **Implemented & Verified**
- **Architecture & Implementation**:
  - `TripManager.kt`: Sensitive auto-start ($\ge 25\text{m}$ displacement over 8s) and configurable stationary auto-stop (1m, 3m, 5m, 10m).
- **Decision History & Evolution**:
  - *Issue*: Auto-stop failed to trigger when parked at home because of a hardcoded 45m displacement override.
  - *Fix*: Removed override; now strictly respects user's configured auto-stop timeout while checking stationary detector.

### TRP-03: Gapless Low-Speed Polyline Recording
- **Core Value**: Continuous, accurate GPS tracks during slow maneuvers, traffic jams, and walking ($< 18\text{ km/h}$).
- **Status**: **Implemented & Verified**
- **Architecture & Implementation**:
  - `TripManager.kt`: Displacement threshold measured against `points.last()`.
- **Decision History & Evolution**:
  - *Issue*: In field testing, the red trip polyline lagged far behind the cursor during slow driving.
  - *Root Cause*: Points were filtered out if displacement between 1-second ticks was $< 5\text{m}$, creating large gaps when moving slowly.
  - *Fix*: Measured $\Delta d \ge 5\text{m}$ accumulatively against the last recorded point, ensuring gapless tracks at all speeds.

### TRP-04: Places & Trips Management Fullscreen Sheet
- **Core Value**: Comprehensive management interface for trip history, statistics, and saved places.
- **Status**: **Implemented & Verified**
- **Architecture & Implementation**:
  - `MainActivity.kt`: Fullscreen dialog with tabs: `Trips (N) | Places (N) | Stats`.
- **Decision History & Evolution**:
  - *Evolution*: Reordered tabs so "Trips" is default (tab 0). Fixed multi-select toolbar with clear buttons (`[N selected ✕]`, `[Merge]`, `[Fit Map]`, `[Select All]`), and resolved squished distance/time columns in visited places timeline.

### TRP-05: Standard GPX Trip Export
- **Core Value**: Exports trip tracks to standard GPX files for Strava, Garmin Connect, and Google Earth.
- **Status**: **Implemented & Verified**
- **Architecture & Implementation**:
  - `GpxExporter.kt`: Direct GPX serialization shared via Android `FileProvider`.

### TRP-06: Trip Splitting & Merging Engine
- **Core Value**: Split accidentally merged trips or merge split segments into a single cohesive route.
- **Status**: **Implemented & Verified**
- **Architecture & Implementation**:
  - `TripDatabaseHelper.kt`: Atomic SQL transaction for merging and splitting trips.
  - Unit test suite: `TripSplitTest.kt`.
- **Decision History & Evolution**:
  - *Issue*: Splitting trips caused duplicate title prefixes (`Trip - Part 1 - Part 1`).
  - *Fix*: Sanitized naming logic: strips existing `- Part X` before applying new suffixes.

### TRP-07: Active Trip Visited Places Chronology
- **Core Value**: Real-time chronology of towns and settlements visited during the current active trip, accessible by tapping the locality card header.
- **Status**: **Implemented & Verified**

---

## 5. Category 4: Live Location Sharing (Self-Hosted Platform)

### LIV-01: Self-Hosted Synology NAS & Docker Architecture
- **Core Value**: Private, self-hosted live location sharing without third-party cloud dependence.
- **Status**: **Implemented & Verified**
- **Architecture & Implementation**:
  - `server/server.js`: Node.js Express server running on port 3003 in Alpine Docker container.
  - Clean URL routing: `https://<domain>/live/:id`.
  - Local testing support via ADB reverse proxy (`127.0.0.1:3003`).

### LIV-02: Permanent Broadcasting Live Share Icon & Status Chip
- **Core Value**: Unambiguous, single-point access to live sharing controls with live status readouts.
- **Status**: **Implemented & Verified**
- **Architecture & Implementation**:
  - `MainActivity.kt`: Broadcasting waves icon (`Icons.Filled.Sensors`) permanently pinned immediately left of Google Maps icon on the Locality Card.
- **Decision History & Evolution**:
  - *User Feedback*: "I wanted you to use the icon of Live Sharing and not duplicate the icons/buttons - now there are two icons that opens the same... The live sharing icon is gone when I stopped live sharing".
  - *Resolution*: Pinned permanently in both Compact and Normal modes. When stopped: displays cyan transmission button to start. When active: expands into active live timer + visitor count badge.

### LIV-03: Real-Time Host Visitor Analytics
- **Core Value**: Shows the host how many people are currently viewing their shared live location.
- **Status**: **Implemented & Verified**
- **Architecture & Implementation**:
  - `server/server.js` tracks unique IP view counts.
  - Mobile UI displays `👥 ${session.viewCount}` directly on the active Locality Card chip and in the management sheet.
- **Decision History & Evolution**:
  - *User Feedback*: "Remove visitor views counter from live web page; show in host app instead. Did you implement the number of visits of my live sharing position on the host's app?"
  - *Resolution*: Removed view counter from public web page. Pushed real-time visitor count to host app telemetry and UI.

### LIV-04: Dual-Link Architecture (Trip Link vs Personal Permanent Link)
- **Core Value**: Supports single-use trip links with expiration alongside a permanent static link for family.
- **Status**: **Implemented & Verified**
- **Architecture & Implementation**:
  - `LiveSharingManager.kt`: Independent flags `isPersonalPaused` and `isRandomPaused`. Management sheet provides separate pause/resume and copy/share controls.

### LIV-05: Live Sharing Lifecycle & On-The-Fly Controls
- **Core Value**: Pause/Resume, Extend (+1h, +6h), Permanent ("∞ No limit"), bidirectional toggle back to continuous mode from timed sessions, live renaming (capped at 40 chars with counter), and live frequency switching (1m, 2m, 5m, 10m) without restarting sessions.
- **Status**: **Implemented & Verified**
- **Architecture & Implementation**:
  - Mobile management sheet communicates with backend endpoints (`/api/sessions/:id/rename`, `/api/sessions/:id/interval`, `/api/sessions/:id/extend`).
  - `LiveSharingManager.kt`: `makeSessionPermanent()` resets `expiresAt = 0L` locally and remotely on the server.
  - Periodic telemetry sync payload (`postPoints`) includes `title` to guarantee the web viewer displays the actual session name, even when auto-created on the backend.
  - Web viewer (`server/public/index.html`): Displays session name prominently in bold 16px cyan typography (`#38bdf8`).
  - Stop Live Sharing button positioned above Pause button for immediate reachability.
  - Session title capped at 40 chars with `${length}/40` character counter. Edit button is permanently visible and responsive regardless of title length.
  - Status chip (`ACTIVE`/`PAUSED`), views count (`👥 X views`), and time info combined into a unified responsive second row.
  - Top Locality Card live sharing pill (`Icons.Filled.Sensors` + elapsed timer) vertically centered with pixel precision using `fillMaxHeight()` and disabled Android font padding (`includeFontPadding = false`).

### LIV-06: Offline Breadcrumb Accumulation Queue
- **Core Value**: Accumulates GPS fixes in local SQLite queue when offline; flushes in batch upon reconnect.
- **Status**: **Implemented & Verified**
- **Architecture & Implementation**:
  - `TripDatabaseHelper.kt` (`live_share_queue` table). Minimizes cellular modem wakeups.

### LIV-07: Interactive Compass Rose & Google Maps on Web Viewer
- **Core Value**: Web viewer provides high glanceability with a dynamic 3D needle rotating to host's GPS bearing and a direct "Open in Google Maps" deep link.
- **Status**: **Implemented & Verified**
- **Architecture & Implementation**:
  - `server/public/index.html`.

### LIV-08: Persistent Foreground Notification with Quick Actions
- **Core Value**: Guarantees Android OS does not kill live sharing, with status updates and quick actions on lock screen.
- **Status**: **Implemented & Verified**
- **Architecture & Implementation**:
  - `LiveTrackingService.kt`: Notification actions for `⏸ Pause` / `▶ Resume`, `🔄 Sync Now`, and `⏹ Stop`. Runtime `POST_NOTIFICATIONS` permission flow for Android 13+.

---

## 6. Category 5: Saved Places ("My Places") & Search

### PLC-01: Saved Places Manager
- **Core Value**: Bookmark current location or any tapped map point with custom name, category, and radius.
- **Status**: **Implemented & Verified**
- **Architecture & Implementation**:
  - `SavedPlace.kt`: Categories: Home, Work, Family, School, Favorite, Custom.

### PLC-02: Custom Category Map Pins & Geofencing Badges
- **Core Value**: Renders category-specific colored emoji pins on map and displays `[🏠 Home]` badge on top card when inside radius.
- **Status**: **Implemented & Verified**

### PLC-03: Global & Local Destination Search
- **Core Value**: Search any place, city, or address worldwide via Nominatim with pin drop and navigation options.
- **Status**: **Implemented & Verified**
- **Architecture & Implementation**:
  - `SearchHelper.kt`.
- **Decision History & Evolution**:
  - *Issue*: Searching for distant cities like "Kraków" failed because the app automatically appended the current locality ("Kraków Czaniec").
  - *Fix*: Removed character length restriction; now queries verbatim globally first, falling back to local search only for street addresses.

### PLC-04: One-Tap Google Maps Navigation Handoff
- **Core Value**: Instantly launches Google Maps turn-by-turn navigation for current location, saved place, or searched pin.
- **Status**: **Implemented & Verified**

### PLC-05: Multi-Language Place Name Support (EN, PL, Native)
- **Core Value**: Allows switching between Polish, English, and native administrative names.
- **Status**: **Implemented & Verified**

---

## 7. Category 6: User Interface, Responsive Layout & UX Polish

### UI-01: Compact vs. Normal Locality Card Styles
- **Core Value**: Toggle between full detailed spatial card and compact 1-line view without sacrificing street name or administrative hierarchy.
- **Status**: **Implemented & Verified**
- **Architecture & Implementation**:
  - `MainActivity.kt`: Locality Card Layout selector promoted to the very top of Settings.

### UI-02: Landscape Split-Screen Layout
- **Core Value**: Dedicated horizontal split-screen when phone is in vehicle mount: 38% left column for controls, 62% right column for full-height interactive map.
- **Status**: **Implemented & Verified**
- **Architecture & Implementation**:
  - `MainActivity.kt`: Responsive layout switching via `LocalConfiguration.current.orientation`.

### UI-03: Responsive Bottom Action Bar (440dp Constraint)
- **Core Value**: Fits all 6 bottom actions across all screen widths without horizontal clipping or wrapping.
- **Status**: **Implemented & Verified**

### UI-04: High-Contrast Dialogs & Button Styling
- **Core Value**: High legibility in bright sunlight and dark themes.
- **Status**: **Implemented & Verified**
- **Architecture & Implementation**:
  - Restyled "Share Current Position" buttons with pure white text and cyan accent icons (`0xFF38BDF8`).

### UI-05: Keep Screen On Toggle
- **Core Value**: One-tap eye button on bottom bar to prevent screen timeout during vehicle or bike mount usage.
- **Status**: **Implemented & Verified**

### UI-06: Consolidated Hierarchy Subtitle Line
- **Core Value**: Consolidates Country into administrative subtitle (`gm. ... • pow. ... • woj. ... • Polska`) to preserve top row exclusively for status badges and live controls.
- **Status**: **Implemented & Verified**

### UI-07: Modernized Typography & Intrinsic Card Heights
- **Core Value**: Clean, non-squashed layout across all summary cards and dialogs.
- **Status**: **Implemented & Verified**

### UI-08: Normalized Panel Close Button Design System
- **Core Value**: Every bottom sheet, dialog, and panel has a consistent 36dp circular dark-navy close button with a white 20dp ✕ icon — matching the `Places & Trips` panel pattern. Eliminates inconsistency between small gray `LightGray` X icons in some panels and the larger styled button in others.
- **Status**: **Implemented & Verified**
- **Architecture & Implementation**:
  - `AppSettingsSheet`, `LiveShareSheet`, `InstantShareDialog`, `ActiveTripRouteDialog`, `MapSettingsDialog`: All headers normalized to `IconButton(modifier = Modifier.size(36.dp).clip(CircleShape).background(Color(0xFF1E293B)))`.
  - `MapSettingsDialog` also gained a `scrollState`-aware `LaunchedEffect` that auto-scrolls to the bottom when a download starts, ensuring progress bar and Cancel button are always visible.
- **Decision History & Evolution**:
  - *Issue*: "App Settings" panel had no close button at all. Live Share, Instant Share, Active Trip Route dialogs used bare unstyled `IconButton` with `Color.LightGray` icons that were hard to see on dark backgrounds.
  - *Resolution*: Applied the dark circular close button pattern uniformly across all panels.

---

## 8. Category 7: Android System Integration & Power Architecture

### SYS-01: Professional Package Modernization (`janush.tech.whereami`)
- **Core Value**: Professional package name and application ID matching project identity.
- **Status**: **Implemented & Verified**
- **Architecture & Implementation**:
  - Renamed from `com.example.whereiam` / `com.example.whereami` to `janush.tech.whereami`.
  - Safely migrated SQLite database (753KB, 32 trips), 7 SharedPreferences XMLs, and 13 boundary JSONs on user device.

### SYS-02: Android Auto Unified Media & Spatial Service
- **Core Value**: Glanceable heads-up driving display on car dashboard via Android Auto media browser queue.
- **Status**: **Implemented & Verified**
- **Architecture & Implementation**:
  - `AutoMediaService.kt` + `LocationManager` Singleton `getInstance(context)`.
- **Decision History & Evolution**:
  - *Issue*: In field tests, Android Auto was occasionally frozen or out of sync with the phone app.
  - *Root Cause*: Multiple independent instances of `LocationManager` running in different service processes.
  - *Resolution*: Replaced factory instantiation with a thread-safe singleton, sharing geocoding caches and GPS listeners across phone UI and car head unit.

### SYS-03: Foreground Service & Guarded WakeLock Architecture
- **Core Value**: Ensures background trip recording and live sharing run uninterrupted without leaking CPU wake locks or running zombie background processes.
- **Status**: **Implemented & Verified**
- **Architecture & Implementation**:
  - `LiveTrackingService.kt`: Wake locks are strictly acquired only during an active trip or active live sharing session. Released immediately when entering standby or stopping. Eliminated zombie service persistence bug by decoupling shutdown from `is_tracking` preference.

### SYS-04: App State Machine & Power Policy (`AppStateManager`)
- **Core Value**: Intelligent power management balancing responsiveness and battery consumption.
- **Status**: **Implemented & Verified**
- **Architecture & Implementation**:
  - States: `IDLE`, `FOREGROUND_VIEW`, `LIVE_ONLY`, `TRIP_RECORDING`, `ANDROID_AUTO`.
  - Disables GPS hardware completely in `IDLE`.
  - Adapts sampling: 6s when charging, 10–12s on battery. Suppresses dispatch when stationary ($< 0.35\text{ m/s}$).

### SYS-05: Battery Optimization Exemption Flow
- **Core Value**: Directly guides user to whitelist WhereAmI from OS battery restrictions.
- **Status**: **Implemented & Verified**

### SYS-06: Zero-Power Hardware Motion Wake & Sleep Architecture (`MotionWakeManager`)
- **Core Value**: Eliminates overnight idle battery drain (~0 mAh when stationary) while preserving automatic trip detection across all activity modes.
- **Status**: **Implemented & Verified**
- **Architecture & Implementation**:
  - `MotionWakeManager.kt`: Integrates Android's hardware `Sensor.TYPE_SIGNIFICANT_MOTION` with `TriggerEventListener` (~0 mW micro-power sensor hub operation).
  - In `AppLifecycleMode.IDLE`, continuous GNSS hardware polling is powered down completely (`LocationManager.stopLocationUpdates()`).
  - Upon locomotion, triggers a profile-adapted confirmation GPS burst (`(profile.autoStartDurationMs + 20_000L).coerceAtLeast(35_000L)`). Auto-starts recording if movement threshold is sustained; powers back down and re-arms sensor if motion stops.
  - Option A for MANUAL mode: powers down GPS completely when screen is off with no active trip or live sharing.
  - Unit tests: `BatteryOptimizationAndAutoStartTest.kt`.

---

## 9. Category 8: Diagnostics, Telemetry & CI/CD Pipeline

### OPS-01: Diagnostic Telemetry Logger & JSON Export
- **Core Value**: In-memory ring buffer logging GPS fixes, kinematic gating decisions, and hysteresis transitions with FileProvider JSON export.
- **Status**: **Implemented & Verified**
- **Architecture & Implementation**:
  - `TelemetryLogger.kt`. Fixed FileProvider cache-path crash.

### OPS-02: GitHub Actions Automated Build & Test Gate
- **Core Value**: Continuous integration workflow enforcing automated unit tests before producing APK artifacts.
- **Status**: **Implemented & Verified**
- **Architecture & Implementation**:
  - `.github/workflows/build-apk.yml`: Executes `testDebugUnitTest` and `assembleDebug`.

### OPS-03: Docker Container Build & GitHub Container Registry (`ghcr.io`)
- **Core Value**: Automated publishing of Synology NAS Docker images.
- **Status**: **Implemented & Verified**
- **Architecture & Implementation**:
  - `.github/workflows/docker-publish.yml`.

### OPS-04: Multi-Tier Cache Management (Map & Boundaries)
- **Core Value**: Full offline resilience with controlled disk usage.
- **Status**: **Implemented & Verified**
- **Architecture & Implementation**:
  - Map tile cache management (500MB limit) and boundary polygon caching in `cacheDir/boundaries`.

### OPS-05: Architectural Standards Documentation (`AGENTS.md`)
- **Core Value**: Mandatory architectural guidelines, Git branching rules, and pre-completion gates.
- **Status**: **Implemented & Verified**

---

## 10. Dedicated Section: Abandoned, Resigned & Replaced Features

This section documents features that were requested, prototyped, or previously deployed, but subsequently abandoned, replaced, or retired, along with the technical rationale, user decisions, and historical context.

### ABANDONED-01: Home Screen App Widget (`SYS-02`)
- **Original Intent**: A home screen Android launcher widget displaying current locality, street, speed, and refresh/live toggle.
- **Why Abandoned / Retired**:
  - In field operation, Android AppWidget provider periodic broadcasts triggered severe main-thread reverse geocoding ANR storms when waking up from deep sleep.
  - Battery profiling revealed high standby drain due to OS alarm manager wakeups.
  - *Decision*: Retired in v1.3.2. Architecture specifications were preserved in backlog for a potential future rewrite using Jetpack Glance with non-polling push architecture. Glanceable spatial awareness is fulfilled by Android Auto and persistent foreground notifications.
- **Attribution**: Technical necessity (ANR elimination and battery optimization).

### ABANDONED-02: Extreme Raster Bitmap Font Zoom (340% / tilesScaleFactor 2.0x+)
- **Original Intent**: Scaling raster map tiles up to 3.4x to provide huge map text labels.
- **Why Abandoned / Replaced**:
  - User field testing confirmed that scaling OSM raster tiles above 1.7x merely performed digital bitmap magnification, producing blurry, illegible maps rather than crisp typography.
  - *Decision*: Abandoned 3.4x raster scaling. Replaced with calibrated scale presets (0.90x–1.75x) combined with the introduction of native Hi-DPI 512x512 Freemap Outdoor tiles (`@2x`), which deliver razor-sharp labels natively.
- **Attribution**: User feedback and raster graphics physical constraints.

### ABANDONED-03: GitHub Pages Live Sharing Fallback Provider (`LIV-GH`)
- **Original Intent**: A serverless fallback mode publishing live location breadcrumbs to GitHub Pages / Gists before Synology NAS was configured.
- **Why Abandoned / Removed**:
  - Introduced configuration complexity in the mobile UI (multiple confusing URL fields).
  - WebSockets and fast polling were unreliable against GitHub static infrastructure.
  - *Decision*: Removed in v1.3.1. Live sharing was unified exclusively around the self-hosted Synology NAS / Alpine Docker container on port 3003.
- **Attribution**: User explicit instruction to eliminate confusion and streamline infrastructure.

### ABANDONED-04: Floating On-Screen Zoom `+`/`-` Buttons & Compass Cycle Button
- **Original Intent**: Dedicated on-screen zoom buttons and a floating compass button on the map viewport to cycle orientation (Auto, N, E, S, W).
- **Why Abandoned / Removed**:
  - The floating buttons obstructed map visibility and created visual clutter.
  - Multi-touch pinch-to-zoom is universal and preferred by the user.
  - Cycling through 5 cardinal modes on a floating FAB was unintuitive.
  - *Decision*: Removed `+`/`-` zoom buttons and compass cycle FAB from map viewport. Map orientation mode was consolidated into `MapSettingsDialog` (with intuitive `AUTO Course-Up` vs `Fixed North-Up` modes).
- **Attribution**: User request to eliminate visual redundancy and declutter map.

### ABANDONED-05: Public Visitor View Counter on Web Page
- **Original Intent**: Displaying total viewer impressions as a badge in the header of the public `/live/:id` web viewer.
- **Why Abandoned / Replaced**:
  - Visitors viewing the page do not need to see how many other people are watching.
  - The host, however, needs this awareness on their phone.
  - *Decision*: Removed view counter from public web page. Pushed real-time visitor count directly to host's mobile UI on the Locality Card (`👥 X`).
- **Attribution**: User request.

### ABANDONED-06: `com.example.*` Namespace & Application ID
- **Original Intent**: Project initialized with default Android package name `com.example.whereiam`.
- **Why Abandoned / Replaced**:
  - `com.example` is an amateur placeholder not suitable for a production-grade utility.
  - Inconsistent with project branding `WhereAmI`.
  - *Decision*: Completely migrated codebase, namespace, and Android application ID to `janush.tech.whereami`. All database, preference, and cache files were safely backed up and migrated on device.
- **Attribution**: User instruction.

---

## 11. Future Planned Roadmap & Backlog

| Backlog ID | Feature Description | Category | Target Milestone |
| :--- | :--- | :---: | :---: |
| **BKL-01** | MapLibre Native Vector Map Engine (Vector MBTiles / Vector Styles for true infinite font scaling and dynamic label rotation) | Map Engine | Next Major Architecture Release (v2.0) |
| **BKL-02** | Root URL Landing Page (`/` or `/live/` without ID) with browser cookie/localStorage remembered past sessions | Web Platform | Future Sprint |
| **BKL-03** | Server data retention policy and TTL cleanup job for expired session breadcrumbs | Server Architecture | Future Sprint |
| **BKL-04** | Visitor on-demand location refresh request from web page to mobile app | Live Protocol | Future Sprint |
| **BKL-05** | Historical shared routes catalog portal | Web Platform | Future Sprint |

---

*Last Comprehensive Audit: 2026-09-16 (Post-Field Test Round 2, Package Migration to `janush.tech.whereami` Verified on Device, Round 2 UI/UX Polish: orientation modes, live pill alignment, cancel/caching improvements, normalized close buttons, live title propagation, ∞ continuous toggle)*
