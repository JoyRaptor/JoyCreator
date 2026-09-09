#!/usr/bin/env bash
# SPEC Q — a bent picture must render inside its own helper frame, at every angle.
# The identity-bend measurement: fold + place, swept 0-360 in 15-degree steps, both mirror
# states, with and without a corner pin, at all nine pivot anchors, on portrait, landscape and
# square frames — checked against an independent PIXEL-space model of the flat draw.
# Off device, seconds to run.
#
# Usage:  bash tools/jvm-harness/run-specq.sh
set -u
cd "$(dirname "$0")/../.." || exit 1

GSON=$(find "$HOME/.gradle/caches/modules-2" -name 'gson-2*.jar' 2>/dev/null | head -1 | sed 's|^/c/|C:/|')
if [ -z "$GSON" ]; then echo "no gson jar in the gradle cache"; exit 1; fi

OUT=tools/jvm-harness/out-specq
rm -rf "$OUT"; mkdir -p "$OUT"

# @argfile, not a bare -cp: a semicolon-separated classpath passed as a shell argument gets mangled
# by MSYS path conversion, and the failure is silent — javac succeeds while java reports
# NoClassDefFoundError for a jar that is demonstrably present. run-mask.sh was bitten by this.
ARGS=$(mktemp); RUNARGS=$(mktemp)
{ echo "-nowarn"; echo "-encoding UTF-8"; echo "-d $OUT";
  printf -- '-cp "%s"\n' "$GSON";
  echo '-sourcepath "app/src/main/java"'; } > "$ARGS"
printf -- '-cp "%s;%s"\n' "$OUT" "$GSON" > "$RUNARGS"

javac @"$ARGS" tools/jvm-harness/SpecQMeshPlaceTest.java || exit 1

# Positive control on the COMPILE itself: an empty out dir means the command never ran, which a
# grep for "error:" would report as success.
[ -f "$OUT/SpecQMeshPlaceTest.class" ] || { echo "no SpecQMeshPlaceTest class — the compile did not run"; exit 1; }

# The android-free property, enforced rather than trusted — it is what makes this measurement
# runnable without a phone at all.
if grep -rn "^import android\|^import androidx" app/src/main/java/com/fadcam/ui/faditor/transform/mesh/; then
  echo "FAIL: an Android import leaked into the mesh engine"
  exit 1
fi

java @"$RUNARGS" SpecQMeshPlaceTest
