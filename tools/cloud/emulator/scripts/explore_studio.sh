# Explore: from the lobby into a Studio project (demo project / New), shot each step, to learn how to reach a picture.
. "$HERE_LIB/lib_app.sh"
through_intro
settle 3
shot lobby
dump lobby
for label in "Start one" "demo project" "New"; do
    if tap_text "$label" >/dev/null 2>&1; then echo "tapped $label"; settle 8; shot "after_$(echo $label | tr -c 'A-Za-z' _)"; dump "after_$(echo $label | tr -c 'A-Za-z' _)"; python3 "$HERE_LIB/uia.py" list /tmp/ui.xml | head -25; break; fi
done
adb shell dumpsys activity activities | grep -m2 -E "mResumedActivity|topResumedActivity"
# the Studio toolbar: scroll it and list what is there
swipe 900 2100 150 2100 300; settle 1; shot toolbar_scrolled; dump toolbar
python3 "$HERE_LIB/uia.py" list /tmp/ui.xml | head -30
