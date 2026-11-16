#!/usr/bin/env bash
# Codex audit 11-15, TextLab and packs (tasks/audits/codex/ROUTING_20261115.md): typing in TextLab's undo (LAB-06),
# the unsaved-work draft (KNOWN-LAB-01), an export's Stop on its last step (LAB-04), a pack update as one step
# (R2-PACK-01), pack cache folders that never collide (R2-PACK-02), a failed read keeping the last good copy
# (R2-PACK-03). The REAL classes on temporary folders; plain javac, no Gradle. Compiled against android.jar (never
# run: the tests touch only plain files); the log class is the harness stand-in.
# Usage:  bash tools/jvm-harness/run-labs-audit.sh
set -u
cd "$(dirname "$0")/../.." || exit 1
SDK="${ANDROID_HOME:-$HOME/AppData/Local/Android/Sdk}"
AJ=$(ls -d "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1 | sed 's|^/c/|C:/|')
[ -n "$AJ" ] || { echo "no android.jar under $SDK/platforms"; exit 1; }
ANN=$(find "$HOME/.gradle/caches/modules-2" -name 'annotation-jvm-*.jar' ! -name '*sources*' 2>/dev/null | head -1 | sed 's|^/c/|C:/|')
GSON=$(ls ~/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson/*/*/gson-*.jar 2>/dev/null | grep -v sources | head -1 | sed 's|^/c/|C:/|')
JSON=$(find "$HOME/.gradle/caches/modules-2" -name 'json-2*.jar' ! -name '*sources*' 2>/dev/null | head -1 | sed 's|^/c/|C:/|')
[ -n "$GSON" ] && [ -n "$JSON" ] || { echo "need gson and org.json jars in the Gradle cache"; exit 1; }
# the real org.json goes BEFORE android.jar (whose org.json is only stubs)
CP="$JSON;$AJ;$ANN;$GSON"
OUT=tools/jvm-harness/out-labs-audit.$$
trap 'rm -rf "$OUT"' EXIT
rm -rf "$OUT"; mkdir -p "$OUT"
javac -nowarn -encoding UTF-8 -proc:none -d "$OUT" -cp "$CP" -implicit:class \
  -sourcepath "app/src/main/java;studiokit/src/main/java" \
  tools/jvm-harness/stubs/com/fadcam/FLog.java \
  app/src/main/java/cc/joycreator/textlab/TlUndo.java \
  app/src/main/java/cc/joycreator/textlab/TlDraft.java \
  app/src/main/java/cc/joycreator/textlab/StopStream.java \
  app/src/main/java/cc/joycreator/textlab/ExportStopped.java \
  tools/jvm-harness/labs-audit/cc/joycreator/textlab/TextLabAuditTest.java \
  tools/jvm-harness/labs-audit/com/fadcam/joyfolder/studio/PackAuditTest.java || exit 1
RUNARGS=$(mktemp)
printf -- '-cp "%s;%s"\n' "$OUT" "$CP" > "$RUNARGS"
rc=0
echo "TextLab:"; java @"$RUNARGS" cc.joycreator.textlab.TextLabAuditTest || rc=1
echo "Packs:"; java @"$RUNARGS" com.fadcam.joyfolder.studio.PackAuditTest || rc=1
exit $rc
