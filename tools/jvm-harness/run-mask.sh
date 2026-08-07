#!/usr/bin/env bash
# Multi-shape masks (SPEC_ADJUSTMENT_LAYERS_FX M0) — the boolean-op fold, the feather-cache
# signature, serialization byte-identity, and slot stability.
# Off device, seconds to run.
#
# MaskFold and CompositingSpec are android-free apart from androidx.annotation (stubs) and
# gson, which is the whole reason the fold DECISION lives in MaskFold rather than inside
# MaskPathBuilder — that class imports Path/Bitmap/Canvas and could never load here. If this
# script ever needs a real Android classpath, a mask rule has leaked back into the renderer.
#
# Usage:  bash tools/jvm-harness/run-mask.sh
set -u
cd "$(dirname "$0")/../.." || exit 1

GSON=$(find "$HOME/.gradle/caches/modules-2" -name 'gson-2*.jar' 2>/dev/null | head -1 | sed 's|^/c/|C:/|')
if [ -z "$GSON" ]; then echo "no gson jar in the gradle cache"; exit 1; fi

OUT=tools/jvm-harness/out-mask
rm -rf "$OUT"; mkdir -p "$OUT"

# @argfile, not a bare -cp: a semicolon-separated classpath passed as a shell argument gets
# mangled by MSYS path conversion, and the failure is silent — javac succeeds (it sees the
# jar) while java reports NoClassDefFoundError for a jar that is demonstrably present.
ARGS=$(mktemp); RUNARGS=$(mktemp)
{ echo "-nowarn"; echo "-encoding UTF-8"; echo "-d $OUT";
  printf -- '-cp "%s"\n' "$GSON";
  echo '-sourcepath "tools/jvm-harness/stubs;app/src/main/java"'; } > "$ARGS"
printf -- '-cp "%s;%s"\n' "$OUT" "$GSON" > "$RUNARGS"

javac @"$ARGS" tools/jvm-harness/MaskFoldTest.java tools/jvm-harness/CompositingSpecTest.java || exit 1

# Positive control on the COMPILE itself: an empty out dir means the command never ran, which
# a grep for "error:" would report as success. run-matte.sh was bitten by exactly this.
[ -f "$OUT/MaskFoldTest.class" ] || { echo "no MaskFoldTest class — the compile did not run"; exit 1; }
[ -f "$OUT/CompositingSpecTest.class" ] || { echo "no CompositingSpecTest class"; exit 1; }

java @"$RUNARGS" MaskFoldTest || exit 1
java @"$RUNARGS" CompositingSpecTest
