# AGENTS.md - Antigravity & AI Pair Programming Operational Rules

This document establishes the mandatory architectural standards, development workflows, testing requirements, and operational rules for AI coding agents (Antigravity, Gemini, Claude, Cursor, etc.) working on the **WhereAmI** codebase.

---

## 1. Project Identity & Core Purpose

- **Project Name**: WhereAmI
- **Android Root Directory**: `WhereIAm`
- **Android Application ID / Package**: `janush.tech.whereami`
- **Companion Web/Sync Backend**: Node.js / Express in `server/`
- **Technical Architecture Document**: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)

### The Core Value Proposition (Non-Negotiable)
The absolute core purpose of this application is **ultra-fast, unambiguous, and reliable real-time spatial awareness**:
1. **Primary Locality Display**: Instantly and legibly showing the **current place name** (City, Town, Village, or Settlement) with its complete, unambiguous **structural administrative hierarchy** (Municipality `gm.`, County `pow.`, Voivodeship `woj.`, Country).
2. **Canonical Road / Street Display**: Instantly showing the current road designation (canonical Polish designations `DK52`, `DW946`, `A4`, `S7`, `E77` without house numbers on major corridors, or formatted residential streets `ul. Mickiewicza 12`).
3. **Core Supremacy**: All supplementary features (trip recording, live sharing, map overlays, GPX export, statistics) exist to support this primary experience. **Never compromise, regress, or visually obstruct the primary locality and road awareness display.**

---

## 2. Crucial Architectural Priorities

### A. Battery Optimization & Zero-Idle-Drain (Highest Priority)
The app runs for hours in pockets, bike mounts, and vehicle cradles. Excessive battery consumption is treated as a critical bug.
- **Complete Idle GPS Shutdown**:
  - In `AppLifecycleMode.IDLE` (screen off, no active trip, no live sharing), **GPS hardware MUST be completely powered down** via `LocationManager.stopLocationUpdates()`.
  - **Never poll GPS continuously in background idle**.
- **Hardware Motion Wake (`MotionWakeManager`)**:
  - In `TripMode.AUTO`, idle locomotion detection relies strictly on Android's hardware `Sensor.TYPE_SIGNIFICANT_MOTION` trigger sensor (~0 mW draw in sensor hub).
  - Physical motion triggers a temporary, profile-adapted confirmation GPS burst (`(profile.autoStartDurationMs + 20_000L).coerceAtLeast(35_000L)`). If locomotion criteria are met, the trip auto-starts; otherwise GPS powers down and the hardware sensor re-arms.
- **Screen-Off MANUAL Mode (Option A)**:
  - When in `TripMode.MANUAL` and screen turns off without an active trip or live sharing, all background location polling and services MUST stop completely.
- **Wake Lock Strict Scoping**:
  - `LiveTrackingService` wake locks MUST ONLY be acquired when an active trip is recording or live sharing is streaming.
  - Wake locks must **NEVER** be acquired in `onCreate()` or held in standby/idle. Releasing wake locks is mandatory upon trip completion or standby transition.
- **Adaptive GPS Sampling**:
  - Recording/Charging: High frequency (~5s–6s sampling).
  - Battery/Live Tracking: Conservation frequency (10s–12s sampling).
  - Stationary: Suppress frequent location dispatching when stationary ($< 0.35\text{ m/s}$).
- **Stationary Jitter Gating**: When displacement $< 4\text{m}$ and speed $< 0.35\text{ m/s}$, suppress heavy calculations, map repaints, and network transmissions.
- **Aggressive Caching**:
  - Multi-tier in-memory and persistent disk caching for boundary polygons (`cacheDir/boundaries`).
  - Spatial SQLite LRU grid caching for reverse geocoding (~11m cell resolution, 30-minute expiry).
  - Avoid redundant HTTP calls at all costs.

### B. Kinematic Sanity, Smooth Heading & Position Interpolation
Raw mobile GPS fixes exhibit multipath noise, satellite loss jumps, and cell-tower fallbacks ($1.5\text{km}-5\text{km}$ accuracy).
- **Mandatory Filter Pass**: All raw location fixes MUST pass through `GpsFilterEngine` before dispatching to UI, trip recording, or live telemetry.
- **4-Stage Pipeline**:
  1. *Horizontal Accuracy Gate*: Reject fixes exceeding profile thresholds (Hiking $\le 40\text{m}$, Cycling $\le 55\text{m}$, Driving $\le 55\text{m}$).
  2. *Kinematic Speed Gate*: Reject displacement exceeding profile max velocity (Hiking $> 22\text{ km/h}$, Cycling $> 90\text{ km/h}$, Driving $> 220\text{ km/h}$) when displacement $> 30\text{m}$.
  3. *Consecutive Anomaly Recovery*: Recover after 3 consecutive agreeing fixes (e.g. exiting long tunnels).
  4. *Stationary Jitter Dampener*: Dampen micro-oscillations when stopped.
