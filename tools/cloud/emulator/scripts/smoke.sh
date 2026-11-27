# Smoke: does the app start on the emulator, and what is the first screen? (Sourced by run.sh.)
launch_app
settle 12
shot first_launch
dump first_launch
python3 "$HERE_LIB/uia.py" list /tmp/ui.xml | head -40
adb shell dumpsys activity activities | grep -m3 -E "mResumedActivity|topResumedActivity"
