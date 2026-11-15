#!/usr/bin/env bash
# TAB-1 §K: where Studio's drawers go on a wide window, and how far the picture slides aside.
set -u
cd "$(dirname "$0")/../.." || exit 1
SDK="${ANDROID_HOME:-$HOME/AppData/Local/Android/Sdk}"
ANDROID_JAR=$(ls -d "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1 | sed 's|^/c/|C:/|')
ANNOT=$(find "$HOME/.gradle/caches/modules-2" -path '*androidx.annotation*' -name 'annotation-jvm-*.jar' ! -name '*sources*' 2>/dev/null | sort -V | tail -1 | sed 's|^/c/|C:/|')
OUT=tools/jvm-harness/out-drawerplace
rm -rf "$OUT"; mkdir -p "$OUT"
javac -nowarn -encoding UTF-8 -d "$OUT" -cp "$ANDROID_JAR;$ANNOT" \
  app/src/main/java/com/fadcam/ui/faditor/tools/DrawerPlace.java \
  tools/jvm-harness/DrawerPlaceTest.java || exit 1
java -cp "$OUT;$ANDROID_JAR" DrawerPlaceTest
