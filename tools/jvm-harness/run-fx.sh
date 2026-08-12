#!/usr/bin/env bash
# The FX compiler (SPEC_ADJUSTMENT_LAYERS_FX M1) — pass planning, both shader emits, the stack
# model, and the shared blend authority.
# Off device, seconds to run.
#
# The whole fx/ package is android-free apart from androidx.annotation (stubs) and gson, which
# is what lets the compiler be pinned with GOLDEN STRINGS here rather than eyeballed on a
# phone. Keep it that way: the moment this needs a real Android classpath, shader source has
# started depending on a renderer, and preview/export parity stops being checkable off-device.
#
# Usage:  bash tools/jvm-harness/run-fx.sh
set -u
cd "$(dirname "$0")/../.." || exit 1

GSON=$(find "$HOME/.gradle/caches/modules-2" -name 'gson-2*.jar' 2>/dev/null | head -1 | sed 's|^/c/|C:/|')
if [ -z "$GSON" ]; then echo "no gson jar in the gradle cache"; exit 1; fi

OUT=tools/jvm-harness/out-fx
rm -rf "$OUT"; mkdir -p "$OUT"

# @argfile, not a bare -cp: a semicolon-separated classpath passed as a shell argument gets
# mangled by MSYS path conversion, and the failure is silent — javac succeeds (it sees the
# jar) while java reports NoClassDefFoundError for a jar that is demonstrably present.
ARGS=$(mktemp); RUNARGS=$(mktemp)
{ echo "-nowarn"; echo "-encoding UTF-8"; echo "-d $OUT";
  printf -- '-cp "%s"\n' "$GSON";
  echo '-sourcepath "tools/jvm-harness/stubs;app/src/main/java"'; } > "$ARGS"
printf -- '-cp "%s;%s"\n' "$OUT" "$GSON" > "$RUNARGS"

javac @"$ARGS" tools/jvm-harness/FxCompilerTest.java tools/jvm-harness/BlendModesTest.java tools/jvm-harness/CurveMigrationTest.java || exit 1

# Positive control on the COMPILE itself: an empty out dir means the command never ran, which
# a grep for "error:" would report as success. run-matte.sh was bitten by exactly this.
[ -f "$OUT/FxCompilerTest.class" ] || { echo "no class file — the compile did not run"; exit 1; }
[ -f "$OUT/BlendModesTest.class" ] || { echo "no BlendModesTest class"; exit 1; }
[ -f "$OUT/CurveMigrationTest.class" ] || { echo "no CurveMigrationTest class"; exit 1; }

java @"$RUNARGS" BlendModesTest || exit 1
java @"$RUNARGS" FxCompilerTest || exit 1
java @"$RUNARGS" CurveMigrationTest
