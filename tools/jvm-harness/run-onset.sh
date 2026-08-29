#!/usr/bin/env bash
# OnsetDetector — word-start detection for transcript snapping. Pure Java (androidx
# annotations only), so the heuristics are pinned against synthetic signals whose answers
# are known by construction rather than tuned by listening.
#
#   bash tools/jvm-harness/run-onset.sh
set -u
cd "$(dirname "$0")/../.." || exit 1

OUT=tools/jvm-harness/out-onset
rm -rf "$OUT"; mkdir -p "$OUT"

# @argfile, not a bare -cp: MSYS mangles a semicolon classpath silently (see run-caption.sh).
ARGS=$(mktemp)
{ echo "-nowarn"; echo "-encoding UTF-8"; echo "-d $OUT";
  echo '-sourcepath "tools/jvm-harness/stubs;app/src/main/java"'; } > "$ARGS"

javac @"$ARGS" tools/jvm-harness/OnsetDetectorTest.java || exit 1

# Positive control on the COMPILE itself: an empty out dir means the command never ran, which
# a grep for "error:" would report as success. run-matte.sh was bitten by exactly this.
[ -f "$OUT/OnsetDetectorTest.class" ] || { echo "no class file — the compile did not run"; exit 1; }

java -cp "$OUT" OnsetDetectorTest
