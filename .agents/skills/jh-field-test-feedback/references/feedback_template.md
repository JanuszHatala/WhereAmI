# Field Test Feedback Template & Prompt Shortcuts (`jh-field-test-feedback`)

## Quick Prompt Shortcut

Whenever you have tested a new build and have comments or screenshots, you can invoke the workflow using any of these simple prompts:

```text
/jh-field-test-feedback
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

## Device Connection Validation:

1. **When Phone is Connected**:
   - Automatically runs `pull_device_data.sh` to extract `where_am_i_trips.db`, `where_am_i_spatial_cache.db`, all SharedPreferences XMLs, and device logcat into a fresh round directory.
   - Performs deep telemetry diagnostics on velocities, pause retention, and geocoding transitions.

2. **When Phone is NOT Connected**:
   - The agent **stops immediately** and alerts you that no device was detected via ADB.
   - You can connect your phone, or reply `continue without telemetry` if you want the agent to skip telemetry extraction and proceed directly with your UI/code feedback.

---

## What Antigravity Automatically Does Upon Completion:

- Enforces unit tests and `./gradlew testDebugUnitTest assembleDebug`.
- Quotes each of your feedback items **verbatim**.
- Explains the root cause and the exact action taken (`Implemented & Fixed`, `Design Choice / Left Untouched (with Evidence)`, or `Moved to Backlog`).
- Details all additional unprompted findings, edge case fixes, or performance enhancements.
- Updates `docs/ENHANCEMENT_TRACKER.md`.
