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
adb shell mkdir -p /sdcard/Documents/JoyCreatorTest
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
settle 6
tap_text "Eagerly" >/dev/null 2>&1; tap 789 1218; settle 8   # the dialog has no text node in the dump: by position too
shot audio_added
dump audio_added
python3 "$HERE_LIB/uia.py" list /tmp/ui.xml | head -40
# leave the editor by its back arrow (top left), then the lobby, the Library, and reopen the project
tap 48 48; settle 3
shot close_dialog
tap_text "Close & Save" >/dev/null 2>&1 || tap 755 1169; settle 5
shot after_back
dump after_back
python3 "$HERE_LIB/uia.py" list /tmp/ui.xml | head -30
# closing the editor lands on the folder step (the harness skipped it at first run): make the folder like choose_folder does
if tap_text "Make my Joy Creator folder" >/dev/null 2>&1; then
    settle 8; shot folder_picker_open
    tap_text "=Documents" >/dev/null 2>&1 || tap_text "Documents" >/dev/null 2>&1
    settle 3
    tap_text "JoyCreatorTest" >/dev/null 2>&1; settle 3
    tap_text "ALLOW ACCESS TO" >/dev/null 2>&1 || tap_text "Allow access to" >/dev/null 2>&1; settle 3
    tap_text "=ALLOW" >/dev/null 2>&1 || tap_text "ALLOW" >/dev/null 2>&1; settle 5
    shot folder_chosen
    tap_text "Start creating" >/dev/null 2>&1; settle 4
fi
shot lobby_after_folder
tap_text "Library" >/dev/null 2>&1; settle 4
shot library
dump library
python3 "$HERE_LIB/uia.py" list /tmp/ui.xml | head -30
tap 277 411; settle 9   # the Library card has no text node of its own
tap 789 1218 ; settle 1   # the waveform question may come again on reopen
shot reopened
dump reopened
python3 "$HERE_LIB/uia.py" list /tmp/ui.xml | head -40
# the clip is on its row: select it and open Clean
tap 540 1800; settle 2
shot clip_selected
tap_text "Clean" >/dev/null 2>&1; settle 3
shot clean_open
dump clean_open
python3 "$HERE_LIB/uia.py" list /tmp/ui.xml | head -50
