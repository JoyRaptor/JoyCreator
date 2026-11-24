#!/usr/bin/env bash
# TakeVoiceTest: the voice kept with a take (stretch rule, 6 ms blend, WAV). Pure Java; no Gradle.
# Usage:  bash tools/jvm-harness/run-avatar-voice.sh
set -u
cd "$(dirname "$0")/../.." || exit 1

OUT=tools/jvm-harness/out-avatar-voice
rm -rf "$OUT"; mkdir -p "$OUT"

javac -nowarn -encoding UTF-8 -d "$OUT" \
  app/src/main/java/cc/joycreator/avatar/perform/Wav.java \
  app/src/main/java/cc/joycreator/avatar/perform/TakeVoice.java \
  tools/jvm-harness/TakeVoiceTest.java || exit 1
java -cp "$OUT" TakeVoiceTest
