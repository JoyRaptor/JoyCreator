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
# key the selected pin with the diamond in the drawer header (custom drawn: by position, 1080x2220 frame)
tap 837 135
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
# a live take: a slow drag of the second pin (the lower pin, at 540,1250)
swipe 540 1250 760 1080 2500; settle 3
shot after_drag
dump after_drag
python3 "$HERE_LIB/uia.py" list /tmp/ui.xml | head -40
# scrub back to the start (tap the timeline's left end) and photograph the posed frame, then play
swipe 100 1760 40 1760 300; settle 2
shot at_zero
tap 542 1672; settle 3
shot playing
settle 2
shot playing2
