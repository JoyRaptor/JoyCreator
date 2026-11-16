#!/usr/bin/env bash
# Runs the test suites on GitHub's runner (.github/workflows/build.yml, variant "tests"), one after another, and
# writes a SHORT summary: each suite's time and pass count, and for failures only the failing tests' own output.
# Started by `tools/cloud-build.ps1 test <ref> [suites]`; not meant for the PC (there, run suites under the lock).
#
# Suites (default: all):
#   jb-core        JoyBrush core            gradlew -p joybrush :core:jvmTest
#   jb-androidkit  JoyBrush androidkit      gradlew -p joybrush :androidkit:test
#   jb-android     JoyBrush Android unit    gradlew :joybrush-android:testDebugUnitTest
#   studio         Studio JVM harness       tools/jvm-harness/run-studio-all.sh (one line per harness suite)
#   joybot         Joybot harness + bench   run-joybot-flight.sh, run-joybot-bench.sh (fake model only), --bridge
#
# The logs are public: nothing here prints a secret, and no live model is ever called (no key exists here).
# Exit code: the number of suites that failed.
set -u
cd "$(dirname "$0")/../.." || exit 1

SUITES="${*:-all}"
SUITES="${SUITES//,/ }"
[ "$SUITES" = "all" ] && SUITES="jb-core jb-androidkit jb-android studio joybot"

OUT=cloud-out
LOGS=$OUT/logs
mkdir -p "$LOGS"
SUM=$OUT/tests-summary.md
FAILS=$OUT/failures.md
: > "$FAILS"
ROWS=()
failed=0

# JUnit XML results under the given folders -> "tests failures errors skipped"; failing cases go to $FAILS.
junit() {
    python3 - "$FAILS" "$@" <<'PY'
import sys, glob, os, xml.etree.ElementTree as ET
fails_file, dirs = sys.argv[1], sys.argv[2:]
t = f = e = s = 0
out = []
for d in dirs:
    for p in glob.glob(os.path.join(d, "**", "TEST-*.xml"), recursive=True):
        try:
            root = ET.parse(p).getroot()
        except ET.ParseError:
            continue
        for case in root.iter("testcase"):
            t += 1
            if case.find("skipped") is not None:
                s += 1
            for kind in ("failure", "error"):
                bad = case.find(kind)
                if bad is not None:
                    if kind == "failure": f += 1
                    else: e += 1
                    text = (bad.get("message") or "") + "\n" + (bad.text or "")
                    lines = [l for l in text.strip().splitlines() if l.strip()][:25]
                    out.append("#### %s.%s\n```\n%s\n```\n" % (case.get("classname"), case.get("name"), "\n".join(lines)))
with open(fails_file, "a", encoding="utf-8") as fh:
    for chunk in out[:40]:
        fh.write(chunk + "\n")
    if len(out) > 40:
        fh.write("(%d more failing tests not shown)\n\n" % (len(out) - 40))
print(t, f, e, s)
PY
}

record() {   # suite seconds ok result
    local mark="PASS"
    [ "$3" = 1 ] || { mark="FAIL"; failed=$((failed + 1)); }
    ROWS+=("| $1 | $mark | $(( $2 / 60 ))m $(( $2 % 60 ))s | $4 |")
    echo "$mark $1 ($2 s): $4"
}

gradle_suite() {   # name results-dir gradle-args...
    local name=$1 dir=$2; shift 2
    rm -rf "$dir"
    local start=$(date +%s) ok=1
    ./gradlew "$@" --continue > "$LOGS/$name.log" 2>&1 || ok=0
    local secs=$(( $(date +%s) - start ))
    read -r t f e s < <(junit "$dir")
    if [ "$t" = 0 ] && [ "$ok" = 0 ]; then
        { echo "#### $name: Gradle failed before any test ran"; echo '```'; grep -E "error:|e: |FAILURE|What went wrong" -A3 "$LOGS/$name.log" | head -40; echo '```'; echo; } >> "$FAILS"
    fi
    [ "$f" = 0 ] && [ "$e" = 0 ] || ok=0
    record "$name" "$secs" "$ok" "$t tests, $((f + e)) failed, $s skipped"
}

harness() {   # name result-pattern command...
    local name=$1 pattern=$2; shift 2
    local start=$(date +%s) ok=1
    "$@" > "$LOGS/$name.log" 2>&1 || ok=0
    local secs=$(( $(date +%s) - start ))
    local line
    line=$(grep -E "$pattern" "$LOGS/$name.log" | tail -1)
    [ -n "$line" ] || line="(no result line; last: $(tail -1 "$LOGS/$name.log" | cut -c1-120))"
    if [ "$ok" = 0 ]; then
        { echo "#### $name"; echo '```'; tail -40 "$LOGS/$name.log"; echo '```'; echo; } >> "$FAILS"
    fi
    record "$name" "$secs" "$ok" "$line"
}

for s in $SUITES; do
    case "$s" in
        jb-core)       gradle_suite jb-core joybrush/core/build/test-results -p joybrush :core:jvmTest ;;
        jb-androidkit) gradle_suite jb-androidkit joybrush/androidkit/build/test-results -p joybrush :androidkit:test ;;
        jb-android)    gradle_suite jb-android joybrush-android/build/test-results :joybrush-android:testDebugUnitTest ;;
        studio)
            harness studio "^Studio harness:" bash tools/jvm-harness/run-studio-all.sh
            # Each failing harness suite's own last lines (the overall log only names it).
            for l in tools/jvm-harness/out-studio-all/*.log; do
                n=$(basename "$l" .log)
                if grep -q "^FAIL *$n " "$LOGS/studio.log" 2>/dev/null; then
                    { echo "#### studio / $n"; echo '```'; tail -30 "$l"; echo '```'; echo; } >> "$FAILS"
                fi
            done ;;
        joybot)
            harness joybot-flight "flights," bash tools/jvm-harness/run-joybot-flight.sh
            harness joybot-bench "FAKE run|tasks" bash tools/jvm-harness/run-joybot-bench.sh
            harness joybot-bridge "passed, .* failed" bash tools/jvm-harness/run-joybot-bench.sh --bridge ;;
        *) record "$s" 0 0 "unknown suite (known: jb-core jb-androidkit jb-android studio joybot)" ;;
    esac
done

{
    echo "### Tests @ ${TESTS_REF:-?} (${TESTS_SHA:-?})"
    echo
    echo "| Suite | Result | Time | Count |"
    echo "|---|---|---|---|"
    printf '%s\n' "${ROWS[@]}"
    echo
    if [ -s "$FAILS" ]; then echo "### Failures"; echo; cat "$FAILS"; else echo "No failures."; fi
} > "$SUM"
cat "$SUM"
exit "$failed"
