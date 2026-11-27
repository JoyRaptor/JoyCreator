#!/usr/bin/env bash
# SolidColourTest (Avatar build, Studio reuse item 6): the Solid colour clip's still and name. Pure Java; no Gradle.
# Usage:  bash tools/jvm-harness/run-solid-colour.sh
set -u
cd "$(dirname "$0")/../.." || exit 1

OUT=tools/jvm-harness/out-solid-colour
rm -rf "$OUT"; mkdir -p "$OUT"

javac -nowarn -encoding UTF-8 -d "$OUT" \
  app/src/main/java/com/fadcam/ui/faditor/model/SolidColour.java \
  tools/jvm-harness/SolidColourTest.java || exit 1
java -cp "$OUT" SolidColourTest
