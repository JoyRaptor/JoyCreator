#!/usr/bin/env bash
# TakeRecorderTest + InputsTest: recording over a take, and the hands and the life of the character. Pure Java.
# Usage:  bash tools/jvm-harness/run-avatar-take.sh
set -u
cd "$(dirname "$0")/../.." || exit 1

OUT=tools/jvm-harness/out-avatar-take
rm -rf "$OUT"; mkdir -p "$OUT"

javac -nowarn -encoding UTF-8 -d "$OUT" \
  $(find tools/jvm-harness/joybot-bench/shim/org/json -name '*.java') \
  $(find app/src/main/java/cc/joycreator/avatar/solve -name '*.java' ! -name 'BitmapPics.java') \
  app/src/main/java/cc/joycreator/avatar/file/Js.java \
  app/src/main/java/cc/joycreator/avatar/perform/Take.java \
  app/src/main/java/cc/joycreator/avatar/perform/TakeRecorder.java \
  app/src/main/java/cc/joycreator/avatar/perform/Inputs.java \
  app/src/main/java/cc/joycreator/avatar/perform/Walker.java \
  tools/jvm-harness/TakeRecorderTest.java \
  tools/jvm-harness/InputsTest.java || exit 1
java -cp "$OUT" TakeRecorderTest || exit 1
java -cp "$OUT" InputsTest
