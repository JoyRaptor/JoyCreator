#!/usr/bin/env bash
# PUPPETEERING stage 1: the alpha contour tracer (SPEC_20260904_PUPPET_ARCHITECTURE).
# The Catmull-Rom lattice, the topology/deformer seam, the pose track, the fold guard, the wire
# format, and the two properties the whole design rests on:
#   * subdividing the lattice does not change the picture (measured, not asserted);
#   * a puppet topology with a completely different deformer slots in without touching the pose
#     track, the output buffers, the guard or the JSON.
# Off device, seconds to run.
#
# The engine package imports NOTHING but gson — no android.*, no androidx, no FadCam class. That is
# exactly what makes this script possible, and if it ever needs an Android classpath then a
# renderer concept has leaked into the maths.
#
# Usage:  bash tools/jvm-harness/run-mesh.sh
set -u
cd "$(dirname "$0")/../.." || exit 1

GSON=$(find "$HOME/.gradle/caches/modules-2" -name 'gson-2*.jar' 2>/dev/null | head -1 | sed 's|^/c/|C:/|')
if [ -z "$GSON" ]; then echo "no gson jar in the gradle cache"; exit 1; fi

OUT=tools/jvm-harness/out-puppet
rm -rf "$OUT"; mkdir -p "$OUT"

# @argfile, not a bare -cp: a semicolon-separated classpath passed as a shell argument gets mangled
# by MSYS path conversion, and the failure is silent — javac succeeds while java reports
# NoClassDefFoundError for a jar that is demonstrably present. run-mask.sh was bitten by this.
ARGS=$(mktemp); RUNARGS=$(mktemp)
{ echo "-nowarn"; echo "-encoding UTF-8"; echo "-d $OUT";
  printf -- '-cp "%s"\n' "$GSON";
  echo '-sourcepath "app/src/main/java"'; } > "$ARGS"
printf -- '-cp "%s;%s"\n' "$OUT" "$GSON" > "$RUNARGS"

javac @"$ARGS" tools/jvm-harness/PuppetContourTest.java tools/jvm-harness/PuppetSolverTest.java tools/jvm-harness/PuppetWeightsTest.java tools/jvm-harness/PuppetIslandTest.java tools/jvm-harness/PuppetKnobsTest.java tools/jvm-harness/PuppetRigSolverTest.java tools/jvm-harness/PuppetDrawOrderTest.java tools/jvm-harness/PuppetDepthFieldTest.java tools/jvm-harness/PuppetDangleBakeTest.java tools/jvm-harness/PuppetRigTest.java tools/jvm-harness/PuppetTapeTest.java tools/jvm-harness/PuppetKeysTest.java tools/jvm-harness/DangleTest.java tools/jvm-harness/PinWarpTest.java || exit 1

# Positive control on the COMPILE itself: an empty out dir means the command never ran, which a
# grep for "error:" would report as success.
[ -f "$OUT/PuppetSolverTest.class" ] || { echo "no PuppetContourTest class — the compile did not run"; exit 1; }

# The android-free property, enforced rather than trusted. This is what keeps the engine portable
# and what keeps this harness runnable at all.
if grep -rn "^import android\|^import androidx" app/src/main/java/com/fadcam/ui/faditor/transform/mesh/; then
  echo "FAIL: an Android import leaked into the mesh engine"
  exit 1
fi
# Same rule for the two RIG MODEL files. PuppetPalette is deliberately exempt: it is the one
# place a puppet thing gets a colour, it reads HandleModel so the hues cannot drift from the
# transform tool's, and nothing in this harness touches it.
if grep -nE "^import (android|androidx|com\.fadcam|com\.google)"         app/src/main/java/com/fadcam/ui/faditor/puppet/PuppetPin.java         app/src/main/java/com/fadcam/ui/faditor/puppet/PuppetRig.java; then
  echo "FAIL: a platform import leaked into the rig model — it must stay harness-runnable"
  exit 1
fi

java @"$RUNARGS" PuppetContourTest || exit 1
java @"$RUNARGS" PuppetSolverTest || exit 1
java @"$RUNARGS" PuppetWeightsTest || exit 1
java @"$RUNARGS" PuppetIslandTest || exit 1
java @"$RUNARGS" PuppetKnobsTest || exit 1
java @"$RUNARGS" PuppetRigSolverTest || exit 1
java @"$RUNARGS" PuppetDrawOrderTest || exit 1
java @"$RUNARGS" PuppetDepthFieldTest || exit 1
java @"$RUNARGS" PuppetDangleBakeTest || exit 1
# The RIG's bookkeeping (SPEC_20260915_PUPPET_UI): names, chains, and the index renumbering
# that corrupts a rig silently when a pin is deleted. PuppetPin/PuppetRig import nothing, which
# is what lets them run here — enforced just below.
java @"$RUNARGS" PuppetRigTest || exit 1
# The tape's Option C: bars and diamonds inferred from key DENSITY, so the
# boundaries are the whole correctness story.
java @"$RUNARGS" PuppetTapeTest || exit 1
# A pin KEYS on its own components, and deleting one must not take the rest with it.
java @"$RUNARGS" PuppetKeysTest || exit 1
# The avatar package's OWN proofs. They existed with no runner driving them — orphaned
# tests prove nothing. Same puppet problem, same suite.
java @"$RUNARGS" DangleTest || exit 1
java @"$RUNARGS" PinWarpTest
