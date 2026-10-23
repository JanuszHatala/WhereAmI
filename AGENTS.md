# AGENTS.md - Antigravity & AI Pair Programming Operational Rules

This document establishes the mandatory architectural standards, development workflows, testing requirements, and operational rules for AI coding agents (Antigravity, Gemini, Claude, Cursor, etc.) working on the **WhereAmI** codebase.

---

## 1. Project Identity & Core Purpose

- **Project Name**: WhereAmI
- **Android Root Directory**: `WhereIAm`
- **Android Application ID / Package**: `janush.tech.whereami`
- **Companion Web/Sync Backend**: Node.js / Express in `server/`
- **Technical Architecture Document**: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)
- **Design System & UI Architecture**: [`docs/DESIGN_SYSTEM.md`](docs/DESIGN_SYSTEM.md)

### The Core Value Proposition (Non-Negotiable)
The absolute core purpose of this application is **ultra-fast, unambiguous, and reliable real-time spatial awareness**:
1. **Primary Locality Display**: Instantly and legibly showing the **current place name** (City, Town, Village, or Settlement) with its complete, unambiguous **structural administrative hierarchy** (Municipality `gm.`, County `pow.`, Voivodeship `woj.`, Country).
2. **Canonical Road / Street Display**: Instantly showing the current road designation (canonical Polish designations `DK52`, `DW946`, `A4`, `S7`, `E77` without house numbers on major corridors, or formatted residential streets `ul. Mickiewicza 12`).
3. **Core Supremacy**: All supplementary features (trip recording, live sharing, map overlays, GPX export, statistics) exist to support this primary experience. **Never compromise, regress, or visually obstruct the primary locality and road awareness display.**

### The AI Pair Programming Covenant & Quality Invariant (Non-Negotiable)
Every AI agent (Antigravity, Gemini, Claude, Cursor) contributing to this codebase is strictly bound by this operational covenant:
1. **Zero Degradation of Core Value**: Never degrade locality hierarchy formatting, road designation precision, kinematic filtering, or spatial cache integrity for expedience, brevity, or lazy shortcuts.
2. **Zero Battery & GNSS Leaks**: Never introduce unbounded GPS subscriptions, continuous high-frequency background sensor listening (e.g. 60Hz accelerometer polling), or notification wake-lock storms. All lifecycle transitions must be verified with automated unit tests.
3. **Rigorous Iteration Over Quick Hacks**: Always iterate as many times as necessary to produce flawless, production-ready code. Workarounds and temporary hacks are strictly forbidden unless explicitly requested by the user and heavily documented.

---

## 2. Crucial Architectural Priorities

### A. Battery Optimization & Zero-Idle-Drain (Highest Priority)
The app runs for hours in pockets, bike mounts, and vehicle cradles. Excessive battery consumption is treated as a critical bug.
- **Complete Idle GPS Shutdown**:
  - In `AppLifecycleMode.IDLE` (screen off, no active trip, no live sharing), **GPS hardware MUST be completely powered down** via `LocationManager.stopLocationUpdates()`.
  - **Never poll GPS continuously in background idle**.
- **Live Sharing Stationary Low-Frequency Cadence & Motion Wake Arming (`LIVE_ONLY`)**:
 - When Live Sharing is active in the background without an active trip recording (`LIVE_ONLY` mode), frequent GNSS polling while stationary is strictly forbidden.
 - When stationary for $\ge 30\text{s}$ (accelerometer variance $< 0.045$ via `StationaryDetector` or speed $< 0.35\text{ m/s}$):
 - **If the screen is off**: GPS MUST be powered down completely via `LocationManager.stopLocationUpdates()` (charging or battery). Continuous 30–60 s GNSS holds the modem out of Deep Doze (field 2026-10-22: ~10 min screen-off GNSS via FGS).
 - **If the screen is on**: relax to 60s (30s when charging) via `updateSamplingInterval`.
 - Android's hardware `Sensor.TYPE_SIGNIFICANT_MOTION` trigger sensor (~0 mW) MUST be armed via `MotionWakeManager.arm()`.
 - A lightweight periodic presence heartbeat transmits presence over HTTP (~50ms): every `syncIntervalMinutes` when screen on (floored to ≥5 min when screen off; 60 min while Sleep Mode). Companion web viewer stays "ONLINE" without a GNSS subscription.
 - Significant physical locomotion triggers `MotionWakeManager.handleMotionWake()`, immediately restoring moving GPS cadence (15s / 6s) and evaluating trip auto-start. The confirmation GPS burst MUST continue in both `IDLE` and `LIVE_ONLY` (never abort solely because Live Sharing is active); while the burst is active, `LIVE_ONLY` must not re-apply stationary GPS-off.
 - Overnight Sleep Mode should pause Live Sharing by default (`pref_auto_sleep_pause_live_share`). When Sync with Morning Alarm (`pref_auto_sleep_follow_alarm`) is enabled, Sleep ends when the morning alarm fires during the sleep window (even if bedtime end is later). Overnight drain is addressed by GPS-off screen-off + pause-live-on-sleep, not by ignoring the alarm.
