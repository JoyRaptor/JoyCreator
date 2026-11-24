#!/usr/bin/env bash
# STU-5: the one snapping authority. Pure Java, no Gradle.
# Usage:  bash tools/jvm-harness/run-snap.sh
set -u
cd "$(dirname "$0")/../.." || exit 1
OUT=tools/jvm-harness/out-snap.$$
trap 'rm -rf "$OUT"' EXIT
rm -rf "$OUT"; mkdir -p "$OUT"
javac -nowarn -encoding UTF-8 -d "$OUT" \
  studiokit/src/main/java/com/fadcam/ui/faditor/tools/SnapAuthority.java \
  tools/jvm-harness/SnapAuthorityTest.java || exit 1
java -cp "$OUT" SnapAuthorityTest
