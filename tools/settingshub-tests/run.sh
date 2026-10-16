#!/usr/bin/env bash
# Compile and run the Settings hub's pure-logic tests with plain javac: no Gradle, so it never
# competes with the watcher. Only the Android-free files of com.fadcam.ui.settingshub are compiled;
# the tests read the catalog, the strings and the classic Settings layout from the source tree.
set -euo pipefail
cd "$(dirname "$0")/../.."
OUT=$(mktemp -d)
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) OUT=$(cygpath -w "$OUT");; esac
HUB=app/src/main/java/com/fadcam/ui/settingshub
javac -Xlint:unchecked -encoding UTF-8 --release 11 -d "$OUT" \
  $HUB/SettingsKey.java $HUB/SettingsSearch.java $HUB/SettingsLines.java \
  tools/settingshub-tests/SettingsHubTests.java
java -Dfile.encoding=UTF-8 -cp "$OUT" SettingsHubTests
