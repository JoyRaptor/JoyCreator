# App-level helpers for the phone scripts (sourced after lib.sh).

# through_intro: from a fresh install to the lobby (the intro's "Start creating" and "Continue" pages).
through_intro() {
    launch_app
    settle 8
    local i label
    for i in 1 2 3 4 5 6 7 8; do
        for label in "Start creating" "Continue" "Skip" "Not now" "Allow" "Got it"; do
            tap_text "$label" >/dev/null 2>&1 && break
        done
        settle 3
        adb shell dumpsys activity activities | grep -m1 -E "mResumedActivity|topResumedActivity" | grep -q "com.fadcam.MainActivity" && break
    done
}

# make_test_png <path>: a 512x512 PNG (a coloured star-ish blob on transparent) with only the standard library
make_test_png() {
    python3 - "$1" <<'PY'
import struct, sys, zlib
n = 512
rows = []
for y in range(n):
    row = bytearray([0])
    for x in range(n):
        dx, dy = x - n / 2, y - n / 2
        r2 = (dx * dx + dy * dy) ** 0.5
        a = 255 if r2 < n * 0.42 * (0.8 + 0.2 * abs(((x * 7 + y * 3) % 64) - 32) / 32) else 0
        row += bytes([int(80 + 150 * x / n), int(60 + 160 * y / n), 200, a])
    rows.append(bytes(row))
raw = b"".join(rows)
def chunk(t, d):
    c = struct.pack(">I", len(d)) + t + d
    return c + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", n, n, 8, 6, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b"")
open(sys.argv[1], "wb").write(png)
PY
}

# seed_picture_project: from a fresh install to a Studio project that holds one picture layer ("joy_test_pic.png"),
# selected, with the Studio toolbar showing. Reusable by any check that needs "a picture in a project".
#   steps: intro -> lobby -> Start one -> Blank audio project -> turn the system picker back on -> Add -> Image as new
#   layer -> pick the PNG in Downloads. Returns 1 (after a shot and dump of where it stopped) if a step is missing.
seed_picture_project() {
    make_test_png /tmp/joy_test_pic.png
    adb push /tmp/joy_test_pic.png /sdcard/Download/joy_test_pic.png >/dev/null
    through_intro
    settle 3
    tap_text "Start one" >/dev/null 2>&1 || tap_text "New" >/dev/null 2>&1
    settle 4
    tap_text "Blank audio project" >/dev/null 2>&1 || { shot seed_no_blank; return 1; }
    settle 10
    # the picker is needed from here on (it was switched off only to skip the first-run folder step)
    adb shell pm enable com.android.documentsui >/dev/null 2>&1 || true
    tap_text "=Add" >/dev/null 2>&1; settle 3
    tap_text "Image as new layer" >/dev/null 2>&1 || { shot seed_no_image_option; return 1; }
    settle 5
    shot seed_picker
    dump seed_picker
    python3 "$HERE_LIB/uia.py" list /tmp/ui.xml | head -25
    # the picker may open on Recent: go to Downloads if the file is not in sight
    if ! python3 "$HERE_LIB/uia.py" count /tmp/ui.xml "joy_test_pic" | grep -qv '^0$'; then
        tap_text "Show roots" >/dev/null 2>&1; settle 2
        tap_text "=Downloads" >/dev/null 2>&1 || tap_text "Downloads" >/dev/null 2>&1; settle 3
        dump seed_downloads
    fi
    tap_text "joy_test_pic" >/dev/null 2>&1 || { shot seed_png_not_found; return 1; }
    settle 8
    shot seed_done
    return 0
}

# choose_folder: give the app a Joy Creator folder on the emulator (Settings > Joy Creator folder > Make my Joy Creator folder).
# The system picker opens at the storage root, which Android will not hand out, so the Documents folder is entered first.
# Needs the system file picker enabled and the first-run intro already passed. Returns 1 (after a shot) if a step is missing.
choose_folder() {
    adb shell pm enable com.android.documentsui >/dev/null 2>&1 || true
    tap_text "Setup" >/dev/null 2>&1; settle 4
    tap_text "Joy Creator folder" >/dev/null 2>&1; settle 5
    tap_text "Make my Joy Creator folder" >/dev/null 2>&1 || { shot folder_no_button; return 1; }
    settle 5
    tap_text "=Documents" >/dev/null 2>&1 || tap_text "Documents" >/dev/null 2>&1 || { shot folder_no_documents; return 1; }
    settle 3
    shot folder_in_documents
    tap_text "ALLOW ACCESS TO" >/dev/null 2>&1 || tap_text "Allow access to" >/dev/null 2>&1; settle 3
    tap_text "=ALLOW" >/dev/null 2>&1 || tap_text "ALLOW" >/dev/null 2>&1; settle 5
    shot folder_chosen
    return 0
}
