#!/usr/bin/env bash
# Studio object time shuttle: ObjectTimeMover (collision + push-through) and
# ObjectTimeScrubSession (ticks, snap, end cap, lock-only step-past; Codex R2-TIME-01/02).
set -e
cd "$(dirname "$0")/../.."
OUT=tools/jvm-harness/out-objtime
rm -rf "$OUT"; mkdir -p "$OUT"
javac -nowarn -encoding UTF-8 -d "$OUT" \
  app/src/main/java/com/fadcam/ui/faditor/move/ObjectTimeMover.java \
  app/src/main/java/com/fadcam/ui/faditor/move/ObjectTimeScrubSession.java \
  tools/jvm-harness/ObjectTimeMoverTest.java \
  tools/jvm-harness/ObjectTimeScrubSessionTest.java
java -cp "$OUT" ObjectTimeMoverTest
java -cp "$OUT" ObjectTimeScrubSessionTest
