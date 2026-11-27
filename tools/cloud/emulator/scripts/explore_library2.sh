# Explore 2: the Library card (tap, long press), the item sheet, Space and Recently deleted, with a drawing made first.
launch_app; settle 10
for label in "Start creating" "Continue" "Skip" "Not now"; do tap_text "$label" >/dev/null 2>&1 && settle 3; done
settle 3
tap_text "Joybot" >/dev/null 2>&1 || tap 1020 105; settle 4
wait_for "Create something" 25 >/dev/null; tap_text "Create something" >/dev/null 2>&1; settle 3
tap_text "Drawing or painting" >/dev/null 2>&1; settle 3
tap_text "1:1" >/dev/null 2>&1 || tap_text "Square" >/dev/null 2>&1; settle 12
adb shell am force-stop "$PKG"; settle 2; launch_app; settle 9
tap_text "Open the library" >/dev/null 2>&1; settle 4
shot library_card
# a tap on the card's own picture area (centre of the first card), then wait
tap 270 440; settle 7
shot after_tap_card; dump after_tap_card; python3 "$HERE_LIB/uia.py" list /tmp/ui.xml | head -30
key KEYCODE_BACK; settle 3
shot back_from_card
hold 270 440 1300; settle 4
shot after_long_press; dump after_long_press; python3 "$HERE_LIB/uia.py" list /tmp/ui.xml | head -30
echo "---- selection mode / sheet actions"
tap_text "Drawing 1" >/dev/null 2>&1; settle 3
shot after_second_tap; dump after_second_tap; python3 "$HERE_LIB/uia.py" list /tmp/ui.xml | head -30
echo "---- Space and Recently deleted"
adb shell am force-stop "$PKG"; settle 2; launch_app; settle 8
tap_text "Setup" >/dev/null 2>&1; settle 4
tap_text "Space" >/dev/null 2>&1; settle 6
shot space; dump space; python3 "$HERE_LIB/uia.py" list /tmp/ui.xml | head -40
key KEYCODE_BACK; settle 2
tap_text "Recently deleted" >/dev/null 2>&1; settle 4
shot recently_deleted; dump recently_deleted; python3 "$HERE_LIB/uia.py" list /tmp/ui.xml | head -20
echo "explore done"
