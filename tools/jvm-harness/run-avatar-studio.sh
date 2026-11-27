#!/usr/bin/env bash
# AvatarStudioTest: a character inside Video Studio (slice 2 / 2b of the Avatar build): the take on Studio's clock,
# splits, the voice to a mouth, acting from a video. Pure Java plus the PC stand-in for org.json; no Gradle.
# Usage:  bash tools/jvm-harness/run-avatar-studio.sh
set -u
cd "$(dirname "$0")/../.." || exit 1

OUT=tools/jvm-harness/out-avatar-studio
rm -rf "$OUT"; mkdir -p "$OUT"

javac -nowarn -encoding UTF-8 -d "$OUT" \
  $(find tools/jvm-harness/joybot-bench/shim/org/json -name '*.java') \
  app/src/main/java/cc/joycreator/avatar/perform/Take.java \
  app/src/main/java/cc/joycreator/avatar/studio/TakeClock.java \
  app/src/main/java/cc/joycreator/avatar/studio/VoiceMouth.java \
  app/src/main/java/cc/joycreator/avatar/studio/Acting.java \
  tools/jvm-harness/AvatarStudioTest.java || exit 1
java -cp "$OUT" AvatarStudioTest
