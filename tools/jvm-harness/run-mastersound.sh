#!/usr/bin/env bash
# MasterSoundTest - STU-C3: a main-track video with no sound track lays silence in the audio-only export.
# Pure Java: compiles the production MasterSound alone, with a test door in its package.
set -u
cd "$(dirname "$0")/../.." || exit 1
OUT=tools/jvm-harness/out-mastersound
rm -rf "$OUT"; mkdir -p "$OUT"
javac -nowarn -encoding UTF-8 -d "$OUT" \
  app/src/main/java/com/fadcam/ui/faditor/export/MasterSound.java \
  tools/jvm-harness/mastersound/com/fadcam/ui/faditor/export/MasterSoundProbe.java \
  tools/jvm-harness/MasterSoundTest.java || exit 1
[ -f "$OUT/MasterSoundTest.class" ] || { echo "no MasterSoundTest class - the compile did not run"; exit 1; }
java -cp "$OUT" MasterSoundTest
