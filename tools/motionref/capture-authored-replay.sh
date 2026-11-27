#!/usr/bin/env bash
# Pre-encode Studio crops from an isolated test emulator. No owner media is used.
set -euo pipefail
out=$1
pkg=com.fadcam.beta
component="$pkg/com.fadcam.ui.faditor.export.ExportDebugActivity"
center=400000
adb() { timeout --kill-after=5s 30s adb -e "$@"; }
test "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = 1
mkdir -p "$out"

capture() {
    local scene=$1 white=$2 enabled=$3 folder=$4
    local export_pid complete=false
    mkdir -p "$folder"
    adb shell am force-stop "$pkg"
    adb shell run-as "$pkg" rm -f "cache/lane_frame_$center.rgba"
    adb logcat -c
    adb shell am start -W -n "$component" --es motion_authored_reference "$scene" \
        --ez motion_reference_white "$white" --ez motion_enabled "$enabled" > "$folder/start.log"
    grep -q '^Status: ok' "$folder/start.log"
    for attempt in {1..75}; do
        adb logcat -d -s ExportDebug ExportService ExportManager LaneComposite LaneReadback SpriteBlend AndroidRuntime \
            > "$folder/export.log"
        if grep -qiE 'FATAL EXCEPTION|debug export failed|Export failed' "$folder/export.log"; then
            cat "$folder/export.log"
            return 1
        fi
        export_pid=$(awk -v frame="frame=lane_frame_$center.rgba" '$7 == frame {print $3; exit}' "$folder/export.log")
        if [[ -n "$export_pid" ]] && awk -v pid="$export_pid" \
            '$3 == pid && /ExportService: Export completed/ {found=1} END {exit !found}' "$folder/export.log"; then
            complete=true
            break
        fi
        sleep 1
    done
    if [[ "$complete" != true ]]; then
        cat "$folder/export.log"
        return 1
    fi
    grep "C7 authored reference fixture scene=$scene " "$folder/export.log"
    grep 'LANE_EXPORT 96x80 .*SPRITE:' "$folder/export.log"
    grep "frame=lane_frame_$center.rgba editorUs=$center size=96x80" "$folder/export.log"
    adb exec-out run-as "$pkg" cat "cache/lane_frame_$center.rgba" > "$folder/studio.rgba"
    test "$(wc -c < "$folder/studio.rgba")" -eq $((96 * 80 * 4))
}

capture slide-fast false true "$out/slide-fast"
capture slide-fast false false "$out/slide-fast-control"
if cmp -s "$out/slide-fast/studio.rgba" "$out/slide-fast-control/studio.rgba"; then
    echo 'Animated fixture must differ from the sharp control' >&2
    exit 1
fi
capture still false true "$out/still"
capture still false false "$out/still-control"
cmp "$out/still/studio.rgba" "$out/still-control/studio.rgba"
capture slide-slow true true "$out/slide-slow"
capture zoom false true "$out/zoom"
capture spin true true "$out/spin"
capture teleport true true "$out/teleport"
