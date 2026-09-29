#!/usr/bin/env bash
# JB-2.20a — build the Studio's blend maths into Joy Brush's golden table, and prove it has not
# drifted. Lead ruling R23.
#
# WHY THIS FILE EXISTS. Joy Brush's core is pure Kotlin (the iOS door), so it cannot call the
# Studio's Java `BlendModes`. The two copies of the same 26 equations are therefore tied together
# by a GENERATED table plus this drift check — never by somebody reading both and deciding they
# agree. A hand-copied table would be a failed task even with every test green, because the values
# would be a second opinion rather than an observation.
#
# What it does:
#   (no flag)   regenerate BlendGolden.kt in place
#   --check     regenerate to a temp file and diff against the committed one; non-zero on ANY
#               difference. Run this in CI: it is what notices that BlendModes.java changed and
#               nobody re-ran the generator.
#   --model     re-derive every row from the Studio's GLSL source (a SECOND, independent
#               transcription, in Python) and diff that against the Java-generated table. Catches
#               the case where BlendModes.java's Java mirror and its GLSL have drifted from each
#               other, which no Java-side check can see.
#
# Usage:
#   bash tools/gen_blend_golden.sh            # regenerate
#   bash tools/gen_blend_golden.sh --check    # drift check
#   bash tools/gen_blend_golden.sh --model    # independent GLSL cross-check
#   bash tools/gen_blend_golden.sh --all      # regenerate, then both checks
#
# Needs: a JDK (javac/java) on PATH and python3. Nothing else — no Android SDK, no Gradle, no
# device. BlendModes.java is Android-free apart from androidx.annotation, which comes from the
# harness's existing stub sourcepath.
set -euo pipefail
cd "$(dirname "$0")/.." || exit 1

GENERATOR=tools/blend-golden/GenBlendGolden.java
MODEL=tools/blend-golden/glsl_model.py
COMMITTED=joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/blend/BlendGolden.kt

# app/ before D.05, studiokit/ after it. Both are accepted so this script does not have to be
# edited when BlendModes moves — a script that must be edited is a script that will not be run.
SRC=""
for candidate in app/src/main/java studiokit/src/main/java; do
  if [ -f "$candidate/com/fadcam/ui/faditor/model/BlendModes.java" ]; then
    SRC="$candidate"
    break
  fi
done
if [ -z "$SRC" ]; then
  echo "blend golden: no BlendModes.java under app/src/main/java or studiokit/src/main/java" >&2
  exit 1
fi

OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT

# @argfile, not a bare -cp: a semicolon-separated classpath handed to javac as a shell argument
# gets mangled by MSYS path conversion, and the failure is silent — javac sees the jar, java does
# not. tools/jvm-harness/run-fx.sh was bitten by exactly this.
ARGS="$OUT/args"
{ echo "-nowarn"; echo "-encoding UTF-8"; echo "-d $OUT/classes";
  printf -- '-sourcepath "%s;tools/jvm-harness/stubs"\n' "$SRC"; } > "$ARGS"

echo "blend golden: compiling $SRC/com/fadcam/ui/faditor/model/BlendModes.java"
javac @"$ARGS" "$GENERATOR"
# Positive control on the COMPILE: an empty classes dir means javac never ran, which a grep for
# "error:" would report as success.
[ -f "$OUT/classes/GenBlendGolden.class" ] || { echo "no GenBlendGolden.class — the compile did not run" >&2; exit 1; }
[ -f "$OUT/classes/com/fadcam/ui/faditor/model/BlendModes.class" ] || { echo "no BlendModes.class — the Studio's source did not compile" >&2; exit 1; }

REGEN="$OUT/BlendGolden.kt"
java -cp "$OUT/classes" GenBlendGolden "$REGEN"

run_model_check() {
  echo "blend golden: independent GLSL cross-check"
  python3 "$MODEL" "$COMMITTED"
}

case "${1:---all}" in
  --all)
    cp "$REGEN" "$COMMITTED"
    echo "blend golden: wrote $COMMITTED"
    run_model_check
    ;;
  --check)
    # The diff is on BYTES, not on "the numbers look right". A regeneration that differs by a
    # single literal has to fail, because that literal is the Studio's answer to something.
    if cmp -s "$REGEN" "$COMMITTED"; then
      echo "blend golden: in sync with $SRC"
      run_model_check
    else
      echo "blend golden: OUT OF SYNC with $SRC" >&2
      echo "  BlendModes.java's arithmetic changed and $COMMITTED was not regenerated." >&2
      echo "  Fix:  bash tools/gen_blend_golden.sh          (then commit the result)" >&2
      echo "  Or, if the change was an accident:  git restore $COMMITTED" >&2
      diff -u "$COMMITTED" "$REGEN" | head -60 >&2 || true
      exit 1
    fi
    ;;
  --model)
    run_model_check
    ;;
  *)
    echo "usage: bash tools/gen_blend_golden.sh [--check|--model|--all]" >&2
    exit 2
    ;;
esac
