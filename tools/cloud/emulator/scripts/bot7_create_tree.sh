# BOT-7 create-tree landings on the cloud emulator (no AI key): open Joybot in the lobby, "Create something", and for each
# bubble answer its questions and see where it lands. Evidence: numbered shots + the foreground activity after each.
launch_app
settle 10
for label in "Start creating" "Continue" "Skip" "Not now"; do tap_text "$label" >/dev/null 2>&1 && settle 3; done
settle 3
shot lobby
# Joybot's orb is the round picture at the top right (custom drawn: coordinates from the lobby shot, 1080x2220).
tap_text "Joybot" >/dev/null 2>&1 || tap 1020 105
settle 4
shot joybot_open
dump joybot_open
python3 "$HERE_LIB/uia.py" list /tmp/ui.xml | head -40
tap_text "Create something" >/dev/null 2>&1; settle 3
shot create_tree
dump create_tree
python3 "$HERE_LIB/uia.py" list /tmp/ui.xml | head -60
top() { adb shell dumpsys activity activities | grep -m1 -E "topResumedActivity" | sed 's/.*u0 //; s/ t[0-9]*}//'; }
# one landing: bubble text, then answer strings tried in order (each tapped if present), then the room reached
landing() {
    local name="$1"; shift
    echo "=== landing $name"
    adb shell am force-stop "$PKG"; settle 2; launch_app; settle 8      # always from a clean start
    tap_text "Joybot" >/dev/null 2>&1 || tap 1020 105; settle 3
    tap_text "Create something" >/dev/null 2>&1; settle 2
    local found=0 k
    for k in 0 1 2 3 4 5 6 7 8 9 10 11; do
        if tap_text "$name" >/dev/null 2>&1; then found=1; break; fi
        swipe 900 1960 200 1960 300; settle 1      # the bubbles are a row that scrolls sideways
        dump; echo "bubbles after swipe $k:"; python3 "$HERE_LIB/uia.py" list /tmp/ui.xml | grep -i "|" | head -12
    done
    [ "$found" = 1 ] || { echo "RESULT $name: bubble not found"; shot "${name}_notfound"; return; }
    settle 3; shot "${name}_asks"
    dump "${name}_asks"; python3 "$HERE_LIB/uia.py" list /tmp/ui.xml | head -25
    for answer in "$@"; do tap_text "$answer" >/dev/null 2>&1 && { echo "answered $answer"; settle 3; }; done
    settle 14; shot "${name}_lands"
    echo "RESULT $name: lands in $(top)"
    key KEYCODE_BACK; settle 2; key KEYCODE_BACK; settle 2
    adb shell am start -n "$PKG/com.fadcam.SplashActivity" >/dev/null; settle 6
    tap_text "Joybot" >/dev/null 2>&1 || tap 1020 105; settle 3
}
landing "Video" "16:9" "Use this"
landing "Audio project"
echo "script done"
