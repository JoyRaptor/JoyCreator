#!/usr/bin/env bash
# Compiles every app source (like typecheck.sh) into tools/jvm-harness/out-joybot-app: the Joybot
# benchmark runs the REAL agent and tools from these classes. Writes the library classpath
# (android.jar last) to out-joybot-app/.libs and the source root it used to .root.
#
# Sources come from the last COMMIT by default (BENCH_SRC=head): other sessions share the working
# folder and a half-made edit there must not break the benchmark. BENCH_SRC=worktree uses the
# folder as it is (to measure an uncommitted change); BENCH_SRC=<commit> measures that commit.
set -u
cd "$(dirname "$0")/../../.." || exit 1
# Build outputs (R.jar, BuildConfig, the JoyBrush and studiokit jars) come from this folder or, in a
# separate git worktree that never ran Gradle, from the main folder the worktree belongs to.
MAINDIR=$(cd "$(dirname "$(git rev-parse --path-format=absolute --git-common-dir)")" && { pwd -W 2>/dev/null || pwd; })
built() { if [ -e "$1" ]; then echo "$1"; else echo "$MAINDIR/$1"; fi; }

SDK="${ANDROID_HOME:-$HOME/AppData/Local/Android/Sdk}"
ANDROID_JAR=$(ls -d "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1 | sed 's|^/c/|C:/|')
[ -n "$ANDROID_JAR" ] || { echo "no android.jar under $SDK/platforms"; exit 1; }
M3=$(grep '^media3.patched.path' "$(built local.properties)" 2>/dev/null | cut -d= -f2- | tr -d '\r')
[ -n "$M3" ] || M3="/tmp/media3-patched"
RJAR=$(built "app/build/intermediates/compile_and_runtime_not_namespaced_r_class_jar/defaultDebug/processDefaultDebugResources/R.jar")
[ -f "$RJAR" ] || { echo "no R.jar — run a real Gradle build once"; exit 1; }

LIBS="$RJAR"
LIBS="$LIBS;$(find "$HOME/.gradle/caches" -path '*/transforms/*' -name '*.jar' 2>/dev/null | sed 's|^/c/|C:/|' | tr '\n' ';')"
LIBS="$LIBS$(find "$M3/libraries" -path '*compile_library_classes_jar*' -name 'classes.jar' 2>/dev/null | tr '\n' ';')"
LIBS="$LIBS$(find "$HOME/.gradle/caches/modules-2" -name '*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' ! -path '*robolectric*' 2>/dev/null | sed 's|^/c/|C:/|' | tr '\n' ';')"
for J in joybrush-android/build/intermediates/compile_library_classes_jar/debug/bundleLibCompileToJarDebug/classes.jar \
         joybrush/core/build/libs/*.jar studiokit/build/intermediates/compile_r_class_jar/debug/*/R.jar; do
  [ -f "$J" ] && LIBS="$LIBS;$J"
done
if [ ! -d joybrush-android/build ]; then
  for J in "$MAINDIR"/joybrush-android/build/intermediates/compile_library_classes_jar/debug/bundleLibCompileToJarDebug/classes.jar \
           "$MAINDIR"/joybrush/core/build/libs/*.jar "$MAINDIR"/studiokit/build/intermediates/compile_r_class_jar/debug/*/R.jar; do
    [ -f "$J" ] && LIBS="$LIBS;$J"
  done
fi

OUT=tools/jvm-harness/out-joybot-app
BCFG=$(built "app/build/generated/source/buildConfig/default/debug")
if [ "${BENCH_SRC:-head}" = "worktree" ]; then
  ROOT=.; KEY="worktree-$(date +%s)"
else
  REV=${BENCH_SRC:-head}; [ "$REV" = head ] && REV=HEAD
  KEY=$(git rev-parse "$REV") || exit 1
  ROOT=tools/jvm-harness/out-joybot-src
  if [ "$(cat "$ROOT/.commit" 2>/dev/null)" != "$KEY" ]; then
    rm -rf "$ROOT"; mkdir -p "$ROOT"
    git archive "$KEY" app/src/main/java app/src/main/assets studiokit/src/main/java | tar -x -C "$ROOT" || exit 1
    echo "$KEY" > "$ROOT/.commit"
  fi
fi
if [ "$(cat "$OUT/.commit" 2>/dev/null)" = "$KEY" ]; then
  echo "app classes up to date (${KEY:0:9})"; exit 0
fi

SRCS=$(mktemp); ARGS=$(mktemp)
{ find "$ROOT/studiokit/src/main/java" -name '*.java'
  find "$ROOT/app/src/main/java" -name '*.java' | while read -r f; do
    [ -f "$ROOT/studiokit/src/main/java/${f#"$ROOT"/app/src/main/java/}" ] || echo "$f"; done
  find "$BCFG" -name '*.java' 2>/dev/null; } | sed 's|^|"|; s|$|"|' > "$SRCS"
rm -rf "$OUT"; mkdir -p "$OUT"
{ echo "-nowarn"; echo "-encoding UTF-8"; echo "-d $OUT"; echo "-proc:none";
  printf -- '-cp "%s"\n' "$LIBS;$ANDROID_JAR";
  echo '-sourcepath "tools/jvm-harness/stubs-typecheck"'; } > "$ARGS"
echo "compiling $(wc -l < "$SRCS") app sources from ${KEY:0:9} (about a minute)..."
javac @"$ARGS" @"$SRCS" 2>&1 | grep --text -v '^Note:' | head -40
STATUS=${PIPESTATUS[0]}
rm -f "$SRCS" "$ARGS"
[ "$STATUS" -eq 0 ] || { echo "APP COMPILE FAILED"; exit 1; }
CLASSES=$(find "$OUT" -name '*.class' | wc -l)
[ "$CLASSES" -gt 1000 ] || { echo "FAIL: only $CLASSES classes — the compile did not really run"; exit 1; }
printf '%s' "$LIBS;$ANDROID_JAR" > "$OUT/.libs"
echo "$ROOT" > "$OUT/.root"
echo "$KEY" > "$OUT/.commit"
echo "app classes: $CLASSES"
