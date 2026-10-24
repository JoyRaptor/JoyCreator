#!/usr/bin/env bash
# VisLab MIDI reader (roadmap R5): a tiny MIDI file written in code, every event time checked.
# Usage:  bash tools/jvm-harness/run-midi.sh
set -u
cd "$(dirname "$0")/../.." || exit 1
OUT=tools/jvm-harness/out-midi
rm -rf "$OUT"; mkdir -p "$OUT"
javac -nowarn -encoding UTF-8 -d "$OUT" \
  tools/jvm-harness/stubs/androidx/annotation/NonNull.java \
  tools/jvm-harness/stubs/androidx/annotation/Nullable.java \
  app/src/main/java/com/fadcam/vislab/audio/MidiFile.java \
  tools/jvm-harness/MidiFileTest.java || exit 1
java -cp "$OUT" MidiFileTest
