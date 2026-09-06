#!/usr/bin/env bash
# SPEC_C_SINGLE_FRAME — FrameTimecodeTest: the typed frame-timecode grammar, off device.
#
# TimeFormatter is pure Java (androidx annotations only), so the parser is pinned here
# against the formats the export dialog pre-fills and the garbage a typo can produce.
# The parser's contract matters more than its code: an out-of-range or unparsable time
# must be REJECTED (-1), never clamped (spec acceptance criterion #4).
#
# Usage:  bash tools/jvm-harness/run-frame-time.sh
set -u
cd "$(dirname "$0")/../.." || exit 1

OUT=tools/jvm-harness/out-frame-time
rm -rf "$OUT"; mkdir -p "$OUT"

# @argfile, not a bare -cp: MSYS mangles a semicolon classpath silently (see run-caption.sh).
ARGS=$(mktemp)
{ echo "-nowarn"; echo "-encoding UTF-8"; echo "-d $OUT";
  echo '-sourcepath "tools/jvm-harness/stubs;app/src/main/java"'; } > "$ARGS"

javac @"$ARGS" tools/jvm-harness/FrameTimecodeTest.java || exit 1

# Positive control on the COMPILE itself: an empty out dir means the command never ran.
[ -f "$OUT/FrameTimecodeTest.class" ] || { echo "no class file - the compile did not run"; exit 1; }

java -cp "$OUT" FrameTimecodeTest
