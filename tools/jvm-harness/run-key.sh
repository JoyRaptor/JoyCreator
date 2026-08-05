#!/usr/bin/env bash
# ChromaKeyTest — §3a chroma key arithmetic, off device, seconds to run.
#
# ChromaKey and CompositingSpec are android-free apart from androidx.annotation (stubs) and
# gson, so this needs a far smaller classpath than run-matte.sh — no android.jar, no patched
# media3. Keep it that way: the moment this test needs a real Android classpath, the key math
# has leaked out of the model layer and back into a renderer.
#
# Usage:  bash tools/jvm-harness/run-key.sh
set -u
cd "$(dirname "$0")/../.." || exit 1

GSON=$(find "$HOME/.gradle/caches/modules-2" -name 'gson-2*.jar' 2>/dev/null | head -1 | sed 's|^/c/|C:/|')
if [ -z "$GSON" ]; then echo "no gson jar in the gradle cache"; exit 1; fi

OUT=tools/jvm-harness/out-key
rm -rf "$OUT"; mkdir -p "$OUT"

# @argfile, not a bare -cp: a semicolon-separated classpath passed as a shell argument gets
# mangled by MSYS path conversion, and the failure is silent — javac succeeds (it sees the
# jar) while java reports NoClassDefFoundError for a jar that is demonstrably present.
ARGS=$(mktemp); RUNARGS=$(mktemp)
{ echo "-nowarn"; echo "-encoding UTF-8"; echo "-d $OUT";
  printf -- '-cp "%s"\n' "$GSON";
  echo '-sourcepath "tools/jvm-harness/stubs;app/src/main/java"'; } > "$ARGS"
printf -- '-cp "%s;%s"\n' "$OUT" "$GSON" > "$RUNARGS"

javac @"$ARGS" tools/jvm-harness/ChromaKeyTest.java || exit 1

# Positive control on the COMPILE itself: an empty out dir means the command never ran, which
# a grep for "error:" would report as success. run-matte.sh was bitten by exactly this.
[ -f "$OUT/ChromaKeyTest.class" ] || { echo "no class file — the compile did not run"; exit 1; }

java @"$RUNARGS" ChromaKeyTest