- **Notification Content Stability Invariant (Zero-Wakelock-Storm)**:
  - When a trip is NOT actively recording (e.g. `LIVE_ONLY` mode, `TripMode.AUTO` standby), the persistent notification text MUST NOT include dynamic ticking time strings (e.g. `⏱️ 7h 12m elapsed` or minutes/seconds counters).
  - Changing notification strings causes `contentChanged = true` on every minute tick, triggering `NotificationManager.notify()`, acquiring system wake locks (`NotificationManagerService:post`), firing wakeup alarms, and pulling the CPU out of Android Doze.
  - Standby and live-only notification text MUST remain strictly static (e.g. `"Live Sharing Active • Auto-record: Car (>10 km/h) • $place"` or `"WhereAmI • Auto-record Standby"`).
- **Standby Location Stream Decoupling**:
  - In `LiveTrackingService`, when in pure standby without an active trip and without live sharing (`TripMode.AUTO` standby), `stopLocationTracking()` MUST be called to release `masterLocationFlow` subscribers.
  - When Live Sharing is active (`LIVE_ONLY`) with the screen on, `startLocationTracking()` remains active to stream live fixes, while CPU wake locks remain released (`updateWakeLock(false)`). With the screen off and stationary, GPS updates MUST stop; presence continues via heartbeat only until `MotionWakeManager` or screen-on restores cadence.
- **Hardware Motion Wake (`MotionWakeManager`)**:
  - In `TripMode.AUTO`, idle locomotion detection relies strictly on Android's hardware `Sensor.TYPE_SIGNIFICANT_MOTION` trigger sensor (~0 mW draw in sensor hub).
  - Physical motion triggers a temporary, profile-adapted confirmation GPS burst (`(profile.autoStartDurationMs + 20_000L).coerceAtLeast(35_000L)`). If locomotion criteria are met, the trip auto-starts; otherwise GPS powers down and the hardware sensor re-arms.
- **Screen-Off MANUAL Mode (Option A)**:
  - When in `TripMode.MANUAL` and screen turns off without an active trip or live sharing, all background location polling and services MUST stop completely.
- **Wake Lock Strict Scoping**:
  - `LiveTrackingService` wake locks MUST ONLY be acquired when an active trip is actively recording (`TripManager.activeTrip.value != null`).
  - Wake locks must **NEVER** be held during Live Sharing alone (`LIVE_ONLY` mode), in `onCreate()`, or in standby/idle (`TripMode.AUTO` standby), ensuring the device can safely enter Android Doze when stationary.
  - Releasing wake locks is mandatory upon trip completion, stop, or standby transition.
- **Live Sharing Breadcrumb Decoupling**:
  - In `LiveSharingManager`, breadcrumb points (`memoryPointsQueue`, polylines) MUST ONLY be appended when an active trip is actively recording (`TripManager.activeTrip.value != null`) AND spatial displacement $\ge 15\text{m}$.
  - When Live Sharing is active without an active trip recording, it streams presence ONLY (current location snapshot, locality, speed) with zero points appended to the trail, eliminating jitter starburst polylines and unneeded wakeups when resting or overnight.
- **Standby Foreground Service & Android 14+ Location FGS Invariant**:
  - On Android 14+ (`targetSdk >= 34`), starting a ForegroundService with `foregroundServiceType="location"` from the background is forbidden and throws `ForegroundServiceStartNotAllowedException`.
  - In `TripMode.AUTO`, `LiveTrackingService` MUST be started while the app is in the **FOREGROUND** and kept running as a **Zero-CPU Standby Foreground Service** with a persistent notification ("WhereAmI • Auto-record Standby").
  - In Standby mode:
    - GPS polling MUST be completely shut down via `LocationManager.stopLocationUpdates()`.
    - Location stream collectors in the service MUST be detached (`stopLocationTracking()`).
    - Wake locks MUST be released (`updateWakeLock(false)`).
    - Hardware `Sensor.TYPE_SIGNIFICANT_MOTION` is armed to trigger confirmation bursts directly within the existing FGS process.
  - Never attempt to stop the FGS in `IDLE` expecting to restart it later from background sensors or receivers.
- **Smart `FLAG_KEEP_SCREEN_ON` Inactivity Gating**:
  - When `pref_keep_screen_on` is enabled, the screen remains awake continuously during active trip recording, live sharing, or active movement.
  - When verified physically stationary for $\ge 3$ minutes without an active trip and without live sharing, `FLAG_KEEP_SCREEN_ON` MUST be cleared to allow Android display sleep and prevent overnight battery drain at home or on desks.
- **Standby Hibernation & One-Tap Controls**:
  - In `TripMode.AUTO` standby with screen off, after 90 minutes of verified stationary rest (`STANDBY_HIBERNATION_TIMEOUT_MS`), `AppStateManager` transitions the standby foreground service into hibernation, completely disarming `Sensor.TYPE_SIGNIFICANT_MOTION`.
  - Standby notification MUST include `[⏸ Pause]` (immediate hibernation) and sleep notification MUST provide `[▶ Resume]` (user-interaction wakeup complying with Android 14+ FGS restrictions). Standby sleep also automatically wakes on app resume (`MainActivity.onStart()`).
  - In `TripMode.MANUAL`, when screen turns off without an active trip or live sharing, the service and notification MUST stop completely (Option A).
