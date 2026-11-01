#!/usr/bin/env bash
# A region export batch shares one project snapshot: its lifetime across the jobs.
# Usage:  bash tools/jvm-harness/run-sharedsnapshot.sh
set -u
cd "$(dirname "$0")/../.." || exit 1
OUT=tools/jvm-harness/out-sharedsnapshot
rm -rf "$OUT"; mkdir -p "$OUT"
javac -nowarn -encoding UTF-8 -d "$OUT" \
  app/src/main/java/com/fadcam/ui/faditor/export/SharedExportSnapshot.java \
  tools/jvm-harness/export/SharedExportSnapshotTest.java || exit 1
java -cp "$OUT" com.fadcam.ui.faditor.export.SharedExportSnapshotTest
