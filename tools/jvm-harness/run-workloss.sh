#!/usr/bin/env bash
# WorkLossStorageTest -- work-loss audit 2026-11-10, off device.
#
# Drives the REAL ProjectStorage save / saveAsync / load on a temporary folder: an audio-only
# project reopens as last saved, the same project held in two places (the one that saves over
# the other keeps what it replaces), a queued save never lands on a later one, saves racing on
# several threads never leave an unreadable project, a cut-off project.json opens from its
# backup and is kept.
#
# Classpath archaeology and stubs are run-audioid.sh's.
# Positive control: WORKLOSS_BASE=<git rev> compiles against that revision's storage. A revision
# before the audit has no ProjectStorage.changedOutside, so it must FAIL to compile.
#
# Usage:  bash tools/jvm-harness/run-workloss.sh
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

OUT=tools/jvm-harness/out-workloss
rm -rf "$OUT"; mkdir -p "$OUT"

SRC='tools/jvm-harness/stubs-clipwarp;tools/jvm-harness/stubs;app/src/main/java;studiokit/src/main/java'
if [ -n "${WORKLOSS_BASE:-}" ]; then
  BASE="$OUT/base-src"
  for f in model/Clip.java model/TextOverlayItem.java model/WaveformOverlayInstance.java sprite/SpriteOverlayItem.java project/ProjectStorage.java; do
    mkdir -p "$BASE/com/fadcam/ui/faditor/$(dirname "$f")"
    git show "$WORKLOSS_BASE:app/src/main/java/com/fadcam/ui/faditor/$f" \
      > "$BASE/com/fadcam/ui/faditor/$f" || exit 1
  done
  SRC="$BASE;$SRC"
  echo "(positive control: model + ProjectStorage from $WORKLOSS_BASE)"
fi

ARGS=$(mktemp); RUNARGS=$(mktemp)
{ echo "-nowarn"; echo "-encoding UTF-8"; echo "-d $OUT";
  printf -- '-cp "%s"\n' "$CP";
  printf -- '-sourcepath "%s"\n' "$SRC"; } > "$ARGS"
printf -- '-cp "%s;%s"\n' "$OUT" "$CP" > "$RUNARGS"

javac @"$ARGS" tools/jvm-harness/WorkLossStorageTest.java || exit 1
# Positive control on the COMPILE itself — the run-matte lesson.
[ -f "$OUT/WorkLossStorageTest.class" ] || { echo "no WorkLossStorageTest class — the compile did not run"; exit 1; }

java @"$RUNARGS" WorkLossStorageTest
