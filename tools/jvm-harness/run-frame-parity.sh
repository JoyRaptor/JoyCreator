#!/usr/bin/env bash
# Preview/export frame parity — negative controls, then the real comparison if given one.
#
#   bash tools/jvm-harness/run-frame-parity.sh
#       Runs the negative controls only. This is what CI should run: it proves the
#       comparison can still FAIL. A check that cannot fail proves nothing, and this repo
#       has already shipped one that could not.
#
#   bash tools/jvm-harness/run-frame-parity.sh <exported.mp4> <ms> <serial> [l,t,r,b]
#       Extracts the export's frame at <ms>, screenshots the device preview (cropped to
#       the player bounds if given), and compares them.
#
# GETTING THE BOUNDS. Do not hard-code a rect; it is silently wrong on another screen:
#   adb -s <serial> shell uiautomator dump /sdcard/w.xml
#   adb -s <serial> shell cat /sdcard/w.xml | tr '>' '\n' | grep player_container
# and read the bounds="[l,t][r,b]" attribute.
#
# THE PREVIEW MUST BE SHOWING THE SAME TIME as the frame you extract, and paused. Scrub
# to <ms>, pause, then run. A mismatched timestamp is not a parity defect.
set -u
cd "$(dirname "$0")/../.." || exit 1
PY=tools/jvm-harness/frame_parity.py
OUT=tools/jvm-harness/out-frameparity
mkdir -p "$OUT"

echo "=== frame parity: negative controls ==="
python "$PY" selftest || { echo "ALL FAIL"; exit 1; }

if [ "$#" -lt 3 ]; then
  echo
  echo "no live comparison requested (negative controls only)"
  echo "ALL PASS"
  exit 0
fi

VIDEO="$1"; MS="$2"; SERIAL="$3"; BOUNDS="${4:-}"
echo
echo "=== live comparison: $VIDEO @ ${MS}ms vs $SERIAL ==="
python "$PY" extract "$VIDEO" "$MS" "$OUT/export.png" || exit 1
if [ -n "$BOUNDS" ]; then
  python "$PY" capture "$SERIAL" "$OUT/preview.png" --bounds "$BOUNDS" || exit 1
else
  python "$PY" capture "$SERIAL" "$OUT/preview.png" || exit 1
fi
python "$PY" compare "$OUT/export.png" "$OUT/preview.png" --diff "$OUT/diff.png"
RC=$?
echo "  diff image: $OUT/diff.png (differences amplified 4x)"
[ $RC -eq 0 ] && echo "ALL PASS" || echo "ALL FAIL"
exit $RC
