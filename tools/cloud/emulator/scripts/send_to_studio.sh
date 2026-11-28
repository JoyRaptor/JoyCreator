# SHELL-23 / DOOR-4: send a JoyBrush board to Studio, into an EXISTING project: chooser, one more layer, the same board twice, back out.
. "$HERE_LIB/lib_app.sh"
adb shell mkdir -p /sdcard/Documents/JoyCreatorTest
adb shell pm enable com.android.documentsui >/dev/null 2>&1; settle 15
launch_app; settle 10
for label in "Start creating" "Continue" "Skip" "Not now"; do tap_text "$label" >/dev/null 2>&1 && settle 3; done
settle 3
choose_folder || echo "choose_folder stopped early"
# an existing project
tap_text "Start one" >/dev/null 2>&1 || tap_text "New" >/dev/null 2>&1; settle 4
tap_text "Blank audio project" >/dev/null 2>&1; settle 14; shot project_exists
# a board
adb shell am force-stop "$PKG"; settle 3; launch_app; settle 9
tap_text "Joybot" >/dev/null 2>&1 || tap 1020 105; settle 4
wait_for "Create something" 25 >/dev/null; tap_text "Create something" >/dev/null 2>&1; settle 3
tap_text "Drawing or painting" >/dev/null 2>&1; settle 3
tap_text "1:1" >/dev/null 2>&1 || tap_text "Square" >/dev/null 2>&1; settle 14
shot board_ready
dump board; python3 "$HERE_LIB/uia.py" list /tmp/ui.xml | head -60
# the board's own bar has a send-to-Studio icon (the film-and-arrow one); find it by its words
send_it() {
    tap_text "Export this board" >/dev/null 2>&1 || { echo "no export control found"; return 1; }
    settle 4
    # the export sheet: "This board" with the Send to Studio chip, then the big Export button
    tap_text "=Send to Studio" 1 >/dev/null 2>&1 || tap_text "Send to Studio" >/dev/null 2>&1; settle 2
    tap_text "=Export" >/dev/null 2>&1; settle 8
}
echo "== 1. back out of the chooser"
send_it; shot chooser; dump chooser; python3 "$HERE_LIB/uia.py" list /tmp/ui.xml | head -30
key KEYCODE_BACK; settle 3; shot after_back_out
echo "== 2. into the existing project"
send_it; tap_text "Untitled audio" >/dev/null 2>&1; settle 12; shot landed_in_project
dump landed; python3 "$HERE_LIB/uia.py" list /tmp/ui.xml | head -30
echo "== 3. the same board twice"
adb shell am force-stop "$PKG"; settle 3; launch_app; settle 9
tap_text "Drawing" >/dev/null 2>&1; settle 10
send_it; tap_text "Untitled audio" >/dev/null 2>&1; settle 12; shot landed_second_time
dump landed2; python3 "$HERE_LIB/uia.py" list /tmp/ui.xml | head -30
echo "script done"
