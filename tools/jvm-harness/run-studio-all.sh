#!/usr/bin/env bash
# Every Studio harness suite, one after another, with one PASS/FAIL line each and a total.
# STU-H1 (11-13): Studio had ~50 run-*.sh scripts and no single command; nothing ran them all.
#
# Usage:  bash tools/jvm-harness/run-studio-all.sh            (all Studio suites)
#         bash tools/jvm-harness/run-studio-all.sh timemap fx  (only these, by run-<name>.sh)
#
# Heavy: each suite compiles part of the app with javac. It takes the machine's build lock
# (jb-gradle.lock, see tasks/AGENT_START.md) for the whole run and refuses if someone holds it.
# Logs: tools/jvm-harness/out-studio-all/<suite>.log. Exit code = number of failed suites (0 = all pass).
set -u
cd "$(dirname "$0")/../.." || exit 1

STUDIO_SUITES="adjust align anchor audio-fx audio-fx2 audioid bakedparse bakepop beats caption
clipwarp consolidate cutspan duck envelope flextime flip frame-parity frame-time fx gestureangle
key lanechain mask matte mesh motionrange para pinbudget preview-parity puppet rebase resample
rotation scalesnap sidecars specj-suite speck specq specr spect splitcopy sprite textstyle
timemap titlestyle undo visfollow copy-lint deadstate-lint orphan-lint persist-lint stub-lint"

SUITES="${*:-$STUDIO_SUITES}"
LOCK="${TEMP:-$HOME/AppData/Local/Temp}/jb-gradle.lock"
[ -d "$HOME/AppData/Local/Temp" ] && LOCK="$HOME/AppData/Local/Temp/jb-gradle.lock"
if ! ( set -o noclobber; echo "run-studio-all.sh $(date -Iseconds)" > "$LOCK" ) 2>/dev/null; then
  echo "Build lock held by: $(head -1 "$LOCK" 2>/dev/null). Try again when it is free."; exit 99
fi
trap 'rm -f "$LOCK"' EXIT

LOGS=tools/jvm-harness/out-studio-all
mkdir -p "$LOGS"
pass=0; fail=0; failed=""
for s in $SUITES; do
  script="tools/jvm-harness/run-$s.sh"
  if [ ! -f "$script" ]; then echo "MISSING  $s"; fail=$((fail+1)); failed="$failed $s"; continue; fi
  start=$(date +%s)
  if bash "$script" > "$LOGS/$s.log" 2>&1; then
    printf 'PASS     %-16s %3ss\n' "$s" "$(( $(date +%s) - start ))"; pass=$((pass+1))
  else
    printf 'FAIL     %-16s %3ss  (see %s)\n' "$s" "$(( $(date +%s) - start ))" "$LOGS/$s.log"
    fail=$((fail+1)); failed="$failed $s"
  fi
done
echo "Studio harness: $pass passed, $fail failed${failed:+ ->$failed}"
exit "$fail"
