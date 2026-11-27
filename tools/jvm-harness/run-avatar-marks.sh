#!/usr/bin/env bash
# MarksTest: editing a head's landmarks (Avatar Studio's landmarks screen engine). Pure Java; no Gradle.
# Usage:  bash tools/jvm-harness/run-avatar-marks.sh
set -u
cd "$(dirname "$0")/../.." || exit 1

OUT=tools/jvm-harness/out-avatar-marks
rm -rf "$OUT"; mkdir -p "$OUT"

javac -nowarn -encoding UTF-8 -d "$OUT" \
  $(find tools/jvm-harness/joybot-bench/shim/org/json -name '*.java') \
  app/src/main/java/cc/joycreator/avatar/file/Js.java \
  app/src/main/java/cc/joycreator/avatar/file/CharacterFile.java \
  app/src/main/java/cc/joycreator/avatar/file/FormatCheck.java \
  app/src/main/java/cc/joycreator/avatar/file/Marks.java \
  tools/jvm-harness/MarksTest.java || exit 1
java -cp "$OUT" MarksTest
