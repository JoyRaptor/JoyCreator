#!/usr/bin/env bash
set -u
cd "$(dirname "$0")/../.." || exit 1
OUT=tools/jvm-harness/out-solve
rm -rf "$OUT"; mkdir -p "$OUT"
javac -nowarn -encoding UTF-8 -d "$OUT" \
  -sourcepath "app/src/main/java" \
  tools/jvm-harness/SpecKPinSolveVerify.java || exit 1
java -cp "$OUT" SpecKPinSolveVerify
