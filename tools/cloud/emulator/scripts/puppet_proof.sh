# ANI-1 puppet proof on a seeded picture project: place pins, key a pin, drag a pin (a live take), replay it.
# Explores as it goes: every step photographs the screen and lists the controls, so a missed needle is visible.
. "$HERE_LIB/lib_app.sh"
seed_picture_project || { echo "seed failed"; exit 1; }
hold 540 880 1100; settle 3
shot held
tap 718 39   # the drawer's person-icon tab is the Puppet tab (custom drawn, no text node)
settle 3
shot puppet_open
dump puppet_open
python3 "$HERE_LIB/uia.py" list /tmp/ui.xml | head -60
# pins: Pin tool, then taps on the picture (the drawer grows after the first pin: shoot between taps)
tap_text "Pin tool" >/dev/null 2>&1; settle 1
tap 540 1000; settle 2
shot pin1
dump pin1
python3 "$HERE_LIB/uia.py" list /tmp/ui.xml | head -60
tap 540 1250; settle 2
shot pin2
dump pin2
python3 "$HERE_LIB/uia.py" list /tmp/ui.xml | head -60
# key the selected pin with the diamond
tap_text "Key" >/dev/null 2>&1 || tap_text "diamond" >/dev/null 2>&1 || tap_text "Add key" >/dev/null 2>&1
settle 2
shot keyed
dump keyed
python3 "$HERE_LIB/uia.py" list /tmp/ui.xml | head -60
# close the drawer (double-tap on the picture closes it), then pose: tap the clip, tap the puppet badge, drag a pin
key KEYCODE_BACK; settle 2
shot drawer_closed
dump drawer_closed
python3 "$HERE_LIB/uia.py" list /tmp/ui.xml | head -60
tap 540 700; settle 2
tap_text "pins" >/dev/null 2>&1 || tap_text "Puppet" >/dev/null 2>&1
settle 2
shot badge
# a live take: a slow drag of the pin placed second
swipe 540 1250 700 1150 1500; settle 3
shot after_drag
dump after_drag
python3 "$HERE_LIB/uia.py" list /tmp/ui.xml | head -60
# replay: a second drag and a frame later
swipe 540 1250 380 1300 1500; settle 3
shot after_drag2
