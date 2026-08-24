#!/usr/bin/env bash
# WHOLE-APP TYPE CHECK, without Gradle.
#
# Gradle cannot run from inside the agent sandbox ("Unable to establish loopback
# connection", runbook §7f), which historically meant an agent could not know whether its
# own edits compiled until a human started the watcher. This closes that gap: javac over
# EVERY app source file, against the same classpath run-matte.sh derived, plus the R.jar
# the last real Gradle build left behind.
#
# WHAT IT DOES NOT COVER (know this before trusting a green run):
#   - RESOURCES. R.jar is a build ARTEFACT, so a NEW id/layout/string/drawable you just
#     added to res/ is not in it and will read as "cannot find symbol". That is a true
#     negative about this checker, not about your code — it means the change needs a real
#     Gradle build before it can be believed.
#   - Anything aapt/lint/dex does after javac.
# A green run means "the Java type-checks". It does not mean "the APK builds".
#
# Usage:  bash tools/jvm-harness/typecheck.sh
set -u
cd "$(dirname "$0")/../.." || exit 1

SDK="${ANDROID_HOME:-$HOME/AppData/Local/Android/Sdk}"
ANDROID_JAR=$(ls -d "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1 | sed 's|^/c/|C:/|')
[ -n "$ANDROID_JAR" ] || { echo "no android.jar under $SDK/platforms"; exit 1; }

M3=$(grep '^media3.patched.path' local.properties 2>/dev/null | cut -d= -f2- | tr -d '\r')
[ -n "$M3" ] || M3="/tmp/media3-patched"

RJAR="app/build/intermediates/compile_and_runtime_not_namespaced_r_class_jar/defaultDebug/processDefaultDebugResources/R.jar"
[ -f "$RJAR" ] || echo "WARNING: no R.jar — every R.* reference will fail. Run a real build once."

CP="$ANDROID_JAR;$RJAR"
CP="$CP;$(find "$HOME/.gradle/caches" -path '*/transforms/*' -name '*.jar' 2>/dev/null | sed 's|^/c/|C:/|' | tr '\n' ';')"
CP="$CP$(find "$M3/libraries" -path '*compile_library_classes_jar*' -name 'classes.jar' 2>/dev/null | tr '\n' ';')"
# modules-2 as well as transforms: pure-JVM artifacts (androidx.annotation, room-common,
# gson, kotlin-stdlib) never go through the AAR transform, so the transforms glob alone
# leaves gaps that surface as "cannot find symbol androidx.annotation.NonNull".
CP="$CP$(find "$HOME/.gradle/caches/modules-2" -name '*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' 2>/dev/null | sed 's|^/c/|C:/|' | tr '\n' ';')"

# Per-process output directory. A SHARED one is a false-green generator: when two agents
# run this at once they compile into the same tree and delete each other's classes, and the
# loser still prints "TYPECHECK OK" — observed 2026-08-24 as "652 sources, 294 classes"
# against a true 1806. A low class count is the only tell, and it is easy to skim past.
OUT=tools/jvm-harness/out-typecheck.$$
trap 'rm -rf "$OUT"' EXIT
rm -rf "$OUT"; mkdir -p "$OUT"

# @argfile, not a bare -cp: this classpath is ~70KB, well past what a Windows command line
# accepts, and the overflow fails SILENTLY (empty out dir, no "error:" line to grep).
SRCS=$(mktemp); ARGS=$(mktemp)
# BuildConfig is GENERATED, so it is not under src/ — include the generated tree or ~40
# files fail on a symbol that exists in every real build.
BCFG="app/build/generated/source/buildConfig/default/debug"
{ find app/src/main/java -name '*.java'; find "$BCFG" -name '*.java' 2>/dev/null; } \
  | sed 's|^|"|; s|$|"|' > "$SRCS"
COUNT=$(wc -l < "$SRCS")
{ echo "-nowarn"; echo "-encoding UTF-8"; echo "-d $OUT"; echo "-proc:none";
  printf -- '-cp "%s"\n' "$CP";
  # stubs-typecheck ONLY (not the harness stubs/, which shadow real app classes like Log):
  # the vendored AppLockLibrary needs its own R and so cannot be javac'd here.
  echo '-sourcepath "tools/jvm-harness/stubs-typecheck"'; } > "$ARGS"

echo "type-checking $COUNT sources..."
# grep --text: on this machine javac's failure output contains bytes grep treats as
# BINARY, which suppressed the entire error list ("Binary file (standard input)
# matches") — ebc9144e shipped as a false "TYPECHECK OK" partly behind this. Failures
# must always PRINT (LANE C, 2026-08-23, rule 9).
javac @"$ARGS" @"$SRCS" 2>&1 | grep --text -v '^Note:' | head -60
STATUS=${PIPESTATUS[0]}

# Positive control on the COMPILE itself: an empty out dir means the command never ran,
# which a grep for "error:" reports as success. run-matte.sh was bitten by exactly this.
CLASSES=$(find "$OUT" -name '*.class' | wc -l)
[ "$CLASSES" -gt 100 ] || { echo "FAIL: only $CLASSES class files — the compile did not really run"; exit 1; }
# Second positive control: class count must be in the same ballpark as source count. A run
# that emits far fewer classes than sources compiled SOMETHING, so the >100 gate above waves
# it through, but it did not compile THIS tree. See the shared-directory race noted at OUT=.
[ "$CLASSES" -gt "$COUNT" ] || { echo "FAIL: $CLASSES classes for $COUNT sources — the output tree is not this compile's"; exit 1; }

if [ "$STATUS" -eq 0 ]; then echo "TYPECHECK OK — $COUNT sources, $CLASSES classes"; else echo "TYPECHECK FAILED"; fi
exit "$STATUS"
