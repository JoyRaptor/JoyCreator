#!/usr/bin/env bash
# StickTest: the joystick, its speed curve, its gestures and what each act does. Pure Java.
# Usage:  bash tools/jvm-harness/run-avatar-stick.sh
set -u
cd "$(dirname "$0")/../.." || exit 1

OUT=tools/jvm-harness/out-avatar-stick
rm -rf "$OUT"; mkdir -p "$OUT"

javac -nowarn -encoding UTF-8 -d "$OUT" \
  $(find tools/jvm-harness/joybot-bench/shim/org/json -name '*.java') \
  $(find app/src/main/java/cc/joycreator/avatar/solve -name '*.java' ! -name 'BitmapPics.java') \
  app/src/main/java/cc/joycreator/avatar/file/Js.java \
  app/src/main/java/cc/joycreator/avatar/file/CharacterFile.java \
  app/src/main/java/cc/joycreator/avatar/perform/Take.java \
  app/src/main/java/cc/joycreator/avatar/perform/TakeRecorder.java \
  app/src/main/java/cc/joycreator/avatar/perform/Inputs.java \
  app/src/main/java/cc/joycreator/avatar/perform/Walker.java \
  app/src/main/java/cc/joycreator/avatar/perform/DepthWalk.java \
  app/src/main/java/cc/joycreator/avatar/face/FaceDrive.java \
  app/src/main/java/cc/joycreator/avatar/perform/Stick.java \
  tools/jvm-harness/StickTest.java || exit 1
java -Djava.awt.headless=true -cp "$OUT" StickTest
