#!/usr/bin/env bash
# Persistence lint - every model field must have a home on disk. See persist_lint.py.
set -u
cd "$(dirname "$0")/../.." || exit 1
python tools/jvm-harness/persist_lint.py
