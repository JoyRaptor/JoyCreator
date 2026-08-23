#!/usr/bin/env bash
# BakedAudioParseTest — C2.E loudnorm JSON parsing against REAL ffmpeg pass-1 logs.
#
# Same classpath archaeology as run-matte.sh (needs the com.arthenica ffmpeg-kit jars on
# CP to compile BakedAudioCache itself). The two log files under test are captured by
# running desktop ffmpeg with BakedAudioCache's exact filter strings — see
# tasks/SPEC_AUDIO_UX_V1.md row C2.E for the capture commands.
#
# Usage:  bash tools/jvm-harness/run-bakedparse.sh <pass1.log> <silence.log>
set -u
cd "$(dirname "$0")/../.." || exit 1

[ $# -ge 2 ] || { echo "usage: run-bakedparse.sh <pass1.log> <silence.log>"; exit 1; }

SDK="${ANDROID_HOME:-$HOME/AppData/Local/Android/Sdk}"
ANDROID_JAR=$(ls -d "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1 | sed 's|^/c/|C:/|')
if [ -z "$ANDROID_JAR" ]; then echo "no android.jar under $SDK/platforms"; exit 1; fi

M3=$(grep '^media3.patched.path' local.properties 2>/dev/null | cut -d= -f2- | tr -d '\r')
if [ -z "$M3" ]; then M3="/tmp/media3-patched"; fi

CP="$ANDROID_JAR"
CP="$CP;$(find "$HOME/.gradle/caches" -path '*/transforms/*' -name '*.jar' 2>/dev/null | sed 's|^/c/|C:/|' | tr '\n' ';')"
CP="$CP$(find "$M3/libraries" -path '*compile_library_classes_jar*' -name 'classes.jar' 2>/dev/null | tr '\n' ';')"
CP="$CP$(find "$HOME/.gradle/caches/modules-2" -name 'gson-2*.jar' 2>/dev/null | head -1 | sed 's|^/c/|C:/|')"

OUT=tools/jvm-harness/out-bakedparse
rm -rf "$OUT"; mkdir -p "$OUT"

ARGS=$(mktemp); RUNARGS=$(mktemp)
{ echo "-nowarn"; echo "-encoding UTF-8"; echo "-d $OUT";
  printf -- '-cp "%s"\n' "$CP";
  echo '-sourcepath "tools/jvm-harness/stubs;app/src/main/java"'; } > "$ARGS"
printf -- '-cp "%s;%s"\n' "$OUT" "$CP" > "$RUNARGS"

# NOTE: the test reaches parseMeasured by REFLECTION, so javac sees no dependency on
# BakedAudioCache — list its SOURCE explicitly or its .class never lands in OUT.
javac @"$ARGS" tools/jvm-harness/BakedAudioParseTest.java app/src/main/java/com/fadcam/ui/faditor/audio/BakedAudioCache.java || exit 1
# Positive control on the COMPILE itself.
[ -f "$OUT/BakedAudioParseTest.class" ] || { echo "no class file — compile did not run"; exit 1; }
java @"$RUNARGS" BakedAudioParseTest "$(cygpath -w "$1")" "$(cygpath -w "$2")"
