#!/usr/bin/env bash
# PREVIEW-1: the CPU colour-grade maths behind grade swatches. Plain javac, no Gradle.
# Usage:  bash tools/jvm-harness/run-lutmath.sh
set -u
cd "$(dirname "$0")/../.." || exit 1
OUT=tools/jvm-harness/out-lutmath.$$
trap 'rm -rf "$OUT"' EXIT
rm -rf "$OUT"; mkdir -p "$OUT"
javac -nowarn -encoding UTF-8 -d "$OUT" \
  app/src/main/java/com/fadcam/ui/faditor/effects/LutMath.java \
  tools/jvm-harness/LutMathTest.java || exit 1
java -cp "$OUT" LutMathTest
