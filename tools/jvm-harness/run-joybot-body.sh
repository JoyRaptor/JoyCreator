#!/usr/bin/env bash
# JoybotBodyTest: Joybot's hover and body language (BOT-5), off device. Pure Java: only the annotation stubs.
#
# Usage:  bash tools/jvm-harness/run-joybot-body.sh
set -u
cd "$(dirname "$0")/../.." || exit 1
OUT=tools/jvm-harness/out-joybot-body
rm -rf "$OUT"; mkdir -p "$OUT"
javac -nowarn -encoding UTF-8 -d "$OUT" -sourcepath "tools/jvm-harness/stubs" \
  app/src/main/java/com/fadcam/ui/lobby/JoybotBody.java tools/jvm-harness/JoybotBodyTest.java || exit 1
java -cp "$OUT" com.fadcam.ui.lobby.JoybotBodyTest || exit 1
