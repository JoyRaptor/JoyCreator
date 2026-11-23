#!/usr/bin/env bash
# STU-7 C6 footage-flow core: estimator, confidence, camera-move fit, time scale.
# Pure Java (no Android classes), synthetic scenes with exactly known motion.
# Every scene has a negative control that must fail it first (break-test rule).
set -eu
cd "$(dirname "$0")/../.."

OUT=tools/jvm-harness/out-motionflow
rm -rf "$OUT"; mkdir -p "$OUT"
javac -nowarn -encoding UTF-8 -d "$OUT" \
  studiokit/src/main/java/com/fadcam/ui/faditor/motion/flow/*.java \
  tools/jvm-harness/motion/flow/*.java

expect_failure() {
    local expected=$1
    shift
    local log
    if log=$(java -cp "$OUT" "$@" 2>&1); then
        printf 'Negative control passed unexpectedly: %s\n' "$*" >&2
        return 1
    fi
    if [[ "$log" != *"java.lang.AssertionError: $expected"* ]]; then
        printf 'Negative control failed for the wrong reason: %s\n%s\n' "$*" "$log" >&2
        return 1
    fi
    printf 'Negative control rejected: %s\n' "$expected"
}

expect_failure 'large translation needs the pyramid' FlowCoreTest --no-pyramid
expect_failure 'a flash (+0.12 brightness) must not throw the match' FlowCoreTest --no-mean-norm
expect_failure 'covered background is not trusted' FlowCoreTest --no-consistency
expect_failure 'camera move ignores the person crossing' FlowCoreTest --plain-ls
expect_failure '2x speed smears twice as far' FlowCoreTest --wrong-time-scale
java -cp "$OUT" FlowCoreTest
expect_failure 'after a seek back the frame is sharp until a neighbour' SourceFrameRingTest --naive
java -cp "$OUT" SourceFrameRingTest
