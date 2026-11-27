#!/usr/bin/env bash
# SceneTest: the scene model, its file, its undo, its clock and camera lane against the pilot's numbers. Pure Java.
# Usage:  bash tools/jvm-harness/run-avatar-scene.sh
set -u
cd "$(dirname "$0")/../.." || exit 1

OUT=tools/jvm-harness/out-avatar-scene
rm -rf "$OUT"; mkdir -p "$OUT"

javac -nowarn -encoding UTF-8 -d "$OUT" \
  $(find tools/jvm-harness/joybot-bench/shim/org/json -name '*.java') \
  $(find app/src/main/java/cc/joycreator/avatar/scene -name '*.java') \
  app/src/main/java/cc/joycreator/avatar/file/Js.java \
  app/src/main/java/cc/joycreator/avatar/file/CharacterFile.java \
  app/src/main/java/cc/joycreator/avatar/file/FormatCheck.java \
  app/src/main/java/cc/joycreator/avatar/perform/Take.java \
  tools/jvm-harness/SceneTest.java || exit 1
java -Djava.awt.headless=true -cp "$OUT" SceneTest
