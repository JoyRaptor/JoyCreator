#!/usr/bin/env bash
# Work-loss audit 2026-11-10: a project file written by someone else is noticed, and what a save
# replaces is kept. Plain javac, no Gradle.
# Usage:  bash tools/jvm-harness/run-diskstamp.sh
set -u
cd "$(dirname "$0")/../.." || exit 1
OUT=tools/jvm-harness/out-diskstamp.$$
trap 'rm -rf "$OUT"' EXIT
rm -rf "$OUT"; mkdir -p "$OUT"
javac -nowarn -encoding UTF-8 -d "$OUT" \
  app/src/main/java/com/fadcam/ui/faditor/project/DiskStamp.java \
  tools/jvm-harness/DiskStampTest.java || exit 1
java -cp "$OUT" DiskStampTest
