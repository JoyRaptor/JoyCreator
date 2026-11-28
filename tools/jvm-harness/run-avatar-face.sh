#!/usr/bin/env bash
# The phone room's face (cc.joycreator.avatar.face: FaceDrive, FaceWalk) against the web pilot's behaviour, with the
# phone's own face engine on a scripted head. Pure Java + gson + the androidx annotation stubs (as run-face.sh).
#
# Usage:  bash tools/jvm-harness/run-avatar-face.sh           # the checks
#         bash tools/jvm-harness/run-avatar-face.sh --proof   # plant each fault, expect a FAIL for every one
set -u
cd "$(dirname "$0")/../.." || exit 1

if [ "${1:-}" = "--proof" ]; then
  exec python -B tools/jvm-harness/avatar_face_plants.py
fi

SEP=";"; case "$(uname -s)" in Linux*|Darwin*) SEP=":";; esac     # (the cloud runs Linux)
GSON=$(find "$HOME/.gradle/caches/modules-2" -name 'gson-2*.jar' 2>/dev/null | head -1 | sed 's|^/c/|C:/|')
if [ -z "$GSON" ]; then echo "no gson jar in the gradle cache"; exit 1; fi

OUT=${FACE_OUT:-tools/jvm-harness/out-avatar-face}
PLANT=${FACE_PLANT_DIR:-}
rm -rf "$OUT"; mkdir -p "$OUT"
ARGS=$(mktemp); RUNARGS=$(mktemp)
trap 'rm -f "$ARGS" "$RUNARGS"' EXIT
SP="tools/jvm-harness/stubs${SEP}app/src/main/java${SEP}studiokit/src/main/java"
if [ -n "$PLANT" ]; then SP="$PLANT${SEP}$SP"; fi
{ echo "-nowarn"; echo "-encoding UTF-8"; echo "-d $OUT";
  printf -- '-cp "%s"\n' "$GSON";
  printf -- '-sourcepath "%s"\n' "$SP"; } > "$ARGS"
printf -- '-cp "%s%s%s"\n' "$OUT" "$SEP" "$GSON" > "$RUNARGS"

# (the room's hands, Inputs, read character settings: the fuller org.json shim, named so it wins over the stubs' small one)
javac @"$ARGS" $(find tools/jvm-harness/joybot-bench/shim/org/json -name '*.java') tools/jvm-harness/AvatarFaceTest.java tools/jvm-harness/AvatarFaceTakeTest.java || exit 2
[ -f "$OUT/AvatarFaceTest.class" ] && [ -f "$OUT/AvatarFaceTakeTest.class" ] || { echo "no class file: the compile did not run"; exit 2; }
# the room's face stays harness-runnable: no Android in its pure files
if grep -lE "^import android\." app/src/main/java/cc/joycreator/avatar/face/Face{Drive,Walk}.java; then
  echo "FAIL: an Android import leaked into the room's face logic"; exit 1
fi
# the emulator test plays the same take: its copy in the test assets must be the same file
cmp -s tasks/avatar/golden/face-take-20261127.track.json app/src/androidTest/assets/avatar-face/face-take-20261127.track.json || { echo "FAIL: the emulator's copy of his face take differs from tasks/avatar/golden"; exit 1; }
java @"$RUNARGS" AvatarFaceTest; RC=$?
# the owner's real face take (tasks/avatar/golden/face-take-20261127.track.json), every move through the room as it runs
java @"$RUNARGS" AvatarFaceTakeTest || RC=1
exit $RC
