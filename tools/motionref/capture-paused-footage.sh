#!/usr/bin/env bash
# Run against the pinned paused-proof APK on a disposable Android emulator.
set -euo pipefail
out=$1
pkg=com.fadcam.beta
fixture="$pkg/com.fadcam.ui.faditor.export.ExportDebugActivity"
pts=533333
seek=300
test "$(adb -e shell getprop ro.kernel.qemu | tr -d '\r')" = 1
mkdir -p "$out"

launch() {
    local name=$1
    shift
    adb -e shell am force-stop "$pkg"
    adb -e logcat -c
    adb -e shell am start -W -n "$fixture" --ez motion_footage_fixture true "$@" |
        tee "$out/$name.start"
    grep -q '^Status: ok' "$out/$name.start"
}

wait_for() {
    local name=$1 pattern=$2 pid
    pid=$(adb -e shell pidof -s "$pkg" | tr -d '\r')
    [[ "$pid" =~ ^[0-9]+$ ]]
    for attempt in {1..120}; do
        adb -e logcat -d --pid="$pid" > "$out/$name.log"
        if grep -Eq 'FATAL EXCEPTION|debug export failed to start|Inspect Frame returned no bitmap' "$out/$name.log"; then
            tail -100 "$out/$name.log"
            return 1
        fi
        if grep -Eq "$pattern" "$out/$name.log"; then return; fi
        sleep 1
    done
    grep -Ei 'MotionPreviewBench|PausedFootageColor|MotionPreviewReadback|ExportDebug|FxLivePreview|HighFootageExport|MediaCodec' "$out/$name.log" | tail -100 || true
    echo "Timed out waiting for $pattern" >&2
    return 1
}

preview() {
    local name=$1 mode=$2 enabled=$3
    local png="motion_paused_${mode}_${pts}.png"
    launch "$name" --ez open_preview true --ez motion_enabled "$enabled" \
        --el motion_preview_seek_ms "$seek" --el motion_preview_source_pts_us "$pts"
    wait_for "$name" "MotionPreviewReadback.*frame=${png}"
    if [[ "$mode" == high ]]; then
        grep -E 'HIGH_FOOTAGE_PAUSED.*previousPtsUs=500000 centerPtsUs=533333 nextPtsUs=566666' "$out/$name.log"
    else
        ! grep -q 'HIGH_FOOTAGE_PAUSED' "$out/$name.log"
    fi
    adb -e exec-out run-as "$pkg" cat "cache/$png" > "$out/$name.png"
    test -s "$out/$name.png"
}

inspect() {
    local name=$1 enabled=$2 filename
    launch "$name" --ez motion_enabled "$enabled" --el motion_inspect_ms "$seek"
    wait_for "$name" 'C2 inspect frame=motion_inspect_'
    if [[ "$enabled" == true ]]; then
        grep -E 'HIGH_FOOTAGE_EXPORT.*ptsUs=533333' "$out/$name.log"
    else
        ! grep -q 'HIGH_FOOTAGE_EXPORT' "$out/$name.log"
    fi
    filename=$(sed -n 's/.*C2 inspect frame=\(motion_inspect_[^ ]*\.png\).*/\1/p' "$out/$name.log" | tail -1)
    adb -e exec-out run-as "$pkg" cat "cache/$filename" > "$out/$name.png"
    test -s "$out/$name.png"
}

preview preview-high-cold high true
preview preview-high-warm high true
preview preview-sharp sharp false
inspect inspect-high true
inspect inspect-sharp false

"${PYTHON:-python3}" - "$out" <<'PY' | tee "$out/summary.json"
import json
import math
from pathlib import Path
import sys

from PIL import Image, ImageChops

out = Path(sys.argv[1])

def load(name):
    with Image.open(out / (name + ".png")) as image:
        return image.convert("RGB")

def delta(a, b):
    if a.size != b.size:
        raise SystemExit(f"Pixel test needs matching capture sizes, got {a.size} and {b.size}")
    difference = ImageChops.difference(a, b)
    squared = sum((level % 256) ** 2 * n for level, n in enumerate(difference.histogram()))
    channels = a.width * a.height * 3
    return {"rms_rgb": round(math.sqrt(squared / channels), 4)}

cold = load("preview-high-cold")
warm = load("preview-high-warm")
sharp = load("preview-sharp")
inspect_high = load("inspect-high")
inspect_sharp = load("inspect-sharp")
if cold.size != inspect_high.size:
    # Inspect returns the decoded single-frame export, whose output resolution can differ.
    inspect_high = inspect_high.resize(cold.size, Image.Resampling.BILINEAR)
    inspect_sharp = inspect_sharp.resize(cold.size, Image.Resampling.BILINEAR)

summary = {
    "source_pts_us": 533333,
    "preview_size": list(cold.size),
    "cold_warm": delta(cold, warm),
    "preview_blur_effect": delta(cold, sharp),
    "inspect_blur_effect": delta(inspect_high, inspect_sharp),
    "preview_inspect_high": delta(cold, inspect_high),
    "preview_inspect_sharp": delta(sharp, inspect_sharp),
}
print(json.dumps(summary, indent=2))
if cold.tobytes() != warm.tobytes():
    raise SystemExit("Cold and warm paused High preview differ")
if cold.tobytes() == sharp.tobytes() or inspect_high.tobytes() == inspect_sharp.tobytes():
    raise SystemExit("A High result did not differ from its sharp control")
PY
