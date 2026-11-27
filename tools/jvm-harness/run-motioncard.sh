#!/usr/bin/env bash
# STU-7 §K: the Motion Blur card's shutter strip geometry. Pure Java, no Android.
set -eu
cd "$(dirname "$0")/../.."
OUT=tools/jvm-harness/out-motioncard
rm -rf "$OUT"
mkdir -p "$OUT"
javac -nowarn -encoding UTF-8 -d "$OUT" -sourcepath "tools/jvm-harness/stubs;studiokit/src/main/java" \
    tools/jvm-harness/motioncard/ShutterStripModelTest.java
java -cp "$OUT" ShutterStripModelTest
