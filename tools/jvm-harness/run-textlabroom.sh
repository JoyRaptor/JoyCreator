#!/usr/bin/env bash
# TextLabRoomTest -- the TextLab room's logic off the phone (slider groups, pieces, progress, export framing, undo,
# the shelf's "your copy wins"), against the real parts.json and looks. Usage: bash tools/jvm-harness/run-textlabroom.sh
set -u
cd "$(dirname "$0")/../.." || exit 1
SDK="${ANDROID_HOME:-$HOME/AppData/Local/Android/Sdk}"
ANDROID_JAR=$(ls -d "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1 | sed 's|^/c/|C:/|')
JSON=$(find "$HOME/.gradle/caches/modules-2" -name 'json-2*.jar' 2>/dev/null | head -1 | sed 's|^/c/|C:/|')
GSON=$(find "$HOME/.gradle/caches/modules-2" -name 'gson-2*.jar' 2>/dev/null | head -1 | sed 's|^/c/|C:/|')
if [ -z "$ANDROID_JAR" ] || [ -z "$JSON" ]; then echo "need android.jar and org.json in the Gradle cache"; exit 1; fi
ANNO=$(find "$HOME/.gradle/caches" -name 'annotation-1*.jar' 2>/dev/null | head -1 | sed 's|^/c/|C:/|')
OUT=tools/jvm-harness/out-textlabroom
rm -rf "$OUT"; mkdir -p "$OUT/anno"
# only the androidx annotation stand-ins: the harness's stubs folder also holds an org.json stand-in that would hide the real one
cp -r tools/jvm-harness/stubs/androidx "$OUT/anno/"
# the real org.json goes BEFORE android.jar (whose org.json is only stubs)
# the app's R classes (from its last build) and the AndroidX jars the room's classes name
RJAR=$(find app/build/intermediates -name R.jar -path '*defaultDebug*' 2>/dev/null | head -1)
if [ -z "$RJAR" ]; then echo "build the app once first (its R.jar is needed)"; exit 1; fi
AX="$(find "$HOME/.gradle/caches" -path '*/transforms/*' -name 'classes.jar' 2>/dev/null | grep -E '/(core-1\.1[0-9.]+|appcompat-1[0-9.]+|recyclerview-1[0-9.]+|lifecycle-common[^/]*|documentfile-1[0-9.]+)/' | sed 's|^/c/|C:/|' | tr '
' ';')"
CP="$JSON;$ANDROID_JAR;$GSON;$ANNO;$RJAR;$AX"
javac -nowarn -encoding UTF-8 -proc:none -d "$OUT" -cp "$CP" -implicit:class \
  -sourcepath "$OUT/anno;app/src/main/java;studiokit/src/main/java" \
  tools/jvm-harness/TextLabRoomTest.java app/src/main/java/cc/joycreator/textlab/TlUndo.java app/src/main/java/cc/joycreator/textlab/LookKnobs.java app/src/main/java/cc/joycreator/textlab/LookFrame.java 2>&1 | grep -v "^Note:" | head -30
java -cp "$OUT;$CP" TextLabRoomTest
