#!/usr/bin/env bash
# VisLab Sprite layers (roadmap R7): the frame clock (own speed, per beat, per kick, loudness,
# hold; loop or back and forth), and the 2026-11-01 review's V1, V3, V4 and V5 (VisReviewTest).
# Usage:  bash tools/jvm-harness/run-sprite.sh
set -u
cd "$(dirname "$0")/../.." || exit 1
OUT=tools/jvm-harness/out-sprite
rm -rf "$OUT"; mkdir -p "$OUT"
javac -nowarn -encoding UTF-8 -d "$OUT" \
  tools/jvm-harness/stubs/androidx/annotation/NonNull.java \
  tools/jvm-harness/stubs/androidx/annotation/Nullable.java \
  app/src/main/java/com/fadcam/ui/faditor/waveform/Fft.java \
  app/src/main/java/com/fadcam/vislab/gl/SpriteClock.java \
  app/src/main/java/com/fadcam/vislab/room/VideoHeads.java \
  app/src/main/java/com/fadcam/vislab/studio/LookTicket.java \
  app/src/main/java/com/fadcam/vislab/studio/EarShelf.java \
  app/src/main/java/com/fadcam/vislab/audio/Features.java \
  app/src/main/java/com/fadcam/vislab/audio/OnsetDetector.java \
  app/src/main/java/com/fadcam/vislab/audio/FeatureExtractor.java \
  app/src/main/java/com/fadcam/vislab/audio/SpectrumAnalyzer.java \
  app/src/main/java/com/fadcam/vislab/audio/HitCounts.java \
  tools/jvm-harness/SpriteClockTest.java \
  tools/jvm-harness/VisReviewTest.java || exit 1
rc=0
java -cp "$OUT" SpriteClockTest || rc=1
java -cp "$OUT" VisReviewTest || rc=1
exit $rc
