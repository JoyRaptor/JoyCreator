#!/usr/bin/env bash
# Disposable emulator only. Compare the same recorded pose times before export encoding.
set -euo pipefail
out=$1
python=$2
pkg=com.fadcam.beta
fixture="$pkg/com.fadcam.ui.faditor.export.ExportDebugActivity"
test "$(adb -e shell getprop ro.kernel.qemu | tr -d '\r')" = 1
mkdir -p "$out"/{preview,blur-raw,sharp-raw}

app_start() {
    adb -e shell am force-stop "$pkg"
    adb -e logcat -c
    adb -e shell am start -W -n "$fixture" "$@" | tee "$out/start.log"
    grep -q '^Status: ok' "$out/start.log"
}

play_button() {
    adb -e shell uiautomator dump /sdcard/stu7-window.xml >/dev/null &&
        adb -e exec-out cat /sdcard/stu7-window.xml | "$python" -c '
import re
import sys
import xml.etree.ElementTree as xml
for node in xml.parse(sys.stdin).iter("node"):
    if node.attrib.get("resource-id") == "com.fadcam.beta:id/btn_play_pause":
        left, top, right, bottom = map(int, re.findall(r"\d+", node.attrib["bounds"]))
        if left < right and top < bottom:
            print((left + right) // 2, (top + bottom) // 2)
            sys.exit(0)
sys.exit(1)'
}

play() {
    local coords
    for ((attempt = 0; attempt < 20; attempt++)); do
        if coords=$(play_button); then
            read -r x y <<< "$coords"
            adb -e shell input tap "$x" "$y"
            return
        fi
        sleep 1
    done
    return 1
}

wait_for_log() {
    local result=$1 pattern=$2 process=$3 pid
    shift 3
    for ((attempt = 0; attempt < 75; attempt++)); do
        pid=$(adb -e shell pidof -s "$process" | tr -d '\r' || true)
        if [[ ! "$pid" =~ ^[0-9]+$ ]]; then sleep 1; continue; fi
        adb -e logcat -d --pid="$pid" -s "$@" AndroidRuntime > "$result"
        if grep -qiE 'FATAL EXCEPTION|Export failed|debug export failed|shutter (unavailable|failed)' "$result"; then
            cat "$result"
            return 1
        fi
        if grep -qE "$pattern" "$result"; then return; fi
        sleep 1
    done
    if [[ -f "$result" ]]; then cat "$result"; fi
    return 1
}

times=()
for target in 750000 1450000 2750000; do
    matched=false
    for attempt in 1 2 3; do
        app_start --ez motion_avatar_fixture true --ez motion_enabled true \
            --ez motion_avatar_parity_loop true \
            --ez open_preview true --el motion_preview_readback_us "$target"
        play
        log="$out/preview/$target-$attempt.log"
        wait_for_log "$log" 'frame=motion_preview_[0-9]+\.png' "$pkg" \
            ExportDebug MotionPreviewReadback FxPreviewGl AvatarParity
        read -r file actual dimensions <<< "$(sed -nE \
            's/.*frame=(motion_preview_[0-9]+\.png) editorUs=([0-9]+) size=([0-9]+x[0-9]+).*/\1 \2 \3/p' "$log" | tail -1)"
        if [[ "$actual" =~ ^[0-9]+$ ]] && (( actual >= target && actual < target + 1250000 )) \
                && grep -q "AvatarParity: preview editorUs=$actual " "$log" \
                && { (( ${#times[@]} == 0 )) || (( actual > times[-1] )); }; then
            matched=true
            break
        fi
    done
    if [[ "$matched" != true ]]; then
        echo "Preview did not capture an active avatar stamp for requested time $target" >&2
        exit 1
    fi
    times+=("$actual")
    adb -e exec-out run-as "$pkg" cat "cache/$file" > "$out/preview/$file"
    test -s "$out/preview/$file"
    printf '%s %s %s\n' "$file" "$actual" "$dimensions" >> "$out/preview/samples"
done
project=$(sed -nE 's/.*C5 avatar fixture project_id=([^ ]+).*/\1/p' "$log" | tail -1)
test -n "$project"
sample_arg=$(IFS=,; echo "${times[*]}")

export_raw() {
    local folder=$1
    shift
    for actual in "${times[@]}"; do
        adb -e shell run-as "$pkg" rm -f "cache/lane_frame_$actual.rgba"
    done
    app_start "$@" --ela motion_lane_sample_us "$sample_arg"
    wait_for_log "$folder/device.log" 'ExportService: Export completed' "$pkg:export" \
        ExportDebug LaneReadback ExportService SpriteBlend AvatarParity
    for actual in "${times[@]}"; do
        adb -e exec-out run-as "$pkg" cat "cache/lane_frame_$actual.rgba" > "$folder/lane_frame_$actual.rgba"
    done
}

export_raw "$out/blur-raw" --es project_id "$project"
export_raw "$out/sharp-raw" --ez motion_avatar_fixture true --ez motion_enabled false \
    --ez motion_avatar_parity_loop true

"$python" - "$out" <<'PY'
import json
from pathlib import Path
import re
import sys
from PIL import Image

root = Path(sys.argv[1])
preview = []
for line in (root / "preview/samples").read_text().splitlines():
    filename, pts, shape = line.split()
    width, height = map(int, shape.split("x"))
    with Image.open(root / "preview" / filename) as image:
        if image.size != (width, height):
            raise SystemExit("Preview framebuffer dimensions disagree")
        rgba = image.convert("RGBA").transpose(Image.Transpose.FLIP_TOP_BOTTOM).tobytes()
    filename = Path(filename).with_suffix(".rgba").name
    (root / "preview" / filename).write_bytes(rgba)
    preview.append(dict(file=filename, requestUs=int(pts), editorUs=int(pts), width=width, height=height))
(root / "preview/manifest.json").write_text(json.dumps({"samples": preview}))

for route in ("blur-raw", "sharp-raw"):
    folder = root / route
    log = (folder / "device.log").read_text()
    samples = []
    for request, pts, width, height in re.findall(
        r"frame=lane_frame_(\d+)\.rgba editorUs=(\d+) size=(\d+)x(\d+)", log
    ):
        samples.append(dict(file=f"lane_frame_{request}.rgba", requestUs=int(request),
                            editorUs=int(pts), width=int(width), height=int(height)))
    (folder / "manifest.json").write_text(json.dumps({"samples": samples}))

trace = {}
for screen in ("preview", "blur-raw"):
    logs = "".join(path.read_text() for path in sorted((root / screen).glob("*.log")))
    kind = "preview" if screen == "preview" else "export"
    trace[screen] = {
        sample["editorUs"]: [
            line.split("AvatarParity: ", 1)[1] for line in logs.splitlines()
            if f"AvatarParity: {kind} editorUs={sample['editorUs']} " in line
        ]
        for sample in preview
    }
(root / "avatar-stage-trace.json").write_text(json.dumps(trace, indent=2))

preview_log = "".join(path.read_text() for path in (root / "preview").glob("*.log"))
route = {
    "preview": {
        sample["editorUs"]: f"AvatarParity: preview native-stage editorUs={sample['editorUs']} "
        in preview_log
        for sample in preview
    },
    "export": [
        line.split("AvatarParity: ", 1)[1]
        for line in (root / "blur-raw/device.log").read_text().splitlines()
        if "AvatarParity: export identity=" in line
    ],
    "sharp": [
        line.split("AvatarParity: ", 1)[1]
        for line in (root / "sharp-raw/device.log").read_text().splitlines()
        if "AvatarParity: export identity=" in line
    ],
}
(root / "avatar-staging-route.json").write_text(json.dumps(route, indent=2))
PY

if node tools/motionref/compare-lanes.mjs "$out/preview/manifest.json" "$out/sharp-raw/manifest.json" \
    > "$out/avatar-negative.json"; then
    echo 'Negative control unexpectedly treated the sharp export as equivalent'
    exit 1
fi
# A timestamp, dimension or file error is not a pixel-level negative control.
"$python" -c 'import json, sys
result = json.load(open(sys.argv[1]))
assert len(result["samples"]) == 3 and all(not sample["equivalent"] for sample in result["samples"])
' "$out/avatar-negative.json"
node tools/motionref/compare-lanes.mjs "$out/preview/manifest.json" "$out/blur-raw/manifest.json" \
    | tee "$out/avatar-parity.json"
"$python" - "$out/avatar-staging-route.json" <<'PY'
import json
import sys
from pathlib import Path

proof = json.loads(Path(sys.argv[1]).read_text())
if len(proof["preview"]) != 3 or not all(proof["preview"].values()):
    raise SystemExit("A matching preview frame missed native SDR staging")
if not proof["export"] or not all(line.startswith("export identity=true ") for line in proof["export"]):
    raise SystemExit("The registered export format did not bypass the same-size canvas pass")
if proof["sharp"]:
    raise SystemExit("The sharp export unexpectedly reached the gated avatar canvas route")
print(json.dumps(proof, indent=2))
PY
