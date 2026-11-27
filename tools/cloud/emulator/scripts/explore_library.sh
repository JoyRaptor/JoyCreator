# Explore: make one drawing through Joybot, then look at the Library, its item sheet, where the drawing's file lives, and Space.
launch_app; settle 10
for label in "Start creating" "Continue" "Skip" "Not now"; do tap_text "$label" >/dev/null 2>&1 && settle 3; done
settle 3
tap_text "Joybot" >/dev/null 2>&1 || tap 1020 105; settle 4
wait_for "Create something" 25 >/dev/null; tap_text "Create something" >/dev/null 2>&1; settle 3
tap_text "Drawing or painting" >/dev/null 2>&1; settle 3
tap_text "1:1" >/dev/null 2>&1 || tap_text "Square" >/dev/null 2>&1; settle 12
shot drawing_made
adb shell am force-stop "$PKG"; settle 2; launch_app; settle 9
shot lobby_with_drawing
dump lobby_with_drawing; python3 "$HERE_LIB/uia.py" list /tmp/ui.xml | head -30
# where is the drawing on disk?
adb shell "find /data/data/$PKG /sdcard/Android/data/$PKG -maxdepth 4 \( -iname '*.jb*' -o -iname '*draw*' -o -iname '*.joybrush*' \) 2>/dev/null | head -20"
adb shell "ls -la /data/data/$PKG/files 2>/dev/null | head -30"
# Library
tap_text "LIBRARY" >/dev/null 2>&1 || tap_text "Library" >/dev/null 2>&1; settle 4
shot library
dump library; python3 "$HERE_LIB/uia.py" list /tmp/ui.xml | head -40
tap_text "Drawing 1" >/dev/null 2>&1; settle 4
shot item_sheet_or_open
dump item_sheet; python3 "$HERE_LIB/uia.py" list /tmp/ui.xml | head -40
# Space and Settings
key KEYCODE_BACK; settle 2; key KEYCODE_BACK; settle 2
adb shell am start -n "$PKG/com.fadcam.SplashActivity" >/dev/null; settle 5
tap_text "Setup" >/dev/null 2>&1; settle 4
shot setup
dump setup; python3 "$HERE_LIB/uia.py" list /tmp/ui.xml | head -50
tap_text "Space" >/dev/null 2>&1; settle 5
shot space
dump space; python3 "$HERE_LIB/uia.py" list /tmp/ui.xml | head -40
echo "explore done"
