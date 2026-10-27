#!/usr/bin/env bash
# Joy Brush "Send to Studio" into an existing project: placement and sheet-id rules.
# Usage:  bash tools/jvm-harness/run-boardattach.sh
set -u
cd "$(dirname "$0")/../.." || exit 1
OUT=tools/jvm-harness/out-boardattach
rm -rf "$OUT"; mkdir -p "$OUT"
javac -nowarn -encoding UTF-8 -d "$OUT" \
  app/src/main/java/com/fadcam/ui/faditor/sprite/BoardAttachPlan.java \
  tools/jvm-harness/BoardAttachPlanTest.java || exit 1
java -cp "$OUT" BoardAttachPlanTest
