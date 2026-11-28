#!/usr/bin/env bash
# TextFxPreviewTest - STU-C6: the preview composites a text with its own effects, from the export's own drawing.
# TEXTFX_BASE=<rev> runs it against that revision's files (it must FAIL on one before the fix).
set -u
cd "$(dirname "$0")/../.." || exit 1
OUT=tools/jvm-harness/out-textfxpreview
rm -rf "$OUT"; mkdir -p "$OUT"
javac -nowarn -encoding UTF-8 -d "$OUT" tools/jvm-harness/TextFxPreviewTest.java || exit 1
ROOT=.
if [ -n "${TEXTFX_BASE:-}" ]; then
  ROOT="$OUT/base"
  for f in app/src/main/java/com/fadcam/ui/faditor/overlay/TextOverlayLayer.java app/src/main/java/com/fadcam/ui/faditor/export/TextFxGlEffect.java; do
    mkdir -p "$ROOT/$(dirname "$f")"; git show "$TEXTFX_BASE:$f" > "$ROOT/$f" || exit 1
  done
  echo "(against $TEXTFX_BASE)"
fi
java -cp "$OUT" TextFxPreviewTest "$ROOT"
