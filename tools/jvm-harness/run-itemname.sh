#!/usr/bin/env bash
# ItemNameTest -- the optional per-item `name` (NAMING_DESIGN_20261014 work item 1), off device.
#
# Drives the REAL ProjectStorage toJson/fromJson: round trip per kind (clip, video layer, text,
# image, sprite, visualizer), old files with no name, name independent of Clip.displayName,
# duplicate + split keep the name, and the name is rendering-neutral.
#
# Classpath archaeology and stubs are run-audioid.sh's.
# Positive control: ITEMNAME_BASE=<git rev> compiles against that revision's model + storage
# (a revision before the name field must FAIL to compile or fail the checks).
#
# Usage:  bash tools/jvm-harness/run-itemname.sh
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

OUT=tools/jvm-harness/out-itemname
rm -rf "$OUT"; mkdir -p "$OUT"

SRC='tools/jvm-harness/stubs-clipwarp;tools/jvm-harness/stubs;app/src/main/java;studiokit/src/main/java'
if [ -n "${ITEMNAME_BASE:-}" ]; then
  BASE="$OUT/base-src"
  for f in model/Clip.java model/TextOverlayItem.java model/WaveformOverlayInstance.java sprite/SpriteOverlayItem.java project/ProjectStorage.java; do
    mkdir -p "$BASE/com/fadcam/ui/faditor/$(dirname "$f")"
    git show "$ITEMNAME_BASE:app/src/main/java/com/fadcam/ui/faditor/$f" \
      > "$BASE/com/fadcam/ui/faditor/$f" || exit 1
  done
  SRC="$BASE;$SRC"
  echo "(positive control: model + ProjectStorage from $ITEMNAME_BASE)"
fi

ARGS=$(mktemp); RUNARGS=$(mktemp)
{ echo "-nowarn"; echo "-encoding UTF-8"; echo "-d $OUT";
  printf -- '-cp "%s"\n' "$CP";
  printf -- '-sourcepath "%s"\n' "$SRC"; } > "$ARGS"
printf -- '-cp "%s;%s"\n' "$OUT" "$CP" > "$RUNARGS"

javac @"$ARGS" tools/jvm-harness/ItemNameTest.java || exit 1
# Positive control on the COMPILE itself — the run-matte lesson.
[ -f "$OUT/ItemNameTest.class" ] || { echo "no ItemNameTest class — the compile did not run"; exit 1; }

java @"$RUNARGS" ItemNameTest
