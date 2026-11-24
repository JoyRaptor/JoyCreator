#!/usr/bin/env bash
# Host-only ownership/lifecycle diagnostic for LiveFlowGl. GLES and the estimator are fakes;
# source-frame sequencing uses the production ring.
set -eu
cd "$(dirname "$0")/../.."

OUT=tools/jvm-harness/out-live-flow-gl
rm -rf "$OUT"
mkdir -p "$OUT"
if command -v javac >/dev/null 2>&1; then
  JAVAC=(javac)
else
  JAVAC=(java --module jdk.compiler/com.sun.tools.javac.Main)
fi

"${JAVAC[@]}" -nowarn -encoding UTF-8 -d "$OUT" \
  tools/jvm-harness/liveflow-stubs/android/content/res/AssetManager.java \
  tools/jvm-harness/liveflow-stubs/android/opengl/GLES20.java \
  tools/jvm-harness/liveflow-stubs/androidx/media3/common/util/GlUtil.java \
  tools/jvm-harness/liveflow-stubs/androidx/media3/common/util/GlProgram.java \
  tools/jvm-harness/liveflow-stubs/com/fadcam/ui/faditor/motion/flow/GpuPyramidFlow.java \
  tools/jvm-harness/liveflow-stubs/com/fadcam/ui/faditor/motion/flow/PyramidFlowEstimator.java \
  studiokit/src/main/java/com/fadcam/ui/faditor/motion/flow/SourceFrameRing.java \
  app/src/main/java/com/fadcam/ui/faditor/motion/flow/LiveFlowGl.java \
  tools/jvm-harness/liveflow/LiveFlowGlLifecycleTest.java

java -cp "$OUT" com.fadcam.ui.faditor.motion.flow.LiveFlowGlLifecycleTest
