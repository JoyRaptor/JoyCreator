#!/usr/bin/env bash
# STU-5 primitives step 3 (hidden): the one plan the preview and the export draw a path from.
# Usage:  bash tools/jvm-harness/run-pathplan.sh
set -u
cd "$(dirname "$0")/../.." || exit 1
GSON=$(find "$HOME/.gradle/caches/modules-2" -name 'gson-2*.jar' ! -name '*sources*' 2>/dev/null | head -1 | sed 's|^/c/|C:/|')
if [ -z "$GSON" ]; then echo "no gson jar in the gradle cache"; exit 1; fi
OUT=tools/jvm-harness/out-pathplan.$$
trap 'rm -rf "$OUT"' EXIT
rm -rf "$OUT"; mkdir -p "$OUT"
javac -nowarn -encoding UTF-8 -d "$OUT" -cp "$GSON" \
  tools/jvm-harness/stubs/androidx/annotation/NonNull.java \
  tools/jvm-harness/stubs/androidx/annotation/Nullable.java \
  app/src/main/java/com/fadcam/ui/faditor/transform/mesh/AlphaContour.java \
  app/src/main/java/com/fadcam/ui/faditor/model/path/StudioPath.java \
  app/src/main/java/com/fadcam/ui/faditor/model/path/PathItem.java \
  app/src/main/java/com/fadcam/ui/faditor/model/path/PathPlan.java \
  tools/jvm-harness/PathPlanTest.java || exit 1
RUNARGS=$(mktemp)
printf -- '-cp "%s;%s"\n' "$OUT" "$GSON" > "$RUNARGS"
java @"$RUNARGS" PathPlanTest
