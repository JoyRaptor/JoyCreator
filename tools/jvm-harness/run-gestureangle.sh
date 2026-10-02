#!/usr/bin/env bash
# D.02a — the transform tool's rotation unwrap, its centre-anchored corner factor, and the fold that
# decides what a rotation gesture STORES (pure helpers, no Android).
#
# The test now reaches through the real KeyframeSet/KeyframeTrack interpolator as well, because the
# harm of an unfolded stored angle is only visible there. That package needs androidx.annotation's
# @NonNull/@Nullable, which are CLASS-retention markers and load anywhere — so the jar goes on the
# classpath (the same trick run-spech.sh uses for gson) rather than being stubbed.
set -u
cd "$(dirname "$0")/../.." || exit 1

ANNOT=$(find "$HOME/.gradle/caches/modules-2/files-2.1/androidx.annotation" -name 'annotation-jvm-*.jar' 2>/dev/null | sort | tail -1 | sed 's|^/c/|C:/|')
if [ -z "$ANNOT" ]; then
  echo "no androidx.annotation jar in the gradle cache (needed only for the @NonNull marker)"
  exit 1
fi

OUT=tools/jvm-harness/out-gestureangle
rm -rf "$OUT"; mkdir -p "$OUT"

# @argfile, not a bare -cp: a semicolon-separated classpath passed as a shell argument gets mangled
# by MSYS path conversion, and the failure is silent — javac succeeds while java reports
# NoClassDefFoundError for a jar that is demonstrably present. run-mask.sh was bitten by this.
ARGS=$(mktemp); RUNARGS=$(mktemp)
{ echo "-nowarn"; echo "-encoding UTF-8"; echo "-d $OUT";
  printf -- '-cp "%s"\n' "$ANNOT";
  echo '-sourcepath "app/src/main/java;studiokit/src/main/java"'; } > "$ARGS"
printf -- '-cp "%s;%s"\n' "$OUT" "$ANNOT" > "$RUNARGS"

javac @"$ARGS" tools/jvm-harness/GestureAngleTest.java || exit 1

# Positive control on the COMPILE itself: an empty out dir means the command never ran, which a
# grep for "error:" would report as success.
[ -f "$OUT/GestureAngleTest.class" ] || { echo "no GestureAngleTest class — the compile did not run"; exit 1; }

# The android-free property, enforced rather than trusted, for exactly the files this test loads:
# TransformQuad plus KeyframeSet/KeyframeTrack/Keyframe/Easing. (KeyframeCodec needs gson and
# KeyframeGlyph draws with android.graphics.Path — neither is on this test's path, so neither is in
# the guard; androidx.annotation is only @NonNull/@Nullable markers, which is why it is on the
# classpath above and is not the leak this looks for.)
KF=studiokit/src/main/java/com/fadcam/ui/faditor/keyframe
TQ=studiokit/src/main/java/com/fadcam/ui/faditor/transform/TransformQuad.java
if { grep -n "^import android\." "$TQ" "$KF/KeyframeSet.java" "$KF/KeyframeTrack.java" "$KF/Keyframe.java" "$KF/Easing.java";
     grep -n "^import androidx\." "$TQ" "$KF/KeyframeSet.java" "$KF/KeyframeTrack.java" "$KF/Keyframe.java" "$KF/Easing.java" | grep -v "androidx\.annotation\."; }; then
  echo "FAIL: an Android import leaked into the rotation geometry or the keyframe interpolator"
  exit 1
fi

java @"$RUNARGS" GestureAngleTest
