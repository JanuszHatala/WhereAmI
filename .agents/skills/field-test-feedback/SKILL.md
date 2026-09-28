---
name: field-test-feedback
description: >-
  Execute the complete end-to-end WhereAmI field test feedback and diagnostic cycle.
  Use this skill whenever the user provides field testing feedback, mentions testing results,
  uploads test screenshots, or asks to analyze device telemetry, databases, and logs.
---

# Field Test Feedback & Diagnostic Protocol

This skill standardizes the end-to-end operational procedure for receiving, diagnosing, implementing, and reporting on field testing rounds for the **WhereAmI** application.

Whenever the user provides test feedback, run this exact 5-stage workflow without skipping any phase.

---

## Stage 1: Device Telemetry, DB & Log Extraction

Before formulating hypotheses or altering code, automatically pull the device telemetry, app databases, preferences, and logs from the connected phone.

1. **Run the Automated Extraction Script**:
   ```bash
   ROUND_DIR="device_data_round_$(date +%Y%m%d_%H%M)"
   ./.agents/skills/field-test-feedback/scripts/pull_device_data.sh "$ROUND_DIR"
   ```
   *If the phone is disconnected, politely prompt the user to connect via USB or `adb connect`.*

2. **Files Captured**:
   - `where_am_i_trips.db`: Trip records, breadcrumb points, pauses, velocities, and distance logs.
   - `where_am_i_spatial_cache.db`: SQLite LRU cache of resolved coordinates and street names.
   - `shared_prefs/*.xml`: User preferences, activity profiles, and last-known locations.
   - `logcat_full.txt` & `logcat_app.txt`: System logs and app-specific telemetry (`WHEREAMI`, `GEOCODE`, `STREET`, `TRIP`, `LIVE`, `OSM`).

---

## Stage 2: Deep Diagnostics & Telemetry Inspection

Inspect the downloaded data to identify anomalies, kinematic violations, or boundary misalignments:

1. **Trip & Kinematics Diagnostics**:
   ```bash
   sqlite3 "$ROUND_DIR/where_am_i_trips.db" "SELECT id, title, datetime(start_time/1000, 'unixepoch', 'localtime'), distance_meters, avg_speed_kmh, profile FROM trips ORDER BY id DESC LIMIT 5;"
   ```
   - Check point frequency, accuracy distribution, displacement jumps, and stationary pauses.
   - Validate if pause detection correctly captured turnaround stops ($\ge 45\text{s}$) or if auto-stop triggered as configured.

2. **Geocoding & Street Hysteresis Inspection**:
   - Check `where_am_i_spatial_cache.db` and app logcat for:
     - Boundary transitions (locality changes across gminas/powiats).
     - Street name switches, candidate debounce counts, and viaduct/corridor inertia.
     - HTTP 429 rate-limiting responses from OpenStreetMap Nominatim.
     - Out-of-order geocoding drops or timestamp reversals.

3. **Logcat Error & Lifecycle Auditing**:
   - Search for fatal exceptions, ANRs, wake-lock leak warnings, or unexpected service restarts.

---

## Stage 3: Feedback Confrontation & Plan Formulation

1. **Inspect User Attachments**:
   - View any uploaded screenshots (`.user_uploaded/media_*.png`) using `view_file`.
   - Cross-reference the timestamps, coordinates, and visual glitches (e.g. alignment, missing labels, color contrast) against the telemetry extracted in Stage 1 & 2.

2. **Confront Every Single Item**:
   - For every point raised by the user, isolate the root cause in the code/database.
   - If user asks a question (e.g. *"in what conditions do we display house numbers?"*), formulate a clear, technically sound proposal with trade-offs.

3. **Formulate Plan**:
   - Present a concise implementation plan or use `/grill-me` / `/plan` if substantial architectural decisions are required.

---

## Stage 4: Implementation, Unit Tests & Build Gates

1. **Dedicated Branch**:
   - Always branch from up-to-date `master`:
     ```bash
     git checkout master && git pull origin master
     git checkout -b feat/<descriptive-name>  # or fix/<descriptive-name>
     ```

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
   - Explicitly list any unprompted bugs, database anomalies, or performance leaks discovered during Stage 2 analysis and explain how they were resolved.
3. **Register Updates**:
   - Update `docs/ENHANCEMENT_TRACKER.md` with new entries, backlog items (`BKL-xx`), and incremented progress matrices.
4. **Safeguard Reminder**:
   - Do **NOT** push to GitHub or install on device until the user explicitly responds with authorization.
