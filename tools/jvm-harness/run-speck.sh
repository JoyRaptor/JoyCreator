#!/usr/bin/env bash
# SPEC K — host read/write round-trip (no snap on release, no single-corner throw).
# TransformQuad is android-free; this test replicas host math with plain floats.
set -u
cd "$(dirname "$0")/../.." || exit 1

OUT=tools/jvm-harness/out-speck
rm -rf "$OUT"; mkdir -p "$OUT"

javac -nowarn -encoding UTF-8 -d "$OUT" \
  -sourcepath "app/src/main/java" \
  tools/jvm-harness/SpecKPinRoundTripTest.java || exit 1

[ -f "$OUT/SpecKPinRoundTripTest.class" ] || { echo "no SpecKPinRoundTripTest class"; exit 1; }

if grep -rn "^import android\|^import androidx" app/src/main/java/com/fadcam/ui/faditor/transform/TransformQuad.java; then
  echo "FAIL: an Android import leaked into TransformQuad"
  exit 1
fi

java -cp "$OUT" SpecKPinRoundTripTest
