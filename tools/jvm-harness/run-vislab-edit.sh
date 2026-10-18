#!/usr/bin/env bash
# VisLabEditTest — Joybot's hands on a VisLab look, off device, against the real types.json and looks.
# Pure Java + Gson (from the Gradle cache; the app uses the same library).
#
# Usage:  bash tools/jvm-harness/run-vislab-edit.sh
set -u
cd "$(dirname "$0")/../.." || exit 1

GSON=$(find "$HOME/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson" -name 'gson-2*.jar' ! -name '*sources*' 2>/dev/null | sort | tail -1)
[ -n "$GSON" ] || { echo "no gson jar in the Gradle cache — build the app once first"; exit 1; }
OUT=tools/jvm-harness/out-vislab-edit
rm -rf "$OUT"; mkdir -p "$OUT"

javac -nowarn -encoding UTF-8 -cp "$GSON" -d "$OUT" \
  tools/jvm-harness/stubs/androidx/annotation/NonNull.java \
  tools/jvm-harness/stubs/androidx/annotation/Nullable.java \
  app/src/main/java/com/fadcam/ui/faditor/ai/actions/ActionError.java \
  app/src/main/java/com/fadcam/ui/faditor/ai/actions/VisLabEdit.java \
  tools/jvm-harness/VisLabEditTest.java || exit 1
CP="$OUT:$GSON"
# Windows java wants Windows paths and ";" (Git Bash).
command -v cygpath >/dev/null 2>&1 && CP="$(cygpath -w "$OUT");$(cygpath -w "$GSON")"
java -cp "$CP" VisLabEditTest
