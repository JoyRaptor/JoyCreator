#!/usr/bin/env bash
# SPEC G — the corner-pin budget bake (flip/fold/rotate/scale decompose to centre/size/
# rotation/mirror, only genuine perspective stays in the pin).
# Off device, seconds to run.
#
# TransformQuad is android-free by design (no android.*, no androidx, no FadCam class) —
# that is what makes this script possible. If it ever needs an Android classpath, a
# renderer concept has leaked into the maths. The guard below enforces it.
#
# Usage:  bash tools/jvm-harness/run-pinbudget.sh
set -u
cd "$(dirname "$0")/../.." || exit 1

OUT=tools/jvm-harness/out-pinbudget
rm -rf "$OUT"; mkdir -p "$OUT"

javac -nowarn -encoding UTF-8 -d "$OUT" \
  -sourcepath "app/src/main/java" \
  tools/jvm-harness/PinNormalizeTest.java || exit 1

# Positive control on the COMPILE itself (run-matte.sh was bitten by an empty out dir).
[ -f "$OUT/PinNormalizeTest.class" ] || { echo "no PinNormalizeTest class — the compile did not run"; exit 1; }

# The android-free property, enforced rather than trusted.
if grep -rn "^import android\|^import androidx" app/src/main/java/com/fadcam/ui/faditor/transform/TransformQuad.java; then
  echo "FAIL: an Android import leaked into TransformQuad"
  exit 1
fi

java -cp "$OUT" PinNormalizeTest
