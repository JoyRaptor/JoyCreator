#!/usr/bin/env bash
# ExportSafetyTest: export-safety audit 2026-11-12, off device. The free-space estimate and refusal
# rule (ExportSpace, E15) and the render-cache key following a changed gallery source
# (RenderCacheKeys + its UriStamper, E13). Plain javac, no Gradle. Needs the real org.json (the
# repo's stub throws) and the androidx annotations, both in the Gradle cache.
# Usage:  bash tools/jvm-harness/run-exportsafe.sh
set -u
cd "$(dirname "$0")/../.." || exit 1
JSON=$(find "$HOME/.gradle/caches/modules-2" -name 'json-20240303.jar' 2>/dev/null | head -1 | sed 's|^/c/|C:/|')
ANN=$(find "$HOME/.gradle/caches/modules-2" -name 'annotation-jvm-1.8.1.jar' 2>/dev/null | head -1 | sed 's|^/c/|C:/|')
if [ -z "$JSON" ] || [ -z "$ANN" ]; then echo "org.json or androidx.annotation jar not in the Gradle cache"; exit 1; fi
OUT=tools/jvm-harness/out-exportsafe.$$
trap 'rm -rf "$OUT"' EXIT
rm -rf "$OUT"; mkdir -p "$OUT"
E=app/src/main/java/com/fadcam/ui/faditor/export
javac -nowarn -encoding UTF-8 -d "$OUT" -cp "$JSON;$ANN" \
  $E/ExportSpace.java $E/RenderCacheKeys.java tools/jvm-harness/ExportSafetyTest.java || exit 1
java -cp "$OUT;$JSON;$ANN" com.fadcam.ui.faditor.export.ExportSafetyTest
