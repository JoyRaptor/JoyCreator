#!/usr/bin/env bash
# D.02a — the transform tool's rotation unwrap and centre-anchored corner factor (pure helpers, no Android).
set -u
cd "$(dirname "$0")/../.." || exit 1

OUT=tools/jvm-harness/out-gestureangle
rm -rf "$OUT"; mkdir -p "$OUT"

javac -nowarn -encoding UTF-8 -d "$OUT" \
  -sourcepath "app/src/main/java" \
  tools/jvm-harness/GestureAngleTest.java || exit 1

[ -f "$OUT/GestureAngleTest.class" ] || { echo "no GestureAngleTest class — the compile did not run"; exit 1; }

if grep -rn "^import android" app/src/main/java/com/fadcam/ui/faditor/transform/TransformQuad.java; then
  echo "FAIL: an Android import leaked into TransformQuad"
  exit 1
fi

java -cp "$OUT" GestureAngleTest
