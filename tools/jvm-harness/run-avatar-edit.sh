#!/usr/bin/env bash
# RoomEditTest: Avatar Studio's room edits (named steps, one Undo press each). Pure Java plus stand-ins; no Gradle.
# Usage:  bash tools/jvm-harness/run-avatar-edit.sh
set -u
cd "$(dirname "$0")/../.." || exit 1

OUT=tools/jvm-harness/out-avatar-edit
rm -rf "$OUT"; mkdir -p "$OUT"

javac -nowarn -encoding UTF-8 -d "$OUT" \
  tools/jvm-harness/stubs/android/util/Log.java \
  $(find tools/jvm-harness/joybot-bench/shim/org/json -name '*.java') \
  app/src/main/java/cc/joycreator/avatar/file/Js.java \
  app/src/main/java/cc/joycreator/avatar/file/CharacterFile.java \
  app/src/main/java/cc/joycreator/avatar/file/CharacterDoc.java \
  app/src/main/java/cc/joycreator/avatar/ui/RoomEdit.java \
  tools/jvm-harness/RoomEditTest.java || exit 1
java -cp "$OUT" RoomEditTest
