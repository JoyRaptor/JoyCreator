# Explore: blank audio project, then what Add / Image offer without a file picker.
. "$HERE_LIB/lib_app.sh"
through_intro
settle 3
tap_text "Start one" >/dev/null 2>&1; settle 5
tap_text "Blank audio project" >/dev/null 2>&1; settle 10
shot project
dump project
python3 "$HERE_LIB/uia.py" list /tmp/ui.xml | head -40
for label in "Add" "Image"; do
    tap_text "=$label" >/dev/null 2>&1; settle 3
    shot "tap_$label"; dump "tap_$label"
    python3 "$HERE_LIB/uia.py" list /tmp/ui.xml | head -25
done
