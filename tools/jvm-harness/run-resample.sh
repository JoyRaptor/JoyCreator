#!/usr/bin/env bash
# ResampleTest — A6 ResamplingAudioProcessor, off device.
#
# Same classpath archaeology as run-envelope.sh: real media3 classes resolve from the
# patched tree's compile jars, android.jar satisfies the stub-level references. The test
# drives the processor through configure/queueInput/queueEndOfStream exactly like the
# export pipeline does, so a wrong resampler (pitch shift, length drift, chunk-boundary
# glitches) fails here instead of shipping into every 44.1kHz-source export.
#
# Usage:  bash tools/jvm-harness/run-resample.sh
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

OUT=tools/jvm-harness/out-resample
rm -rf "$OUT"; mkdir -p "$OUT"

ARGS=$(mktemp); RUNARGS=$(mktemp)
{ echo "-nowarn"; echo "-encoding UTF-8"; echo "-d $OUT";
  printf -- '-cp "%s"\n' "$CP";
  echo '-sourcepath "tools/jvm-harness/stubs;app/src/main/java"'; } > "$ARGS"
printf -- '-cp "%s;%s"\n' "$OUT" "$CP" > "$RUNARGS"

javac @"$ARGS" tools/jvm-harness/ResampleTest.java || exit 1
# Positive control on the COMPILE itself — the run-matte lesson.
[ -f "$OUT/ResampleTest.class" ] || { echo "no ResampleTest class — compile did not run"; exit 1; }
java @"$RUNARGS" ResampleTest || exit 1
