"""Build deliberately hostile projects to see what the loader survives.

Every case here is something a real edit session can actually produce -- a clip trimmed
to nothing, a keyframe pair written at the same instant by a fast slider drag, a pan
value from a future build. None of it is random fuzzing; the point is that each one has
a plausible path to existing on a real user's phone.
"""
import copy
import io
import json
import os

tmpl = json.load(io.open("tasks/a6/night/tmpl.json", encoding="utf-8"))
out_dir = "tasks/a6/night/adv"
os.makedirs(out_dir, exist_ok=True)

AUDIO_URI = "content://com.android.providers.downloads.documents/document/msf%3A1000119061"


def audio_clip(**over):
    ac = {
        "id": "adv-audio-0001",
        "sourceUri": AUDIO_URI,
        "sourceDurationMs": 6023,
        "inPointMs": 0,
        "outPointMs": 6023,
        "offsetMs": 0,
        "volumeLevel": 1.0,
        "pan": 0.0,
        "muted": False,
        "label": "Adv",
    }
    ac.update(over)
    return ac


cases = {}

# 1. Truly empty: no clips, no audio. The loader's emptiness gate decides whether this
#    reopens at all -- the exact bug that made audio-only projects unopenable.
d = copy.deepcopy(tmpl)
d["timeline"]["clips"] = []
d["timeline"]["audioClips"] = []
if "layers" in d["timeline"]:
    d["timeline"]["layers"]["masterTrack"] = {"id": "master", "kind": "MASTER",
                                              "name": "Master", "items": []}
    d["timeline"]["layers"]["audioTracks"] = []
cases["empty"] = d

# 2. Audio-only, no video spine. This is what a podcast project IS.
d = copy.deepcopy(tmpl)
d["timeline"]["clips"] = []
d["timeline"]["audioClips"] = [audio_clip()]
cases["audio_only"] = d

# 3. Zero-length audio clip: trimmed until in == out. Division by duration is everywhere
#    (envelope fractions, fade math, the duck curve), so this is the classic /0.
d = copy.deepcopy(tmpl)
d["timeline"]["audioClips"] = [audio_clip(outPointMs=0)]
cases["zero_length_audio"] = d

# 4. Negative offset -- a clip dragged before time zero.
d = copy.deepcopy(tmpl)
d["timeline"]["audioClips"] = [audio_clip(offsetMs=-5000)]
cases["negative_offset"] = d

# 5. Keyframes out of order, duplicated at one instant, and out of range. A fast slider
#    drag writes many keyframes quickly; a sort that assumes order will read garbage.
d = copy.deepcopy(tmpl)
d["timeline"]["audioClips"] = [audio_clip(volumeKeyframes=[
    {"timeMs": 3000, "volume": 0.5},
    {"timeMs": 0, "volume": 1.0},
    {"timeMs": 3000, "volume": 0.2},
    {"timeMs": -100, "volume": 2.0},
    {"timeMs": 999999, "volume": -1.0},
])]
cases["bad_keyframes"] = d

# 6. Out-of-range pan and volume, as a future build or a corrupt write might leave.
d = copy.deepcopy(tmpl)
d["timeline"]["audioClips"] = [audio_clip(pan=9.0, volumeLevel=-3.0)]
cases["out_of_range"] = d

# 7. Audio clip pointing at a URI that does not resolve. Storage moves; SD cards leave.
d = copy.deepcopy(tmpl)
d["timeline"]["audioClips"] = [audio_clip(sourceUri="content://does.not.exist/nope/1")]
cases["dead_uri"] = d

base = "22220000-0000-4000-8000-0000000000"
for i, (name, doc) in enumerate(sorted(cases.items()), start=1):
    pid = base + "%02d" % i
    doc["id"] = pid
    doc["name"] = "ADV " + name
    p = os.path.join(out_dir, pid + ".json")
    io.open(p, "w", encoding="utf-8").write(json.dumps(doc))
    print("%s  %s" % (pid, name))

# ─────────────────────────────────────────────────────────────────────────────────────
# HOW TO RUN THESE ON A DEVICE, AND THE TRAP THAT MAKES IT LOOK LIKE THEY PASSED
#
#   python tasks/adversarial/make_hostile_projects.py        # writes the json files
#   adb push <file> /sdcard/adv_<id>.json
#   adb shell "run-as com.fadcam.beta mkdir -p files/faditor/projects/<id> \
#              && run-as com.fadcam.beta sh -c 'cat /sdcard/adv_<id>.json > \
#                 files/faditor/projects/<id>/project.json'"
#
# Launch with monkey, NOT with `am start -n .../FaditorEditorActivity`:
#
#   adb shell "monkey -p com.fadcam.beta -c android.intent.category.LAUNCHER 1"
#
# FaditorEditorActivity is NOT exported. `am start` against it returns
#   SecurityException: Permission Denial: ... not exported from uid 10320
# and the shell prints that to STDERR while the activity never starts. A loop that only
# checks "did the process crash?" then reports a clean pass for every case, because
# nothing ever ran. That happened here on 2026-08-24: seven hostile projects all read
# "alive=NO fatal=0", which looks like seven graceful refusals and was actually seven
# launches that never happened.
#
# Any device test must prove it RAN before it can prove anything passed. Check the
# process is alive, or that a marker you expect appears in logcat -- absence of a crash
# is not evidence of correct behaviour when absence of a launch produces the same
# reading.
#
# What HAS been verified with these fixtures (2026-08-24): with all seven installed,
# the app launches and stays alive, so the project LIST tolerates every one of them --
# including the empty project, the dead URI, and the out-of-range values. Opening each
# one still needs a UI path.
