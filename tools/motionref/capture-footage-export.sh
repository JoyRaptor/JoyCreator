#!/usr/bin/env bash
# Disposable Android emulator only. Capture pre-encode RGBA from the exact pinned APK.
set -euo pipefail
out=$1
pkg=com.fadcam.beta
fixture="$pkg/com.fadcam.ui.faditor.export.ExportDebugActivity"
test "$(adb -e shell getprop ro.kernel.qemu | tr -d '\r')" = 1
mkdir -p "$out"

capture() {
    local name=$1 enabled=$2 slow=$3 second=$4 expected=$5 requested=$6 pid
    local directory="$out/$name" log="$out/$name/device.log"
    mkdir -p "$directory"
    adb -e shell am force-stop "$pkg"
    adb -e logcat -c
    adb -e shell am start -W -n "$fixture" \
        --ez motion_footage_fixture true --ez motion_enabled "$enabled" \
        --ez motion_slow "$slow" --ez motion_second_source "$second" \
        --ela motion_lane_sample_us "$requested" | tee "$directory/start.log"
    grep -q '^Status: ok' "$directory/start.log"
    for ((attempt = 0; attempt < 150; attempt++)); do
        pid=$(adb -e shell pidof -s "$pkg:export" | tr -d '\r' || true)
        if [[ ! "$pid" =~ ^[0-9]+$ ]]; then sleep 1; continue; fi
        adb -e logcat -d --pid="$pid" -s HighFootageExport LaneReadback ExportService ExportDebug AndroidRuntime > "$log"
        if grep -qiE 'FATAL EXCEPTION|Export failed|Indexed export flow unavailable' "$log"; then
            cat "$log"
            return 1
        fi
        if grep -q 'ExportService: Export completed' "$log"; then break; fi
        sleep 1
    done
    grep -q 'ExportService: Export completed' "$log"
    test "$(grep -c 'HIGH_FOOTAGE_EXPORT' "$log" || true)" -eq "$expected"
    python3 - "$directory" "$requested" <<'PY'
import json
from pathlib import Path
import re
import subprocess
import sys

root = Path(sys.argv[1])
expected = set(map(int, sys.argv[2].split(",")))
samples = []
for request, pts, width, height in re.findall(
    r"frame=lane_frame_(\d+)\.rgba editorUs=(\d+) size=(\d+)x(\d+)",
    (root / "device.log").read_text(),
):
    filename = f"lane_frame_{request}.rgba"
    rgba = subprocess.check_output(
        ["adb", "-e", "exec-out", "run-as", "com.fadcam.beta", "cat", f"cache/{filename}"]
    )
    assert len(rgba) == int(width) * int(height) * 4
    (root / filename).write_bytes(rgba)
    samples.append(dict(file=filename, requestUs=int(request), editorUs=int(pts), width=int(width),
                        height=int(height)))
assert len(samples) == 3 and {sample["requestUs"] for sample in samples} == expected
(root / "manifest.json").write_text(json.dumps({"samples": samples}))
PY
}

capture normal-cold true false false 1 110000,290000,485000
capture normal-warm true false false 1 110000,290000,485000
capture normal-sharp false false false 0 110000,290000,485000
capture slow-two-sources true true true 2 210000,1450000,2100000

status=0
node tools/motionref/compare-lanes.mjs "$out/normal-cold/manifest.json" "$out/normal-warm/manifest.json" \
    > "$out/cold-warm.json" || status=$?
test "$status" -eq 0
status=0
node tools/motionref/compare-lanes.mjs "$out/normal-sharp/manifest.json" "$out/normal-cold/manifest.json" \
    > "$out/sharp-negative.json" || status=$?
test "$status" -eq 1
echo 'HIGH_FOOTAGE_EXPORT_DIAGNOSTIC_PASS'
