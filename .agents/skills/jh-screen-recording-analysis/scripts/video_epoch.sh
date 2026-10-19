#!/bin/bash
# Print the recording start epoch (ms) embedded in an Android screen-recorder file name,
# e.g. screen-20261019-112404-1792401447947.mp4 -> 1792401447947.
# Map telemetry to video time with: t_video_s = (tu_ms - epoch_ms) / 1000.
set -euo pipefail
name="$(basename "$1")"
epoch="$(echo "$name" | sed -nE 's/.*-([0-9]{13})\.[A-Za-z0-9]+$/\1/p')"
if [ -z "$epoch" ]; then
    echo "no 13-digit epoch in file name: $name" >&2
    exit 1
fi
echo "$epoch"
