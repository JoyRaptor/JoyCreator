#!/usr/bin/env bash
# WordSyncRipple — ripple/stretch arithmetic for Word Sync mode. Pure Java, so the maths
# that moves a hundred words on one gesture is pinned by cases with known answers rather
# than by dragging on a phone and squinting.
#
#   bash tools/jvm-harness/run-wordsync.sh
set -u
cd "$(dirname "$0")/../.." || exit 1

OUT=tools/jvm-harness/out-wordsync
rm -rf "$OUT"; mkdir -p "$OUT"

# @argfile, not a bare -cp: MSYS mangles a semicolon classpath silently (see run-caption.sh).
ARGS=$(mktemp)
{ echo "-nowarn"; echo "-encoding UTF-8"; echo "-d $OUT";
  echo '-sourcepath "tools/jvm-harness/stubs;app/src/main/java"'; } > "$ARGS"

javac @"$ARGS" tools/jvm-harness/WordSyncRippleTest.java || exit 1

# Positive control on the COMPILE itself: an empty out dir means the command never ran, which
# a grep for "error:" would report as success. run-matte.sh was bitten by exactly this.
[ -f "$OUT/WordSyncRippleTest.class" ] || { echo "no class file — the compile did not run"; exit 1; }

java -cp "$OUT" WordSyncRippleTest
