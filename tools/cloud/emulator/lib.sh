# Helpers for the cloud-emulator phone scripts (sourced). Needs: adb, python3, $OUT, $PKG.
# Method (tasks/AGENT_START.md): find a control by its text or content description with a uiautomator dump and tap its
# bounds; use fixed coordinates only on custom-drawn screens (the lobby has no text nodes), and only after a screenshot.
HERE_LIB="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SHOTN=0

# shot <name>: a numbered PNG in $OUT
shot() {
    SHOTN=$((SHOTN + 1))
    local f
    f=$(printf '%s/%02d_%s.png' "$OUT" "$SHOTN" "$1")
    adb exec-out screencap -p > "$f"
    echo "shot $f ($(stat -c %s "$f") bytes)"
}

# dump [name]: the uiautomator tree as XML in $OUT (and /tmp/ui.xml)
dump() {
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    adb exec-out cat /sdcard/ui.xml > /tmp/ui.xml 2>/dev/null
    [ -n "${1:-}" ] && cp /tmp/ui.xml "$OUT/ui_$1.xml"
}

# find <needle>: list matches (text / content-desc / resource-id) with bounds
find_ui() { dump; python3 "$HERE_LIB/uia.py" find /tmp/ui.xml "$1"; }

# tap_text <needle> [n]: tap the n-th match (default 0); returns 1 when none
tap_text() {
    dump
    python3 "$HERE_LIB/uia.py" tap /tmp/ui.xml "$1" "${2:-0}" | while read -r x y; do
        adb shell input tap "$x" "$y"; echo "tap '$1' at $x,$y"
    done
    python3 "$HERE_LIB/uia.py" count /tmp/ui.xml "$1" | grep -qv '^0$'
}

# hold_text <needle> [ms]: press and hold the first match
hold_text() {
    dump
    python3 "$HERE_LIB/uia.py" tap /tmp/ui.xml "$1" "${3:-0}" | while read -r x y; do
        adb shell input swipe "$x" "$y" "$x" "$y" "${2:-1100}"; echo "hold '$1' at $x,$y"
    done
}

tap() { adb shell input tap "$1" "$2"; }
hold() { adb shell input swipe "$1" "$2" "$1" "$2" "${3:-1100}"; }
swipe() { adb shell input swipe "$1" "$2" "$3" "$4" "${5:-300}"; }
key() { adb shell input keyevent "$1"; }
settle() { sleep "${1:-2}"; }

# launch_app: the launcher activity of the debug app
launch_app() { adb shell am start -n "$PKG/com.fadcam.SplashActivity" >/dev/null; }
# wait_for <needle> [seconds]: until a node matches, or give up (returns 1)
wait_for() {
    local i
    for i in $(seq 1 "${2:-30}"); do
        dump
        python3 "$HERE_LIB/uia.py" count /tmp/ui.xml "$1" | grep -qv '^0$' && return 0
        sleep 1
    done
    return 1
}
