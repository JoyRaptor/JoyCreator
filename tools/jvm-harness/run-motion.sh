#!/usr/bin/env bash
# C1 shutter, real timeline adapters and CPU image reference. The same suite runs in Studio CI.
set -eu
cd "$(dirname "$0")/../.."

SDK="${ANDROID_HOME:-$HOME/AppData/Local/Android/Sdk}"
ANDROID_JAR=$(ls -d "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1 | sed 's|^/c/|C:/|')
if [ -z "$ANDROID_JAR" ]; then echo "no android.jar under $SDK/platforms"; exit 1; fi
M3=${MOTION_MEDIA3_PATH:-$(grep '^media3.patched.path' local.properties 2>/dev/null | cut -d= -f2- | tr -d '\r')}
M3=${M3:-/tmp/media3-patched}

CP="$ANDROID_JAR;"
CP="$CP$(find "$HOME/.gradle/caches" -path '*/transforms/*' -name '*.jar' 2>/dev/null | sed 's|^/c/|C:/|' | tr '\n' ';')"
CP="$CP$(find "$M3/libraries" -path '*compile_library_classes_jar*' -name classes.jar 2>/dev/null | tr '\n' ';')"
CP="$CP$(find "$HOME/.gradle/caches/modules-2" -name 'gson-2*.jar' 2>/dev/null | head -1 | sed 's|^/c/|C:/|')"

OUT=tools/jvm-harness/out-motion
mkdir -p "$OUT"
ARGS=$(mktemp)
RUNARGS=$(mktemp)
trap 'rm -f "$ARGS" "$RUNARGS"' EXIT
{ printf '%s\n' '-nowarn' '-encoding UTF-8' "-d $OUT";
  printf -- '-cp "%s"\n' "$CP";
  echo '-sourcepath "tools/jvm-harness/stubs;tools/jvm-harness/motion;app/src/main/java;studiokit/src/main/java"';
} > "$ARGS"
printf -- '-cp "%s;%s"\n' "$OUT" "$CP" > "$RUNARGS"

javac @"$ARGS" tools/jvm-harness/motion/*.java
test -s "$OUT/MotionCoreTest.class"
expect_failure() {
    local expected=$1
    shift
    local log
    if log=$(java @"$RUNARGS" "$@" 2>&1); then
        printf 'Negative control passed unexpectedly: %s\n' "$*" >&2
        return 1
    fi
    if [[ "$log" != *"java.lang.AssertionError: $expected"* ]]; then
        printf 'Negative control failed for the wrong reason: %s\n%s\n' "$*" "$log" >&2
        return 1
    fi
    printf 'Negative control rejected: %s\n' "$expected"
}

expect_failure 'sub-millisecond box midpoint' MotionCoreTest --wrong-clock
expect_failure 'export shutter reads the 60 fps presentation interval' MotionCoreTest --wrong-fixed-export-rate
expect_failure 'previous shot cannot see the cut' MotionCoreTest --wrong-cuts
expect_failure 'quarter millisecond interpolation' MotionCoreTest --wrong-keyframe
expect_failure 'all signed 12-bit values round trip' MotionCoreTest --wrong-pack
expect_failure 'cut start' -Dmotion.wrongNoFences=true MotionTimelineBreaksTest
expect_failure 'sprite exposure intersects pose and cell fences' -Dmotion.wrongNoSpriteExposureFence=true MotionTimelineBreaksTest
expect_failure 'sprite follows fractional parent motion' -Dmotion.wrongRoundedItem=true MotionItemAdaptersTest
expect_failure 'upright title stays centered at fractional time' -Dmotion.wrongTitlePlacement=true MotionItemAdaptersTest
java @"$RUNARGS" MotionCoreTest
java @"$RUNARGS" MotionTimelineBreaksTest
java @"$RUNARGS" MotionItemAdaptersTest
expect_failure "rotation maps a canvas pixel back to its UV" RigidShutterPlanTest wrong-direction
java @"$RUNARGS" RigidShutterPlanTest
expect_failure "fast slide spreads farther" MotionReferenceFixtureTest "$OUT/negative/slide" slide
expect_failure "zoom must grow coverage about its pivot" MotionReferenceFixtureTest "$OUT/negative/zoom" zoom
expect_failure "spin sweeps the narrow axis" MotionReferenceFixtureTest "$OUT/negative/spin" spin
expect_failure "child is drawn in the moving parent's space" MotionReferenceFixtureTest "$OUT/negative/parent" parent
expect_failure "teleport must never smear across a hold boundary" MotionReferenceFixtureTest "$OUT/negative/teleport" teleport
expect_failure "still image is bit exact" MotionReferenceFixtureTest "$OUT/negative/still" still
java @"$RUNARGS" MotionReferenceFixtureTest "$OUT"
