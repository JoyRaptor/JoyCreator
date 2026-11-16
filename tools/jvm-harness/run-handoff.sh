#!/usr/bin/env bash
# HandoffMovesTest: the hand-off show's running order and Joybot's path (FX-1), off device. Pure Java.
#
# Usage:  bash tools/jvm-harness/run-handoff.sh
set -u
cd "$(dirname "$0")/../.." || exit 1
OUT=tools/jvm-harness/out-handoff
rm -rf "$OUT"; mkdir -p "$OUT"
javac -nowarn -encoding UTF-8 -d "$OUT" \
  app/src/main/java/cc/joycreator/handoff/HandoffMoves.java \
  app/src/main/java/cc/joycreator/handoff/HandoffScript.java \
  tools/jvm-harness/handoff/cc/joycreator/handoff/HandoffTestDoor.java \
  tools/jvm-harness/HandoffMovesTest.java || exit 1
[ -f "$OUT/HandoffMovesTest.class" ] || { echo "no HandoffMovesTest class: compile did not run"; exit 1; }
java -cp "$OUT" HandoffMovesTest || exit 1
