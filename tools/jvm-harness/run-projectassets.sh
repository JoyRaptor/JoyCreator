#!/usr/bin/env bash
# Real disk save/load for sheet-only and rig-only projects; same compile closure as run-audioid.sh.
# Usage: bash tools/jvm-harness/run-projectassets.sh
# Negative control: PROJECTASSETS_BASE=HEAD must reject the saved asset-only projects.
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

OUT=tools/jvm-harness/out-projectassets
JB_HARNESS_ROOT=$(cd tools/jvm-harness && pwd -P) || exit 1
JB_OUTPUT_ABS=$(realpath -m -- "$JB_HARNESS_ROOT/out-projectassets") || exit 1
if [ "$JB_OUTPUT_ABS" != "$JB_HARNESS_ROOT/out-projectassets" ]; then
  echo "refusing cleanup outside the expected harness output directory"; exit 1
fi
rm -rf -- "$JB_OUTPUT_ABS" || exit 1
mkdir -p -- "$JB_OUTPUT_ABS" || exit 1

SRC='tools/jvm-harness/stubs-clipwarp;tools/jvm-harness/stubs;app/src/main/java;studiokit/src/main/java'
if [ -n "${PROJECTASSETS_BASE:-}" ]; then
  BASE="$OUT/base-src"
  for f in project/ProjectStorage.java; do
    mkdir -p "$BASE/com/fadcam/ui/faditor/$(dirname "$f")"
    git show "$PROJECTASSETS_BASE:app/src/main/java/com/fadcam/ui/faditor/$f" \
      > "$BASE/com/fadcam/ui/faditor/$f" || exit 1
  done
  SRC="$BASE;$SRC"
  echo "(positive control: ProjectStorage from $PROJECTASSETS_BASE)"
fi

ARGS=$(mktemp); RUNARGS=$(mktemp)
{ echo "-nowarn"; echo "-encoding UTF-8"; echo "-d $OUT";
  printf -- '-cp "%s"\n' "$CP";
  printf -- '-sourcepath "%s"\n' "$SRC"; } > "$ARGS"
printf -- '-cp "%s;%s"\n' "$OUT" "$CP" > "$RUNARGS"

javac @"$ARGS" tools/jvm-harness/ProjectAssetLibraryTest.java || exit 1
# Positive control on the COMPILE itself — the run-matte lesson.
[ -f "$OUT/ProjectAssetLibraryTest.class" ] || { echo "no ProjectAssetLibraryTest class — the compile did not run"; exit 1; }

java @"$RUNARGS" ProjectAssetLibraryTest
