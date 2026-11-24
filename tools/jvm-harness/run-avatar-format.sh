#!/usr/bin/env bash
# CharacterFormatTest: Avatar Studio's shared file format (CharacterFile + FormatCheck) on the same files and the same
# planted faults as `node tasks/avatar/format-tests/run.mjs`. Pure Java plus the PC stand-in for org.json; no Gradle.
#
# Usage:  bash tools/jvm-harness/run-avatar-format.sh
set -u
cd "$(dirname "$0")/../.." || exit 1

OUT=tools/jvm-harness/out-avatar-format
rm -rf "$OUT"; mkdir -p "$OUT"

javac -nowarn -encoding UTF-8 -d "$OUT" \
  $(find tools/jvm-harness/joybot-bench/shim/org/json -name '*.java') \
  app/src/main/java/cc/joycreator/avatar/file/Js.java \
  app/src/main/java/cc/joycreator/avatar/file/CharacterFile.java \
  app/src/main/java/cc/joycreator/avatar/file/FormatCheck.java \
  tools/jvm-harness/CharacterFormatTest.java || exit 1
java -cp "$OUT" CharacterFormatTest
