#!/usr/bin/env bash
# Work-loss audit 2026-11-11: VisLab looks made while the Joy Creator folder was out of reach come
# home to the folder (the REAL LookFiles.bringHome, on temporary folders). Plain javac, no Gradle.
# Compiled against the real android.jar (never run: the test only touches plain files); the log
# class is the harness stand-in, named as a source so it wins over the app's own.
# Usage:  bash tools/jvm-harness/run-vislab-home.sh
set -u
cd "$(dirname "$0")/../.." || exit 1
SDK="${ANDROID_HOME:-$HOME/AppData/Local/Android/Sdk}"
AJ=$(ls -d "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1 | sed 's|^/c/|C:/|')
[ -n "$AJ" ] || { echo "no android.jar under $SDK/platforms"; exit 1; }
ANN=$(find "$HOME/.gradle/caches/modules-2" -name 'annotation-jvm-*.jar' ! -name '*sources*' 2>/dev/null | head -1 | sed 's|^/c/|C:/|')
GSON=$(ls ~/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson/*/*/gson-*.jar 2>/dev/null | grep -v sources | head -1 | sed 's|^/c/|C:/|')
[ -n "$GSON" ] || { echo "no gson jar in the Gradle cache"; exit 1; }
CP="$AJ;$ANN;$GSON"
OUT=tools/jvm-harness/out-vislab-home.$$
trap 'rm -rf "$OUT"' EXIT
rm -rf "$OUT"; mkdir -p "$OUT"
javac -nowarn -encoding UTF-8 -d "$OUT" -cp "$CP" \
  -sourcepath "app/src/main/java;studiokit/src/main/java" \
  tools/jvm-harness/stubs/com/fadcam/FLog.java \
  tools/jvm-harness/vislab-home/com/fadcam/vislab/room/LookFilesHomeTest.java || exit 1
[ -f "$OUT/com/fadcam/vislab/room/LookFilesHomeTest.class" ] || { echo "the compile did not run"; exit 1; }
# An @argfile, as the other runners use: the shell rewrites a long ';' list given on the command line.
RUNARGS=$(mktemp)
printf -- '-cp "%s;%s"\n' "$OUT" "$CP" > "$RUNARGS"
java @"$RUNARGS" com.fadcam.vislab.room.LookFilesHomeTest
