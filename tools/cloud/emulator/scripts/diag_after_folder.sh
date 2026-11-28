# DIAGNOSTIC (no taps after the folder step): what does a first-run user see after choosing the Joy Creator folder?
# Shots and the top activity every 2 s for 30 s; the activity starts are read from logcat afterwards.
. "$HERE_LIB/lib_app.sh"
adb shell mkdir -p /sdcard/Documents/JoyCreatorTest
adb shell pm enable com.android.documentsui >/dev/null 2>&1; settle 15
adb logcat -c
launch_app; settle 10
for label in "Start creating" "Continue" "Skip" "Not now"; do tap_text "$label" >/dev/null 2>&1 && settle 3; done
settle 3
choose_folder || echo "choose_folder stopped early"
echo "== from here no taps: watching for 30 s"
for i in $(seq 1 15); do
    top=$(adb shell dumpsys activity activities | grep -m1 -E "topResumedActivity" | sed 's/.*u0 //; s/ t[0-9]*}//')
    echo "t=$((i*2))s top=$top"
    shot "watch_$i"
    sleep 2
done
echo "== activity starts"
adb logcat -d -v time | grep -E "ActivityTaskManager.*(START|Displayed)|ActivityManager.*(START|Displayed)" | grep -i "com.fadcam" | head -40 | cut -c1-260
echo "script done"
