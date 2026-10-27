#!/usr/bin/env bash
# VisLab tracker modules (roadmap R13): read, one-pass timing, drum judging, MIDI conversion and
# playback, on a module written in the test. Pass real .mod paths to summarise them too.
# Usage:  bash tools/jvm-harness/run-mod.sh [file.mod ...]
set -u
cd "$(dirname "$0")/../.." || exit 1
OUT=tools/jvm-harness/out-mod
rm -rf "$OUT"; mkdir -p "$OUT"
javac -nowarn -encoding UTF-8 -d "$OUT" \
  tools/jvm-harness/stubs/androidx/annotation/NonNull.java \
  tools/jvm-harness/stubs/androidx/annotation/Nullable.java \
  app/src/main/java/com/fadcam/vislab/audio/MidiFile.java \
  app/src/main/java/com/fadcam/vislab/audio/ModFile.java \
  app/src/main/java/com/fadcam/vislab/audio/ModPlayer.java \
  app/src/main/java/com/fadcam/vislab/audio/ModMidi.java \
  tools/jvm-harness/ModFileTest.java || exit 1
java -cp "$OUT" ModFileTest "$@"
