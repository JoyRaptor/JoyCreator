#!/usr/bin/env bash
# ReshapeTest: reshape with pins against the pilot. Pure Java.
# Usage:  bash tools/jvm-harness/run-avatar-reshape.sh
set -u
cd "$(dirname "$0")/../.." || exit 1

OUT=tools/jvm-harness/out-avatar-reshape
rm -rf "$OUT"; mkdir -p "$OUT"

javac -nowarn -encoding UTF-8 -d "$OUT" \
  $(find tools/jvm-harness/joybot-bench/shim/org/json -name '*.java') \
  $(find app/src/main/java/cc/joycreator/avatar/solve -name '*.java' ! -name 'BitmapPics.java') \
  app/src/main/java/cc/joycreator/avatar/file/Js.java \
  app/src/main/java/cc/joycreator/avatar/file/CharacterFile.java \
  app/src/main/java/cc/joycreator/avatar/file/Stances.java \
  app/src/main/java/cc/joycreator/avatar/file/Reshape.java \
  app/src/main/java/cc/joycreator/avatar/perform/DepthWalk.java \n  app/src/main/java/cc/joycreator/avatar/perform/Walker.java \
  tools/jvm-harness/WalkerTest.java \
  tools/jvm-harness/ReshapeTest.java || exit 1
java -Djava.awt.headless=true -cp "$OUT" ReshapeTest
