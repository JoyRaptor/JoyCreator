#!/usr/bin/env bash
# Preview/export parity lint (SPEC_20260825 §3.3) -- real check, then its negative control.
# A probe that cannot fail proves nothing; the negctl run below must FAIL the check for
# the whole script to pass.
set -u
cd "$(dirname "$0")/../.." || exit 1

echo "=== preview parity: negative control ==="
python tools/jvm-harness/preview_parity_lint.py --negctl
NEG=$?

echo
echo "=== preview parity: real check ==="
python tools/jvm-harness/preview_parity_lint.py
REAL=$?

if [ "$NEG" -eq 0 ] && [ "$REAL" -eq 0 ]; then
  echo "ALL PASS"
  exit 0
fi
echo "FAILED (negctl=$NEG real=$REAL)"
exit 1
