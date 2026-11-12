#!/usr/bin/env bash
# EditorClockTest -- job STU-5, the editor side of the timeline clock, off device.
#
# One rounding rule between source and timeline ms (a clip split many times keeps its length),
# the later clip owns a cut, and caption-style keys on the timeline ruler: an old file (source
# ms) converts once on load, a new one never does, normal speed is untouched. Real Clip,
# Timeline and ProjectStorage on a temporary folder.
#
# Usage:  bash tools/jvm-harness/run-editorclock.sh

set -u
cd "$(dirname "$0")/../.." || exit 1

SDK="${ANDROID_HOME:-$HOME/AppData/Local/Android/Sdk}"
ANDROID_JAR=$(ls -d "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1 | sed 's|^/c/|C:/|')
if [ -z "$ANDROID_JAR" ]; then echo "no android.jar under $SDK/platforms"; exit 1; fi

M3=$(grep '^media3.patched.path' local.properties 2>/dev/null | cut -d= -f2- | tr -d '\r')
if [ -z "$M3" ]; then M3="/tmp/media3-patched"; fi

CP="$ANDROID_JAR"
CP="$CP;$(find "$HOME/.gradle/caches" -path '*/transforms/*' -name '*.jar' 2>/dev/null | sed 's|^/c/|C:/|' | tr '\n' ';')"
CP="$CP$(find "$M3/libraries" -path '*compile_library_classes_jar*' -name 'classes.jar' 2>/dev/null | tr '\n' ';')"
CP="$CP$(find "$HOME/.gradle/caches/modules-2" -name 'gson-2*.jar' 2>/dev/null | head -1 | sed 's|^/c/|C:/|')"

OUT=tools/jvm-harness/out-editorclock
rm -rf "$OUT"; mkdir -p "$OUT"

SRC='tools/jvm-harness/stubs-clipwarp;tools/jvm-harness/stubs;app/src/main/java;studiokit/src/main/java'

ARGS=$(mktemp); RUNARGS=$(mktemp)
{ echo "-nowarn"; echo "-encoding UTF-8"; echo "-d $OUT";
  printf -- '-cp "%s"\n' "$CP";
  printf -- '-sourcepath "%s"\n' "$SRC"; } > "$ARGS"
printf -- '-cp "%s;%s"\n' "$OUT" "$CP" > "$RUNARGS"

javac @"$ARGS" tools/jvm-harness/EditorClockTest.java || exit 1
# Positive control on the COMPILE itself — the run-matte lesson.
[ -f "$OUT/EditorClockTest.class" ] || { echo "no EditorClockTest class — the compile did not run"; exit 1; }

java @"$RUNARGS" EditorClockTest
