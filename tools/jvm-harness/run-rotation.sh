#!/usr/bin/env bash
# SPEC A — the typed rotation field's grammar (plain degrees past 360, negatives, "16x" turns),
# and with it the refusal list that keeps garbage from silently overwriting a value.
# Off device, seconds to run. KeyframeSet is android-free apart from androidx.annotation
# (stubs), same minimal classpath as run-key.sh. Keep it that way: the moment this test needs
# a real Android classpath, the parse has leaked out of the model layer.
#
# Usage:  bash tools/jvm-harness/run-rotation.sh
set -u
cd "$(dirname "$0")/../.." || exit 1

GSON=$(find "$HOME/.gradle/caches/modules-2" -name 'gson-2*.jar' 2>/dev/null | head -1 | sed 's|^/c/|C:/|')
if [ -z "$GSON" ]; then echo "no gson jar in the gradle cache"; exit 1; fi

OUT=tools/jvm-harness/out-rotation
rm -rf "$OUT"; mkdir -p "$OUT"

# @argfile, not a bare -cp — MSYS path conversion mangles shell-argument classpaths silently
# (see run-key.sh for the full story).
ARGS=$(mktemp); RUNARGS=$(mktemp)
{ echo "-nowarn"; echo "-encoding UTF-8"; echo "-d $OUT";
  printf -- '-cp "%s"\n' "$GSON";
  echo '-sourcepath "tools/jvm-harness/stubs;app/src/main/java"'; } > "$ARGS"
printf -- '-cp "%s;%s"\n' "$OUT" "$GSON" > "$RUNARGS"

javac @"$ARGS" tools/jvm-harness/RotationInputTest.java || exit 1

# Positive control on the COMPILE itself (run-matte.sh was bitten by an empty out dir).
[ -f "$OUT/RotationInputTest.class" ] || { echo "no class file — the compile did not run"; exit 1; }

java @"$RUNARGS" RotationInputTest
