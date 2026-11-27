# Puppet drawer on a seeded picture project: Selected and Recording scopes (the tool column must sit on the RIGHT with
# the rows clear of it; website lane fix, 3325ef7fa). Pin placement is checked too.
. "$HERE_LIB/lib_app.sh"
seed_picture_project || { echo "seed failed"; exit 1; }
# open the picture's drawer: hold the picture in the preview
hold 540 880 1100; settle 3
shot held
dump held
if ! tap_text "=Puppet" >/dev/null 2>&1; then tap_text "Puppet" >/dev/null 2>&1; fi
settle 3
shot puppet_selected
dump puppet_selected
python3 "$HERE_LIB/uia.py" list /tmp/ui.xml | head -30
# Recording scope
tap_text "Recording settings" >/dev/null 2>&1 || tap_text "RECORDING" >/dev/null 2>&1
settle 2
shot puppet_recording
dump puppet_recording
python3 "$HERE_LIB/uia.py" list /tmp/ui.xml | head -30
# a pin: Pin tool, one tap on the picture
tap_text "Selected settings" >/dev/null 2>&1; settle 1
tap_text "Pin tool" >/dev/null 2>&1; settle 1
tap 540 900; settle 2
shot pin_placed
python3 "$HERE_LIB/uia.py" count /tmp/ui.xml "Waist" | head -1
