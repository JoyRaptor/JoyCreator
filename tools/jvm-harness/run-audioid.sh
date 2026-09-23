#!/usr/bin/env bash
# AudioIdRoundTripTest — audio clip ids survive save -> load (and undo), off device.
#
# 2026-09-23: two saves of project a32d24e2 minutes apart differed ONLY in the audio clip
# ids, because the loader never read the id it wrote. This drives the REAL ProjectStorage
# toJson/fromJson pair and proves save -> load -> save is byte-identical, that visualizer
# and link-group references to audio clips still resolve, and that missing / repeated ids
# in a file still load safely.
#
# Classpath archaeology and stubs are run-clipwarp.sh's (stubs-clipwarp supplies the
# working Uri + the Context members ProjectStorage's compile closure needs).
#
# Positive control: AUDIOID_BASE=<git rev> compiles the test against that revision's
# AudioClip + ProjectStorage instead, e.g. AUDIOID_BASE=0cc6dedd must FAIL.
#
# Usage:  bash tools/jvm-harness/run-audioid.sh
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

OUT=tools/jvm-harness/out-audioid
rm -rf "$OUT"; mkdir -p "$OUT"

SRC='tools/jvm-harness/stubs-clipwarp;tools/jvm-harness/stubs;app/src/main/java'
if [ -n "${AUDIOID_BASE:-}" ]; then
  BASE="$OUT/base-src"
  for f in model/AudioClip.java project/ProjectStorage.java; do
    mkdir -p "$BASE/com/fadcam/ui/faditor/$(dirname "$f")"
    git show "$AUDIOID_BASE:app/src/main/java/com/fadcam/ui/faditor/$f" \
      > "$BASE/com/fadcam/ui/faditor/$f" || exit 1
  done
  SRC="$BASE;$SRC"
  echo "(positive control: AudioClip + ProjectStorage from $AUDIOID_BASE)"
fi

ARGS=$(mktemp); RUNARGS=$(mktemp)
{ echo "-nowarn"; echo "-encoding UTF-8"; echo "-d $OUT";
  printf -- '-cp "%s"\n' "$CP";
  printf -- '-sourcepath "%s"\n' "$SRC"; } > "$ARGS"
printf -- '-cp "%s;%s"\n' "$OUT" "$CP" > "$RUNARGS"

javac @"$ARGS" tools/jvm-harness/AudioIdRoundTripTest.java || exit 1
# Positive control on the COMPILE itself — the run-matte lesson.
[ -f "$OUT/AudioIdRoundTripTest.class" ] || { echo "no AudioIdRoundTripTest class — the compile did not run"; exit 1; }

java @"$RUNARGS" AudioIdRoundTripTest