- **Stationary Bearing Freeze**:
  - When speed $< 1.2\text{ m/s}$ ($4.3\text{ km/h}$), lock and preserve the `lastValidBearing`.
  - Do NOT allow the map orientation or cursor arrow to spin erratically at traffic lights or rest stops.
- **Kinematic / Geocoding Decoupling**:
  - Real-time map tracking (`LocationFix`) MUST be decoupled from asynchronous geocoding enrichment.
  - Geocoding completions must never re-emit past coordinates or past velocities back into the map kinematic pipeline.
- **60fps Dead-Reckoning Position Interpolator (`PositionInterpolator`)**:
  - The map tracking marker and camera recentering MUST use `PositionInterpolator` on display VSYNC (`withFrameNanos`).
  - Never call overlapping `animateTo()` calls on every GPS fix; use direct `setCenter(centerGp)` driven by interpolation with a 250ms ease-out blend.

### C. Geocoding Stability, Road Precedence & Boundary Integrity
- **Road Precedence**: `OSRM Snapped Road` $\to$ `OSM Nominatim Vector Road` $\to$ `Android Platform Geocoder`. Always prioritize OpenStreetMap vector road designations over platform geocoders in Poland.
- **Viaduct / Overpass Inertia**: At driving speeds ($> 35\text{ km/h}$) on major corridors (`DK*`, `DW*`, `A*`, `S*`), enforce inertia: require at least 5 consecutive candidate readings AND $\ge 7.0\text{s}$ of sustained fixes before switching road names.
- **15-Second Decay Grace Period**: If reverse geocoding temporarily returns no street during brief signal cutouts or tunnels, maintain the last confirmed street name for 15 seconds. Never flicker or blank out the display.
- **Intentional Visit Filter**: Qualify a town/place as visited during a trip ONLY if the user penetrates $\ge 150\text{m}$ into the locality OR remains within it for $\ge 45\text{s}$. Boundary skimming along border highways must never trigger accidental visits.
- **Rate-Limit Mutex**: Respect Nominatim & Overpass limits (minimum 1.5s mutex between boundary requests, exponential backoff on HTTP 429).

### D. Offline-First & Network Resilience
- Offline breadcrumb accumulation: live fixes are queued locally and uploaded in batches.
- Points are deduplicated by timestamp `t`.
- Previously downloaded administrative boundaries persist on disk and must load when offline.
- Spatial cache falls back to cached entries indefinitely when completely offline.

---

## 3. Mandatory Testing & Quality Assurance Gates

### Unit Test Requirements
- **Every feature, algorithmic rule, parser, kinematic filter, or data transformation MUST be covered by automated unit tests** in `app/src/test/java/`.
- Automated regression test suites must be maintained for:
  - Position interpolation & dead-reckoning (`PositionInterpolatorTest.kt`).
  - Battery optimization & activity auto-start (`BatteryOptimizationAndAutoStartTest.kt`).
  - Bearing smoothing & circular EMA (`BearingSmootherTest.kt`).
  - Road & street normalization (`RoadNameNormalizerTest.kt`).
  - GPS filtering & anomaly rejection (`GpsFilterEngine`).
  - Trip continuity, auto-merge, and pause splitting logic (`TripSplitTest.kt`).
  - Reverse geocoding hierarchy formatting and deduplication (`HierarchyResolutionTest.kt`).

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

### 1. No Direct Pushes to `master`
- Never commit or push directly to `master`.

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
5. **Merge to Master**: After validation and user instruction, merge the branch into `master`:
   ```bash
   git checkout master
   git merge --no-ff <branch-name>
   git push origin master
   ```

### 3. Persistent Tracking Registers
- Update `docs/ENHANCEMENT_TRACKER.md` after completing each sprint item or milestone.
- Update `docs/FEATURES_INVENTORY.md` as features evolve or retire.
- Update `docs/ARCHITECTURE.md` when architectural decisions (ADRs) are introduced.

---

## 5. Deployment Safeguards & Communication Rules

- **Do NOT push to GitHub** without explicit user instruction.
- **Do NOT install APKs to the user's phone** without explicit user instruction.
- **Preserve Existing Code**: Do not delete unrelated comments, docstrings, or test cases.
- **Direct & Honest Communication**: Follow core communication rules—concise, technically rigorous, actively surfacing trade-offs and risks.
