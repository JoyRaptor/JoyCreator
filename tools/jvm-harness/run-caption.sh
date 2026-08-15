#!/usr/bin/env bash
# CaptionAnimator — unit splitting, zones, presets. Pure-Java (androidx annotations only), so
# the thing a "letter" IS can be pinned off-device.
#
# WHY THIS RUNNER EXISTS: GraphemeUnitsTest.java has been in this directory for a long time
# with NO runner referencing it, so nothing ever compiled or ran it. That is worse than having
# no test — it reads as coverage while asserting nothing. It was an orphan when a per-char
# LETTER split was shipping tofu for every emoji (JoyRaptor, 2026-08-15).
#
#   bash tools/jvm-harness/run-caption.sh
set -u
cd "$(dirname "$0")/../.." || exit 1

GSON=$(find "$HOME/.gradle/caches/modules-2" -name 'gson-2*.jar' 2>/dev/null | head -1 | sed 's|^/c/|C:/|')
if [ -z "$GSON" ]; then echo "no gson jar in the gradle cache"; exit 1; fi

OUT=tools/jvm-harness/out-caption
rm -rf "$OUT"; mkdir -p "$OUT"

# @argfile, not a bare -cp — see run-textstyle.sh for why (MSYS mangles a semicolon classpath
# silently: javac succeeds and java then cannot find the jar).
ARGS=$(mktemp); RUNARGS=$(mktemp)
{ echo "-nowarn"; echo "-encoding UTF-8"; echo "-d $OUT";
  printf -- '-cp "%s"\n' "$GSON";
  echo '-sourcepath "tools/jvm-harness/stubs;app/src/main/java"'; } > "$ARGS"
printf -- '-cp "%s;%s"\n' "$OUT" "$GSON" > "$RUNARGS"

javac @"$ARGS" tools/jvm-harness/GraphemeUnitsTest.java || exit 1

# Positive control on the COMPILE itself: an empty out dir means the command never ran, which a
# grep for "error:" would report as success. run-matte.sh was bitten by exactly this.
[ -f "$OUT/GraphemeUnitsTest.class" ] || { echo "no class file — the compile did not run"; exit 1; }

# -Dfile.encoding=UTF-8: the assertions carry emoji and combining marks, and on a Windows JVM
# defaulting to windows-1252 the source literals survive (javac was told UTF-8) but the console
# print of a failure would be mojibake — which is a miserable way to read a surrogate-pair bug.
java -Dfile.encoding=UTF-8 @"$RUNARGS" GraphemeUnitsTest
