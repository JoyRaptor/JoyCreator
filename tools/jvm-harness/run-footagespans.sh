#!/usr/bin/env bash
# FootageSpansTest (STU-7, 11-28): which frames of a filmed clip carry its own Motion Blur, for
# the preview and the export alike. Real model classes, so the real classpath (as run-timemap.sh).
# Usage:  bash tools/jvm-harness/run-footagespans.sh
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

OUT=tools/jvm-harness/out-footagespans
rm -rf "$OUT"; mkdir -p "$OUT"

ARGS=$(mktemp); RUNARGS=$(mktemp)
trap 'rm -f "$ARGS" "$RUNARGS"' EXIT
{ echo "-nowarn"; echo "-encoding UTF-8"; echo "-d $OUT";
  printf -- '-cp "%s"\n' "$CP";
  echo '-sourcepath "tools/jvm-harness/stubs;app/src/main/java;studiokit/src/main/java"'; } > "$ARGS"
printf -- '-cp "%s;%s"\n' "$OUT" "$CP" > "$RUNARGS"

javac @"$ARGS" tools/jvm-harness/FootageSpansTest.java || exit 1
[ -f "$OUT/FootageSpansTest.class" ] || { echo "no class file: the compile did not run"; exit 1; }

# The planted fault must fail: the pre-11-28 rule dropped footage blur from any project with a
# transition.
expect_failure() {
    local log
    if log=$(java @"$RUNARGS" FootageSpansTest "$@" 2>&1); then
        printf 'Negative control passed unexpectedly: %s\n%s\n' "$*" "$log" >&2
        exit 1
    fi
    printf 'Negative control rejected (%s):\n%s\n' "$*" "$(printf '%s\n' "$log" | grep -E '^FAIL' | head -4)"
}
expect_failure --old-project-rule
java @"$RUNARGS" FootageSpansTest
