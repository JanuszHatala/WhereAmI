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
   ROUND_DIR="device_data_round_$(date +%Y%m%d_%H%M)"
   ./.agents/skills/jh-field-test-feedback/scripts/pull_device_data.sh "$ROUND_DIR"
   ```
   **Files Captured**:
   - `where_am_i_trips.db`: Trip records, breadcrumb points, pauses, velocities, and distance logs.
   - `where_am_i_spatial_cache.db`: SQLite LRU cache of resolved coordinates and street names.
   - `shared_prefs/*.xml`: User preferences, activity profiles, and last-known locations.
   - `logcat_full.txt` & `logcat_app.txt`: System logs and app-specific telemetry (`WHEREAMI`, `GEOCODE`, `STREET`, `TRIP`, `LIVE`, `OSM`).

---

## Stage 2: Deep Diagnostics & Telemetry Inspection

*(Omitted only if user explicitly requested to proceed without telemetry).*

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
   - Cross-reference timestamps, coordinates, and visual discrepancies against telemetry (or code layout if telemetry was bypassed).

2. **Confront Every Single Item**:
   - For every point raised by the user, isolate the root cause in the code or database.
   - If user asks a question or proposes a feature, formulate a technically rigorous response with trade-offs.

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
   - Explicitly list any unprompted bugs, database anomalies, or performance leaks discovered during analysis.
3. **Register Updates**:
   - Update `docs/ENHANCEMENT_TRACKER.md` with new entries, backlog items (`BKL-xx`), and incremented progress matrices.
4. **Safeguard Reminder**:
   - Do **NOT** push to GitHub or install on device until the user explicitly responds with authorization.
