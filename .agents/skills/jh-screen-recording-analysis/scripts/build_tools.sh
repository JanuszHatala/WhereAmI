#!/bin/bash
# Compile the frame tools once into a cache dir outside the repo. Prints the bin dir.
set -euo pipefail
SRC="$(cd "$(dirname "$0")" && pwd)"
BIN="${WAI_VIDEO_TOOLS:-$HOME/.cache/wai-video-tools}"
mkdir -p "$BIN"
for tool in frames montage; do
    if [ ! -x "$BIN/$tool" ] || [ "$SRC/$tool.swift" -nt "$BIN/$tool" ]; then
        swiftc -O -suppress-warnings "$SRC/$tool.swift" -o "$BIN/$tool" >&2
    fi
done
echo "$BIN"
