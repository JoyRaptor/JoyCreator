#!/usr/bin/env bash
# SPEC P — the object must not pop when you let go of a corner.
#
# The commit-time bake rewrites the pin into centre/size/rotation and is supposed to be
# appearance-neutral. On JoyRaptor's Note 9 it moved the picture 50-130px on EVERY corner drag,
# in a direction that depended on the rotation angle. The old harness proved neutrality at
# ONE pose, which is exactly why it shipped green — so this one sweeps -180..+180 in 5 degree
# steps, at all nine pivots, in all four mirror states.
#
# Off device, seconds to run. TransformQuad is android-free by design; the guard below
# enforces it, exactly as run-escape.sh does.
#
# Usage:  bash tools/jvm-harness/run-bakepop.sh
set -u
cd "$(dirname "$0")/../.." || exit 1

OUT=tools/jvm-harness/out-bakepop
rm -rf "$OUT"; mkdir -p "$OUT"

javac -nowarn -encoding UTF-8 -d "$OUT" \
  -sourcepath "app/src/main/java" \
  tools/jvm-harness/SpecPBakePopTest.java || exit 1

[ -f "$OUT/SpecPBakePopTest.class" ] || { echo "no SpecPBakePopTest class — the compile did not run"; exit 1; }

if grep -rn "^import android\|^import androidx" app/src/main/java/com/fadcam/ui/faditor/transform/TransformQuad.java; then
  echo "FAIL: an Android import leaked into TransformQuad"
  exit 1
fi

# ── The wiring lint ───────────────────────────────────────────────────────────
# The sweep above tests the arithmetic. The bug was never in the arithmetic — it was in WHICH
# CENTRE the host handed it. box.centerX() is the PRESENTED centre: Target.frame() ends in
# TextOverlayLayer.foldRotationPivotIntoBox, so the pivot fold is already inside it, and using
# it as the anchor for target.moveTo (which writes the POSE centre) wrote the fold twice. The
# host is android-bound and cannot run here, so its wiring is checked by reading it.
HOST=app/src/main/java/com/fadcam/ui/faditor/transform/CornerPinTransformHost.java

ANCHOR=$(grep -n "float bcx0" "$HOST")
echo "$ANCHOR"
case "$ANCHOR" in
  *"box.centerX()"*)
    echo "FAIL: the bake anchors at box.centerX() again — that rect is already pivot-folded."
    echo "      Anchor at the POSE centre: v.left + target.centerX(t) * v.width()."
    exit 1;;
  *"target.centerX(t)"*) ;;
  *)
    echo "FAIL: could not tell what the bake anchors its new centre at (float bcx0 ...)."
    exit 1;;
esac

for fn in bakedPoseCentre renderQuad; do
  if ! grep -q "TransformQuad\.$fn(" "$HOST"; then
    echo "FAIL: the host stopped calling TransformQuad.$fn — the render equation has been"
    echo "      transcribed a second time, which is how the bake and its self-check drifted."
    exit 1
  fi
done

java -cp "$OUT" SpecPBakePopTest
