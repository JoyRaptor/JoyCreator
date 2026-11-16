#!/usr/bin/env bash
# A VisLab look owns its sprite pictures: deleting the source project cannot take them (Codex audit
# 11-15, XROOM-03). See SheetCopiesTest.java.
# Usage:  bash tools/jvm-harness/run-sheetcopies.sh
set -u
cd "$(dirname "$0")/../.." || exit 1

GSON=$(find "$HOME/.gradle/caches/modules-2" -name 'gson-2*.jar' 2>/dev/null | head -1 | sed 's|^/c/|C:/|')
if [ -z "$GSON" ]; then echo "no gson jar in the gradle cache"; exit 1; fi

OUT=tools/jvm-harness/out-sheetcopies
rm -rf "$OUT"; mkdir -p "$OUT"

ARGS=$(mktemp); RUNARGS=$(mktemp)
{ echo "-nowarn"; echo "-encoding UTF-8"; echo "-d $OUT";
  printf -- '-cp "%s"\n' "$GSON";
  echo '-sourcepath "tools/jvm-harness/stubs;app/src/main/java;studiokit/src/main/java"'; } > "$ARGS"
printf -- '-cp "%s;%s"\n' "$OUT" "$GSON" > "$RUNARGS"

javac @"$ARGS" tools/jvm-harness/SheetCopiesTest.java || exit 1
[ -f "$OUT/com/fadcam/vislab/room/SheetCopiesTest.class" ] || { echo "no class file - the compile did not run"; exit 1; }

java @"$RUNARGS" com.fadcam.vislab.room.SheetCopiesTest
