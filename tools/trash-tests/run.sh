#!/usr/bin/env bash
# Compile and run the Recently Deleted screen's pure-logic tests with plain javac: no Gradle, so it
# never competes with the watcher. Only the Android-free files of com.fadcam.ui.trash are compiled,
# plus the Library's pure files they lean on and the folder-core FileKind.
set -euo pipefail
cd "$(dirname "$0")/../.."
OUT=$(mktemp -d)
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) OUT=$(cygpath -w "$OUT");; esac
LIB=app/src/main/java/com/fadcam/ui/library
TR=app/src/main/java/com/fadcam/ui/trash
javac -Xlint:unchecked -encoding UTF-8 --release 11 -d "$OUT" \
  studiokit/src/main/java/com/fadcam/joyfolder/FileKind.java \
  $LIB/LibraryKind.java $LIB/LibraryItem.java $LIB/LibraryClassifier.java \
  $TR/TrashKind.java $TR/TrashEntry.java $TR/TrashClassifier.java $TR/TrashExpiry.java \
  $TR/TrashFilter.java $TR/TrashSelection.java \
  tools/trash-tests/TrashTests.java
java -cp "$OUT" TrashTests
