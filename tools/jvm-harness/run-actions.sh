#!/usr/bin/env bash
# ActionRegistryTest — Joybot's action list (gap F2), off device. Pure Java: only the androidx
# annotation stubs are needed, so this compiles in seconds and never touches Gradle.
#
# Usage:  bash tools/jvm-harness/run-actions.sh
set -u
cd "$(dirname "$0")/../.." || exit 1

OUT=tools/jvm-harness/out-actions
rm -rf "$OUT"; mkdir -p "$OUT"

javac -nowarn -encoding UTF-8 -d "$OUT" \
  tools/jvm-harness/stubs/androidx/annotation/NonNull.java \
  tools/jvm-harness/stubs/androidx/annotation/Nullable.java \
  app/src/main/java/com/fadcam/ui/faditor/ai/actions/ActionSpec.java \
  app/src/main/java/com/fadcam/ui/faditor/ai/actions/ActionRegistry.java \
  app/src/main/java/com/fadcam/ui/faditor/ai/actions/Args.java \
  app/src/main/java/com/fadcam/ui/faditor/ai/actions/ActionResult.java \
  app/src/main/java/com/fadcam/ui/faditor/ai/actions/ActionError.java \
  app/src/main/java/com/fadcam/ui/faditor/ai/joybot/ToolDocs.java \
  tools/jvm-harness/ActionRegistryTest.java || exit 1
[ -f "$OUT/ActionRegistryTest.class" ] || { echo "no ActionRegistryTest class — compile did not run"; exit 1; }
java -cp "$OUT" ActionRegistryTest
