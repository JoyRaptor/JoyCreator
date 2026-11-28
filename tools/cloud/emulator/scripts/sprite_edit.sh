# BOT-7, the twelfth bubble: "Sprite (edit one)" lists the sprite sheets in the folder and opens the chosen one in Sprite Lab.
. "$HERE_LIB/lib_app.sh"
make_test_png /tmp/TestSheet.4x4.sprite.png
adb shell mkdir -p /sdcard/Documents/JoyCreatorTest
adb push /tmp/TestSheet.4x4.sprite.png /sdcard/Documents/JoyCreatorTest/TestSheet.4x4.sprite.png >/dev/null
adb shell pm enable com.android.documentsui >/dev/null 2>&1; settle 15
launch_app; settle 10
for label in "Start creating" "Continue" "Skip" "Not now"; do tap_text "$label" >/dev/null 2>&1 && settle 3; done
settle 3
choose_folder || echo "choose_folder stopped early"
adb shell am force-stop "$PKG"; settle 3; launch_app; settle 10
wait_for "Got it" 40 >/dev/null; tap_text "Got it" >/dev/null 2>&1; settle 3      # the What's New page after the folder step
tap_text "Home" >/dev/null 2>&1; settle 3
tap_text "Open the library" >/dev/null 2>&1; settle 6; shot library_with_sheet
dump library; python3 "$HERE_LIB/uia.py" list /tmp/ui.xml | head -20
adb shell am force-stop "$PKG"; settle 3; launch_app; settle 10
wait_for "Got it" 8 >/dev/null; tap_text "Got it" >/dev/null 2>&1; settle 2
tap_text "Home" >/dev/null 2>&1; settle 3
tap_text "Joybot" >/dev/null 2>&1 || tap 1020 105; settle 4
wait_for "Create something" 25 >/dev/null; tap_text "Create something" >/dev/null 2>&1; settle 3
for k in 0 1 2 3 4 5 6 7 8 9 10 11; do
    tap_text "Sprite (edit one)" >/dev/null 2>&1 && break
    swipe 900 1960 200 1960 300; settle 1
done
settle 4; shot sprite_edit_asks; dump asks; python3 "$HERE_LIB/uia.py" list /tmp/ui.xml | head -30
tap_text "TestSheet" >/dev/null 2>&1; settle 14; shot sprite_edit_lands
dump lands; python3 "$HERE_LIB/uia.py" list /tmp/ui.xml | head -30
adb shell dumpsys activity activities | grep -m2 -E "topResumedActivity"
echo "script done"
