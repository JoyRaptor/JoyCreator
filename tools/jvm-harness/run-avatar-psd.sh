#!/usr/bin/env bash
# PsdReaderTest + PsdDoorTest: the layered-file door (Avatar Studio) on hand-written files. Pure Java; no Gradle.
# Usage:  bash tools/jvm-harness/run-avatar-psd.sh
set -u
cd "$(dirname "$0")/../.." || exit 1

OUT=tools/jvm-harness/out-avatar-psd
rm -rf "$OUT"; mkdir -p "$OUT"

javac -nowarn -encoding UTF-8 -d "$OUT" \
  $(find tools/jvm-harness/joybot-bench/shim/org/json -name '*.java') \
  app/src/main/java/cc/joycreator/avatar/file/Js.java \
  app/src/main/java/cc/joycreator/avatar/file/CharacterFile.java \
  app/src/main/java/cc/joycreator/avatar/file/CharacterDoc.java \
  app/src/main/java/cc/joycreator/avatar/file/FormatCheck.java \
  app/src/main/java/cc/joycreator/avatar/intake/PsdReader.java \
  app/src/main/java/cc/joycreator/avatar/intake/RoleGuess.java \
  app/src/main/java/cc/joycreator/avatar/intake/PsdDoor.java \
  tools/jvm-harness/PsdReaderTest.java \
  tools/jvm-harness/PsdDoorTest.java || exit 1
java -cp "$OUT" PsdReaderTest || exit 1
java -cp "$OUT" PsdDoorTest
