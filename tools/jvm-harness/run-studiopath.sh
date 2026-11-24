#!/usr/bin/env bash
# STU-5 primitives step 1: StudioPath (the path object's shape). Pure Java, no Gradle.
# Usage:  bash tools/jvm-harness/run-studiopath.sh
set -u
cd "$(dirname "$0")/../.." || exit 1
OUT=tools/jvm-harness/out-studiopath.$$
trap 'rm -rf "$OUT"' EXIT
rm -rf "$OUT"; mkdir -p "$OUT"
javac -nowarn -encoding UTF-8 -d "$OUT" \
  app/src/main/java/com/fadcam/ui/faditor/transform/mesh/AlphaContour.java \
  app/src/main/java/com/fadcam/ui/faditor/model/path/StudioPath.java \
  tools/jvm-harness/StudioPathTest.java || exit 1
java -cp "$OUT" StudioPathTest
