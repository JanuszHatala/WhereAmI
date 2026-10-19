---
name: jh-field-test-feedback
description: >-
  Execute the complete end-to-end WhereAmI field test feedback and diagnostic cycle.
  Use this skill whenever the user provides field testing feedback, mentions testing results,
  uploads test screenshots, or asks to analyze device telemetry, databases, and logs.
---

# Field Test Feedback & Diagnostic Protocol (`jh-field-test-feedback`)

This skill standardizes the end-to-end operational procedure for receiving, diagnosing, implementing, and reporting on field testing rounds for the **WhereAmI** application.

Whenever the user provides test feedback, run this exact workflow.

---

## Stage 1: Device Connection Validation & Telemetry Extraction

Before analyzing feedback or altering code, verify device connectivity:

1. **Check ADB Device Connectivity**:
   ```bash
   adb devices | grep -w "device"
   ```

2. **Validation Gate (Stop Condition)**:
   - **If NO device is connected**:
     - **STOP IMMEDIATELY**. Do not proceed to hypothesize or alter code.
     - Inform the user:
       > ⚠️ **No Android device detected via ADB.**  
       > Please connect your phone via USB (with USB Debugging enabled) or Wi-Fi (`adb connect <IP>:5555`) so I can extract the latest SQLite databases (`where_am_i_trips.db`, `where_am_i_spatial_cache.db`), SharedPreferences, and logcats.  
       >  
       > *If you prefer to proceed without device telemetry analysis, reply: `continue without telemetry` and I will proceed with your UI/code feedback directly.*
     - Await the user's response.
   - **If the user instructs to continue without telemetry**:
     - Omit Stage 1 and Stage 2.
     - Note explicitly in the report that device telemetry extraction was bypassed per user request.
     - Proceed directly to Stage 3 (Feedback & UI/Code Analysis).

3. **If Device is Connected (Execute Extraction)**:
   ```bash
   ROUND_DIR="/tmp/wai_round_$(date +%Y%m%d_%H%M)"   # outside the repo
   sh ./.agents/skills/jh-field-test-feedback/scripts/pull_device_data.sh "$ROUND_DIR"
   cp "$ROUND_DIR"/diagnostics/*.jsonl device_backup/diagnostics/ 2>/dev/null || true   # git-ignored replay traces
   ```
   **Files Captured**:
   - `where_am_i_trips.db`: Trip records, breadcrumb points, pauses, velocities, and distance logs.
   - `where_am_i_spatial_cache.db`: SQLite LRU cache of resolved coordinates and street names.
   - `shared_prefs/*.xml`: User preferences, activity profiles, and last-known locations.
   - `logcat_full.txt` & `logcat_app.txt`: System logs and app-specific telemetry (`WHEREAMI`, `GEOCODE`, `STREET`, `TRIP`, `LIVE`, `OSM`).
   - `diagnostics/track_*.jsonl`: every raw fix, gate decision and engine state of recorded trips (replayable by `FieldReplayTest`).
   - All of this is personal location data: never commit it; only `device_backup/diagnostics/` (git-ignored) keeps traces.

---

## Stage 2: Deep Diagnostics & Telemetry Inspection

*(Omitted only if user explicitly requested to proceed without telemetry).*

Inspect the downloaded data to identify anomalies, kinematic violations, or boundary misalignments:

1. **Trip & Kinematics Diagnostics**:
   ```bash
   sqlite3 "$ROUND_DIR/where_am_i_trips.db" "SELECT id, title, datetime(start_time/1000, 'unixepoch', 'localtime'), distance_meters, avg_speed_kmh, profile FROM trips ORDER BY id DESC LIMIT 5;"
   ```
   - Check point frequency, accuracy distribution, displacement jumps, and stationary pauses (`track_points` table: `t_utc`, `speed`, `h_acc`, `bearing`, `flags`).
   - Before calling a missing pause a bug, read `pref_trip_mode` and `pref_auto_stop_min*` in `where_am_i_trip_prefs.xml`: a trip ended by auto-stop drops its trailing pause by design.
   - Validate if pause detection correctly captured turnaround stops ($\ge 45\text{s}$) or if auto-stop triggered as configured.

