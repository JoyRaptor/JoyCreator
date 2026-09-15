#!/usr/bin/env bash
# SPEC ZB — Clip corner pin + mesh (model, copy, snapshot, persistence), off device.
#
# The sheet adds fields no renderer reads yet, so no behavioural test can see them.
# ClipWarpTest proves the four promises through the REAL code instead: the accessors
# behave, a copy owns its bend, an unwarped clip serialises with not one extra byte,
# and old / warped / malformed JSON all deserialise with the clip intact.
#
# Classpath archaeology, same as run-envelope.sh (real model classes, so android.jar
# must resolve android.net.Uri at COMPILE time; at RUNTIME a test-only Uri stub wins
# because $OUT comes first — the SDK jar throws Stub! for toString/parse/getPath,
# which the serialise path actually calls). The stub lives in stubs-clipwarp, used by
# THIS runner only — nothing shared, so no other harness can feel it.
#
# Usage:  bash tools/jvm-harness/run-clipwarp.sh
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

OUT=tools/jvm-harness/out-clipwarp
rm -rf "$OUT"; mkdir -p "$OUT"

ARGS=$(mktemp); RUNARGS=$(mktemp)
{ echo "-nowarn"; echo "-encoding UTF-8"; echo "-d $OUT";
  printf -- '-cp "%s"\n' "$CP";
  echo '-sourcepath "tools/jvm-harness/stubs-clipwarp;tools/jvm-harness/stubs;app/src/main/java"'; } > "$ARGS"
printf -- '-cp "%s;%s"\n' "$OUT" "$CP" > "$RUNARGS"

javac @"$ARGS" tools/jvm-harness/ClipWarpTest.java || exit 1
# Positive control on the COMPILE itself — the run-matte lesson.
[ -f "$OUT/ClipWarpTest.class" ] || { echo "no ClipWarpTest class — the compile did not run"; exit 1; }

java @"$RUNARGS" ClipWarpTest
