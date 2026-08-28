#!/usr/bin/env bash
# PSNR parity check — §5.1 of SPEC_20260828_EXPORT_GL_FRAMES
#
# Compares two exported files frame-by-frame with ffmpeg's psnr filter.
# Reports average and minimum PSNR. Anything below ~40dB average needs explaining.
#
# Usage:
#   bash tools/psnr_parity.sh <before.mp4> <after.mp4>
#   bash tools/psnr_parity.sh <before.mp4> <after.mp4> --csv
#
# The two files must be the same project exported before and after the GL change,
# at the same resolution/quality. Pixel-identical (PSNR inf) is the ideal.
#
# Requires ffmpeg on PATH.
set -u
if [ "$#" -lt 2 ]; then
  echo "Usage: $0 <before.mp4> <after.mp4> [--csv]" >&2
  exit 2
fi
A="$1"; B="$2"
if [ ! -f "$A" ]; then echo "not found: $A" >&2; exit 1; fi
if [ ! -f "$B" ]; then echo "not found: $B" >&2; exit 1; fi

# ffmpeg psnr filter: need both inputs, scale to same size if needed, psnr
# Use lavfi psnr with stats_file
TMP=$(mktemp)
# Use -lavfi "psnr=stats_file=$TMP" -f null -
# Need to handle different durations: shortest
ffmpeg -v error -i "$A" -i "$B" -lavfi "psnr=stats_file=$TMP" -f null - 2>&1 | tee /tmp/psnr_stderr.log > /dev/null

if [ ! -f "$TMP" ]; then
  echo "psnr stats not produced" >&2
  cat /tmp/psnr_stderr.log >&2
  exit 1
fi

# stats_file lines: n:1 mse_avg 0.00 mse_y 0 mse_u 0 mse_v 0 psnr_avg inf psnr_y inf ...
# For inf, treat as 100
AVG=$(awk '
  { for(i=1;i<=NF;i++) if($i=="psnr_avg") { v=$(i+1); if(v=="inf") v=100; sum+=v; n++ ; if(min==""){min=v} if(v<min) min=v } }
  END { if(n==0) {print "inf  inf"} else {printf "%.2f %.2f", sum/n, min} }' "$TMP")

MIN=$(echo "$AVG" | awk '{print $2}')
AVG_VAL=$(echo "$AVG" | awk '{print $1}')

# Also check stderr for overall PSNR
OVERALL=$(grep -o "PSNR.*avg: [0-9.]*" /tmp/psnr_stderr.log | tail -1 || echo "n/a")

echo "PSNR average: $AVG_VAL dB"
echo "PSNR minimum: $MIN dB"
echo "Overall: $OVERALL"
echo "stats: $TMP"

if [ "${3:-}" = "--csv" ]; then
  echo "avg_psnr,min_psnr,$AVG_VAL,$MIN"
fi

# Threshold check
# Use awk for float compare
awk -v avg="$AVG_VAL" 'BEGIN { if(avg != "inf" && avg < 40) { print "WARNING: average PSNR below 40dB — needs explaining per §5.1"; exit 1 } }'
RC=$?
rm -f "$TMP" /tmp/psnr_stderr.log
exit $RC
