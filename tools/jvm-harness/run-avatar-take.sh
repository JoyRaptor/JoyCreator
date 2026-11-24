#!/usr/bin/env bash
# TakeRecorderTest: recording over a take, punch-in and punch-out, take back a pass. Pure Java; no Gradle.
# Usage:  bash tools/jvm-harness/run-avatar-take.sh
set -u
cd "$(dirname "$0")/../.." || exit 1

OUT=tools/jvm-harness/out-avatar-take
rm -rf "$OUT"; mkdir -p "$OUT"

javac -nowarn -encoding UTF-8 -d "$OUT" \
  $(find tools/jvm-harness/joybot-bench/shim/org/json -name '*.java') \
  app/src/main/java/cc/joycreator/avatar/perform/Take.java \
  app/src/main/java/cc/joycreator/avatar/perform/TakeRecorder.java \
  tools/jvm-harness/TakeRecorderTest.java || exit 1
java -cp "$OUT" TakeRecorderTest
