#!/usr/bin/env bash
# CharacterSolverTest: Avatar Studio's phone solver (cc.joycreator.avatar.solve, X2) against the web pilot's golden files
# (tasks/avatar/golden/): every vertex within 0.5 px, then each planted fault must be caught. Pure Java plus the PC stand-in
# for org.json; no Gradle. BitmapPics is left out: it is the Android side of Pics and is built by the app only.
#
# Usage:  bash tools/jvm-harness/run-avatar-solve.sh [<character.json> <golden.json> ...]   (extra pairs, e.g. a local character)
set -u
cd "$(dirname "$0")/../.." || exit 1

OUT=tools/jvm-harness/out-avatar-solve
rm -rf "$OUT"; mkdir -p "$OUT"

javac -nowarn -encoding UTF-8 -d "$OUT" \
  $(find tools/jvm-harness/joybot-bench/shim/org/json -name '*.java') \
  $(find app/src/main/java/cc/joycreator/avatar/solve -name '*.java' ! -name 'BitmapPics.java') \
  tools/jvm-harness/CharacterSolverTest.java || exit 1
java -Djava.awt.headless=true -cp "$OUT" CharacterSolverTest "$@"
