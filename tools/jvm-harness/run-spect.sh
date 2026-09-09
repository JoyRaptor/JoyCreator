#!/usr/bin/env bash
# SPEC T — an FX/keyed/blended picture must preview where its own helper frame is, at every angle.
# The same non-square fold SPEC Q found in the mesh path, measured at the SECOND call site:
# TextOverlayLayer.fxPipFor. Sweeps rotation 0-360 in 15-degree steps, all nine pivot anchors,
# both mirror states, portrait/landscape/Note-9 content rects, pinned and flat, preset on and off
# — against an independent PIXEL-space model of the flat draw. Ends with a source lint proving the
# host CALLS the shared definition instead of carrying a third transcription of it.
# Off device, seconds to run.
#
# Usage:  bash tools/jvm-harness/run-spect.sh
set -u
cd "$(dirname "$0")/../.." || exit 1

GSON=$(find "$HOME/.gradle/caches/modules-2" -name 'gson-2*.jar' 2>/dev/null | head -1 | sed 's|^/c/|C:/|')
if [ -z "$GSON" ]; then echo "no gson jar in the gradle cache"; exit 1; fi

OUT=tools/jvm-harness/out-spect
rm -rf "$OUT"; mkdir -p "$OUT"

# @argfile, not a bare -cp: a semicolon-separated classpath passed as a shell argument gets mangled
# by MSYS path conversion, and the failure is silent — javac succeeds while java reports
# NoClassDefFoundError for a jar that is demonstrably present. run-mask.sh was bitten by this.
ARGS=$(mktemp); RUNARGS=$(mktemp)
{ echo "-nowarn"; echo "-encoding UTF-8"; echo "-d $OUT";
  printf -- '-cp "%s"\n' "$GSON";
  echo '-sourcepath "app/src/main/java"'; } > "$ARGS"
printf -- '-cp "%s;%s"\n' "$OUT" "$GSON" > "$RUNARGS"

javac @"$ARGS" tools/jvm-harness/SpecTFxPipFoldTest.java || exit 1

# Positive control on the COMPILE itself: an empty out dir means the command never ran, which a
# grep for "error:" would report as success.
[ -f "$OUT/SpecTFxPipFoldTest.class" ] || { echo "no SpecTFxPipFoldTest class — the compile did not run"; exit 1; }

# The android-free property, enforced rather than trusted — it is what lets the FX Pip's own
# placement arithmetic be measured without a phone at all.
if grep -rn "^import android\|^import androidx" app/src/main/java/com/fadcam/ui/faditor/transform/mesh/; then
  echo "FAIL: an Android import leaked into the mesh engine"
  exit 1
fi

java @"$RUNARGS" SpecTFxPipFoldTest
