#!/usr/bin/env bash
# AvatarDepthWalkTest: walking toward the camera and away (Walker + DepthWalk) on Topps, frame by frame against the pilot's own
# walker (tasks/avatar/golden/topps-walk-depth.json); and walking with your face in depth through the room's adapter. Pure Java.
# Usage:  bash tools/jvm-harness/run-avatar-depth.sh            # the checks
#         bash tools/jvm-harness/run-avatar-depth.sh --proof    # plant each fault, expect a FAIL for every one
set -u
cd "$(dirname "$0")/../.." || exit 1

if [ "${1:-}" = "--proof" ]; then
  exec python -B tools/jvm-harness/avatar_depth_plants.py
fi

OUT=${DEPTH_OUT:-tools/jvm-harness/out-avatar-depth}
SRC=app/src/main/java/cc/joycreator/avatar/perform
PL=${DEPTH_PLANT_DIR:-}
W=$SRC/Walker.java; D=$SRC/DepthWalk.java
FS=app/src/main/java/cc/joycreator/avatar/face; L=$FS/WalkerLegs.java; F=$FS/FaceWalk.java
[ -n "$PL" ] && [ -f "$PL/Walker.java" ] && W=$PL/Walker.java
[ -n "$PL" ] && [ -f "$PL/DepthWalk.java" ] && D=$PL/DepthWalk.java
[ -n "$PL" ] && [ -f "$PL/WalkerLegs.java" ] && L=$PL/WalkerLegs.java
[ -n "$PL" ] && [ -f "$PL/FaceWalk.java" ] && F=$PL/FaceWalk.java
rm -rf "$OUT"; mkdir -p "$OUT"

javac -nowarn -encoding UTF-8 -d "$OUT" \
  $(find tools/jvm-harness/joybot-bench/shim/org/json -name '*.java') \
  $(find app/src/main/java/cc/joycreator/avatar/solve -name '*.java' ! -name 'BitmapPics.java') \
  app/src/main/java/cc/joycreator/avatar/file/Js.java \
  app/src/main/java/cc/joycreator/avatar/file/CharacterFile.java \
  "$W" "$D" "$L" "$F" \
  tools/jvm-harness/AvatarDepthWalkTest.java || exit 2
java -Djava.awt.headless=true -cp "$OUT" AvatarDepthWalkTest
