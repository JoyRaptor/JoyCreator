#!/usr/bin/env bash
# CharacterGlSourceTest: Avatar Studio's phone renderer (cc.joycreator.avatar.draw, X3) compiles the web pilot's own shader text
# (tools/avatarlab/al-gl.js), character for character. The drawing itself needs a GPU: it is proven on the Note 9 with
# CharacterDrawBenchActivity (debug builds), see tasks/avatar/CHANNEL.md.
#
# Usage:  bash tools/jvm-harness/run-avatar-draw.sh
set -u
cd "$(dirname "$0")/../.." || exit 1

OUT=tools/jvm-harness/out-avatar-draw
rm -rf "$OUT"; mkdir -p "$OUT"

javac -nowarn -encoding UTF-8 -d "$OUT" \
  app/src/main/java/cc/joycreator/avatar/draw/CharacterGlSource.java \
  tools/jvm-harness/CharacterGlSourceTest.java || exit 1
java -cp "$OUT" CharacterGlSourceTest
