#!/usr/bin/env bash
# A9 evidence runner — compile + run LaneChainParityTest against the real model + fx + export classes.
set -u
cd /c/+Projects/Screenrecorder/FadCam || exit 1

SDK="${ANDROID_HOME:-$HOME/AppData/Local/Android/Sdk}"
ANDROID_JAR=$(ls -d "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1 | sed 's|^/c/|C:/|')
[ -n "$ANDROID_JAR" ] || { echo "no android.jar"; exit 1; }
M3=$(grep '^media3.patched.path' local.properties | cut -d= -f2- | tr -d '\r')

CP="$ANDROID_JAR"
CP="$CP;$(find "$HOME/.gradle/caches/modules-2" -name 'guava-*.jar' ! -name '*sources*' 2>/dev/null | head -1 | sed 's|^/c/|C:/|')"
# find natively emits /c/... which the WINDOWS javac/java cannot read — convert to C:/.
# (The earlier corruption came from tr-stripping inside $(), not from this sed.)
CP="$CP;$(find /c/+Projects/Screenrecorder/media3-patched/libraries/common \
    -path '*compile_library_classes_jar*' -name 'classes.jar' 2>/dev/null | head -1 | sed 's|^/c/|C:/|')"

OUT=/tmp/opencode-a9parity-out
rm -rf "$OUT"; mkdir -p "$OUT"

javac -nowarn -encoding UTF-8 -cp "$CP" \
  -sourcepath "tools/jvm-harness/stubs;app/src/main/java" -d "$OUT" \
  tasks/a9/LaneChainParityTest.java || exit 1
java -cp "$OUT;$CP" LaneChainParityTest
