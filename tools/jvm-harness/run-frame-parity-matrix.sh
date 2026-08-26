#!/usr/bin/env bash
# Frame parity — COMBINATION MATRIX (one command)
# Proves the compositing stack, not single features alone.
#
#   bash tools/jvm-harness/run-frame-parity-matrix.sh
#       Negative controls + synthetic 8-case stacking matrix (no device).
#
#   bash tools/jvm-harness/run-frame-parity-matrix.sh <serial> [ms] [l,t,r,b]
#       Same, plus live preview vs export on device at <ms> (pause at that time first).
#
# Diff images: tools/jvm-harness/out-frameparity-matrix/*.png (4x amplified)
# Report:      tools/jvm-harness/out-frameparity-matrix/MATRIX.md
set -u
cd "$(dirname "$0")/../.." || exit 1
PY=tools/frame_parity_matrix.py
echo "=== frame parity matrix — one command ==="
if [ "$#" -ge 1 ]; then
  SERIAL="$1"; MS="${2:-1500}"; BOUNDS="${3:-}"
  if [ -n "$BOUNDS" ]; then
    python "$PY" --matrix --serial "$SERIAL" --ms "$MS" --bounds "$BOUNDS"
  else
    python "$PY" --matrix --serial "$SERIAL" --ms "$MS"
  fi
else
  python "$PY" --matrix
fi
RC=$?
if [ $RC -eq 0 ]; then echo; echo "MATRIX DONE — see tools/jvm-harness/out-frameparity-matrix/MATRIX.md"; else echo; echo "MATRIX completed with issues — see report above"; fi
exit $RC
