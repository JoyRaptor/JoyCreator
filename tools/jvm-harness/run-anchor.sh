#!/usr/bin/env bash
# AnchorShiftTest — §3a track-matte visibility, off device, seconds to run.
#
# Unlike the caption harness this one runs against the REAL model classes rather than
# stubs (Timeline/Clip/Track drag in android.net.Uri and media3), so it needs a real
# classpath. Deriving that was 20 minutes of archaeology; it lives here so nobody repeats it:
#
#   - android.jar from the SDK platform
#   - every transformed AAR jar in the gradle cache
#   - the PATCHED media3 build (local.properties: media3.patched.path) — media3-common is
#     NOT in the gradle cache at all, because this project builds it from source
#   - androidx.annotation comes from tools/jvm-harness/stubs, as the other harnesses do
#
# The classpath is ~70KB, which is longer than a Windows command line accepts. javac and
# java BOTH failed on it in a way that does not say so: the shell reports "Argument list
# too long" on stderr, which contains no "error:" line, so a grep for compile errors comes
# back clean and the output directory is silently empty. Hence @argfile, and hence the
# explicit "no class file" check below.
#
# Usage:  bash tools/jvm-harness/run-matte.sh
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

OUT=tools/jvm-harness/out-anchorshift
rm -rf "$OUT"; mkdir -p "$OUT"

ARGS=$(mktemp); RUNARGS=$(mktemp)
{ echo "-nowarn"; echo "-encoding UTF-8"; echo "-d $OUT";
  printf -- '-cp "%s"\n' "$CP";
  echo '-sourcepath "tools/jvm-harness/stubs;app/src/main/java"'; } > "$ARGS"
printf -- '-cp "%s;%s"\n' "$OUT" "$CP" > "$RUNARGS"

javac @"$ARGS" tools/jvm-harness/AnchorShiftTest.java || exit 1
# The positive control on the COMPILE itself: an empty out dir means the command never ran.
[ -f "$OUT/AnchorShiftTest.class" ] || { echo "no class file — the compile did not run"; exit 1; }
java @"$RUNARGS" AnchorShiftTest
