# AGENTS.md - Antigravity & AI Pair Programming Operational Rules

This document establishes the mandatory architectural standards, development workflows, testing requirements, and operational rules for AI coding agents (Antigravity, Gemini, Claude, Cursor, etc.) working on the **WhereAmI** codebase.

---

## 1. Project Identity & Core Purpose

- **Project Name**: WhereAmI
- **Android Root Directory**: `WhereIAm`
- **Android Application ID / Package**: `com.example.whereiam`
- **Companion Web/Sync Backend**: Node.js / Express in `server/`

### The Core Value Proposition (Non-Negotiable)
The absolute core purpose of this application is **ultra-fast, unambiguous, and reliable real-time spatial awareness**:
1. **Primary Locality Display**: Instantly and legibly showing the **current place name** (City, Town, Village, or Settlement) with its complete, unambiguous **structural administrative hierarchy** (Municipality `gm.`, County `pow.`, Voivodeship `woj.`, Country).
2. **Canonical Road / Street Display**: Instantly showing the current road designation (canonical Polish designations `DK52`, `DW946`, `A4`, `S7`, `E77` without house numbers on major corridors, or formatted residential streets `ul. Mickiewicza 12`).
3. **Core Supremacy**: All supplementary features (trip recording, live sharing, map overlays, GPX export, statistics) exist to support this primary experience. **Never compromise, regress, or visually obstruct the primary locality and road awareness display.**

---

## 2. Crucial Architectural Priorities

### A. Battery Optimization (Highest Priority)
The app runs for hours in pockets, bike handlebars, and vehicle mounts. Excessive battery consumption is treated as a critical bug.
- **Adaptive GPS Sampling**:
  - Recording/Charging: High frequency (~6s sampling).
  - Battery/Live Tracking: Conservation frequency (10s–12s sampling).
  - Stationary: Suppress frequent location dispatching when stationary ($< 0.35\text{ m/s}$).
- **Stationary Jitter Gating**: When displacement $< 4\text{m}$ and speed $< 0.35\text{ m/s}$, suppress heavy calculations, map repaints, and network transmissions.
- **Aggressive Caching**:
  - Multi-tier in-memory and persistent disk caching for boundary polygons (`cacheDir/boundaries`).
  - Spatial LRU grid caching for reverse geocoding (~15m cell resolution, 30-minute expiry).
  - Avoid redundant HTTP calls at all costs.
- **Wake Locks**: Keep wake locks strictly scoped to the duration of background foreground services (`LiveTrackingService`). Never leak wake locks.

### B. Kinematic Sanity & GPS Filtering Pipeline
Raw mobile GPS fixes exhibit multipath noise, satellite loss jumps, and cell-tower fallbacks ($1.5\text{km}-5\text{km}$ accuracy).
- **Mandatory Filter Pass**: All raw location fixes MUST pass through `GpsFilterEngine` before dispatching to UI, trip recording, or live telemetry.
- **4-Stage Pipeline**:
  1. *Horizontal Accuracy Gate*: Reject fixes exceeding profile thresholds (Hiking $\le 40\text{m}$, Cycling $\le 55\text{m}$, Driving $\le 75\text{m}$).
  2. *Kinematic Speed Gate*: Reject displacement exceeding profile max velocity (Hiking $> 22\text{ km/h}$, Cycling $> 90\text{ km/h}$, Driving $> 230\text{ km/h}$).
  3. *Consecutive Anomaly Recovery*: Recover after 3 consecutive agreeing fixes (e.g. exiting long tunnels).
  4. *Stationary Jitter Dampener*: Dampen micro-oscillations when stopped.
- **Stationary Bearing Freeze**:
  - When speed $< 1.2\text{ m/s}$ ($4.3\text{ km/h}$), lock and preserve the `lastValidBearing`.
  - Do NOT allow the map orientation or cursor arrow to spin erratically at traffic lights or rest stops.

