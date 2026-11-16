#!/usr/bin/env bash
# VisLab folds 5.1 / 7.1 songs down to stereo (Codex audit 11-15, LAB-07).
# See StereoDownmixTest.java.
# Usage:  bash tools/jvm-harness/run-downmix.sh
set -u
cd "$(dirname "$0")/../.." || exit 1

GSON=$(find "$HOME/.gradle/caches/modules-2" -name 'gson-2*.jar' 2>/dev/null | head -1 | sed 's|^/c/|C:/|')
if [ -z "$GSON" ]; then echo "no gson jar in the gradle cache"; exit 1; fi

OUT=tools/jvm-harness/out-downmix
rm -rf "$OUT"; mkdir -p "$OUT"

ARGS=$(mktemp); RUNARGS=$(mktemp)
{ echo "-nowarn"; echo "-encoding UTF-8"; echo "-d $OUT";
  printf -- '-cp "%s"\n' "$GSON";
  echo '-sourcepath "tools/jvm-harness/stubs;app/src/main/java;studiokit/src/main/java"'; } > "$ARGS"
printf -- '-cp "%s;%s"\n' "$OUT" "$GSON" > "$RUNARGS"

javac @"$ARGS" tools/jvm-harness/StereoDownmixTest.java || exit 1
[ -f "$OUT/com/fadcam/vislab/audio/StereoDownmixTest.class" ] || { echo "no class file - the compile did not run"; exit 1; }

java @"$RUNARGS" com.fadcam.vislab.audio.StereoDownmixTest
