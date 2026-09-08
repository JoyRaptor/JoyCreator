#!/usr/bin/env bash
# SPEC L — stop objects escaping the canvas.
#
# The fixture is JoyRaptor's real broken image, read out of his Note 20 project.json on
# 2026-09-07. Part 1 (always bake the affine, keep only the keystone in the pin) and
# Part 2 (clamp the DRAWN QUAD, never the box centre) are both exercised here.
#
# Off device, seconds to run. TransformQuad is android-free by design; the guard below
# enforces it, exactly as run-pinbudget.sh does.
#
# Usage:  bash tools/jvm-harness/run-escape.sh
set -u
cd "$(dirname "$0")/../.." || exit 1

OUT=tools/jvm-harness/out-escape
rm -rf "$OUT"; mkdir -p "$OUT"

javac -nowarn -encoding UTF-8 -d "$OUT" \
  -sourcepath "app/src/main/java" \
  tools/jvm-harness/SpecLEscapeTest.java || exit 1

[ -f "$OUT/SpecLEscapeTest.class" ] || { echo "no SpecLEscapeTest class — the compile did not run"; exit 1; }

if grep -rn "^import android\|^import androidx" app/src/main/java/com/fadcam/ui/faditor/transform/TransformQuad.java; then
  echo "FAIL: an Android import leaked into TransformQuad"
  exit 1
fi

java -cp "$OUT" SpecLEscapeTest
