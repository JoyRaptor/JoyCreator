#!/usr/bin/env bash
# Compile and run the Library's pure-logic tests with plain javac: no Gradle, so it never competes
# with the watcher. Only the Android-free files of com.fadcam.ui.library are compiled, plus the one
# folder-core class they read (FileKind).
set -euo pipefail
cd "$(dirname "$0")/../.."
OUT=$(mktemp -d)
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) OUT=$(cygpath -w "$OUT");; esac
LIB=app/src/main/java/com/fadcam/ui/library
javac -Xlint:unchecked -encoding UTF-8 --release 11 -d "$OUT" \
  studiokit/src/main/java/com/fadcam/joyfolder/FileKind.java \
  $LIB/LibraryKind.java $LIB/LibraryItem.java $LIB/LibraryClassifier.java \
  $LIB/LibraryFilter.java $LIB/LibrarySections.java \
  tools/library-tests/LibraryTests.java
java -cp "$OUT" LibraryTests
