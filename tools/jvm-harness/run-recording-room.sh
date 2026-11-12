#!/usr/bin/env bash
# Work-loss audit F5 (2026-11-12): the room a recording needs, when it must stop, and a recording file that
# stays whole when a write fails. The REAL RecordingRoom and FragmentFileWriter. Plain javac, no Gradle.
# Usage:  bash tools/jvm-harness/run-recording-room.sh
set -u
cd "$(dirname "$0")/../.." || exit 1
OUT=tools/jvm-harness/out-recroom.$$
trap 'rm -rf "$OUT"' EXIT
rm -rf "$OUT"; mkdir -p "$OUT"
javac -nowarn -encoding UTF-8 -d "$OUT" \
  app/src/main/java/com/fadcam/utils/RecordingRoom.java \
  app/src/main/java/com/fadcam/media/FragmentFileWriter.java \
  tools/jvm-harness/recroom/RecordingRoomTest.java || exit 1
java -cp "$OUT" RecordingRoomTest
