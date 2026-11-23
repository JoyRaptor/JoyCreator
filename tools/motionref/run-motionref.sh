#!/usr/bin/env bash
# STU-7 footage reference tool: self test against an analytic smear, with two negative controls.
set -eu
cd "$(dirname "$0")/../.."
OUT=tools/motionref/out
rm -rf "$OUT"; mkdir -p "$OUT"
javac -nowarn -encoding UTF-8 -d "$OUT" tools/motionref/MotionRef.java
for m in --srgb-average --no-partial; do
  if log=$(java -cp "$OUT" MotionRef selftest "$m" 2>&1); then
    echo "Negative control passed unexpectedly: $m" >&2; exit 1
  fi
  [[ "$log" == *"AssertionError: exposure matches the analytic box smear"* ]] \
    || { echo "Negative control failed for the wrong reason: $m"; echo "$log"; exit 1; }
  echo "Negative control rejected: $m"
done
java -cp "$OUT" MotionRef selftest
