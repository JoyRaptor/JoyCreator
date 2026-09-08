#!/usr/bin/env bash
# SPEC K follow-up — corner flip mirrors about the central axis, bake preserves it.
set -u
cd "$(dirname "$0")/../.." || exit 1
OUT=tools/jvm-harness/out-flip
rm -rf "$OUT"; mkdir -p "$OUT"
javac -nowarn -encoding UTF-8 -d "$OUT" \
  -sourcepath "app/src/main/java" \
  tools/jvm-harness/SpecKFlipTest.java || exit 1
[ -f "$OUT/SpecKFlipTest.class" ] || { echo "no SpecKFlipTest class"; exit 1; }
if grep -rn "^import android\|^import androidx" app/src/main/java/com/fadcam/ui/faditor/transform/TransformQuad.java; then
  echo "FAIL: an Android import leaked into TransformQuad"
  exit 1
fi
java -cp "$OUT" SpecKFlipTest
