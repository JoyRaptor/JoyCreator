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
java -cp "$OUT" ActionRegistryTest || exit 1

# do_trick (BOT-7): the model's way to Joybot's tricks. AppCtx needs the Context stub and the Library item it can open.
OUT2=tools/jvm-harness/out-actions-joybot
rm -rf "$OUT2"; mkdir -p "$OUT2"
javac -nowarn -encoding UTF-8 -d "$OUT2" \
  tools/jvm-harness/stubs/androidx/annotation/NonNull.java \
  tools/jvm-harness/stubs/androidx/annotation/Nullable.java \
  tools/jvm-harness/stubs/android/content/Context.java \
  tools/jvm-harness/stubs/android/content/SharedPreferences.java \
  tools/jvm-harness/stubs/android/content/res/AssetManager.java \
  studiokit/src/main/java/com/fadcam/joyfolder/FileKind.java \
  app/src/main/java/com/fadcam/ui/library/LibraryItem.java \
  app/src/main/java/com/fadcam/ui/library/LibraryKind.java \
  app/src/main/java/com/fadcam/ui/faditor/ai/actions/ActionSpec.java \
  app/src/main/java/com/fadcam/ui/faditor/ai/actions/ActionRegistry.java \
  app/src/main/java/com/fadcam/ui/faditor/ai/actions/Args.java \
  app/src/main/java/com/fadcam/ui/faditor/ai/actions/ActionResult.java \
  app/src/main/java/com/fadcam/ui/faditor/ai/actions/ActionError.java \
  app/src/main/java/com/fadcam/ui/faditor/ai/actions/AppCtx.java \
  app/src/main/java/com/fadcam/ui/faditor/ai/actions/JoybotActions.java \
  tools/jvm-harness/JoybotActionsTest.java || exit 1
java -cp "$OUT2" com.fadcam.ui.faditor.ai.actions.JoybotActionsTest
