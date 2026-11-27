#!/usr/bin/env bash
# VoiceoverTapTest (X4, Avatar build): Studio's VoiceoverRecorder gives a sample listener and a level, its WAV bytes
# unchanged. Pure Java (PcmBlocks only); no Gradle.
# Usage:  bash tools/jvm-harness/run-voiceover-tap.sh
set -u
cd "$(dirname "$0")/../.." || exit 1

OUT=tools/jvm-harness/out-voiceover-tap
rm -rf "$OUT"; mkdir -p "$OUT"

javac -nowarn -encoding UTF-8 -d "$OUT" \
  app/src/main/java/com/fadcam/ui/faditor/audio/PcmBlocks.java \
  tools/jvm-harness/VoiceoverTapTest.java || exit 1
java -cp "$OUT" VoiceoverTapTest
