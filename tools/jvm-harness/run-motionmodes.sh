#!/usr/bin/env bash
# MotionBlurModesTest -- STU-7 §K.3: a Motion Blur card's Off and Export only states, their copies, their
# round trip through the REAL ProjectStorage (old files unchanged), and the picture-at-rest rule.
# Usage: bash tools/jvm-harness/run-motionmodes.sh
# Negative controls: the run also plants each fault below into a copy of the source and requires the test to FAIL.
set -u
cd "$(dirname "$0")/../.." || exit 1

SDK="${ANDROID_HOME:-$HOME/AppData/Local/Android/Sdk}"
ANDROID_JAR=$(ls -d "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1 | sed 's|^/c/|C:/|')
if [ -z "$ANDROID_JAR" ]; then echo "no android.jar under $SDK/platforms"; exit 1; fi

M3=$(grep '^media3.patched.path' local.properties 2>/dev/null | cut -d= -f2- | tr -d '\r')
if [ -z "$M3" ]; then M3="/tmp/media3-patched"; fi

CP="$ANDROID_JAR"
CP="$CP;$(find "$HOME/.gradle/caches" -path '*/transforms/*' -name '*.jar' 2>/dev/null | sed 's|^/c/|C:/|' | tr '\n' ';')"
CP="$CP$(find "$M3/libraries" -path '*compile_library_classes_jar*' -name 'classes.jar' 2>/dev/null | tr '\n' ';')"
CP="$CP$(find "$HOME/.gradle/caches/modules-2" -name 'gson-2*.jar' 2>/dev/null | head -1 | sed 's|^/c/|C:/|')"

APP=app/src/main/java/com/fadcam/ui/faditor
SRC='tools/jvm-harness/stubs-clipwarp;tools/jvm-harness/stubs;app/src/main/java;studiokit/src/main/java'

# run_one <out dir> [fault]: compile and run the test; with a fault, against a copy of the source with it planted.
run_one() {
  local out=$1 fault=${2:-} src=$SRC
  rm -rf "$out"; mkdir -p "$out"
  if [ -n "$fault" ]; then
    local f pat
    case "$fault" in
      nowrite)  f=project/ProjectStorage.java;   pat='/addProperty("motionBlurOff", true)/d' ;;
      noread)   f=project/ProjectStorage.java;   pat='/if (off) target.setRigidMotionBlurEnabled(false);/d' ;;
      nocopy)   f=sprite/SpriteOverlayItem.java; pat='/c.motionBlurOff = motionBlurOff;/d' ;;
      preview)  f=motion/MotionBlurItem.java;    pat='s/return isRigidMotionBlurEnabled() \&\& !isMotionBlurExportOnly();/return isRigidMotionBlurEnabled();/' ;;
      restlive) f=motion/rest/RestFrameRule.java; pat='s/ \&\& !liveDrawn.test(item)//' ;;
      *) echo "unknown fault $fault"; return 2 ;;
    esac
    local base="$out/fault-src"
    mkdir -p "$base/com/fadcam/ui/faditor/$(dirname "$f")"
    sed "$pat" "$APP/$f" > "$base/com/fadcam/ui/faditor/$f"
    if cmp -s "$APP/$f" "$base/com/fadcam/ui/faditor/$f"; then echo "fault $fault planted nothing"; return 2; fi
    src="$base;$src"
  fi
  local args runargs
  args=$(mktemp); runargs=$(mktemp)
  { echo "-nowarn"; echo "-encoding UTF-8"; echo "-d $out";
    printf -- '-cp "%s"\n' "$CP";
    printf -- '-sourcepath "%s"\n' "$src"; } > "$args"
  printf -- '-cp "%s;%s"\n' "$out" "$CP" > "$runargs"
  javac @"$args" tools/jvm-harness/MotionBlurModesTest.java || { rm -f "$args" "$runargs"; return 3; }
  # Positive control on the COMPILE itself -- the run-matte lesson.
  [ -f "$out/MotionBlurModesTest.class" ] || { echo "no MotionBlurModesTest class -- the compile did not run"; rm -f "$args" "$runargs"; return 3; }
  java @"$runargs" MotionBlurModesTest
  local rc=$?
  rm -f "$args" "$runargs"
  return $rc
}

OUT=tools/jvm-harness/out-motionmodes
bad=0
for fault in nowrite noread nocopy preview restlive; do
  if run_one "$OUT-fault" "$fault" > "$OUT-fault.log" 2>&1; then
    echo "Negative control passed unexpectedly: $fault"; cat "$OUT-fault.log"; bad=1
  elif [ $? -ne 1 ]; then
    echo "Negative control failed to build or plant: $fault"; cat "$OUT-fault.log"; bad=1
  else
    echo "Negative control rejected: $fault ($(grep -m1 '^FAIL' "$OUT-fault.log"))"
  fi
done
rm -rf "$OUT-fault" "$OUT-fault.log"
run_one "$OUT" || exit 1
exit $bad
