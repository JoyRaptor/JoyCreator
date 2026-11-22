#!/usr/bin/env bash
# FACE-1: the face engine (com.fadcam.ui.faditor.avatar.face) and its seam in the tracking pipeline,
# off the phone, in seconds. Pure Java + gson + the androidx annotation stubs: no Android classpath.
#
# Usage:  bash tools/jvm-harness/run-face.sh           # the checks
#         bash tools/jvm-harness/run-face.sh --proof   # plant each fault, expect a FAIL for every one
set -u
cd "$(dirname "$0")/../.." || exit 1

if [ "${1:-}" = "--proof" ]; then
  exec python -B tools/jvm-harness/face_plants.py
fi

GSON=$(find "$HOME/.gradle/caches/modules-2" -name 'gson-2*.jar' 2>/dev/null | head -1 | sed 's|^/c/|C:/|')
if [ -z "$GSON" ]; then echo "no gson jar in the gradle cache"; exit 1; fi

OUT=${FACE_OUT:-tools/jvm-harness/out-face}
PLANT=${FACE_PLANT_DIR:-}
rm -rf "$OUT"; mkdir -p "$OUT"
ARGS=$(mktemp); RUNARGS=$(mktemp)
trap 'rm -f "$ARGS" "$RUNARGS"' EXIT
SP="tools/jvm-harness/stubs;app/src/main/java;studiokit/src/main/java"
if [ -n "$PLANT" ]; then SP="$PLANT;$SP"; fi
{ echo "-nowarn"; echo "-encoding UTF-8"; echo "-d $OUT";
  printf -- '-cp "%s"\n' "$GSON";
  printf -- '-sourcepath "%s"\n' "$SP"; } > "$ARGS"
printf -- '-cp "%s;%s"\n' "$OUT" "$GSON" > "$RUNARGS"

javac @"$ARGS" tools/jvm-harness/FaceEngineTest.java || exit 2
[ -f "$OUT/FaceEngineTest.class" ] || { echo "no class file: the compile did not run"; exit 2; }

# The engine must stay harness-runnable: no Android in the face package's pure files.
if grep -lE "^import android\." app/src/main/java/com/fadcam/ui/faditor/avatar/face/Face{Signals,Tuning,Calibration,Mapper,Stage,Follower,Pass}.java; then
  echo "FAIL: an Android import leaked into the face engine"; exit 1
fi
java @"$RUNARGS" FaceEngineTest
