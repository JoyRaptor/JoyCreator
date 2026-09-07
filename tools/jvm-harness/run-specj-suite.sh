#!/bin/bash
cd "C:/+Projects/Screenrecorder/FadCam" || exit 1
fail=0
for h in run-mesh run-rotation run-pinbudget run-preview-parity run-frame-parity run-persist-lint run-key run-mask; do
  echo "=== $h ==="
  if ! out=$(bash tools/jvm-harness/$h.sh 2>&1); then
    fail=1
  fi
  echo "$out" | tail -3
done
echo "=== SUITE RESULT: $([ $fail -eq 0 ] && echo ALL GREEN || echo RED) ==="
