#!/usr/bin/env bash
# Work-loss audit 2026-11-10: timeline marks ride in a project's Joy Creator folder copy and come back
# on restore, and files that live inside a project go back inside it. Plain javac, no Gradle.
# Usage:  bash tools/jvm-harness/run-sidecars.sh
set -u
cd "$(dirname "$0")/../.." || exit 1
GSON=$(ls ~/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson/*/*/gson-*.jar 2>/dev/null | grep -v sources | head -1)
[ -n "$GSON" ] || { echo "no gson jar in the Gradle cache"; exit 1; }
OUT=tools/jvm-harness/out-sidecars.$$
trap 'rm -rf "$OUT"' EXIT
rm -rf "$OUT"; mkdir -p "$OUT"
SEP=":"; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";"; GSON=$(cygpath -w "$GSON");; esac
javac -nowarn -encoding UTF-8 -cp "$GSON" -d "$OUT" \
  app/src/main/java/com/fadcam/joyfolder/studio/ProjectSidecars.java   app/src/main/java/com/fadcam/joyfolder/studio/ProjectLocals.java   studiokit/src/main/java/com/fadcam/joyfolder/*.java \
  tools/jvm-harness/ProjectSidecarsTest.java || exit 1
java -cp "$OUT${SEP}$GSON" ProjectSidecarsTest
