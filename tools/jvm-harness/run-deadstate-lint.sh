#!/usr/bin/env bash
# Dead-state lint - cross-layer state written and never read. See deadstate_lint.py.
set -u
cd "$(dirname "$0")/../.." || exit 1
python tools/jvm-harness/deadstate_lint.py
