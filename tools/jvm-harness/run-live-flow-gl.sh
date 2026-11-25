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
  tools/jvm-harness/stubs/androidx/annotation/{Nullable,NonNull}.java \
  tools/jvm-harness/liveflow-stubs/androidx/media3/common/util/GlUtil.java \
  tools/jvm-harness/liveflow-stubs/androidx/media3/common/util/GlProgram.java \
  tools/jvm-harness/liveflow-stubs/com/fadcam/ui/faditor/motion/flow/GpuPyramidFlow.java \
  tools/jvm-harness/liveflow-stubs/com/fadcam/ui/faditor/motion/flow/PyramidFlowEstimator.java \
  studiokit/src/main/java/com/fadcam/ui/faditor/motion/VelocityPack.java \
  studiokit/src/main/java/com/fadcam/ui/faditor/motion/flow/{FlowField,FlowCache,FlowTexturePixels}.java \
  studiokit/src/main/java/com/fadcam/ui/faditor/motion/flow/SourceFrameRing.java \
  studiokit/src/main/java/com/fadcam/ui/faditor/keyframe/{Easing,Keyframe,KeyframeTrack}.java \
  studiokit/src/main/java/com/fadcam/ui/faditor/motion/{MotionBreaks,MotionShutter}.java \
  app/src/main/java/com/fadcam/ui/faditor/motion/flow/LiveFlowGl.java \
  app/src/main/java/com/fadcam/ui/faditor/motion/flow/FastFootagePreviewOwner.java \
  app/src/main/java/com/fadcam/ui/faditor/motion/flow/FastFootageGatherGl.java \
  app/src/main/java/com/fadcam/ui/faditor/motion/flow/HighFootageGatherGl.java \
  app/src/main/java/com/fadcam/ui/faditor/motion/flow/FlowPairTexturesGl.java \
  tools/jvm-harness/liveflow/LiveFlowGlLifecycleTest.java \
  tools/jvm-harness/liveflow/{FastFootagePreviewOwnerTest,FastFootageGatherGlTest,HighFootageGatherGlTest,FlowPairTexturesGlTest}.java

java -cp "$OUT" com.fadcam.ui.faditor.motion.flow.LiveFlowGlLifecycleTest
java -cp "$OUT" com.fadcam.ui.faditor.motion.flow.FastFootagePreviewOwnerTest
java -cp "$OUT" com.fadcam.ui.faditor.motion.flow.FastFootageGatherGlTest
java -cp "$OUT" com.fadcam.ui.faditor.motion.flow.HighFootageGatherGlTest
java -cp "$OUT" com.fadcam.ui.faditor.motion.flow.FlowPairTexturesGlTest
