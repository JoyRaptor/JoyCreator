#!/usr/bin/env bash
# StancesTest + PinGuessTest: stances, all fours, the round head and pins guessed from the pixels, each against what the
# pilot's own code gives for Topps. Pure Java (the PC decodes the pictures); no Gradle.
# Usage:  bash tools/jvm-harness/run-avatar-stances.sh
set -u
cd "$(dirname "$0")/../.." || exit 1

OUT=tools/jvm-harness/out-avatar-stances
rm -rf "$OUT"; mkdir -p "$OUT"

javac -nowarn -encoding UTF-8 -d "$OUT" \
  $(find tools/jvm-harness/joybot-bench/shim/org/json -name '*.java') \
  app/src/main/java/cc/joycreator/avatar/solve/Pics.java \
  app/src/main/java/cc/joycreator/avatar/file/Js.java \
  app/src/main/java/cc/joycreator/avatar/file/CharacterFile.java \
  app/src/main/java/cc/joycreator/avatar/file/FormatCheck.java \
  app/src/main/java/cc/joycreator/avatar/file/Stances.java \
  app/src/main/java/cc/joycreator/avatar/file/Ball.java \
  app/src/main/java/cc/joycreator/avatar/file/PinGuess.java \
  app/src/main/java/cc/joycreator/avatar/intake/RoleGuess.java \
  app/src/main/java/cc/joycreator/avatar/intake/PsdReader.java \
  app/src/main/java/cc/joycreator/avatar/intake/PsdDoor.java \
  app/src/main/java/cc/joycreator/avatar/intake/PsdUpdate.java \
  tools/jvm-harness/StancesTest.java \
  tools/jvm-harness/PinGuessTest.java || exit 1
java -Djava.awt.headless=true -cp "$OUT" StancesTest || exit 1
java -Djava.awt.headless=true -cp "$OUT" PinGuessTest
