#!/usr/bin/env bash
# SignalMapTest: a take's mouth shape as the pilot stores it (a number) and as the phone does (a name). Pure Java.
set -u
cd "$(dirname "$0")/../.." || exit 1
OUT=tools/jvm-harness/out-avatar-signalmap
rm -rf "$OUT"; mkdir -p "$OUT"
javac -nowarn -encoding UTF-8 -d "$OUT" \
  $(find app/src/main/java/cc/joycreator/avatar/solve -name '*.java' ! -name 'BitmapPics.java') \
  $(find tools/jvm-harness/joybot-bench/shim/org/json -name '*.java') \
  app/src/main/java/cc/joycreator/avatar/file/Js.java \
  app/src/main/java/cc/joycreator/avatar/ui/SignalMap.java \
  tools/jvm-harness/SignalMapTest.java || exit 1
java -cp "$OUT" SignalMapTest
