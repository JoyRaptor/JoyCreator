#!/usr/bin/env bash
# StancesTest: stances and all fours worked out from the art, against the pilot's own for Topps. Pure Java; no Gradle.
# Usage:  bash tools/jvm-harness/run-avatar-stances.sh
set -u
cd "$(dirname "$0")/../.." || exit 1

OUT=tools/jvm-harness/out-avatar-stances
rm -rf "$OUT"; mkdir -p "$OUT"

javac -nowarn -encoding UTF-8 -d "$OUT" \
  $(find tools/jvm-harness/joybot-bench/shim/org/json -name '*.java') \
  app/src/main/java/cc/joycreator/avatar/file/Js.java \
  app/src/main/java/cc/joycreator/avatar/file/CharacterFile.java \
  app/src/main/java/cc/joycreator/avatar/file/FormatCheck.java \
  app/src/main/java/cc/joycreator/avatar/file/Stances.java \
  app/src/main/java/cc/joycreator/avatar/file/Ball.java \
  app/src/main/java/cc/joycreator/avatar/intake/RoleGuess.java \
  app/src/main/java/cc/joycreator/avatar/intake/PsdReader.java \
  app/src/main/java/cc/joycreator/avatar/intake/PsdDoor.java \
  app/src/main/java/cc/joycreator/avatar/intake/PsdUpdate.java \
  tools/jvm-harness/StancesTest.java || exit 1
java -cp "$OUT" StancesTest
