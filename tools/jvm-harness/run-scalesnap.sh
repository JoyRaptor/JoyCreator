#!/usr/bin/env bash
# SPEC K follow-up — uniform-snap corner scaling (the touch-screen Shift key).
set -u
cd "$(dirname "$0")/../.." || exit 1
OUT=tools/jvm-harness/out-scalesnap
rm -rf "$OUT"; mkdir -p "$OUT"
javac -nowarn -encoding UTF-8 -d "$OUT" \
  -sourcepath "app/src/main/java" \
  tools/jvm-harness/SpecKScaleSnapTest.java || exit 1
[ -f "$OUT/SpecKScaleSnapTest.class" ] || { echo "no SpecKScaleSnapTest class"; exit 1; }
if grep -rn "^import android\|^import androidx" app/src/main/java/com/fadcam/ui/faditor/transform/TransformQuad.java; then
  echo "FAIL: an Android import leaked into TransformQuad"
  exit 1
fi
java -cp "$OUT" SpecKScaleSnapTest
