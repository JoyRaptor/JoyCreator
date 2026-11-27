#!/usr/bin/env bash
# SheetCutterTest: the sheet-of-parts door on a made-up sheet, and what the cut drawings become. Pure Java.
# Usage:  bash tools/jvm-harness/run-avatar-sheet.sh
set -u
cd "$(dirname "$0")/../.." || exit 1

OUT=tools/jvm-harness/out-avatar-sheet
rm -rf "$OUT"; mkdir -p "$OUT"

javac -nowarn -encoding UTF-8 -d "$OUT" \
  $(find tools/jvm-harness/joybot-bench/shim/org/json -name '*.java') \
  app/src/main/java/cc/joycreator/avatar/file/Js.java \
  app/src/main/java/cc/joycreator/avatar/file/CharacterFile.java \
  app/src/main/java/cc/joycreator/avatar/file/LooseCharacter.java \
  app/src/main/java/cc/joycreator/avatar/intake/SheetCutter.java \
  app/src/main/java/cc/joycreator/avatar/intake/SheetParts.java \
  tools/jvm-harness/SheetCutterTest.java || exit 1
java -cp "$OUT" SheetCutterTest
