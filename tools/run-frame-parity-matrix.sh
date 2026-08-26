#!/usr/bin/env bash
# Frame parity — COMBINATION MATRIX (one command)
#
# Proves the compositing stack, not single features alone.
#
#   bash tools/run-frame-parity-matrix.sh
#       Negative controls + synthetic 8-case stacking matrix (no device).
#
#   bash tools/run-frame-parity-matrix.sh <serial> [ms] [l,t,r,b]
#       Same, plus live preview vs export on device at <ms> (pause at that time first).
#       Bounds: l,t,r,b from `adb -s <serial> shell uiautomator dump /sdcard/w.xml`
#
# Diff images: tools/jvm-harness/out-frameparity-matrix/*.png (4x amplified)
# Report:      tools/jvm-harness/out-frameparity-matrix/MATRIX.md
#
# Combination coverage (every bug this month was a stack):
#   01 mask x blend, 02 mask on NORMAL, 03 FX+blend, 04 adjustment+mask,
#   05 track matte still, 06 crop+blend, 07 two images blending, 08 z mixed
set -u
cd "$(dirname "$0")/.." || exit 1
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
if [ $RC -eq 0 ]; then
  echo
  echo "MATRIX DONE — see tools/jvm-harness/out-frameparity-matrix/MATRIX.md"
else
  echo
  echo "MATRIX completed with issues — see report above"
fi
exit $RC
