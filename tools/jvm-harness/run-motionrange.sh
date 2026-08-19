#!/usr/bin/env bash
# TextOverlayItem motion range — kept inside the object's own span.
#   bash tools/jvm-harness/run-motionrange.sh
set -u
cd "$(dirname "$0")/../.." || exit 1

GSON=$(find "$HOME/.gradle/caches/modules-2" -name 'gson-2*.jar' 2>/dev/null | head -1 | sed 's|^/c/|C:/|')
OUT=tools/jvm-harness/out-motionrange
rm -rf "$OUT"; mkdir -p "$OUT"

ARGS=$(mktemp); RUNARGS=$(mktemp)
{ echo "-nowarn"; echo "-encoding UTF-8"; echo "-d $OUT";
  [ -n "$GSON" ] && printf -- '-cp "%s"\n' "$GSON";
  echo '-sourcepath "tools/jvm-harness/stubs;app/src/main/java"'; } > "$ARGS"
if [ -n "$GSON" ]; then printf -- '-cp "%s;%s"\n' "$OUT" "$GSON" > "$RUNARGS";
else printf -- '-cp "%s"\n' "$OUT" > "$RUNARGS"; fi

javac @"$ARGS" tools/jvm-harness/MotionRangeTest.java || exit 1
[ -f "$OUT/MotionRangeTest.class" ] || { echo "no class file — the compile did not run"; exit 1; }
java -Dfile.encoding=UTF-8 @"$RUNARGS" MotionRangeTest
