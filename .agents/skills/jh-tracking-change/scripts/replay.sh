#!/bin/bash
# Replay pulled device diagnostics through the current engine and print the field metrics line.
# Usage: replay.sh <track_*.jsonl> [PROFILE=WALKING|HIKING|RUNNING|MTB|CYCLING|CAR]
# Run once on the base commit and once on your change; report both lines.
# Needs JDK 17 (set JAVA_HOME) and ANDROID_HOME, like the normal build gate.
set -euo pipefail
JSONL="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
PROFILE="${2:-WALKING}"
ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"
WAI_REPLAY_JSONL="$JSONL" WAI_REPLAY_PROFILE="$PROFILE" \
    sh ./gradlew testDebugUnitTest --tests '*FieldReplayTest*' --rerun -i 2>&1 | grep -E "REPLAY|FAILED|BUILD"
