#!/usr/bin/env bash
# STU-F7: a ping-pong clip from a content:// source is baked reversed (through a SAF parameter).
cd "$(dirname "$0")/../.." || exit 1
for PY in python3 python; do "$PY" -c "import sys" >/dev/null 2>&1 && exec "$PY" tools/jvm-harness/run-reversebake.py; done
echo "no python"; exit 1
