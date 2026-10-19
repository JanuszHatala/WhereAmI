---
name: jh-tracking-change
description: >-
  Guardrails and evidence workflow for any change to WhereAmI position, heading, recording or map
  rendering: the tracking package (TrackingEngine, KinematicEkf, FixedLagSmoother, RenderPipeline,
  RotationSlew, PencilTrail, AdaptiveTrackSampler, MotionCourse, GyroHeadingSensor), the cursor and
  polyline code in OsmMapView, TripManager.onSmoothedPoints / pause detection, LocationManager request
  cadence, and the altitude badge. Use before editing any of these, when tuning filters or density, or
  when a field test reports cursor, heading, polyline or recording problems.
---

# Tracking Change Protocol (`jh-tracking-change`)

The tracking stack (ADR-07 in `docs/ARCHITECTURE.md`) was rebuilt over several field-test rounds.
Most regressions come from re-introducing a pattern that was removed for a measured reason. Check
every invariant below against the diff, and prove the change with replay numbers, not impressions.

## 1. Invariants (a diff that breaks one needs an explicit ADR amendment)

**Estimation (`TrackingEngine`, `KinematicEkf`, `FixedLagSmoother`)**
1. One estimator. No parallel filters, smoothers or "stabilizers" (`GpsFilterEngine`,
   `PositionStabilizer`, `PositionInterpolator` were retired). One clock: `elapsedRealtime`.
2. Every location of a `LocationResult` batch is ingested, in order.
3. The selected profile is a prior, not a cage. Physical limits go through `activeProfile`, which
   escalates when measured Doppler speed exceeds the envelope. A wrong profile must never produce
   reject/reset cycles.
4. A still device is not lost tracking: sparse fixes while stationary continue the anchored state
   (`continuesStationary`). Re-seeding jumps the anchor and breaks rest-stop clustering.
5. Never zero the hardware Doppler speed. Course weighting uses the provider's bearing accuracy; any
   low-speed inflation is relative to the profile's `minHeadingSpeedMps`.

**Display (`RenderPipeline`, `OsmMapView` frame loop)**
6. Marker, camera, rotation, accuracy circle, altitude badge and polyline tip are positioned only in
   the VSYNC frame loop from `RenderPipeline.frame()`. Other effects may change content, never position
   (two writers = flicker).
7. Live polyline = committed points + `PencilTrail` + cursor tip. Never draw estimates that will be
   revised (`provisional()` / RTS tail): they morph on every fix. The line must end under the cursor.
8. Heading arrow: fresh filter heading (`Pose.headingFresh`), else the visible motion course
   (`MotionCourse`) while moving, else a dot. Never show a stale held heading while moving; never
   dead-reckon along an unobserved heading (chord prediction instead).
9. Course-up rotation only through `RotationSlew`; no per-fix snapping, no overlapping `animateTo`.

**Recording (`TripManager`, `AdaptiveTrackSampler`)**
10. Geometry and distance come only from smoothed points. Vertex density follows measured speed
    (opening-window simplification), not the profile label. Stationary points are never committed.
11. Pause rules (12 m cluster, 150 s dwell, 45 s resume debounce) and auto-stop semantics (trailing
    pauses dropped on auto-stop) are product rules: change them only on explicit request.

**Battery and threads**
12. Gyro only under `GyroPolicy.shouldRun`; screen-off cadence and batching per `AppStateManager`;
    `LocationRequest` re-issues deduplicated. No SQLite or network on the main thread per fix.

## 2. Evidence workflow

1. **Get a trace.** Use the latest `device_backup/diagnostics/*.jsonl` (git-ignored, personal data) or
   pull one with `jh-field-test-feedback`. Pick the trace that shows the reported problem.
2. **Baseline.** On the base commit: `sh .agents/skills/jh-tracking-change/scripts/replay.sh <trace> <PROFILE>`.
3. **Change + tests.** Encode each rule as a unit test first (`WalkingRefinementsTest`,
   `TrackingEngineTest`, `RenderPipelineTest` patterns; `TrackSim` for synthetic paths: straight,
   turn, stand, profile mismatch).
4. **Replay again** and put both `REPLAY` lines in the report and the tracker entry:
   heading confident while moving, heading error p50/p90/p97, filter resets, stored vertices and spacing.
   A change that worsens any metric needs a stated reason.
5. **Build gate:** `./gradlew testDebugUnitTest assembleDebug`.
6. **Docs:** ADR-07 amendment for behavioural changes, `ENHANCEMENT_TRACKER` round, `FEATURES_INVENTORY` LOC-11.

## 3. Known traps

- Measuring heading error against the GNSS bearing hides lag; compare against the course of the
  smoothed track a few seconds around the fix (what `FieldReplayTest` does).
- Fused provider throttles to 5-20 s fixes when still; do not treat those gaps as signal loss.
- Bearing accuracy 90-110 deg at walk start is normal: show a dot, do not fake a direction.
- `osmdroid` marker rotation with `isFlat=false`: screen angle = `bearing + map.mapOrientation`.
