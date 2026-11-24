#!/usr/bin/env bash
# L18 ratchet: hard-coded English must not grow. See tools/check_hardcoded_english.py.
set -u
cd "$(dirname "$0")/../.." || exit 1
python -B tools/check_hardcoded_english.py