- **Adaptive GPS Sampling**:
  - Recording/Charging: High frequency (~5s–6s sampling).
  - Battery/Live Tracking: Conservation frequency (10s–12s sampling).
  - Stationary: Suppress frequent location dispatching when stationary ($< 0.35\text{ m/s}$).
- **Stationary Jitter Gating**: When displacement $< 4\text{m}$ and speed $< 0.35\text{ m/s}$, suppress heavy calculations, map repaints, and network transmissions.
- **Aggressive Caching**:
  - Multi-tier in-memory and persistent disk caching for boundary polygons (`cacheDir/boundaries`).
  - Spatial SQLite LRU grid caching for reverse geocoding (~11m cell resolution, 30-minute expiry).
  - Avoid redundant HTTP calls at all costs.

### B. Kinematic Sanity, Smooth Heading & Position Estimation (Tracking Engine v2, ADR-07)
Raw mobile GPS fixes exhibit multipath noise, satellite loss jumps, and cell-tower fallbacks ($1.5\text{km}-5\text{km}$ accuracy).
- **Mandatory Estimator Pass**: All raw location fixes MUST pass through `tracking/TrackingEngine` (CTRA EKF + 3 s fixed-lag RTS smoother) before dispatching to UI, trip recording, or live telemetry. `GpsFilterEngine`, `PositionStabilizer` and `PositionInterpolator` were retired in v1.11.0; do not reintroduce parallel filters.
- **Engine Rules**:
  1. *Single time base*: `elapsedRealtimeNanos` everywhere; UTC only for persistence. Process EVERY location in `LocationResult.locations`, in order.
  2. *Innovation gate*: chi-square gating plus profile max-speed cap; 3 mutually consistent rejections reset the filter (no recovery deadlock).
  3. *Stationary handling*: ZUPT, low process noise and stationary anchoring (no multipath creep); never zero the hardware Doppler speed. A still device with sparse fixes (5-20 s apart indoors) continues its anchored state across gaps (`continuesStationary`); never re-seed it from one noisy fix.
  4. *Recorded geometry and distance* come only from the smoother output (`TripManager.onSmoothedPoints`) stored in the `track_points` table. Commit path runs `TrackSpikeGuard` (slow/impossible tips, gap teleports ≪ Doppler travel, ≥135° course-reversal chords, and 3-point `isLateralVSpike` single-vertex lateral excursions) with a 2-strike moving-point recovery rule and stationary-dwell anchor timestamp refresh so pause/stop multipath nests do not enter `track_points` and legitimate turns or road bends never lock up the commit pipeline.
  5. *Profile is a prior, not a cage*: physical limits (max speed, accuracy gates, course trust) go through `TrackingEngine.activeProfile`, which escalates when measured motion exceeds the selected profile. A wrong profile choice must never cause reject/reset cycles or sparse recording.
- **Heading Display**:
  - Arrow direction = fresh filter heading (`Pose.headingFresh`); otherwise, while visibly moving, the cursor's own motion course (`MotionCourse`; vehicles never flip it more than 90 deg from the heading fresh within the last 8 s, `MotionCourse.forVehicle`); stationary = last confident heading held (no spinning at traffic lights or rest stops); no observable heading at all = dot. Never show a stale held heading while moving, and never dead-reckon the cursor along an unobserved heading.
  - Course-up rotation is driven by `RotationSlew` (rate/acceleration limited, profile aware), never by per-fix deadband snapping.
- **Kinematic / Geocoding Decoupling**:
  - Real-time map tracking (`LocationFix`) MUST be decoupled from asynchronous geocoding enrichment.
  - Geocoding completions must never re-emit past coordinates or past velocities back into the map kinematic pipeline.
