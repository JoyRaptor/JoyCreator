#!/usr/bin/env bash
# Isolated Android service proof. Generates synthetic video on the disposable runner.
set -euo pipefail
out=$(realpath -m "$1")
mkdir -p "$out"
cd "$out"
pkg=com.fadcam.beta
private="/sdcard/Android/data/$pkg/files/studio-stu7-reference.mp4"
adb() { timeout --kill-after=5s 40s adb -e "$@"; }
test "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = 1

encode() {
    ffmpeg -hide_banner -loglevel error -f lavfi -i "$1" -frames:v 30 -an \
        -c:v libx264 -crf 14 -pix_fmt yuv420p -colorspace bt709 \
        -color_trc bt709 -color_primaries bt709 "$2"
}
encode 'testsrc2=size=640x360:rate=25' after.mp4
encode 'color=c=black:size=640x360:rate=25' before-a.mp4
encode 'color=c=white:size=640x360:rate=25' before-b.mp4
for variant in a b; do
    printf '%s\n' "file before-$variant.mp4" "file after.mp4" > "$variant.txt"
    ffmpeg -hide_banner -loglevel error -f concat -safe 1 -i "$variant.txt" -c copy "$variant.mp4"
    ffmpeg -hide_banner -loglevel error -i "$variant.mp4" -an -f framemd5 - | grep -v '^#' > "$variant.frames"
    test "$(wc -l < "$variant.frames")" -eq 60
done
cmp <(tail -30 a.frames) <(tail -30 b.frames)

# The app must create its scoped external directory before adb can stage the clip.
adb shell am start -W -n "$pkg/com.fadcam.ui.faditor.export.ExportDebugActivity" \
    --ez motion_footage_fixture true --ez motion_private_reference true \
    --ez motion_enabled false > external-storage-bootstrap.log
adb shell test -d "/sdcard/Android/data/$pkg/files"

capture() {
    local variant=$1 enabled=$2 folder="$1-$2"
    local pid complete=false
    mkdir -p "$folder"
    adb shell am force-stop "$pkg"
    adb shell run-as "$pkg" rm -f cache/lane_frame_1160000.rgba cache/lane_frame_1200000.rgba
    adb push "$variant.mp4" "$private"
    adb logcat -c
    adb shell am start -W -n "$pkg/com.fadcam.ui.faditor.export.ExportDebugActivity" \
        --ez motion_footage_fixture true --ez motion_private_reference true \
        --ez motion_footage_calibration true --ez motion_enabled "$enabled" \
        --ela motion_lane_sample_us 1160000,1200000 > "$folder/start.log"
    grep -q '^Status: ok' "$folder/start.log"
    for _ in {1..160}; do
        adb logcat -d -s HighFootageExport LaneReadback ExportDebug ExportService ExportManager AndroidRuntime > "$folder/export.log"
        if grep -qiE 'FATAL EXCEPTION|Export failed|Indexed export flow unavailable' "$folder/export.log"; then
            cat "$folder/export.log"; return 1
        fi
        pid=$(awk '/frame=lane_frame_1200000.rgba editorUs=1200000 size=640x360/ {print $3; exit}' "$folder/export.log")
        if [[ -n "$pid" ]] && awk -v pid="$pid" '$3 == pid && /ExportService: Export completed/ {found=1} END {exit !found}' "$folder/export.log"; then
            complete=true
            break
        fi
        sleep 1
    done
    if [[ "$complete" != true ]]; then cat "$folder/export.log"; return 1; fi
    grep 'frame=lane_frame_1160000.rgba editorUs=1160000 size=640x360' "$folder/export.log"
    if [[ "$enabled" == true ]]; then grep 'HIGH_FOOTAGE_EXPORT' "$folder/export.log"; fi
    for pts in 1160000 1200000; do
        adb exec-out run-as "$pkg" cat "cache/lane_frame_$pts.rgba" > "$folder/$pts.rgba"
        test "$(wc -c < "$folder/$pts.rgba")" -eq $((640*360*4))
    done
    adb shell rm -f "$private"
}

capture a true
capture b true
capture a false
capture b false
if cmp -s a-false/1160000.rgba b-false/1160000.rgba; then
    echo 'The preceding shot must change' >&2; exit 1
fi
cmp a-false/1200000.rgba b-false/1200000.rgba
cmp a-true/1200000.rgba b-true/1200000.rgba
cmp a-true/1200000.rgba a-false/1200000.rgba
echo 'Exact first frame after the synthetic cut is independent of either preceding shot.'
