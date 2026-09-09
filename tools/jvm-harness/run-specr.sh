#!/usr/bin/env bash
# SPEC R — a meshed picture must render UPRIGHT: exactly ONE vertical flip end to end.
# Walks an identity lattice vertex by vertex through the REAL placement matrix and the REAL
# shipped fragment sampling line, and counts the flips between the bitmap row and the screen
# row. Also pins the two downstream composites (preview + export) sampling the stamp FBO
# unflipped, so the fix cannot land on one surface and not the other.
# Off device, seconds to run.
#
# Usage:  bash tools/jvm-harness/run-specr.sh
set -u
cd "$(dirname "$0")/../.." || exit 1

GSON=$(find "$HOME/.gradle/caches/modules-2" -name 'gson-2*.jar' 2>/dev/null | head -1 | sed 's|^/c/|C:/|')
if [ -z "$GSON" ]; then echo "no gson jar in the gradle cache"; exit 1; fi

OUT=tools/jvm-harness/out-specr
rm -rf "$OUT"; mkdir -p "$OUT"

# @argfile, not a bare -cp: a semicolon-separated classpath passed as a shell argument gets mangled
# by MSYS path conversion, and the failure is silent — javac succeeds while java reports
# NoClassDefFoundError for a jar that is demonstrably present. run-mask.sh was bitten by this.
ARGS=$(mktemp); RUNARGS=$(mktemp)
{ echo "-nowarn"; echo "-encoding UTF-8"; echo "-d $OUT";
  printf -- '-cp "%s"\n' "$GSON";
  echo '-sourcepath "app/src/main/java"'; } > "$ARGS"
printf -- '-cp "%s;%s"\n' "$OUT" "$GSON" > "$RUNARGS"

javac @"$ARGS" tools/jvm-harness/SpecRMeshFlipTest.java || exit 1

# Positive control on the COMPILE itself: an empty out dir means the command never ran, which a
# grep for "error:" would report as success.
[ -f "$OUT/SpecRMeshFlipTest.class" ] || { echo "no SpecRMeshFlipTest class — the compile did not run"; exit 1; }

# The android-free property, enforced rather than trusted — it is what makes this measurement
# runnable without a phone at all.
if grep -rn "^import android\|^import androidx" app/src/main/java/com/fadcam/ui/faditor/transform/mesh/; then
  echo "FAIL: an Android import leaked into the mesh engine"
  exit 1
fi

java @"$RUNARGS" SpecRMeshFlipTest
