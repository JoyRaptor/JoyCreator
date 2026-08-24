#!/usr/bin/env bash
# Copy lint - every field must survive a copy constructor. See copy_lint.py.
set -u
cd "$(dirname "$0")/../.." || exit 1
python tools/jvm-harness/copy_lint.py
