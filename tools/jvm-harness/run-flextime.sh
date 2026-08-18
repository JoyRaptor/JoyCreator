#!/usr/bin/env bash
# FlexibleTimeParser — the ONE typed-time grammar (SPEC_IMAGE_SEQUENCE §3c).
# Pure logic, so what "2m30s" means is pinned off-device.
#
#   bash tools/jvm-harness/run-flextime.sh
set -u
cd "$(dirname "$0")/../.." || exit 1

OUT=tools/jvm-harness/out-flextime
rm -rf "$OUT"; mkdir -p "$OUT"

# @argfile, not a bare -cp — MSYS mangles a semicolon classpath silently (see run-textstyle.sh).
ARGS=$(mktemp); RUNARGS=$(mktemp)
{ echo "-nowarn"; echo "-encoding UTF-8"; echo "-d $OUT";
  echo '-sourcepath "tools/jvm-harness/stubs;app/src/main/java"'; } > "$ARGS"
printf -- '-cp "%s"\n' "$OUT" > "$RUNARGS"

javac @"$ARGS" tools/jvm-harness/FlexibleTimeTest.java || exit 1

# Positive control on the COMPILE: an empty out dir means the command never ran, which a grep
# for "error:" would report as success (run-matte.sh was bitten by exactly this).
[ -f "$OUT/FlexibleTimeTest.class" ] || { echo "no class file — the compile did not run"; exit 1; }

java -Dfile.encoding=UTF-8 @"$RUNARGS" FlexibleTimeTest
