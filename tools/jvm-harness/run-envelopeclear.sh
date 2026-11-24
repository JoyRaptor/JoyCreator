#!/usr/bin/env bash
# STU-4: the audio drawer's Clear is one undoable step and the row re-reads. Plain javac, no Gradle.
# Usage:  bash tools/jvm-harness/run-envelopeclear.sh
set -u
cd "$(dirname "$0")/../.." || exit 1
OUT=tools/jvm-harness/out-envelopeclear.$$
trap 'rm -rf "$OUT"' EXIT
rm -rf "$OUT"; mkdir -p "$OUT"
javac -nowarn -encoding UTF-8 -d "$OUT" \
  tools/jvm-harness/stubs/androidx/annotation/NonNull.java \
  tools/jvm-harness/stubs/androidx/annotation/Nullable.java \
  app/src/main/java/com/fadcam/ui/faditor/model/VolumeKeyframe.java \
  app/src/main/java/com/fadcam/ui/faditor/tools/EnvelopeClearStep.java \
  tools/jvm-harness/EnvelopeClearStepTest.java || exit 1
java -cp "$OUT" EnvelopeClearStepTest
