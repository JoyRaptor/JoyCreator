#!/usr/bin/env bash
# Orphan lint - an engine class nothing constructs is not a feature. See orphan_lint.py.
set -u
cd "$(dirname "$0")/../.." || exit 1
python tools/jvm-harness/orphan_lint.py
