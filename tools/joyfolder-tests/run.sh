#!/usr/bin/env bash
# Compile and run the Joy Creator folder core tests with plain javac: no Gradle, so it never
# competes with the watcher. Only the pure-Java package is compiled (not .../joyfolder/android).
set -euo pipefail
cd "$(dirname "$0")/../.."
GSON=$(ls ~/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson/*/*/gson-2.13.2.jar 2>/dev/null | head -1)
if [ -z "$GSON" ]; then GSON=$(ls ~/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson/*/*/gson-*.jar | grep -v sources | head -1); fi
OUT=$(mktemp -d)
SEP=":"; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";"; OUT=$(cygpath -w "$OUT"); GSON=$(cygpath -w "$GSON");; esac
javac -Xlint:unchecked -encoding UTF-8 --release 11 -cp "$GSON" -d "$OUT" \
  studiokit/src/main/java/com/fadcam/joyfolder/*.java tools/joyfolder-tests/JoyFolderTests.java
java -cp "$OUT${SEP}$GSON" JoyFolderTests
