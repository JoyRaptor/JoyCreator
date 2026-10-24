#!/usr/bin/env bash
# VisLab Sprite layers (roadmap R7): the frame clock (own speed, per beat, per kick, loudness,
# hold; loop or back and forth).
# Usage:  bash tools/jvm-harness/run-sprite.sh
set -u
cd "$(dirname "$0")/../.." || exit 1
OUT=tools/jvm-harness/out-sprite
rm -rf "$OUT"; mkdir -p "$OUT"
javac -nowarn -encoding UTF-8 -d "$OUT" \
  app/src/main/java/com/fadcam/vislab/gl/SpriteClock.java \
  tools/jvm-harness/SpriteClockTest.java || exit 1
java -cp "$OUT" SpriteClockTest
