#!/usr/bin/env bash
# W5-2 rich text (XL) — pure-Java span rules: StyleSpan + TextStyleResolver.
# The meaning of "last span wins", mixed-range reporting, "one choice overriding", and the
# text-edit realignment all live in javac-only land (no android.graphics, just androidx
# annotations + gson), which is what keeps them pin-able off-device while the renderer that
# consumes the resolved runs stays Android-native.
#
#   bash tools/jvm-harness/run-textstyle.sh
set -u
cd "$(dirname "$0")/../.." || exit 1

GSON=$(find "$HOME/.gradle/caches/modules-2" -name 'gson-2*.jar' 2>/dev/null | head -1 | sed 's|^/c/|C:/|')
if [ -z "$GSON" ]; then echo "no gson jar in the gradle cache"; exit 1; fi

OUT=tools/jvm-harness/out-textstyle
rm -rf "$OUT"; mkdir -p "$OUT"

# @argfile, not a bare -cp: a semicolon-separated classpath passed as a shell argument gets
# mangled by MSYS path conversion, and the failure is silent — javac succeeds (it sees the
# jar) while java reports NoClassDefFoundError for a jar that is demonstrably present.
ARGS=$(mktemp); RUNARGS=$(mktemp)
{ echo "-nowarn"; echo "-encoding UTF-8"; echo "-d $OUT";
  printf -- '-cp "%s"\n' "$GSON";
  echo '-sourcepath "tools/jvm-harness/stubs;app/src/main/java"'; } > "$ARGS"
printf -- '-cp "%s;%s"\n' "$OUT" "$GSON" > "$RUNARGS"

javac @"$ARGS" tools/jvm-harness/StyleSpanTest.java || exit 1

# Positive control on the COMPILE itself: an empty out dir means the command never ran, which
# a grep for "error:" would report as success. run-matte.sh was bitten by exactly this.
[ -f "$OUT/StyleSpanTest.class" ] || { echo "no class file — the compile did not run"; exit 1; }

java @"$RUNARGS" StyleSpanTest