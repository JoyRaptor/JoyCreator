# Library item sheet (SHELL-12) and Move to Trash / put back (FILE-1) on a drawing made through Joybot, then a missing file.
launch_app; settle 10
for label in "Start creating" "Continue" "Skip" "Not now"; do tap_text "$label" >/dev/null 2>&1 && settle 3; done
settle 3
tap_text "Joybot" >/dev/null 2>&1 || tap 1020 105; settle 4
wait_for "Create something" 25 >/dev/null; tap_text "Create something" >/dev/null 2>&1; settle 3
tap_text "Drawing or painting" >/dev/null 2>&1; settle 3
tap_text "1:1" >/dev/null 2>&1 || tap_text "Square" >/dev/null 2>&1; settle 12
adb shell am force-stop "$PKG"; settle 2; launch_app; settle 9
open_library() { tap_text "Open the library" >/dev/null 2>&1; settle 4; }
open_library
echo "== A. item sheet of a drawing"
hold 270 440 1300; settle 4; shot sheet_drawing
echo "== B. rename"
tap_text "Rename" >/dev/null 2>&1; settle 3; shot rename_dialog
dump rename; python3 "$HERE_LIB/uia.py" list /tmp/ui.xml | head -14
adb shell input keyevent KEYCODE_MOVE_END; for i in $(seq 1 12); do adb shell input keyevent KEYCODE_DEL; done
adb shell input text "Renamed_one"; settle 1; shot rename_typed
tap_text "OK" >/dev/null 2>&1 || tap_text "Rename" >/dev/null 2>&1 || tap_text "Save" >/dev/null 2>&1; settle 4
shot after_rename; dump after_rename; python3 "$HERE_LIB/uia.py" list /tmp/ui.xml | grep -i "renamed\|Drawing" | head
echo "== C. Move to Trash"
hold 270 440 1300; settle 4
tap_text "Move to Trash" >/dev/null 2>&1; settle 4; shot after_trash
dump after_trash; python3 "$HERE_LIB/uia.py" list /tmp/ui.xml | head -16
adb shell am force-stop "$PKG"; settle 2; launch_app; settle 8
tap_text "Setup" >/dev/null 2>&1; settle 4
tap_text "Recently deleted" >/dev/null 2>&1; settle 5; shot recently_deleted
dump recently; python3 "$HERE_LIB/uia.py" list /tmp/ui.xml | head -24
tap_text "Renamed" >/dev/null 2>&1; settle 3; shot recently_item
dump recently_item; python3 "$HERE_LIB/uia.py" list /tmp/ui.xml | head -24
for label in "Put back" "Restore"; do tap_text "$label" >/dev/null 2>&1 && { echo "tapped $label"; settle 4; break; }; done
shot after_put_back
adb shell am force-stop "$PKG"; settle 2; launch_app; settle 8; open_library; shot library_after_put_back
echo "== D. a missing file"
adb shell "find /data/data/$PKG /sdcard/Android/data/$PKG -mmin -20 -type f 2>/dev/null | grep -v -i 'cache\|\.so\|code_cache\|shared_prefs\|databases' | head -30"
adb shell "find /data/data/$PKG /sdcard/Android/data/$PKG -mmin -20 -type f \( -iname '*renamed*' -o -iname '*drawing*' \) -print -delete 2>/dev/null"
adb shell am force-stop "$PKG"; settle 2; launch_app; settle 8; open_library; shot library_missing_file
hold 270 440 1300; settle 4; shot sheet_missing_file
echo "script done"
