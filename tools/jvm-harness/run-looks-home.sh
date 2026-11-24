#!/usr/bin/env bash
# SAFE-1: TextLab looks saved while the Joy Creator folder was out of reach come home, and are never
# deleted before the copy is proven. Plain javac, no Gradle.
# Usage:  bash tools/jvm-harness/run-looks-home.sh
set -u
cd "$(dirname "$0")/../.." || exit 1
OUT=tools/jvm-harness/out-looks-home.$$
trap 'rm -rf "$OUT"' EXIT
rm -rf "$OUT"; mkdir -p "$OUT"
javac -nowarn -encoding UTF-8 -d "$OUT" \
  app/src/main/java/cc/joycreator/textlab/LooksComeHome.java \
  tools/jvm-harness/LooksComeHomeTest.java || exit 1
java -cp "$OUT" LooksComeHomeTest
