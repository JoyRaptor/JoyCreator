#!/usr/bin/env bash
# Runs on the CI runner AFTER the emulator booted (reactivecircus/android-emulator-runner calls it).
# Environment: EMU_PROFILE (phone|tablet), EMU_SCRIPT (a name under scripts/). Output goes to emu-out/ (uploaded).
set -u
cd "$(dirname "$0")/../../.." || exit 1
HERE=tools/cloud/emulator
export OUT="$PWD/emu-out"
mkdir -p "$OUT"
exec > >(tee "$OUT/run.log") 2>&1

PKG=com.fadcam.beta
export PKG
adb() { command timeout "${ADB_TIMEOUT:-60}" adb "$@"; }
echo "profile=${EMU_PROFILE} script=${EMU_SCRIPT}"
adb devices
timeout 120 adb wait-for-device || { echo "emulator did not come up"; exit 1; }
adb shell getprop ro.build.version.sdk | tr -d '\r'

# Make the phone profile the Note 9 as the owner uses it: 1080x2220 at density 315 (about 548 dp wide upright).
if [ "${EMU_PROFILE}" = phone ]; then
    adb shell wm size 1080x2220
    adb shell wm density 315
fi
adb shell wm size; adb shell wm density
# A rooted shell lets scripts start non-exported activities and read the app's files.
adb root >/dev/null 2>&1 || true
sleep 3
adb wait-for-device
# No system file picker, so the app's required "choose the Joy Creator folder" step is skipped (the app skips it on
# a device with no folder picker). Harmless if the package is absent.
adb shell pm disable-user --user 0 com.android.documentsui 2>&1 || true
adb shell settings put secure immersive_mode_confirmations confirmed || true
adb shell settings put global window_animation_scale 0 || true
adb shell settings put global transition_animation_scale 0 || true
adb shell settings put global animator_duration_scale 0 || true

adb install -r -g apk/app-debug.apk || { echo "INSTALL FAILED"; exit 1; }
adb shell dumpsys package "$PKG" | grep -E "versionName|lastUpdateTime" | head -2

# The helper library for the scripts.
. "$HERE/lib.sh"

# EMU_SCRIPT may name several scripts, comma separated: they run one after another on the same emulator, in the same
# installed app (so a later script sees what an earlier one left), each with its own folder under emu-out/.
FINAL=0
IFS=',' read -r -a NAMES <<< "${EMU_SCRIPT}"
for NAME in "${NAMES[@]}"; do
    SCRIPT="$HERE/scripts/${NAME}.sh"
    [ -f "$SCRIPT" ] || { echo "no such script: $SCRIPT"; ls "$HERE/scripts"; FINAL=1; continue; }
    if [ "${#NAMES[@]}" -gt 1 ]; then export OUT_BASE="$OUT"; OUT="$OUT_BASE/$NAME"; mkdir -p "$OUT"; else OUT_BASE="$OUT"; fi
    export OUT
    echo "=== script $NAME ==="
    START=$(date +%s)
    code=0
    timeout "${SCRIPT_TIMEOUT:-600}" bash -c ". '$HERE/lib.sh'; . '$SCRIPT'" || code=$?
    echo "script $NAME exit=$code after $(( $(date +%s) - START )) s"
    [ "$code" -ne 0 ] && FINAL=$code
    OUT="$OUT_BASE"; export OUT
done
code=$FINAL

ADB_TIMEOUT=60 adb logcat -d -v time > "$OUT/logcat.txt" 2>&1 || true
adb shell dumpsys battery | head -12 > "$OUT/battery.txt" 2>&1 || true
# The crash list, if any.
adb shell dumpsys dropbox --print data_app_crash 2>/dev/null | head -80 > "$OUT/crashes.txt" || true
exit $code
