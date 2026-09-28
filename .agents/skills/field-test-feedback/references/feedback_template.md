# Field Test Feedback Template & Prompt Shortcuts

## Quick Prompt Shortcut

Whenever you have tested a new build and have comments or screenshots, you can invoke the workflow using any of these simple prompts:

```text
/field-test-feedback
[Paste your comments, numbered feedback, and/or drop screenshots here]
```

or simply:

```text
Here is my feedback from the recent test:
1. [Feedback item 1]
2. [Feedback item 2 - Screenshot 1: ...]
3. [Feedback item 3 - Screenshot 2: ...]
```

---

## What Antigravity Automatically Does (No Need to Repeat):

1. **Automatic Extraction**:
   - Runs `pull_device_data.sh` to extract `where_am_i_trips.db`, `where_am_i_spatial_cache.db`, all SharedPreferences XMLs, and device logcat into a fresh round directory.

2. **Deep Diagnostics**:
   - Analyzes SQLite trip points, velocity graphs, GPS accuracy, pause segments, OSRM road snapping, Nominatim cache hits, and street hysteresis transitions.
   - Searches logcat for HTTP 429 rate-limiting, out-of-order drops, or background lifecycle anomalies.

3. **Screenshot & Comment Correlation**:
   - Views your uploaded screenshots and cross-references visual discrepancies with exact telemetry timestamps and coordinates.

4. **Implementation & Automated Build Gate**:
   - Creates a dedicated branch (`feat/...` or `fix/...`).
   - Writes automated unit tests in `app/src/test/java/` covering new rules.
   - Enforces `./gradlew testDebugUnitTest assembleDebug` before reporting completion.

5. **Mandatory Verbatim Summary**:
   - Quotes each of your feedback items **verbatim**.
   - Explains the root cause and the exact action taken (`Implemented & Fixed`, `Design Choice / Left Untouched (with Evidence)`, or `Moved to Backlog`).
   - Details all additional unprompted findings, edge case fixes, or performance enhancements.
   - Updates `docs/ENHANCEMENT_TRACKER.md`.
