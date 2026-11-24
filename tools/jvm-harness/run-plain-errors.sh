#!/usr/bin/env bash
# No technical errors shown: raw exception text must go through PlainWords. See tools/check_plain_errors.py.
set -u
cd "$(dirname "$0")/../.." || exit 1
python -B tools/check_plain_errors.py
