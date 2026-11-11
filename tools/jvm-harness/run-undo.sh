#!/usr/bin/env bash
# UndoManagerTest: the REAL undo/redo manager off the phone (snapshot and action steps, merging a
# follow-up change into the step before it, steps loaded from disk). Plain javac, no Gradle.
# Usage:  bash tools/jvm-harness/run-undo.sh
set -u
cd "$(dirname "$0")/../.." || exit 1
SDK="${ANDROID_HOME:-$HOME/AppData/Local/Android/Sdk}"
AJ=$(ls -d "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1 | sed 's|^/c/|C:/|')
[ -n "$AJ" ] || { echo "no android.jar under $SDK/platforms"; exit 1; }
ANN=$(find "$HOME/.gradle/caches/modules-2" -name 'annotation-jvm-*.jar' ! -name '*sources*' 2>/dev/null | head -1 | sed 's|^/c/|C:/|')
OUT=tools/jvm-harness/out-undo.$$
trap 'rm -rf "$OUT"' EXIT
rm -rf "$OUT"; mkdir -p "$OUT"
javac -nowarn -encoding UTF-8 -d "$OUT" -cp "$AJ;$ANN" \
  -sourcepath "tools/jvm-harness/stubs;app/src/main/java" \
  tools/jvm-harness/UndoManagerTest.java || exit 1
[ -f "$OUT/UndoManagerTest.class" ] || { echo "the compile did not run"; exit 1; }
java -cp "$OUT;$AJ;$ANN" UndoManagerTest | tail -3
