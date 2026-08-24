#!/usr/bin/env bash
# Stub lint - contract methods one implementer silently does nothing for.
set -u
cd "$(dirname "$0")/../.." || exit 1
python tools/jvm-harness/stub_lint.py
