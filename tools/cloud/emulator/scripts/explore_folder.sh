# Explore: choose a Joy Creator folder on the emulator through the system picker (Settings > Joy Creator folder).
adb shell mkdir -p /sdcard/Documents/JoyCreatorTest
adb shell pm enable com.android.documentsui >/dev/null 2>&1 || true
launch_app; settle 10
for label in "Start creating" "Continue" "Skip" "Not now"; do tap_text "$label" >/dev/null 2>&1 && settle 3; done
settle 3
tap_text "Setup" >/dev/null 2>&1; settle 4
tap_text "Joy Creator folder" >/dev/null 2>&1; settle 5
shot after_tap_folder; dump after_tap_folder; python3 "$HERE_LIB/uia.py" list /tmp/ui.xml | head -40
for step in 1 2 3 4; do
    for label in "Choose" "Pick" "Use this folder" "USE THIS FOLDER" "Allow" "ALLOW"; do
        tap_text "$label" >/dev/null 2>&1 && { echo "step $step tapped $label"; settle 3; }
    done
    shot "picker_step_$step"; dump "picker_step_$step"; python3 "$HERE_LIB/uia.py" list /tmp/ui.xml | head -25
done
echo "== what the app has stored"
adb shell "cat /data/data/$PKG/shared_prefs/joy_folder.xml 2>&1 | head -12"
echo "explore done"
