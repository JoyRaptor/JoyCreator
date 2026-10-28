#!/usr/bin/env bash
# BridgeTest: the bring-your-own-AI bridge's protocol and pairing rules (BOT-6), off device.
# Pure Java plus the PC stand-in for org.json; compiles in seconds, never touches Gradle.
#
# Usage:  bash tools/jvm-harness/run-bridge.sh
set -u
cd "$(dirname "$0")/../.." || exit 1

OUT=tools/jvm-harness/out-bridge
rm -rf "$OUT"; mkdir -p "$OUT"

javac -nowarn -encoding UTF-8 -d "$OUT" \
  tools/jvm-harness/stubs/androidx/annotation/NonNull.java \
  tools/jvm-harness/stubs/androidx/annotation/Nullable.java \
  $(find tools/jvm-harness/joybot-bench/shim/org/json -name '*.java') \
  app/src/main/java/com/fadcam/ui/faditor/ai/bridge/McpCore.java \
  app/src/main/java/com/fadcam/ui/faditor/ai/bridge/BridgeAuth.java \
  app/src/main/java/com/fadcam/ui/faditor/ai/bridge/BridgeWords.java \
  tools/jvm-harness/BridgeTest.java || exit 1
java -cp "$OUT" BridgeTest
