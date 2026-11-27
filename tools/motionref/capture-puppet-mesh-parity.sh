#!/usr/bin/env bash
# Run only on a disposable Android emulator with the isolated puppet fixture APK.
set -euo pipefail
out=$1
python=$2
pkg=com.fadcam.beta
activity="$pkg/com.fadcam.ui.faditor.export.ExportDebugActivity"
times=()
variants=(blur sharp master-off)
adb() { timeout --kill-after=5s 30s adb "$@"; }
test "$(adb -e shell getprop ro.kernel.qemu | tr -d '\r')" = 1
mkdir -p "$out"

launch() {
    local log=$1
    shift
    adb -e shell am force-stop "$pkg"
    adb -e logcat -c
    adb -e shell am start -n "$activity" "$@" 2>&1 | tee "$log"
    grep -q '^Starting: Intent' "$log"
    if grep -q '^Error' "$log"; then return 1; fi
}

play() {
    local coords x y attempt
    for attempt in {1..15}; do
        if coords=$(adb -e shell uiautomator dump /sdcard/stu7-window.xml >/dev/null &&
            adb -e exec-out cat /sdcard/stu7-window.xml | "$python" -c '
import re
import sys
import xml.etree.ElementTree as ET
for node in ET.parse(sys.stdin).iter("node"):
    if node.attrib.get("resource-id") == "com.fadcam.beta:id/btn_play_pause":
        left, top, right, bottom = map(int, re.findall(r"\d+", node.attrib["bounds"]))
        if left < right and top < bottom:
            print((left + right) // 2, (top + bottom) // 2)
            sys.exit(0)
sys.exit(1)'); then
            read -r x y <<< "$coords"
            adb -e shell input tap "$x" "$y"
            return
        fi
        sleep 1
    done
    return 1
}

await_log() {
    local log=$1 pattern=$2 attempts=$3 process=$4 attempt pid
    for ((attempt = 0; attempt < attempts; attempt++)); do
        pid=$(adb -e shell pidof -s "$process" | tr -d '\r' || true)
        if [[ ! "$pid" =~ ^[0-9]+$ ]]; then sleep 1; continue; fi
        adb -e logcat -d --pid="$pid" -s ExportDebug MotionPreviewReadback LaneReadback PuppetMeshParity \
            FxLivePreview FxPreviewGl SpriteBlend ExportManager ExportService AndroidRuntime > "$log"
        if grep -qiE 'FATAL EXCEPTION|debug export failed|Export failed|authored mesh shutter failed' "$log"; then
            cat "$log"
            return 1
        fi
        if grep -qE "$pattern" "$log"; then return 0; fi
        sleep 1
    done
    if [[ -f "$log" ]]; then cat "$log"; fi
    return 1
}

mkdir -p "$out/blur"
# Let the emulator pick the frame it actually presented. SwiftShader can skip seconds while
# warming up the mesh shader. Only keep real, distinct shutter draws and reuse their exact PTS.
for requested in 150000 500000 1000000 1500000 1900000 2300000 2500000 2700000 2900000; do
    for attempt in 1 2 3; do
        prefix="$out/blur/attempt-$requested-$attempt"
        launch "$prefix-start.log" --ez motion_puppet_mesh_fixture true \
            --ez open_preview true --el motion_preview_readback_us "$requested"
        play
        if ! await_log "$prefix.log" 'frame=motion_preview_[0-9]+\.png' 12 "$pkg" ||
            ! grep -q 'LANE_PREVIEW ordered' "$prefix.log"; then continue; fi
        actual=$(sed -nE 's/.*frame=motion_preview_[0-9]+\.png editorUs=([0-9]+) .*/\1/p' "$prefix.log" | tail -1)
        if [[ ! "$actual" =~ ^[0-9]+$ ]] || (( actual < requested || actual >= 3500000 )) ||
            [[ -e "$out/blur/preview-$actual.png" ]] ||
            ! grep -q "PuppetMeshParity: preview editorUs=$actual " "$prefix.log"; then continue; fi
        cp "$prefix.log" "$out/blur/preview-$actual.log"
        adb -e exec-out run-as "$pkg" cat "cache/motion_preview_$actual.png" > "$out/blur/preview-$actual.png"
        test -s "$out/blur/preview-$actual.png"
        times+=("$actual")
        break
    done
    if (( ${#times[@]} >= 3 )); then break; fi
done
if (( ${#times[@]} != 3 )); then
    echo "Captured only ${#times[@]} distinct authored mesh preview frames" >&2
    exit 1
fi
mapfile -t times < <(printf '%s\n' "${times[@]}" | sort -n)

for variant in "${variants[@]}"; do
    folder="$out/$variant"
    mkdir -p "$folder"
    enabled=true
    master_off=false
    if [[ "$variant" == sharp ]]; then enabled=false; fi
    if [[ "$variant" == master-off ]]; then master_off=true; fi
    fixture=(--ez motion_puppet_mesh_fixture true --ez motion_enabled "$enabled" --ez motion_master_off "$master_off")
    if [[ "$variant" == blur ]]; then
        project=$(sed -nE 's/.*C5 puppet mesh fixture project_id=([^ ]+) .*/\1/p' "$folder/preview-${times[-1]}.log" | tail -1)
        test -n "$project"
        fixture=(--es project_id "$project")
    fi
    for pts in "${times[@]}"; do
        adb -e shell run-as "$pkg" rm -f "cache/lane_frame_$pts.rgba"
    done
    sample_arg=$(IFS=,; echo "${times[*]}")
    launch "$folder/export-start.log" "${fixture[@]}" \
        --ez motion_lane_project_order true --ela motion_lane_sample_us "$sample_arg"
    await_log "$folder/export.log" 'ExportService: Export completed' 90 "$pkg:export"
    grep -q 'LANE_EXPORT' "$folder/export.log"
    for pts in "${times[@]}"; do
        grep -q "frame=lane_frame_$pts.rgba editorUs=$pts " "$folder/export.log"
        if [[ "$variant" == blur ]]; then
            preview_cell=$(sed -nE "s/.*PuppetMeshParity: preview editorUs=$pts cell=([0-9]+).*/\1/p" "$folder/preview-$pts.log" | tail -1)
            grep -q "PuppetMeshParity: export editorUs=$pts cell=$preview_cell$" "$folder/export.log"
        elif grep -q 'PuppetMeshParity: export' "$folder/export.log"; then
            echo "$variant unexpectedly ran the authored shutter in export"
            exit 1
        fi
        adb -e exec-out run-as "$pkg" cat "cache/lane_frame_$pts.rgba" > "$folder/export-$pts.rgba"
        test -s "$folder/export-$pts.rgba"
    done
done

"$python" - "$out" <<'PY'
import json
import re
import sys
from pathlib import Path

from PIL import Image

root = Path(sys.argv[1])
previews = []
for photo in sorted((root / "blur").glob("preview-*.png")):
    pts = int(photo.stem.removeprefix("preview-"))
    with Image.open(photo) as image:
        w, h = image.size
        photo.with_suffix(".rgba").write_bytes(
            image.convert("RGBA").transpose(Image.Transpose.FLIP_TOP_BOTTOM).tobytes()
        )
    previews.append(dict(file=photo.with_suffix(".rgba").name, requestUs=pts, editorUs=pts, width=w, height=h))
(root / "blur/preview.json").write_text(json.dumps({"samples": previews}))

for variant in ("blur", "sharp", "master-off"):
    directory = root / variant
    exports = []
    export_log = (directory / "export.log").read_text()
    for preview in previews:
        pts, w, h = preview["editorUs"], preview["width"], preview["height"]
        export = re.search(rf"frame=lane_frame_{pts}\.rgba editorUs={pts} size=(\d+)x(\d+)", export_log)
        if export is None or tuple(map(int, export.groups())) != (w, h):
            raise SystemExit(f"Mismatched preview/export dimensions at {variant} {pts}")
        sample = dict(requestUs=pts, editorUs=pts, width=w, height=h)
        exports.append(dict(file=f"export-{pts}.rgba", **sample))
    (directory / "export.json").write_text(json.dumps({"samples": exports}))
PY

result=0
compare() {
    local label=$1 first=$2 second=$3
    shift 3
    node tools/motionref/compare-lanes.mjs "$out/$first" "$out/$second" "$@" \
        | tee "$out/$label.json" || result=1
}
compare "blur-parity" "blur/preview.json" "blur/export.json"
compare "preview-vs-sharp" "blur/preview.json" "sharp/export.json" --expect-different
compare "export-blur-vs-sharp" "blur/export.json" "sharp/export.json" --expect-different
compare "export-sharp-vs-off" "sharp/export.json" "master-off/export.json"
exit "$result"
