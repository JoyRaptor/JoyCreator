#!/usr/bin/env bash
# WalkerTest: walking, jumping and going onto all fours on Topps, frame by frame against the pilot's own walker. Pure Java.
# Usage:  bash tools/jvm-harness/run-avatar-walk.sh
set -u
cd "$(dirname "$0")/../.." || exit 1

OUT=tools/jvm-harness/out-avatar-walk
rm -rf "$OUT"; mkdir -p "$OUT"

javac -nowarn -encoding UTF-8 -d "$OUT" \
  $(find tools/jvm-harness/joybot-bench/shim/org/json -name '*.java') \
  $(find app/src/main/java/cc/joycreator/avatar/solve -name '*.java' ! -name 'BitmapPics.java') \
  app/src/main/java/cc/joycreator/avatar/file/Js.java \
  app/src/main/java/cc/joycreator/avatar/file/CharacterFile.java \
  app/src/main/java/cc/joycreator/avatar/perform/Walker.java \
  app/src/main/java/cc/joycreator/avatar/perform/DepthWalk.java \
  tools/jvm-harness/WalkerTest.java || exit 1
java -Djava.awt.headless=true -cp "$OUT" WalkerTest
