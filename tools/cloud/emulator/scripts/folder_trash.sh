# FILE-1: with a real Joy Creator folder chosen, a Studio project (a file on disk) goes to Trash and comes back from
# Recently deleted. (A JoyBrush drawing that was never drawn on has no file, so it cannot show the trash.)
. "$HERE_LIB/lib_app.sh"
adb shell mkdir -p /sdcard/Documents/JoyCreatorTest
adb shell pm enable com.android.documentsui >/dev/null 2>&1; settle 15
launch_app; settle 10
for label in "Start creating" "Continue" "Skip" "Not now"; do tap_text "$label" >/dev/null 2>&1 && settle 3; done
settle 3
choose_folder || echo "choose_folder stopped early"
shot lobby_after_folder
# a project with one picture in it: Start one -> Blank audio project -> Add -> Image as new layer
make_test_png /tmp/joy_test_pic.png; adb push /tmp/joy_test_pic.png /sdcard/Download/joy_test_pic.png >/dev/null
tap_text "Start one" >/dev/null 2>&1 || tap_text "New" >/dev/null 2>&1; settle 4
tap_text "Blank audio project" >/dev/null 2>&1; settle 14
shot project_made
adb shell am force-stop "$PKG"; settle 3; launch_app; settle 9
tap_text "Open the library" >/dev/null 2>&1; settle 5; shot library_with_project
hold 270 440 1300; settle 4; shot sheet_project
tap_text "Move to Trash" >/dev/null 2>&1; settle 2; shot trash_asks_again
tap_text "Tap again" >/dev/null 2>&1; settle 6; shot after_trash
adb shell am force-stop "$PKG"; settle 3; launch_app; settle 8
tap_text "Setup" >/dev/null 2>&1; settle 4
tap_text "Recently deleted" >/dev/null 2>&1; settle 5; shot recently_deleted
dump recently; python3 "$HERE_LIB/uia.py" list /tmp/ui.xml | head -24
tap 270 440; settle 3; shot recently_item
dump recently_item; python3 "$HERE_LIB/uia.py" list /tmp/ui.xml | head -24
for label in "Put back" "Restore"; do tap_text "$label" >/dev/null 2>&1 && { echo "tapped $label"; settle 5; break; }; done
shot after_put_back
adb shell am force-stop "$PKG"; settle 3; launch_app; settle 8
tap_text "Open the library" >/dev/null 2>&1; settle 5; shot library_after_put_back
adb shell "find /sdcard/Documents/JoyCreatorTest -maxdepth 3 | head -20"
echo "script done"
