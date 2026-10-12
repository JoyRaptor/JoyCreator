#!/usr/bin/env bash
# Draws every sheet in joybrush/core/build/linelab to lab/lines/out/<name>.png, plus the tilt-ladder + zigzag crop at 3x
# (out/<name>_tilt_crop.png). Needs NODE_PATH to hold playwright-core (see README.md).
set -eu
here="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$here/out"
for f in "$here"/../../core/build/linelab/*.json; do
  n="$(basename "$f" .json)"
  node "$here/render_sheet.js" "$f" "$here/out/$n.png" --crop 1190,95,780,1050,3 --crop-out "$here/out/${n}_tilt_crop.png"
done
