#!/usr/bin/env bash
# PipAudioLanesTest - STU-F6: overlapping PiPs sound together, and no PiP sound runs past the video.
set -u
cd "$(dirname "$0")/../.." || exit 1
OUT=tools/jvm-harness/out-pipaudiolanes
rm -rf "$OUT"; mkdir -p "$OUT"
javac -nowarn -encoding UTF-8 -d "$OUT" app/src/main/java/com/fadcam/ui/faditor/export/PipAudioLanes.java tools/jvm-harness/PipAudioLanesTest.java || exit 1
java -cp "$OUT" PipAudioLanesTest
