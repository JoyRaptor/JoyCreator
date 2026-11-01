#!/usr/bin/env bash
# An audio-only range export is the sound pass, cut: the time mapping, and the real ffmpeg cut.
# Needs ffmpeg and ffprobe on the path.
# Usage:  bash tools/jvm-harness/run-audiorangecut.sh
set -u
cd "$(dirname "$0")/../.." || exit 1
OUT=tools/jvm-harness/out-audiorangecut
rm -rf "$OUT"; mkdir -p "$OUT"
javac -nowarn -encoding UTF-8 -d "$OUT" \
  app/src/main/java/com/fadcam/ui/faditor/export/AudioRangeCut.java \
  tools/jvm-harness/export/AudioRangeCutTest.java || exit 1
java -cp "$OUT" com.fadcam.ui.faditor.export.AudioRangeCutTest