- **60fps Render Pipeline (`tracking/RenderPipeline`)**:
  - The map tracking marker, camera recentering and rotation MUST use `RenderPipeline.frame(SystemClock.elapsedRealtime())` on display VSYNC (`withFrameNanos`): CTRA prediction to frame time with critically damped correction blending and adaptive 0-1.8 s latency when conditions degrade. Vehicles render at least 1.0 s behind (`RenderPipeline.VEHICLE_LATENCY_MS`), between filter states, so the live line does not draw extrapolation zig-zags that the smoother later re-shapes.
  - Never call overlapping `animateTo()` calls on every GPS fix; use direct `setCenter(centerGp)` driven by the pipeline.
  - **Single position writer**: marker, camera, rotation, accuracy circle, altitude badge and polyline tip are positioned ONLY in the frame loop. Other effects may update content (text, icon, enabled state), never position; two writers cause visible flicker.
  - **Never display estimates that will be revised** (smoother `provisional()` / RTS tail): they re-shape on every fix.
  - Per-fix and per-frame paths on the main thread must not touch SQLite or the network (move lookups to IO, hold the last good value, e.g. `DemAltitudeHold`).
  - The live polyline has ONE source: committed smoothed points + pencil trail (the cursor's rendered path since the newest committed vertex, `PencilTrail`) + cursor tip. It must always end under the cursor and never re-shape ahead of it. Never bridge gaps with a chord: the renderer consumes every engine state (`TrackingRuntime.states`, never the conflated UI fix) and after missed frames or a batch the pencil is refilled from `RenderPipeline.pathBetween`. A batched `LocationRequest` is flushed (`BatchedRequestSequencer`) before it is changed or removed. It is drawn as a display-only Catmull-Rom curve (`LiveTrackLine` / `CurvedPolyline`); stored vertices are never curved.
  - **Slow on-foot mode**: below 2.2 m/s on foot profiles the cursor runs 1.6 s behind on a curve through the filtered positions and the arrow follows that curve (`Pose.pathHeadingDeg`), so it always points along the line being drawn. Vehicle profiles never use it.
  - Recorded vertex density follows measured speed (`AdaptiveTrackSampler`), not the selected profile; the profile is only a kinematic prior (`TrackingEngine.activeProfile` escalates when motion exceeds it).
  - **Compass arrow (on-foot profiles, setting `pref_compass_arrow`, default on)**: the arrow may use the rotation-vector compass, sampled at `pose.renderTimeMs`, only while moving and only through `CompassArrow` (learned offset to the course, trust gate, course fallback). Never rotate the arrow from the compass at rest. Never drive course-up map rotation from the compass or from the slow-mode path tangent (`CourseUpHeading`: filter heading when confident, else motion course). The sensor is registered only when `CompassPolicy.shouldRun` holds.
  - **Join blend**: the pencil start is blended onto the newest committed vertex (`PencilTrail.blendedFrom`), fading out within 4 m and never past the cursor tip.
- **Screen-Off Recording Cadence, Android Auto & Gyro**:
  - While actively recording a trip (`TRIP_RECORDING`) or connected to Android Auto (`ANDROID_AUTO` / `isAutoMediaActive` / `UI_MODE_TYPE_CAR`), vehicle and fast profiles sample at 1 Hz unbatched (`maxDelay = 0L`) so real-time speed on Android Auto and screen-on transitions are never delayed by 20s batch windows; hardware batching (`setMaxUpdateDelayMillis`) is applied only in `BatteryPowerPolicy.BATTERY_SAVER` when not connected to Android Auto.
  - The gyroscope (`GyroHeadingSensor`) MUST be registered only when `GyroPolicy.shouldRun` holds (foreground, moving, following or recording) and unregistered otherwise.
  - Identical `LocationRequest` re-issues are deduplicated in `LocationManager.updateSamplingInterval`.

### C. Geocoding Stability, Road Precedence, International Roads & Boundary Integrity
- **Road Precedence**: `OSRM Snapped Road` $\to$ `OSM Nominatim Vector Road` $\to$ `Android Platform Geocoder`. Always prioritize OpenStreetMap vector road designations over platform geocoders in Poland.
- **Street Card Formatting**: When both a street name and a road ref are present, show `Krakowska (DK52)` via `RoadNameNormalizer.formatStreetDisplay` - never `Krakowska • 52` with a bare numeric ref.
- **Display Road Snap (live only)**: Cursor and live polyline may apply a bounded EMA pull (≤18 m) toward OSRM `/nearest` coordinates for map alignment; recorded `track_points` must remain filter/smoother geometry.
- **Named street over bare highway code**: If OSRM returns only `DW*`/`DK*`/`A*`/`S*` but OSM confirms the same way via `roadRef`, show `Krakowska (DW948)` via `coalesceOsrmAndOsmStreet`. Never glue an OSRM-only code onto an unrelated named way (`Kardynała Karola Wojtyły (DK52)`). When the committed card is a bare code and the spatial-cache cell has a **likely corridor local name** (`isLikelyCorridorLocalName`, not commemorative multi-token names), coalesce via `coalesceBareCodeWithCachedName`. Spatial cache must not overwrite a named street with a bare code; bare highway-only rows and false `(DK*)` on commemorative names are purged on open.
- **High-speed street hysteresis**: Above 35 km/h, non-major → non-major switches require corridor inertia (5 fixes / 7 s) unless a recent turn or locality transition relaxes the gate — prevents perpendicular snaps (`Przecznia` while on `Krakowska`).
- **International Road Designations**: Polish road codes (`DK*`, `DW*`) MUST ONLY be applied when `countryCode == "PL"`. For Slovak roads (`countryCode == "SK"`), format 1–2 digit roads as `I/<num>` (e.g. `I/18`) and 3-digit roads as `II/<num>` (e.g. `II/537`). All other countries must retain clean numeric or international vector refs (e.g. `18`, `E50`), never Polish `DK`.
- **Viaduct / Overpass Inertia**: At driving speeds ($> 35\text{ km/h}$) on major corridors (`DK*`, `DW*`, `A*`, `S*`), enforce inertia: require at least 5 consecutive candidate readings AND $\ge 7.0\text{s}$ of sustained fixes before switching road names.
- **15-Second Decay Grace Period**: If reverse geocoding temporarily returns no street during brief signal cutouts or tunnels, maintain the last confirmed street name for 15 seconds. Never flicker or blank out the display.
- **Intentional Visit Filter**: Qualify a town/place as visited during a trip ONLY if the user penetrates $\ge 150\text{m}$ into the locality OR remains within it for $\ge 45\text{s}$. Boundary skimming along border highways must never trigger accidental visits.
- **Auto-Start Locomotion Gate**: In `TripMode.AUTO`, foot profiles use strict `ResumeMotionGate` (≤25 m accuracy). Vehicle auto-start uses a softer variant (≤35 m, one forgiven accuracy miss, window opens at ≥70% of target speed; CAR displacement floor 25 m). Never start from a single indoor multipath jump; net displacement / half-speed travel still required.
- **Scribble discard (vehicle only)**: Auto-started `CAR`/`CYCLING`/`MTB` indoor multipath nests (`TripScribble`: tiny cluster, path ≫ cluster) may be deleted on stop. Never discard `WALKING`/`HIKING`/`RUNNING` as scribbles — short out-and-back walks near home are valid (field trip 241).
- **Non-PL highway codes**: Polish `DK*`/`DW*` must never be stored or shown outside Poland; Slovakia uses `I/<n>` / `II/<n>` (`sanitizeStreetForCountry` + spatial-cache migration on open).
- **Pause Auto-Resume**: while paused, a trip resumes on motion only through `ResumeMotionGate`: every sample accurate (<= 25 m) and >= 70 % of the profile's auto-start speed for the confirmation window, displacement >= travel at half that speed. Indoor fixes must never resume a paused trip.
- **Rest Stop / Pause Gating**: Enforce $\ge 150\text{s}$ ($2.5\text{ min}$) stationary dwell anchored within a $\le 12\text{m}$ spatial cluster gate before creating a `TripPause`. Enforce $45\text{s}$ resume debounce before finalizing pauses, and coalesce adjacent pauses within $30\text{m}$ and $60\text{s}$.
- **Active Trip Visual Isolation**: An active trip recording (`isRecording || trackPoints.isNotEmpty()`) MUST NEVER display historical trip tracks or historical pauses. In `OsmMapView`, historical polylines must be completely suppressed from rendering during recording. When starting a new trip, clear selected historical trip selections in `MainViewModel` and clear live share breadcrumb buffers (`resetSessionTrail()`).
- **Pin Boundary Containment**: Dropped-pin / saved-place borders (`forPin=true`) MUST enclose the pin (`polygonContains`). Resolve reverse-first (zoom 14→12→10), prefer village/sołectwo over gmina, never powiat/woj/country. Invalidate disk cache polygons that miss the pin. Mute live GPS borders while pin borders are shown. Outdoor pins may title from peak/pass/viewpoint (Nominatim or Overpass ≤400 m); borders stay administrative. Live GPS borders (`forPin=false`) stay name-first for the current locality card — never reverse-first overwrite a named locality cache with a smaller admin unit.
- **Rate-Limit Mutex**: Respect Nominatim & Overpass limits (minimum 1.5s mutex between boundary requests, exponential backoff on HTTP 429).

### D. Offline-First & Network Resilience
- Offline breadcrumb accumulation: live fixes are queued locally and uploaded in batches.
- Points are deduplicated by timestamp `t`.
- Previously downloaded administrative boundaries persist on disk and must load when offline.
- Spatial cache falls back to cached entries indefinitely when completely offline.

### E. UI/UX Alignment, Typography & Layout Stability (`docs/DESIGN_SYSTEM.md`)
- **Emoji/Icon Baseline Alignment & Disentanglement**: Never embed emojis directly into text strings alongside labels. Always separate emojis using the project's standard `EmojiText` composable, paired with `PlatformTextStyle(includeFontPadding = false)`, `LineHeightStyle(alignment = LineHeightStyle.Alignment.Center, trim = LineHeightStyle.Trim.Both)`, explicit `lineHeight = fontSize`, and default optical upward offset (`-maxOf(2.5f, fontSize.value * 0.28f).dp`) to eliminate visual floating/misalignment caused by *Noto Color Emoji*'s asymmetric font metrics and low baseline placement relative to Latin uppercase text. Never wrap in `Box(Modifier.size(...))` or rely on `PlatformTextStyle` alone.
- **Single-Line Multi-Metric Displays**: Metric displays (pace, speed, distance) must declare `maxLines = 1` and `softWrap = false` to prevent word-breaking glitches (e.g. `(5.0 km/` wrapping onto `h)`).
- **Metric Badges & Contextual Grouping**: Never place single-metric badges (e.g. `🚩 N` visited places) on an isolated row above or below titles. Group them alongside related telemetry controls (e.g. Activity Profile pill) to preserve vertical density and avoid UI jitter.
- **Map Viewport Unobstructed Work Area**: When fitting polylines or points to screen (`fitPointsToUnobstructedViewport`), calculate the unobstructed viewport by strictly subtracting all floating controls (top `LocalityCard`, right-side map buttons column, bottom toolbars). Right-side controls (42dp buttons + margins) require at least `72dp` inset to prevent tracks from rendering under buttons, regardless of map rotation. The horizontal optical offset MUST be computed as `offsetPixelsX = screenCenterX - mapWidth / 2.0` (negative when right insets exceed left insets) to correctly shift the camera east and center the polyline away from right-side controls.
- **Emoji/Icon Glyph Padding in Badges**: When embedding emojis inside compact badges or pills (e.g. `🚩 N` in `LocalityCard`), explicitly pass `yOffsetDp = 0.dp` to `EmojiText` to prevent the default upward baseline compensation (`-maxOf(2.5f, fontSize * 0.28f).dp`) from clipping the top tip of glyphs against the pill container border.
- **Compose ModalBottomSheet Expansion**: All secondary configuration or settings bottom sheets (`ModalBottomSheet`) must configure `rememberModalBottomSheetState(skipPartiallyExpanded = true)` to open immediately at full height, preventing half-screen partial drawer states that hide settings behind drag gestures.
- **Landscape Screen Conservation**: In landscape mode, never stack toolbars vertically. Collapse secondary toolbars to icon-only representations or position them side-by-side with the main bottom toolbar.
- **High-Contrast Interactive Buttons Invariant (`docs/DESIGN_SYSTEM.md` Section 6)**: All interactive button labels (`Button`, `OutlinedButton`, `TextButton`, `Surface(onClick = ...)`, `FilterChip`) MUST use crisp, high-contrast white text (`Color.White` / `#F8FAFC`). Never use gray (`#94A3B8`, `Color.LightGray`, `#CBD5E1`) for button labels. Differentiate secondary/tertiary buttons using container backgrounds (`#334155`, `#1E293B`) or border strokes, never dimmed text.
- **Compact Status Badges & Layout Stability Invariant (`docs/DESIGN_SYSTEM.md` Section 5.D)**: Rows containing optional or dynamic status pills/badges (e.g. `SLEEPING`, `ACTIVE`, `PAUSED`) beside labels MUST use fixed container heights (e.g. `Modifier.fillMaxWidth().height(24.dp)`) with `Arrangement.SpaceBetween` and `Alignment.CenterVertically` to guarantee zero layout shifts when state toggles. Both label and badge text MUST normalize font metrics with `PlatformTextStyle(includeFontPadding = false)`, `LineHeightStyle(alignment = LineHeightStyle.Alignment.Center, trim = LineHeightStyle.Trim.Both)`, and explicit `lineHeight = fontSize`. Badge text MUST be centered via `Box(contentAlignment = Alignment.Center)`. Dynamic status descriptions changing across states MUST declare `maxLines = 1` and `softWrap = false` to eliminate multiline line-wrap displacement.
- **Modal, Dialog & Bottom Sheet UX Invariant (`docs/DESIGN_SYSTEM.md` Section 7)**:
  - **Canonical Top-Right Close Button**: All Card-based `Dialog` containers (`usePlatformDefaultWidth = false`) and `ModalBottomSheet` headers MUST use the standard 36dp circular slate button: `IconButton(modifier = Modifier.size(36.dp).clip(CircleShape).background(Color(0xFF1E293B)))` with 20dp `Icons.Default.Close` tinted `Color.White`.
  - **Two-Button Layout**: Binary action modals MUST place `[Cancel]` on the LEFT (`TextButton`, white) and `[Action]` on the RIGHT (`Button`, white text) in a single inline horizontal row (`horizontalArrangement = Arrangement.End`), with `dismissButton = null`. Action buttons must NEVER stack vertically on mobile viewports.
  - **Dismissal Redundancy**: When a dialog or bottom sheet has a top-right `X` button, NEVER add a redundant bottom `[Close]` button. Reserve bottom space for content.
  - **Multi-Button Hierarchy**: Most constructive action full-width on top; secondary actions inline 50/50 below. Fullscreen dialogs use top-left `ArrowBack`.

---

## 3. Mandatory Testing & Quality Assurance Gates

### Unit Test Requirements
- **Every feature, algorithmic rule, parser, kinematic filter, or data transformation MUST be covered by automated unit tests** in `app/src/test/java/`.
- Automated regression test suites must be maintained for:
  - Tracking engine, render pipeline, gyro lifecycle and route matching (`tracking/TrackingEngineTest.kt`, `RenderPipelineTest.kt`, `SupportingTrackingTest.kt`, `GyroHeadingSensorTest.kt`, `RouteMatchingTest.kt`, `TrackPointRowTest.kt`).
  - GPX export (`GpxExporterTest.kt`) and location request cadence (`LocationRequestCadenceTest.kt`).
  - Battery optimization & activity auto-start (`BatteryOptimizationAndAutoStartTest.kt`).
  - Bearing smoothing & circular EMA (`BearingSmootherTest.kt`).
  - Road & street normalization (`RoadNameNormalizerTest.kt`).
  - GPS filtering & anomaly rejection (`tracking/TrackingEngineTest.kt`).
  - Trip continuity, auto-merge, and pause splitting logic (`TripSplitTest.kt`).
  - Reverse geocoding hierarchy formatting and deduplication (`HierarchyResolutionTest.kt`).

### Tracking Change Evidence (`jh-tracking-change`)
- Any change to `tracking/`, the `OsmMapView` cursor/polyline/badge code, `TripManager.onSmoothedPoints` or location cadence MUST follow `.agents/skills/jh-tracking-change/SKILL.md`: check its invariants against the diff and report before/after replay metrics (`FieldReplayTest` via `scripts/replay.sh` on a `device_backup/diagnostics/*.jsonl` trace): heading confidence while moving, heading error p50/p90/p97, filter resets, stored vertices and spacing.

### Pre-Completion Build Gate
Before reporting any implementation or bug fix as complete, the agent MUST run:
```bash
# macOS / Linux:
./gradlew testDebugUnitTest assembleDebug

# Windows:
.\gradlew.bat testDebugUnitTest assembleDebug
```
Both commands must finish with `BUILD SUCCESSFUL` (0 test failures, 0 compilation errors).

### Continuous Integration (CI)
- GitHub Actions workflow (`.github/workflows/build-apk.yml`) MUST execute `testDebugUnitTest` on every push and pull request before generating build artifacts.

---

## 4. Git Branching & Version Control Workflow

To maintain production stability on `master`, the following Git workflow is **strictly enforced**:

### 1. No Direct Pushes or Local Merges to `master` (Pull Request Only)
- Never commit or push directly to `master`.
- Never merge a branch into `master` locally (`git checkout master && git merge ...` followed by `git push origin master` is FORBIDDEN).
- **Every change reaches `master` ONLY through a GitHub Pull Request** whose required check (`Unit Tests & Build Verification`) is green. The PR is the single audit trail for review, CI and the merge commit.

### 2. Feature & Fix Branch Lifecycle
1. **Branch Creation**: Always start work by creating a dedicated branch from up-to-date `master`:
   ```bash
   git checkout master
   git pull origin master
   git checkout -b feat/<descriptive-feature-name>   # For new features
   # OR
   git checkout -b fix/<descriptive-issue-name>      # For bug fixes
   ```
2. **Local Verification**: Implement changes, write/update unit tests, and verify build gates:
   ```bash
   ./gradlew testDebugUnitTest assembleDebug
   ```
3. **Structured Commits**: Commit changes with clear, descriptive conventional commit messages (`feat: ...`, `fix: ...`, `docs: ...`).
4. **Push Branch**: Push the feature branch to GitHub **only upon explicit user instruction**:
   ```bash
   git push -u origin <branch-name>
   ```
5. **Open a Pull Request**: After the branch is pushed on explicit user instruction, open a PR into `master` with a summary, test evidence and doc updates:
   ```bash
   gh pr create --base master --head <branch-name> --title "<conventional title>" --body "<summary, tests, risks>"
   gh pr checks <pr-number> --watch   # wait for 'Unit Tests & Build Verification'
   ```
6. **Merge via PR only**: After CI is green and on explicit user instruction, merge the PR (merge commit, non-fast-forward, so branch history is retained):
   ```bash
   gh pr merge <pr-number> --merge --delete-branch
   ```
   Do NOT run `git merge` into `master` or `git push origin master`.
7. **Post-Merge Cleanup**: After the PR is merged:
   ```bash
   git checkout master && git pull origin master
   git branch -d <branch-name>        # local branch (if not removed already)
   git remote prune origin            # stale remote tracking refs
   ```
   Never leave dead merged branches dangling; the full history stays in `master`'s merge commit.

### 3. Persistent Tracking Registers
- Update `docs/ENHANCEMENT_TRACKER.md` after completing each sprint item or milestone.
- Update `docs/FEATURES_INVENTORY.md` as features evolve or retire.
- Update `docs/ARCHITECTURE.md` when architectural decisions (ADRs) are introduced.

### 4. Mandatory Version Increment Protocol
With **every development session, feature branch, bug fix, or round of changes** where code is modified and prepared for build/installation:
- **`versionCode` MUST be incremented by 1** in `app/build.gradle.kts` (e.g. `37` -> `38`). Android package manager requires monotonically increasing `versionCode` to permit on-device in-place APK upgrades (`adb install -r`).
- **`versionName` MUST be incremented** according to SemVer principles:
  - **Patch increment** (`1.3.0` -> `1.3.1`): For bug fixes, stabilization, kinematic tweaks, and telemetry calibrations.
  - **Minor increment** (`1.3.0` -> `1.4.0`): For new features, UX redesigns, profile additions, and layout restructuring.
- **Always update both** before running the pre-completion build gate (`./gradlew testDebugUnitTest assembleDebug`) so the generated APK and the on-device "About / Settings" footer (`Where Am I v<versionName> (build <versionCode>)`) reflect the exact iteration being tested.

---

## 5. Deployment Safeguards & Communication Rules

- **Do NOT push to GitHub** without explicit user instruction.
- **Do NOT install APKs to the user's phone** without explicit user instruction.
- **Preserve Existing Code**: Do not delete unrelated comments, docstrings, or test cases.
- **Personal Location Data**: device databases, logcats, diagnostics JSONL traces, screen recordings and extracted frames contain the user's home/work locations. Never commit or attach them to PRs; replay traces live only in the git-ignored `device_backup/diagnostics/`. Check `git diff --stat origin/master` before pushing.
- **Release Steps**: when the user authorises install / push / PR / merge, use `.agents/skills/jh-release/scripts/release.sh` (`check`, `install`, `pr`, `merge`) and run only the authorised steps.
- **Direct & Honest Communication**: Follow core communication rules—concise, technically rigorous, actively surfacing trade-offs and risks.

---

## 6. Field Testing Feedback & Diagnostic Protocol (`jh-field-test-feedback`)

Whenever the user reports field testing feedback, mentions test results, or uploads test screenshots:
1. **Device Connection Validation & Telemetry Extraction**:
   - Check if an Android device is connected via ADB (`adb devices | grep -w "device"`).
   - **If disconnected**: STOP and prompt the user to connect their device via USB/Wi-Fi, offering the option to reply `continue without telemetry` if they wish to skip telemetry analysis.
   - **If user opts to bypass telemetry**: Omit database/log extraction and proceed directly with code/UI analysis.
   - **If connected**: Automatically run `.agents/skills/jh-field-test-feedback/scripts/pull_device_data.sh` (or ADB commands) to extract `where_am_i_trips.db`, `where_am_i_spatial_cache.db`, `shared_prefs/`, and device logcat before proposing fixes or altering code.
2. **Deep Diagnostics & Anomaly Hunting**: Inspect trip points, pause retention, kinematic velocity jumps, accuracy distribution, geocoding lags, and boundary transitions in SQLite and logs. For cursor/heading/polyline/recording complaints, analyse `files/diagnostics/*.jsonl` and replay it (`jh-tracking-change`). Read trip-mode and auto-stop prefs before declaring a missing pause a bug.
3. **Confront Every Item & Screenshot**: Cross-reference each screenshot and comment against exact telemetry timestamps and coordinates. Screen recordings follow `jh-screen-recording-analysis` (frames + montages, video time aligned to telemetry via the epoch in the file name).
4. **Mandatory Verbatim Feedback Review**: In the final response, the agent MUST:
   - Quote **every single item** from the user's prompt verbatim (`> ...`).
   - State its status: **Implemented & Fixed**, **Design Choice / Left Untouched (with Evidence)**, or **Moved to Backlog**.
   - Explain the root cause identified, technical action taken, and link to modified files/symbols.
   - List all additional telemetry findings or unprompted bugs caught and resolved.
   - Update `docs/ENHANCEMENT_TRACKER.md`.
5. **Telemetry Lifecycle, Data Backup & Workspace Cleanup**:
   - Diagnostic text logs (`logcat_*.txt`, `dumpsys_*.txt`), process traces, and temporary round extraction folders (`device_data_round_*`, `phone_diagnostics*`, `device_dump_*`, `diagnostic_pull_*`, `.device_telemetry`) are strictly ephemeral.
   - Authoritative database snapshots or reference traces to preserve must be copied to the dedicated `device_backup/` directory (`device_backup/databases/`, `device_backup/shared_prefs/`); diagnostics traces go to the git-ignored `device_backup/diagnostics/`. Extract rounds into a temp dir outside the repo (`/tmp/wai_round_*`).
   - Once fixes are implemented, verified by unit tests, merged, or verified on-device, delete the temporary extraction folders (`rm -rf device_data_round_* phone_diagnostics* device_dump_* diagnostic_pull_* .device_telemetry`) to prevent multi-gigabyte workspace bloat. Never commit raw temporary test dumps into git.

---

## 7. RTK Token Optimization & Command Output Discipline

To conserve LLM context tokens and prevent context bloat:
- **RTK Shell Proxy**: All verbose terminal operations (`git status`, `git diff`, `git log`, `./gradlew`, `adb logcat`, test runners) are routed through `rtk` (Rust Token Killer).
- **Transparent Hooking**: The global `PreToolUse` hook in Antigravity and Cursor automatically rewrites shell commands to pass through `rtk hook`.
- **Compact Output Awareness**: Treat compact output returned by `rtk` as complete and definitive. Re-run with `rtk proxy <cmd>` only if output was unexpectedly empty or garbled.
- **Output-Gated Inspection**: When inspecting Android logcat or SQLite databases, always use targeted filtering (`rtk logcat | grep ...`, `tail -n 100`, SQL `LIMIT`) rather than dumping full multi-megabyte streams.
