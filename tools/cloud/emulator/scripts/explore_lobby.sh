# Explore: get through the intro to the lobby, shot at each step (writing the real scripts needs this map).
launch_app
settle 8
shot intro_1
for i in 1 2 3 4 5 6; do
    dump "step_$i"
    python3 "$HERE_LIB/uia.py" list /tmp/ui.xml | head -12
    for label in "Start creating" "Skip" "Not now" "Allow" "Continue" "Got it" "Done" "Next"; do
        if tap_text "$label" >/dev/null 2>&1; then echo "step $i: tapped $label"; break; fi
    done
    settle 4
    shot "after_step_$i"
done
adb shell dumpsys activity activities | grep -m3 -E "mResumedActivity|topResumedActivity"
