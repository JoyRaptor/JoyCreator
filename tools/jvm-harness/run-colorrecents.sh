#!/usr/bin/env bash
# D.02c — the app-wide colour history's list logic (pure static helpers; the stubs provide the android classes it names).
set -u
cd "$(dirname "$0")/../.." || exit 1

OUT=tools/jvm-harness/out-colorrecents
rm -rf "$OUT"; mkdir -p "$OUT"

ARGS=$(mktemp)
{ echo "-nowarn"; echo "-encoding UTF-8"; echo "-d $OUT";
  echo '-sourcepath "tools/jvm-harness/stubs;app/src/main/java;studiokit/src/main/java"'; } > "$ARGS"
javac @"$ARGS" tools/jvm-harness/ColorRecentsTest.java || exit 1
[ -f "$OUT/ColorRecentsTest.class" ] || { echo "no ColorRecentsTest class — the compile did not run"; exit 1; }
java -cp "$OUT" ColorRecentsTest
