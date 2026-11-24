#!/usr/bin/env bash
# One press, one undo step: a new control that changes the work records a step. See tools/check_undo_steps.py.
set -u
cd "$(dirname "$0")/../.." || exit 1
python -B tools/check_undo_steps.py
