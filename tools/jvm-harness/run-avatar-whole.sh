#!/usr/bin/env bash
# WholeDrawingTest: the whole-drawing door. Pure Java.
# Usage:  bash tools/jvm-harness/run-avatar-whole.sh
set -u
cd "$(dirname "$0")/../.." || exit 1

OUT=tools/jvm-harness/out-avatar-whole
rm -rf "$OUT"; mkdir -p "$OUT"

javac -nowarn -encoding UTF-8 -d "$OUT" \
  $(find tools/jvm-harness/joybot-bench/shim/org/json -name '*.java') \
  $(find app/src/main/java/cc/joycreator/avatar/solve -name '*.java' ! -name 'BitmapPics.java') \
  app/src/main/java/cc/joycreator/avatar/file/Js.java \
  app/src/main/java/cc/joycreator/avatar/file/CharacterFile.java \
  app/src/main/java/cc/joycreator/avatar/intake/WholeDrawing.java \
  app/src/main/java/cc/joycreator/avatar/perform/DepthWalk.java \
  app/src/main/java/cc/joycreator/avatar/perform/Walker.java \
  tools/jvm-harness/WalkerTest.java \
  tools/jvm-harness/WholeDrawingTest.java || exit 1
java -Djava.awt.headless=true -cp "$OUT" WholeDrawingTest
