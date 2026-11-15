#!/usr/bin/env bash
# JoybotFlightTest: Joybot's arrival flight (BOT-5), off device. Pure Java: only the annotation stubs.
#
# Usage:  bash tools/jvm-harness/run-joybot-flight.sh
set -u
cd "$(dirname "$0")/../.." || exit 1
OUT=tools/jvm-harness/out-joybot-flight
rm -rf "$OUT"; mkdir -p "$OUT"
javac -nowarn -encoding UTF-8 -d "$OUT" -sourcepath "tools/jvm-harness/stubs" \
  app/src/main/java/com/fadcam/ui/lobby/arrival/Flight.java tools/jvm-harness/JoybotFlightTest.java || exit 1
[ -f "$OUT/JoybotFlightTest.class" ] || { echo "no JoybotFlightTest class: compile did not run"; exit 1; }
java -cp "$OUT" JoybotFlightTest || exit 1