### C. Geocoding Stability & Boundary Integrity
- **Viaduct / Overpass Inertia**: At driving speeds ($> 35\text{ km/h}$) on major corridors (`DK*`, `DW*`, `A*`, `S*`), enforce inertia: require at least 5 consecutive candidate readings AND $\ge 7.0\text{s}$ of sustained fixes before switching road names.
- **15-Second Decay Grace Period**: If reverse geocoding temporarily returns no street during brief signal cutouts or tunnels, maintain the last confirmed street name for 15 seconds. Never flicker or blank out the display.
- **Intentional Visit Filter**: Qualify a town/place as visited during a trip ONLY if the user penetrates $\ge 150\text{m}$ into the locality OR remains within it for $\ge 45\text{s}$. Boundary skimming along border highways must never trigger accidental visits.
- **Rate-Limit Mutex**: Respect Nominatim & Overpass limits (minimum 1.5s mutex between boundary requests, exponential backoff on HTTP 429).

### D. Offline-First & Network Resilience
- Offline breadcrumb accumulation: live fixes are queued locally and uploaded in batches.
- Points are deduplicated by timestamp `t`.
- Previously downloaded administrative boundaries persist on disk and must load when offline.

---

## 3. Mandatory Testing & Quality Assurance Gates

### Unit Test Requirements
- **Every feature, algorithmic rule, parser, or data transformation MUST be covered by automated unit tests** in `app/src/test/java/`.
- Automated regression test suites must be maintained for:
  - Road & street normalization (`RoadNameNormalizerTest.kt`).
  - GPS filtering & anomaly rejection (`GpsFilterEngine`).
  - Trip continuity, auto-merge, and pause splitting logic.
  - Reverse geocoding hierarchy formatting and deduplication.

### Pre-Completion Build Gate
Before reporting any implementation or bugfix as complete, the agent MUST run:
```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```
Both commands must finish with `BUILD SUCCESSFUL` (0 test failures, 0 compilation errors).

### Continuous Integration (CI)
- GitHub Actions workflow (`.github/workflows/build-apk.yml`) MUST execute `testDebugUnitTest` on every push and pull request before generating build artifacts.

---

## 4. Git Branching & Version Control Workflow

To maintain production stability on `master`, the following Git workflow is **strictly enforced from now on**:

### 1. No Direct Pushes to `master`
- Never commit or push directly to `master`.

### 2. Feature & Fix Branch Lifecycle
1. **Branch Creation**: Always start work by creating a dedicated branch from up-to-date `master`:
   ```powershell
   git checkout master
   git pull origin master
   git checkout -b feat/<descriptive-feature-name>   # For new features
   # OR
   git checkout -b fix/<descriptive-issue-name>      # For bug fixes
   ```
2. **Local Verification**: Implement changes, write/update unit tests, and verify:
   ```powershell
   .\gradlew.bat testDebugUnitTest assembleDebug
   ```
3. **Structured Commits**: Commit changes with clear, descriptive conventional commit messages (`feat: ...`, `fix: ...`, `docs: ...`).
4. **Push Branch**: Push the feature branch to GitHub:
   ```powershell
   git push -u origin feat/<descriptive-feature-name>
   ```
5. **Merge to Master**: After validation, merge the feature branch into `master`:
   ```powershell
   git checkout master
   git merge --no-ff feat/<descriptive-feature-name>
   git push origin master
   ```

### 3. Persistent Tracking Register
- Update `docs/ENHANCEMENT_TRACKER.md` after completing each milestone/phase to keep a permanent audit trail across sessions.

---

## 5. Deployment Safeguards & Communication Rules

- **Do NOT push to GitHub** without explicit user instruction.
- **Do NOT install APKs to the user's phone** without explicit user instruction.
- **Preserve Existing Code**: Do not delete unrelated comments or docstrings.
- **Direct & Honest Communication**: Follow the core user communication rules—concise, technically rigorous, surfacing trade-offs and risks proactively.