2. **Tracking Engine Diagnostics (cursor, heading, polyline, recording complaints)**:
   - From the JSONL: decision counts (`d`), fix interval and accuracy percentiles, heading confidence while moving (`s.hc`), filter resets (`ACCEPTED_AFTER_RESET`) and what preceded them.
   - Replay baseline: `sh .agents/skills/jh-tracking-change/scripts/replay.sh <trace> <PROFILE>`; keep the `REPLAY` line for the before/after comparison. Follow `jh-tracking-change` for any fix.

3. **Geocoding & Street Hysteresis Inspection**:
   - Check `where_am_i_spatial_cache.db` and app logcat for:
     - Boundary transitions (locality changes across gminas/powiats).
     - Street name switches, candidate debounce counts, and viaduct/corridor inertia.
     - HTTP 429 rate-limiting responses from OpenStreetMap Nominatim.
     - Out-of-order geocoding drops or timestamp reversals.

4. **Logcat Error & Lifecycle Auditing**:
   - Search for fatal exceptions, ANRs, wake-lock leak warnings, or unexpected service restarts.

---

## Stage 3: Feedback Confrontation & Plan Formulation

1. **Inspect User Attachments**:
   - Open every screenshot the user attached or referenced with the image-capable file read tool.
   - Screen recordings (`.mp4`): follow `jh-screen-recording-analysis` (frame extraction, montages, video time <-> telemetry via the epoch in the file name).
   - Cross-reference timestamps, coordinates, and visual discrepancies against telemetry (or code layout if telemetry was bypassed).

2. **Confront Every Single Item**:
   - For every point raised by the user, isolate the root cause in the code or database.
   - If user asks a question or proposes a feature, formulate a technically rigorous response with trade-offs.

3. **Formulate Plan**:
   - Present a concise implementation plan, or switch to planning mode if substantial architectural decisions are required.

---

## Stage 4: Implementation, Unit Tests & Build Gates

1. **Dedicated Branch**:
   - Always branch from up-to-date `master`:
     ```bash
     git checkout master && git pull origin master
     git checkout -b feat/<descriptive-name>  # or fix/<descriptive-name>
     ```
   - Bump `versionCode` (+1) and `versionName` (patch for fixes, minor for features) in `app/build.gradle.kts`.

2. **Implement & Test**:
   - Modify the necessary Kotlin/Compose files adhering to `AGENTS.md` and `docs/DESIGN_SYSTEM.md`.
   - Write automated JUnit tests in `app/src/test/java/` covering new rules, edge cases, and kinematic filters.

3. **Pre-Completion Build Gate**:
   - Both commands must succeed with 0 failures:
     ```bash
     ./gradlew testDebugUnitTest assembleDebug
     ```

---

## Stage 5: Verbatim Review & Comprehensive Summary (MANDATORY)

Upon completing implementation, provide a structured summary adhering strictly to this format:

1. **Verbatim Feedback Review**:
   - For **every single item** in the user's input:
     - Quote the user's feedback **verbatim** in a blockquote (`> ...`).
     - State the status: **Implemented & Fixed**, **Design Choice / Left Untouched (with Evidence)**, or **Moved to Backlog**.
     - Detail the root cause found, the technical action taken, and github markdown links to modified files and symbols.
2. **Additional Telemetry Findings**:
   - Explicitly list any unprompted bugs, database anomalies, or performance leaks discovered during analysis.
3. **Register Updates**:
   - Update `docs/ENHANCEMENT_TRACKER.md` with new entries, backlog items (`BKL-xx`), and incremented progress matrices; for tracking changes include the before/after `REPLAY` metrics.
4. **Safeguard Reminder**:
   - Do **NOT** push to GitHub or install on device until the user explicitly responds with authorization. When authorized, use `jh-release`.
