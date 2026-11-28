#!/usr/bin/env bash
# PingPongLegTest - STU-F7: a ping-pong clip's legs alternate starting BACKWARD next to its own pass (preview and export).
set -u
cd "$(dirname "$0")/../.." || exit 1
OUT=tools/jvm-harness/out-pingpong
rm -rf "$OUT"; mkdir -p "$OUT"
javac -nowarn -encoding UTF-8 -d "$OUT" app/src/main/java/com/fadcam/ui/faditor/model/PingPongLeg.java tools/jvm-harness/PingPongLegTest.java || exit 1
[ -f "$OUT/PingPongLegTest.class" ] || { echo "no PingPongLegTest class - the compile did not run"; exit 1; }
java -cp "$OUT" PingPongLegTest
