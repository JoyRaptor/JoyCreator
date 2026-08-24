#!/usr/bin/env bash
# DspMathTest — C1.E Eq / Compressor / Gate, off device.
#
# Same classpath archaeology as run-resample.sh: real media3 classes resolve from the
# patched tree's compile jars, android.jar satisfies stub-level references.
# Each processor is driven through configure/queueInput/queueEndOfStream exactly like
# the export pipeline does, so a wrong implementation fails here instead of shipping.
# Negative controls prove the checks have teeth: a no-op copy must be caught.
#
# Usage:  bash tools/jvm-harness/run-audio-fx.sh
set -u
cd "$(dirname "$0")/../.." || exit 1

SDK="${ANDROID_HOME:-$HOME/AppData/Local/Android/Sdk}"
ANDROID_JAR=$(ls -d "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1 | sed 's|^/c/|C:/|')
if [ -z "$ANDROID_JAR" ]; then echo "no android.jar under $SDK/platforms"; exit 1; fi

M3=$(grep '^media3.patched.path' local.properties 2>/dev/null | cut -d= -f2- | tr -d '\r')
if [ -z "$M3" ]; then M3="/tmp/media3-patched"; fi

# R.jar on the classpath. The FX processors themselves need nothing from resources, but the
# compile CLOSURE reaches them: wiring FxChain into MasterPlaybackEngine (the C1.E preview/export
# parity work) pulls in UI classes that reference R, and without it this harness died with 22
# "package R does not exist" errors while the app itself compiled fine. A harness that breaks
# when the feature it guards gets wired up is a harness that stops guarding exactly when it
# matters. Same path typecheck.sh uses.
RJAR="app/build/intermediates/compile_and_runtime_not_namespaced_r_class_jar/defaultDebug/processDefaultDebugResources/R.jar"
[ -f "$RJAR" ] || echo "WARNING: no R.jar — run a real build once if R.* references fail"
CP="$ANDROID_JAR;$RJAR"
CP="$CP;$(find "$HOME/.gradle/caches" -path '*/transforms/*' -name '*.jar' 2>/dev/null | sed 's|^/c/|C:/|' | tr '\n' ';')"
CP="$CP$(find "$M3/libraries" -path '*compile_library_classes_jar*' -name 'classes.jar' 2>/dev/null | tr '\n' ';')"

OUT=tools/jvm-harness/out-dsp
rm -rf "$OUT"; mkdir -p "$OUT"

ARGS=$(mktemp); RUNARGS=$(mktemp)
{ echo "-nowarn"; echo "-encoding UTF-8"; echo "-d $OUT";
  printf -- '-cp "%s"\n' "$CP";
  echo '-sourcepath "tools/jvm-harness/stubs;app/src/main/java"'; } > "$ARGS"
printf -- '-cp "%s;%s"\n' "$OUT" "$CP" > "$RUNARGS"

javac @"$ARGS" tools/jvm-harness/DspMathTest.java || exit 1
[ -f "$OUT/DspMathTest.class" ] || { echo "no DspMathTest class — compile did not run"; exit 1; }
java @"$RUNARGS" DspMathTest || exit 1
