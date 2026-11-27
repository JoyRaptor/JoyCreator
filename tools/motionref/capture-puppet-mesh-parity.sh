#!/usr/bin/env bash
# Run only on a disposable Android emulator with the isolated puppet fixture APK.
set -euo pipefail
out=$1
python=$2
pkg=com.fadcam.beta
activity="$pkg/com.fadcam.ui.faditor.export.ExportDebugActivity"
wanted=(1233333 1366666 2566666)
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
    local log=$1 pattern=$2 attempts=$3 attempt
    for ((attempt = 0; attempt < attempts; attempt++)); do
        adb -e logcat -d -s ExportDebug MotionPreviewReadback LaneReadback PuppetMeshParity \
            FxLivePreview FxPreviewGl SpriteBlend ExportManager ExportService AndroidRuntime > "$log"
        if grep -qiE 'FATAL EXCEPTION|debug export failed|Export failed|authored mesh shutter failed' "$log"; then
            cat "$log"
            return 1
        fi
        if grep -qE "$pattern" "$log"; then return 0; fi
        sleep 1
    done
    cat "$log"
    return 1
}

for variant in "${variants[@]}"; do
    folder="$out/$variant"
    mkdir -p "$folder"
    enabled=true
    master_off=false
    if [[ "$variant" == sharp ]]; then enabled=false; fi
    if [[ "$variant" == master-off ]]; then master_off=true; fi
    for index in "${!wanted[@]}"; do
        if [[ "$variant" == blur ]]; then pts=${wanted[index]}; else pts=${times[index]}; fi
        matched=false
        for attempt in {1..5}; do
            prefix="$folder/preview-$pts-$attempt"
            launch "$prefix-start.log" --ez motion_puppet_mesh_fixture true \
                --ez motion_enabled "$enabled" --ez motion_master_off "$master_off" \
                --ez open_preview true --el motion_preview_readback_us "$pts"
            play
            if await_log "$prefix.log" 'frame=motion_preview_[0-9]+\.png' 10 &&
                grep -q 'LANE_PREVIEW ordered' "$prefix.log"; then
                actual=$(sed -nE 's/.*frame=motion_preview_[0-9]+\.png editorUs=([0-9]+) .*/\1/p' "$prefix.log" | tail -1)
                if [[ ! "$actual" =~ ^[0-9]+$ ]]; then continue; fi
                # Select the Android decoder's real microsecond timestamp once, then match it
                # exactly in both controls and every export. Allow floor or nearest rounding
                # only while selecting the nominal 30fps frame.
                if [[ "$variant" == blur ]]; then
                    if (( actual < pts || actual > pts + 1 )) ||
                        ! grep -q "PuppetMeshParity: preview editorUs=$actual " "$prefix.log"; then
                        continue
                    fi
                    times+=("$actual")
                elif [[ "$actual" != "$pts" ]]; then
                    continue
                fi
                if [[ "$variant" != blur ]] && grep -q 'PuppetMeshParity: preview' "$prefix.log"; then
                    echo "$variant unexpectedly ran the authored shutter in preview"
                    exit 1
                fi
                cp "$prefix.log" "$folder/preview-$actual.log"
                adb -e exec-out run-as "$pkg" cat "cache/motion_preview_$actual.png" > "$folder/preview-$actual.png"
                test -s "$folder/preview-$actual.png"
                matched=true
                break
            fi
        done
        if [[ "$matched" != true ]]; then
            echo "No $variant preview at exact editor PTS $pts" >&2
            exit 1
        fi
    done

    project=$(sed -nE 's/.*C5 puppet mesh fixture project_id=([^ ]+) .*/\1/p' "$folder/preview-${times[-1]}.log" | tail -1)
    test -n "$project"
    for pts in "${times[@]}"; do
        adb -e shell run-as "$pkg" rm -f "cache/lane_frame_$pts.rgba"
    done
    sample_arg=$(IFS=,; echo "${times[*]}")
    launch "$folder/export-start.log" --es project_id "$project" \
        --ez motion_lane_project_order true --ela motion_lane_sample_us "$sample_arg"
    await_log "$folder/export.log" 'ExportService: Export completed' 90
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
for variant in ("blur", "sharp", "master-off"):
    directory = root / variant
    previews, exports = [], []
    export_log = (directory / "export.log").read_text()
    for photo in sorted(directory.glob("preview-*.png")):
        pts = int(photo.stem.removeprefix("preview-"))
        with Image.open(photo) as image:
            w, h = image.size
            photo.with_suffix(".rgba").write_bytes(
                image.convert("RGBA").transpose(Image.Transpose.FLIP_TOP_BOTTOM).tobytes()
            )
        export = re.search(rf"frame=lane_frame_{pts}\.rgba editorUs={pts} size=(\d+)x(\d+)", export_log)
        if export is None or tuple(map(int, export.groups())) != (w, h):
            raise SystemExit(f"Mismatched preview/export dimensions at {variant} {pts}")
        sample = dict(requestUs=pts, editorUs=pts, width=w, height=h)
        previews.append(dict(file=photo.with_suffix(".rgba").name, **sample))
        exports.append(dict(file=f"export-{pts}.rgba", **sample))
    (directory / "preview.json").write_text(json.dumps({"samples": previews}))
    (directory / "export.json").write_text(json.dumps({"samples": exports}))
PY

result=0
compare() {
    local label=$1 first=$2 second=$3
    shift 3
    node tools/motionref/compare-lanes.mjs "$out/$first" "$out/$second" "$@" \
        | tee "$out/$label.json" || result=1
}
for variant in "${variants[@]}"; do
    compare "$variant-parity" "$variant/preview.json" "$variant/export.json"
done
for route in preview export; do
    compare "$route-blur-vs-sharp" "blur/$route.json" "sharp/$route.json" --expect-different
    compare "$route-sharp-vs-off" "sharp/$route.json" "master-off/$route.json"
done
exit "$result"
