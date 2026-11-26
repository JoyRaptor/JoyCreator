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
    # Inspect Frame can finish before Android reports a displayed activity.
    timeout 20 adb -e shell am start -n "$fixture" --ez motion_footage_fixture true "$@" |
        tee "$out/$name.start"
    grep -q '^Starting: Intent' "$out/$name.start"
}

wait_for() {
    local name=$1 pattern=$2 process=${3:-$pkg} pid
    for attempt in {1..120}; do
        pid=$(adb -e shell pidof -s "$process" | tr -d '\r' || true)
        if [[ ! "$pid" =~ ^[0-9]+$ ]]; then sleep 1; continue; fi
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
    adb -e exec-out run-as "$pkg" cat "cache/${png%.png}.rgba" > "$out/$name.rgba"
    test -s "$out/$name.rgba"
}

export_raw() {
    local name=$1 enabled=$2 editor_us=300333
    local file="lane_frame_$editor_us.rgba"
    adb -e shell run-as "$pkg" rm -f "cache/$file"
    launch "$name" --ez motion_enabled "$enabled" --ela motion_lane_sample_us "$editor_us"
    wait_for "$name" 'ExportService: Export completed' "$pkg:export"
    grep -E "LaneReadback: frame=$file editorUs=$editor_us size=960x540" "$out/$name.log"
    if [[ "$enabled" == true ]]; then
        grep 'HIGH_FOOTAGE_EXPORT' "$out/$name.log"
    else
        ! grep -q 'HIGH_FOOTAGE_EXPORT' "$out/$name.log"
    fi
    adb -e exec-out run-as "$pkg" cat "cache/$file" > "$out/$name.rgba"
    test -s "$out/$name.rgba"
}

inspect() {
    local name=$1 enabled=$2 filename
    launch "$name" --ez motion_enabled "$enabled" --el motion_inspect_ms "$seek"
    wait_for "$name" 'C2 inspect frame=motion_inspect_'
    if [[ "$enabled" == true ]]; then
        grep -E 'HIGH_FOOTAGE_EXPORT' "$out/$name.log"
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
export_raw export-high true
export_raw export-sharp false

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

def raw(name):
    blob = (out / (name + ".rgba")).read_bytes()
    height, width = 540, 960
    if len(blob) != width * height * 4:
        raise SystemExit(f"{name} has an incomplete RGBA readback")
    return Image.frombytes("RGBA", (width, height), blob)


def raw_delta(a, b):
    histogram = ImageChops.difference(a, b).histogram()
    counts = [sum(histogram[level::256]) for level in range(256)]
    return {"max": max(level for level, count in enumerate(counts) if count),
            "mean": sum(level * count for level, count in enumerate(counts)) / sum(counts),
            "above_one": sum(counts[2:])}


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

exact_cold, exact_warm, exact_sharp = map(raw,
    ("preview-high-cold", "preview-high-warm", "preview-sharp"))
exact_export, exact_export_sharp = map(raw, ("export-high", "export-sharp"))
raw_parity = raw_delta(exact_cold, exact_export)
summary = {
    "raw_pts_editor_us": 300333,
    "raw_high_parity": raw_parity,
    "raw_sharp_parity": raw_delta(exact_sharp, exact_export_sharp),
    "source_pts_us": 533333,
    "preview_size": list(cold.size),
    "cold_warm": delta(cold, warm),
    "preview_blur_effect": delta(cold, sharp),
    "inspect_blur_effect": delta(inspect_high, inspect_sharp),
    "preview_inspect_high": delta(cold, inspect_high),
    "preview_inspect_sharp": delta(sharp, inspect_sharp),
}
print(json.dumps(summary, indent=2))
if exact_cold.tobytes() != exact_warm.tobytes():
    raise SystemExit("Cold and warm paused High preview differ")
if (exact_cold.tobytes() == exact_sharp.tobytes()
        or exact_export.tobytes() == exact_export_sharp.tobytes()
        or inspect_high.tobytes() == inspect_sharp.tobytes()):
    raise SystemExit("A High result did not differ from its sharp control")
if raw_parity["max"] > 1 or raw_parity["mean"] > 0.1:
    raise SystemExit("Raw paused High preview and export exceed the pixel parity limit")
PY
