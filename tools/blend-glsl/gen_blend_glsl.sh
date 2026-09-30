#!/usr/bin/env bash
# JB-2.20b -- generate joybrush/shaders/jb_blend.glsl from the Studio's own GLSL string, and prove it
# has not drifted. Lead ruling R23: share, don't copy.
#
#   bash tools/blend-glsl/gen_blend_glsl.sh           regenerate the committed file
#   bash tools/blend-glsl/gen_blend_glsl.sh --check   regenerate to a temp file, cmp against the
#                                                     committed one; non-zero on ANY difference
#
# Unlike gen_blend_golden.sh --model, this READS the string the GPU runs, so an edit to the Studio's
# shader that nobody regenerated for turns the check red. Needs a JDK on PATH; nothing else.
set -euo pipefail
cd "$(dirname "$0")/../.." || exit 1

GENERATOR=tools/blend-glsl/GenBlendGlsl.java
COMMITTED=joybrush/shaders/jb_blend.glsl

SRC=""
for candidate in app/src/main/java studiokit/src/main/java; do
  if [ -f "$candidate/com/fadcam/ui/faditor/model/BlendModes.java" ]; then SRC="$candidate"; break; fi
done
[ -n "$SRC" ] || { echo "blend glsl: no BlendModes.java under app/src/main/java or studiokit/src/main/java" >&2; exit 1; }

OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
# A Windows javac reads "/tmp/x" as C:\tmp\x, not as Git Bash's /tmp, and writes its classes where the
# script never looks. Hand the JDK a path it understands (cygpath -m gives C:/..., and is absent on Linux).
OUTJ=$(cygpath -m "$OUT" 2>/dev/null || echo "$OUT")
SEP=":"
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";";; esac
ARGS="$OUT/args"
{ echo "-nowarn"; echo "-encoding UTF-8"; echo "-d $OUTJ/classes";
  printf -- '-sourcepath "%s%stools/jvm-harness/stubs"\n' "$SRC" "$SEP"; } > "$ARGS"

javac @"$ARGS" "$GENERATOR"
[ -f "$OUT/classes/GenBlendGlsl.class" ] || { echo "no GenBlendGlsl.class -- the compile did not run" >&2; exit 1; }
[ -f "$OUT/classes/com/fadcam/ui/faditor/model/BlendModes.class" ] || { echo "no BlendModes.class -- the Studio's source did not compile" >&2; exit 1; }

REGEN="$OUT/jb_blend.glsl"
java -cp "$OUTJ/classes" GenBlendGlsl "$(cygpath -m "$REGEN" 2>/dev/null || echo "$REGEN")"

case "${1:-}" in
  "")
    cp "$REGEN" "$COMMITTED"
    echo "blend glsl: wrote $COMMITTED"
    ;;
  --check)
    if cmp -s "$REGEN" "$COMMITTED"; then
      echo "blend glsl: in sync with $SRC"
    else
      echo "blend glsl: OUT OF SYNC with $SRC" >&2
      echo "  The Studio's blend shader changed and $COMMITTED was not regenerated." >&2
      echo "  Fix:  bash tools/blend-glsl/gen_blend_glsl.sh   (then commit the result)" >&2
      diff -u "$COMMITTED" "$REGEN" | head -60 >&2 || true
      exit 1
    fi
    ;;
  *)
    echo "usage: bash tools/blend-glsl/gen_blend_glsl.sh [--check]" >&2
    exit 2
    ;;
esac
