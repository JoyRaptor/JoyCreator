#!/usr/bin/env bash
# VisLab look layers following a Studio object (roadmap R8): where the object sits inside the
# visualizer's box, in the look's scene units, for any box place / size / turn.
# Usage:  bash tools/jvm-harness/run-visfollow.sh
set -u
cd "$(dirname "$0")/../.." || exit 1
GSON=$(find "$HOME/.gradle/caches/modules-2" -name 'gson-2*.jar' 2>/dev/null | head -1 | sed 's|^/c/|C:/|')
OUT=tools/jvm-harness/out-visfollow
rm -rf "$OUT"; mkdir -p "$OUT"
ARGS=$(mktemp)
{ echo "-nowarn"; echo "-encoding UTF-8"; echo "-d $OUT"; printf -- '-cp "%s"\n' "$GSON";
  echo '-sourcepath "tools/jvm-harness/stubs;app/src/main/java;studiokit/src/main/java"'; } > "$ARGS"
javac @"$ARGS" tools/jvm-harness/VisFollowTest.java || exit 1
# @argfile for the run too: a semicolon classpath on the command line is mangled by MSYS (run-fx.sh).
RUNARGS=$(mktemp)
printf -- '-cp "%s;%s"\n' "$OUT" "$GSON" > "$RUNARGS"
java @"$RUNARGS" VisFollowTest
