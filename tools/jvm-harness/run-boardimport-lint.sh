#!/usr/bin/env bash
# Board import ownership lint (Codex review 11-01, S1). See board_import_lint.py.
set -u
cd "$(dirname "$0")/../.." || exit 1
python tools/jvm-harness/board_import_lint.py "$@"
