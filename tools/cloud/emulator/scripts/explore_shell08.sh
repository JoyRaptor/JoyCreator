# SHELL-08: the fullscreen camera preview and the classic Studio list, on the Joy Creator theme (shots only; looked at by eye).
launch_app; settle 10
for label in "Start creating" "Continue" "Skip" "Not now"; do tap_text "$label" >/dev/null 2>&1 && settle 3; done
settle 3
tap_text "CAPTURE" >/dev/null 2>&1; settle 5
shot capture_room; dump capture; python3 "$HERE_LIB/uia.py" list /tmp/ui.xml | head -40
for label in "Fullscreen" "Full screen" "Preview" "Enlarge"; do tap_text "$label" >/dev/null 2>&1 && { echo "tapped $label"; settle 5; break; }; done
shot after_fullscreen_try; dump fs; python3 "$HERE_LIB/uia.py" list /tmp/ui.xml | head -30
key KEYCODE_BACK; settle 3
echo "== classic Studio list"
adb shell am start -n "$PKG/com.fadcam.ui.faditor.FaditorMiniFragment" >/dev/null 2>&1
tap_text "Setup" >/dev/null 2>&1; settle 4; shot settings
dump settings; python3 "$HERE_LIB/uia.py" list /tmp/ui.xml | grep -i "classic\|studio\|records\|about" | head
for label in "Classic Studio" "Studio list" "Classic"; do tap_text "$label" >/dev/null 2>&1 && { echo "tapped $label"; settle 5; break; }; done
shot classic_list; dump classic; python3 "$HERE_LIB/uia.py" list /tmp/ui.xml | head -30
echo "script done"
