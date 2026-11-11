#!/usr/bin/env bash
# AnchorMathTest: rider arithmetic and the clip under the playhead (clipAtTime), off device.
# Primitive-only, so it compiles against the annotation stubs alone.
# Usage:  bash tools/jvm-harness/run-anchormath.sh
set -u
cd "$(dirname "$0")/../.." || exit 1
OUT=tools/jvm-harness/out-anchormath
rm -rf "$OUT"; mkdir -p "$OUT"
SEP=":"; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";";; esac
javac -nowarn -encoding UTF-8 -d "$OUT" -sourcepath "tools/jvm-harness/stubs${SEP}app/src/main/java" \
  tools/jvm-harness/AnchorMathTest.java || exit 1
[ -f "$OUT/AnchorMathTest.class" ] || { echo "no class file — the compile did not run"; exit 1; }
java -cp "$OUT" AnchorMathTest
