#!/usr/bin/env bash
# Which clip sits at a timeline time, with the real Clip and Timeline (classpath as run-clipwarp.sh).
# Usage:  bash tools/jvm-harness/run-spinelookup.sh
set -u
cd "$(dirname "$0")/../.." || exit 1

SDK="${ANDROID_HOME:-$HOME/AppData/Local/Android/Sdk}"
ANDROID_JAR=$(ls -d "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1 | sed 's|^/c/|C:/|')
if [ -z "$ANDROID_JAR" ]; then echo "no android.jar under $SDK/platforms"; exit 1; fi

M3=$(grep '^media3.patched.path' local.properties 2>/dev/null | cut -d= -f2- | tr -d '\r')
if [ -z "$M3" ]; then M3="C:/+Projects/Screenrecorder/media3-patched"; fi

CP="$ANDROID_JAR"
CP="$CP;$(find "$HOME/.gradle/caches" -path '*/transforms/*' -name '*.jar' 2>/dev/null | sed 's|^/c/|C:/|' | tr '\n' ';')"
CP="$CP$(find "$M3/libraries" -path '*compile_library_classes_jar*' -name 'classes.jar' 2>/dev/null | tr '\n' ';')"
CP="$CP$(find "$HOME/.gradle/caches/modules-2" -name 'gson-2*.jar' 2>/dev/null | grep -v sources | head -1 | sed 's|^/c/|C:/|')"

OUT=tools/jvm-harness/out-spinelookup
rm -rf "$OUT"; mkdir -p "$OUT"

ARGS=$(mktemp); RUNARGS=$(mktemp)
{ echo "-nowarn"; echo "-encoding UTF-8"; echo "-d $OUT";
  printf -- '-cp "%s"\n' "$CP";
  echo '-sourcepath "tools/jvm-harness/stubs-clipwarp;tools/jvm-harness/stubs;app/src/main/java;studiokit/src/main/java"'; } > "$ARGS"
printf -- '-cp "%s;%s"\n' "$OUT" "$CP" > "$RUNARGS"

javac @"$ARGS" tools/jvm-harness/SpineLookupTest.java || exit 1
[ -f "$OUT/SpineLookupTest.class" ] || { echo "no SpineLookupTest class: the compile did not run"; exit 1; }
java @"$RUNARGS" SpineLookupTest
