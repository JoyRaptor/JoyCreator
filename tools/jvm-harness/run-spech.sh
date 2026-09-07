#!/usr/bin/env bash
# SPEC H — bend tool + mirror-on-mesh parity, off device.
# The net's dot math (project/drag/guard/put) and the shader's mirror, pinned without a phone.
# Off device, seconds to run.
#
# Usage:  bash tools/jvm-harness/run-spech.sh
set -u
cd "$(dirname "$0")/../.." || exit 1

GSON=$(find "$HOME/.gradle/caches/modules-2" -name 'gson-2*.jar' 2>/dev/null | head -1 | sed 's|^/c/|C:/|')
if [ -z "$GSON" ]; then echo "no gson jar in the gradle cache"; exit 1; fi

OUT=tools/jvm-harness/out-spech
rm -rf "$OUT"; mkdir -p "$OUT"

# @argfile, not a bare -cp: a semicolon-separated classpath passed as a shell argument gets mangled
# by MSYS path conversion, and the failure is silent — javac succeeds while java reports
# NoClassDefFoundError for a jar that is demonstrably present. run-mask.sh was bitten by this.
ARGS=$(mktemp); RUNARGS=$(mktemp)
{ echo "-nowarn"; echo "-encoding UTF-8"; echo "-d $OUT";
  printf -- '-cp "%s"\n' "$GSON";
  echo '-sourcepath "app/src/main/java"'; } > "$ARGS"
printf -- '-cp "%s;%s"\n' "$OUT" "$GSON" > "$RUNARGS"

javac @"$ARGS" tools/jvm-harness/SpecHBendMirrorTest.java || exit 1

# Positive control on the COMPILE itself: an empty out dir means the command never ran, which a
# grep for "error:" would report as success.
[ -f "$OUT/SpecHBendMirrorTest.class" ] || { echo "no SpecHBendMirrorTest class — the compile did not run"; exit 1; }

# The android-free property, enforced rather than trusted. The bend seam (TransformQuad +
# the whole mesh package + MeshGlSource strings) must stay harness-loadable.
if grep -rn "^import android\|^import androidx" app/src/main/java/com/fadcam/ui/faditor/transform/mesh/ app/src/main/java/com/fadcam/ui/faditor/transform/TransformQuad.java; then
  echo "FAIL: an Android import leaked into the bend seam"
  exit 1
fi

java @"$RUNARGS" SpecHBendMirrorTest
