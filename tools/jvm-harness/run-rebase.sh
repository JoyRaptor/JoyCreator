#!/usr/bin/env bash
# SPEC K follow-up — mid-drag canvas-rect rebase (no teleport on finger-up).
set -u
cd "$(dirname "$0")/../.." || exit 1
OUT=tools/jvm-harness/out-rebase
rm -rf "$OUT"; mkdir -p "$OUT"
javac -nowarn -encoding UTF-8 -d "$OUT" \
  -sourcepath "app/src/main/java" \
  tools/jvm-harness/SpecKRebaseTest.java || exit 1
[ -f "$OUT/SpecKRebaseTest.class" ] || { echo "no SpecKRebaseTest class"; exit 1; }
if grep -rn "^import android\|^import androidx" app/src/main/java/com/fadcam/ui/faditor/transform/TransformQuad.java; then
  echo "FAIL: an Android import leaked into TransformQuad"
  exit 1
fi
java -cp "$OUT" SpecKRebaseTest
