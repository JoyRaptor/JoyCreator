#!/usr/bin/env bash
# VisLab off-device checks: the looks and knob maths (VisLooksTest) and the sound analysis
# matching the browser pilot frame for frame (VisFeatureParityTest). Pure Java + gson.
#
# Usage:  bash tools/jvm-harness/run-vislab.sh
#         (regenerate the pilot's reference first with: node tools/jvm-harness/vislab_parity.mjs)
set -u
cd "$(dirname "$0")/../.." || exit 1

GSON=$(find "$HOME/.gradle/caches/modules-2" -name 'gson-2*.jar' 2>/dev/null | head -1 | sed 's|^/c/|C:/|')
if [ -z "$GSON" ]; then echo "no gson jar in the gradle cache"; exit 1; fi

OUT=tools/jvm-harness/out-vislab
rm -rf "$OUT"; mkdir -p "$OUT"
# @argfile, not a bare -cp: MSYS mangles a semicolon classpath silently (see run-adjust.sh).
RUNARGS=$(mktemp)
printf -- '-cp "%s;%s"
' "$OUT" "$GSON" > "$RUNARGS"

javac -nowarn -encoding UTF-8 -cp "$GSON" -d "$OUT" \
  tools/jvm-harness/stubs/androidx/annotation/NonNull.java \
  tools/jvm-harness/stubs/androidx/annotation/Nullable.java \
  app/src/main/java/com/fadcam/ui/faditor/waveform/Fft.java \
  app/src/main/java/com/fadcam/vislab/model/*.java \
  app/src/main/java/com/fadcam/vislab/audio/Features.java \
  app/src/main/java/com/fadcam/vislab/audio/Signals.java \
  app/src/main/java/com/fadcam/vislab/audio/OnsetDetector.java \
  app/src/main/java/com/fadcam/vislab/audio/FeatureExtractor.java \
  app/src/main/java/com/fadcam/vislab/audio/SpectrumAnalyzer.java \
  tools/jvm-harness/VisLooksTest.java \
  tools/jvm-harness/VisFeatureParityTest.java \
  studiokit/src/main/java/com/fadcam/ui/faditor/keyframe/Easing.java \
  studiokit/src/main/java/com/fadcam/ui/faditor/keyframe/Keyframe.java \
  studiokit/src/main/java/com/fadcam/ui/faditor/keyframe/KeyframeSet.java \
  studiokit/src/main/java/com/fadcam/ui/faditor/keyframe/KeyframeTrack.java \
  app/src/main/java/com/fadcam/ui/faditor/model/SpaceLink.java \
  app/src/main/java/com/fadcam/ui/faditor/model/LinkPose.java \
  app/src/main/java/com/fadcam/ui/faditor/model/VisLabKeys.java \
  tools/jvm-harness/VisFollowLinkTest.java \
  tools/jvm-harness/VisPlaceFollowTest.java || exit 1

rc=0
[ -f "$OUT/VisLooksTest.class" ] || { echo "no class file: the compile did not run"; exit 1; }
java @"$RUNARGS" VisLooksTest || rc=1
java @"$RUNARGS" VisFeatureParityTest || rc=1
java @"$RUNARGS" VisPlaceFollowTest || rc=1
java @"$RUNARGS" VisFollowLinkTest || rc=1
exit $rc
