#!/usr/bin/env bash
# STU-7 C6 footage-flow core: estimator, confidence, camera-move fit, time scale.
# Pure Java (no Android classes), synthetic scenes with exactly known motion.
# Every scene has a negative control that must fail it first (break-test rule).
set -eu
cd "$(dirname "$0")/../.."

OUT=tools/jvm-harness/out-motionflow
rm -rf "$OUT"; mkdir -p "$OUT"
javac -nowarn -encoding UTF-8 -d "$OUT" \
  studiokit/src/main/java/com/fadcam/ui/faditor/motion/VelocityPack.java \
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
expect_failure 'B frames use display neighbours' \
    com.fadcam.ui.faditor.motion.flow.VideoPresentationIndexTest --decode-order
java -cp "$OUT" com.fadcam.ui.faditor.motion.flow.VideoPresentationIndexTest
java -cp "$OUT" com.fadcam.ui.faditor.motion.flow.IndexedFlowSessionTest
expect_failure 'OpenGL row zero comes from the image bottom' \
  com.fadcam.ui.faditor.motion.flow.FlowTexturePixelsTest --no-row-flip
expect_failure 'downward image motion becomes upward OpenGL motion' \
  com.fadcam.ui.faditor.motion.flow.FlowTexturePixelsTest --no-v-invert
java -cp "$OUT" com.fadcam.ui.faditor.motion.flow.FlowTexturePixelsTest
expect_failure 'after a seek back the frame is sharp until a neighbour' SourceFrameRingTest --naive
java -cp "$OUT" SourceFrameRingTest
java -cp "$OUT" com.fadcam.ui.faditor.motion.flow.FlowCacheTest
java -cp "$OUT" com.fadcam.ui.faditor.motion.flow.FlowAnalyzerTest
java -cp "$OUT" com.fadcam.ui.faditor.motion.flow.DecodedFlowWorkTest
java -cp "$OUT" com.fadcam.ui.faditor.motion.flow.ExactDecodedLumaTest
expect_failure 'clockwise rotation precedes upright box partition' com.fadcam.ui.faditor.motion.flow.YPlaneLumaTest --ignore-rotation
java -cp "$OUT" com.fadcam.ui.faditor.motion.flow.YPlaneLumaTest
java -cp "$OUT" com.fadcam.ui.faditor.motion.flow.FootageGatherTest
expect_failure 'disoccluded donor stays sharp' \
  com.fadcam.ui.faditor.motion.flow.HighFootageGatherTest --ignore-confidence
java -cp "$OUT" com.fadcam.ui.faditor.motion.flow.HighFootageGatherTest
