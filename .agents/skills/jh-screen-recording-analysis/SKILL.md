---
name: jh-screen-recording-analysis
description: >-
  Analyse WhereAmI screen recordings (.mp4 from the Android screen recorder) frame by frame and align
  them with device telemetry. Use when the user shares a screen recording or video of the app, asks to
  check what happened at a moment in a video, or a field test includes recordings. Works on macOS
  without ffmpeg, Python imaging or OpenCV (AVFoundation/AppKit Swift tools).
---

# Screen Recording Analysis (`jh-screen-recording-analysis`)

Turn a recording into evidence: frames you can look at, labelled by video time, and the exact
telemetry rows for the same moments. Never describe a video you have not looked at.

## 1. Tools (one-time compile, outside the repo)

```bash
BIN=$(sh .agents/skills/jh-screen-recording-analysis/scripts/build_tools.sh)   # ~/.cache/wai-video-tools
```

- `$BIN/frames <video> <outDir> <stepSec> [startSec] [endSec] [maxWidth]` -> `f_<seconds>.jpg`
- `$BIN/montage <out.jpg> <columns> <frames...>` -> labelled contact sheet
- Work in a temp dir outside the repo, e.g. `WORK=$(mktemp -d /tmp/wai_video_XXXX)`.

## 2. Overview first, then zoom

1. Overview: `frames video "$WORK/ov" 4 0 -1 360`, then montage 8 columns, ~24 tiles per sheet. Read the
   sheets (image Read tool) and note the time ranges where the reported symptom is visible.
2. Zoom: re-extract only those ranges at 0.25-0.5 s with `maxWidth` 540-720 and montage 4-6 columns.
   Fast artefacts (polyline morphing, cursor hook, badge flicker) need 0.1-0.2 s steps.
3. Read the in-app trip timer in the frames: trip time = video time + offset, a second clock check.

## 3. Align with telemetry

- Video start epoch is in the file name: `sh scripts/video_epoch.sh <video>` (13-digit ms suffix).
- `t_video = (tu_ms - epoch_ms) / 1000` for diagnostics (`tu`), `track_points.t_utc` and trip times.
- Per-moment engine state: `python3 scripts/diag_window.py <track.jsonl> <epoch> <from_s> <to_s>`
  (decision, raw accuracy/bearing accuracy, engine heading/sigma/confidence, yaw rate, and the
  course of the engine positions around each fix).
- Diagnostics come from the field-test pull (`jh-field-test-feedback`, `files/diagnostics/*.jsonl`).

## 4. Interpreting what you see

- Map mode matters: in course-up the arrow points up by design; check whether street labels rotate
  between frames before calling the arrow wrong. In north-up / panned mode the arrow must follow motion.
- Compare the visible direction of the last polyline segment with the arrow and with `disp` in
  `diag_window.py`; a disagreement localises the bug (engine heading vs. render vs. polyline).
- Badge or overlay jumping by a few metres between frames usually means two code paths position it.

## 5. Report and clean up

- Embed the key montages in the answer (`![label](/abs/path.jpg)`) and cite video times.
- Delete `$WORK` once the round is finished (fixes verified). Never copy frames or videos into the repo: they show the user's location.
