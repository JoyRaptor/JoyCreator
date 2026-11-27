# Brush editor: hold a brush in the shelf to open its settings sheet; a whole-screen shot of each (owner BRUSH-UI section L).
# Starts JoyBrush directly (rooted shell; the activity is not exported): no intro, no lobby.
adb shell am start -n "$PKG/cc.joycreator.joybrush.android.JoyBrushActivity" >/dev/null
settle 10
shot canvas
dump canvas
python3 "$HERE_LIB/uia.py" list /tmp/ui.xml | head -30

open_shelf() { tap_text "Brush " >/dev/null 2>&1; settle 2; }
close_sheet() { tap_text "Done" >/dev/null 2>&1 || key 4; settle 2; }

# the shelf, once
open_shelf
shot shelf
dump shelf
python3 "$HERE_LIB/uia.py" list /tmp/ui.xml | head -40

# one brush per family: category, then the brush by its exact name; each held for about a second
for pair in "Oils|Oil round|oil_round" "Watercolour|Round|watercolour_round" "Pencils|HB|pencil_hb" "Inks & pens|Ink|ink_line" "Unibrush|Calligraphy|unibrush_calligraphy"; do
    IFS='|' read -r cat brush name <<< "$pair"
    tap_text "=$cat" >/dev/null 2>&1; settle 2
    shot "shelf_$name"
    if hold_text "=$brush" 1100 >/dev/null 2>&1; then :; fi
    settle 3
    shot "settings_$name"
    dump "settings_$name"
    close_sheet
    open_shelf
done
