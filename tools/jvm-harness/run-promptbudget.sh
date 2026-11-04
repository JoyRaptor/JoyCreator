#!/usr/bin/env bash
# PromptBudgetTest: Joybot's model-aware size budget (BOT-3), off device. Pure Java, no Gradle.
#
# Usage:  bash tools/jvm-harness/run-promptbudget.sh
set -u
cd "$(dirname "$0")/../.." || exit 1

OUT=tools/jvm-harness/out-promptbudget
rm -rf "$OUT"; mkdir -p "$OUT"

javac -nowarn -encoding UTF-8 -d "$OUT" \
  tools/jvm-harness/stubs/androidx/annotation/NonNull.java \
  tools/jvm-harness/stubs/androidx/annotation/Nullable.java \
  app/src/main/java/com/fadcam/ui/faditor/ai/joybot/PromptBudget.java \
  tools/jvm-harness/PromptBudgetTest.java || exit 1
[ -f "$OUT/PromptBudgetTest.class" ] || { echo "no PromptBudgetTest class - compile did not run"; exit 1; }
java -cp "$OUT" PromptBudgetTest
