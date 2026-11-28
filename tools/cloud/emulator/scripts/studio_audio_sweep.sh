# STU-4 audio checks on the emulator: an audio-only project (a blank audio project plus one generated sound),
# whether it reopens with its clip and length (STU-E1 work-loss check), then its Clean tab.
# Explores as it goes: every step photographs the screen and lists the controls.
. "$HERE_LIB/lib_app.sh"
# a 6 s test sound: 2 s tone, 2 s silence, 2 s tone (44.1 kHz mono 16 bit WAV)
python3 - <<'PY'
import math, struct, wave
w = wave.open('/tmp/stu4_gaps_test.wav', 'wb'); w.setnchannels(1); w.setsampwidth(2); w.setframerate(44100)
fr = []
for i in range(44100 * 6):
    t = i / 44100.0
    on = (t < 2) or (t >= 4)
    fr.append(int(12000 * math.sin(2 * math.pi * 440 * t)) if on else 0)
w.writeframes(struct.pack('<%dh' % len(fr), *fr)); w.close()
PY
adb push /tmp/stu4_gaps_test.wav /sdcard/Download/stu4_gaps_test.wav >/dev/null
through_intro
settle 3
tap_text "Start one" >/dev/null 2>&1 || tap_text "New" >/dev/null 2>&1
settle 4
tap_text "Blank audio project" >/dev/null 2>&1 || { shot no_blank; exit 1; }
settle 10
adb shell pm enable com.android.documentsui >/dev/null 2>&1 || true
shot empty_project
tap_text "=Add" >/dev/null 2>&1; settle 3
shot add_sheet
dump add_sheet
python3 "$HERE_LIB/uia.py" list /tmp/ui.xml | head -30
tap_text "Audio" >/dev/null 2>&1; settle 5
shot audio_picker
dump audio_picker
python3 "$HERE_LIB/uia.py" list /tmp/ui.xml | head -25
if ! python3 "$HERE_LIB/uia.py" count /tmp/ui.xml "stu4_gaps_test" | grep -qv '^0$'; then
    tap_text "Show roots" >/dev/null 2>&1; settle 2
    tap_text "=Downloads" >/dev/null 2>&1 || tap_text "Downloads" >/dev/null 2>&1; settle 3
    dump audio_downloads
fi
tap_text "stu4_gaps_test" >/dev/null 2>&1 || { shot sound_not_found; exit 1; }
settle 8
shot audio_added
dump audio_added
python3 "$HERE_LIB/uia.py" list /tmp/ui.xml | head -40
# leave the editor (Back saves) and look at the lobby / library, then reopen the project
key KEYCODE_BACK; settle 4
shot after_back
dump after_back
python3 "$HERE_LIB/uia.py" list /tmp/ui.xml | head -30
key KEYCODE_BACK; settle 3
shot lobby_again
tap_text "Library" >/dev/null 2>&1; settle 4
shot library
dump library
python3 "$HERE_LIB/uia.py" list /tmp/ui.xml | head -30
tap_text "Untitled" >/dev/null 2>&1; settle 8
shot reopened
dump reopened
python3 "$HERE_LIB/uia.py" list /tmp/ui.xml | head -40
