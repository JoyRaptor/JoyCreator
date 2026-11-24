#!/usr/bin/env bash
# FOLD-1 auto-markers: where a Detect moment lands on the timeline. Plain javac, no Gradle.
# Usage:  bash tools/jvm-harness/run-automarks.sh
set -u
cd "$(dirname "$0")/../.." || exit 1
OUT=tools/jvm-harness/out-automarks.$$
trap 'rm -rf "$OUT"' EXIT
rm -rf "$OUT"; mkdir -p "$OUT"
javac -nowarn -encoding UTF-8 -d "$OUT" \
  app/src/main/java/com/fadcam/ui/faditor/markers/AutoMarkMapper.java \
  tools/jvm-harness/AutoMarkMapperTest.java || exit 1
java -cp "$OUT" AutoMarkMapperTest
