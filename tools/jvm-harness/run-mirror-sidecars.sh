#!/usr/bin/env bash
# A .joyproject carries a project's timeline marks: backup and restore through the production
# classes, into a clean folder. The real org.json goes first at runtime, ahead of android.jar's stubs.
# Usage:  bash tools/jvm-harness/run-mirror-sidecars.sh
set -u
cd "$(dirname "$0")/../.." || exit 1

SDK="${ANDROID_HOME:-$HOME/AppData/Local/Android/Sdk}"
ANDROID_JAR=$(ls -d "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1 | sed 's|^/c/|C:/|')
if [ -z "$ANDROID_JAR" ]; then echo "no android.jar under $SDK/platforms"; exit 1; fi
GSON=$(find "$HOME/.gradle/caches/modules-2" -name 'gson-2*.jar' 2>/dev/null | grep -v sources | head -1 | sed 's|^/c/|C:/|')
JSON=$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.json" -name 'json-*.jar' 2>/dev/null | grep -v sources | head -1 | sed 's|^/c/|C:/|')
if [ -z "$GSON" ] || [ -z "$JSON" ]; then echo "gson or org.json jar not in the Gradle cache"; exit 1; fi

OUT=tools/jvm-harness/out-mirror-sidecars
rm -rf "$OUT"; mkdir -p "$OUT"
M=tools/jvm-harness/markers
javac -nowarn -encoding UTF-8 -d "$OUT" -cp "$JSON;$GSON;$ANDROID_JAR" \
  "$M/stubs/android/util/AtomicFile.java" "$M/stubs/com/fadcam/FLog.java" \
  tools/jvm-harness/stubs/androidx/annotation/*.java \
  app/src/main/java/com/fadcam/ui/faditor/markers/MarkerWorkspace.java \
  app/src/main/java/com/fadcam/ui/faditor/markers/MarkerWorkspaceStore.java \
  app/src/main/java/com/fadcam/ui/faditor/project/BookmarkStore.java \
  app/src/main/java/com/fadcam/joyfolder/studio/MirrorSidecars.java \
  "$M/MirrorSidecarsChecks.java" || exit 1
# An args file, as run-clipwarp.sh: a mixed relative/absolute -cp on the command line gets mangled by the shell.
RUNARGS="$OUT/run.args"
printf -- '-cp "%s;%s;%s"\n' "$OUT" "$JSON" "$GSON" > "$RUNARGS"
java @"$RUNARGS" com.fadcam.joyfolder.studio.MirrorSidecarsChecks
