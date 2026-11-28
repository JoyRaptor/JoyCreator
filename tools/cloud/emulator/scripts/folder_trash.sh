# FILE-1: with a real Joy Creator folder chosen, a drawing goes to Trash and comes back from Recently deleted.
. "$HERE_LIB/lib_app.sh"
launch_app; settle 10
for label in "Start creating" "Continue" "Skip" "Not now"; do tap_text "$label" >/dev/null 2>&1 && settle 3; done
settle 3
choose_folder || echo "choose_folder stopped early"
dump after_folder; python3 "$HERE_LIB/uia.py" list /tmp/ui.xml | head -20
adb shell "cat /data/data/$PKG/shared_prefs/joy_folder.xml 2>&1 | head -8"
adb shell am force-stop "$PKG"; settle 2; launch_app; settle 9
tap_text "Joybot" >/dev/null 2>&1 || tap 1020 105; settle 4
wait_for "Create something" 25 >/dev/null; tap_text "Create something" >/dev/null 2>&1; settle 3
tap_text "Drawing or painting" >/dev/null 2>&1; settle 3
tap_text "1:1" >/dev/null 2>&1 || tap_text "Square" >/dev/null 2>&1; settle 12
adb shell am force-stop "$PKG"; settle 2; launch_app; settle 9
tap_text "Open the library" >/dev/null 2>&1; settle 4; shot library_with_drawing
hold 270 440 1300; settle 4
tap_text "Move to Trash" >/dev/null 2>&1; settle 2; shot trash_asks_again
tap_text "Tap again" >/dev/null 2>&1; settle 1; shot trash_just_tapped; settle 3; shot after_trash
adb shell am force-stop "$PKG"; settle 2; launch_app; settle 8
tap_text "Setup" >/dev/null 2>&1; settle 4
tap_text "Recently deleted" >/dev/null 2>&1; settle 5; shot recently_deleted
dump recently; python3 "$HERE_LIB/uia.py" list /tmp/ui.xml | head -24
tap 270 440; settle 3; shot recently_item
dump recently_item; python3 "$HERE_LIB/uia.py" list /tmp/ui.xml | head -24
for label in "Put back" "Restore"; do tap_text "$label" >/dev/null 2>&1 && { echo "tapped $label"; settle 4; break; }; done
shot after_put_back
adb shell am force-stop "$PKG"; settle 2; launch_app; settle 8
tap_text "Open the library" >/dev/null 2>&1; settle 4; shot library_after_put_back
echo "script done"
