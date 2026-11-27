#!/usr/bin/env bash
# StackTest: in front or behind for a whole part and for a part in a stance, checked by drawing again. Pure Java.
# Usage:  bash tools/jvm-harness/run-avatar-stack.sh
set -u
cd "$(dirname "$0")/../.." || exit 1

OUT=tools/jvm-harness/out-avatar-stack
rm -rf "$OUT"; mkdir -p "$OUT"

javac -nowarn -encoding UTF-8 -d "$OUT" \
  $(find tools/jvm-harness/joybot-bench/shim/org/json -name '*.java') \
  $(find app/src/main/java/cc/joycreator/avatar/solve -name '*.java' ! -name 'BitmapPics.java') \
  app/src/main/java/cc/joycreator/avatar/file/Js.java \
  app/src/main/java/cc/joycreator/avatar/file/CharacterFile.java \
  app/src/main/java/cc/joycreator/avatar/file/Stances.java \
  app/src/main/java/cc/joycreator/avatar/file/Stack.java \
  app/src/main/java/cc/joycreator/avatar/perform/Walker.java \
  app/src/main/java/cc/joycreator/avatar/perform/DepthWalk.java \
  tools/jvm-harness/WalkerTest.java \
  tools/jvm-harness/StackTest.java || exit 1
java -Djava.awt.headless=true -cp "$OUT" StackTest
