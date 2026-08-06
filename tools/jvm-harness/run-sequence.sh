#!/usr/bin/env bash
# SPEC_IMAGE_SEQUENCE weight model + the one change it makes to the shared frame evaluator.
# Off device, seconds to run.
#
# Like run-key.sh this deliberately needs only gson + the annotation stubs. SequenceTiming and
# SpriteFrameResolver are pure arithmetic, and the moment this test needs android.jar the timing
# math has leaked out of the model layer and into a renderer — which is exactly the thing
# SPEC_IMAGE_SEQUENCE §8 ("one evaluator, shared by both renderers") exists to prevent.
#
# Usage:  bash tools/jvm-harness/run-sequence.sh
set -u
cd "$(dirname "$0")/../.." || exit 1

GSON=$(find "$HOME/.gradle/caches/modules-2" -name 'gson-2*.jar' 2>/dev/null | head -1 | sed 's|^/c/|C:/|')
if [ -z "$GSON" ]; then echo "no gson jar in the gradle cache"; exit 1; fi

OUT=tools/jvm-harness/out-sequence
rm -rf "$OUT"; mkdir -p "$OUT"

# @argfile, not a bare -cp: a semicolon-separated classpath passed as a shell argument gets
# mangled by MSYS path conversion, and the failure is silent (see run-key.sh).
ARGS=$(mktemp); RUNARGS=$(mktemp)
{ echo "-nowarn"; echo "-encoding UTF-8"; echo "-d $OUT";
  printf -- '-cp "%s"\n' "$GSON";
  echo '-sourcepath "tools/jvm-harness/stubs;app/src/main/java"'; } > "$ARGS"
printf -- '-cp "%s;%s"\n' "$OUT" "$GSON" > "$RUNARGS"

javac @"$ARGS" tools/jvm-harness/SequenceTimingTest.java \
               tools/jvm-harness/SequenceImportTest.java || exit 1

# Positive control on the COMPILE itself: an empty out dir means the command never ran, which a
# grep for "error:" would report as success.
[ -f "$OUT/SequenceTimingTest.class" ] || { echo "no class file — the compile did not run"; exit 1; }
[ -f "$OUT/SequenceImportTest.class" ] || { echo "no SequenceImportTest class"; exit 1; }

java @"$RUNARGS" SequenceTimingTest || exit 1
java @"$RUNARGS" SequenceImportTest
