#!/usr/bin/env bash
# Joybot benchmark: the REAL JoybotAgent and its tools, on the PC, against a task corpus.
#
#   bash tools/jvm-harness/run-joybot-bench.sh                 # fake model: host guarantees + reference runs
#   OPENROUTER_API_KEY=… bash tools/jvm-harness/run-joybot-bench.sh --live --models "a:free,b:free"
#   bash tools/jvm-harness/run-joybot-bench.sh --tasks studio-title-red,host-malformed-args
#
#   bash tools/jvm-harness/run-joybot-bench.sh --phone --models "openrouter/free"   # live, ON the Note 9,
#        with the key saved in the app (claim the phone first: tools/phone.ps1 claim joybot-bench "…")
# Other flags: --out <file.md> (results table), --repeat <n> (live: runs per task), --probe (list tools),
# --bridge (BOT-6: the computer bridge end to end over HTTP, BridgeE2E).
# Live runs only ever use models whose id ends in ":free" (or openrouter/free) unless --allow-paid is
# given, which needs the owner's OK. The key is read from the environment and never printed.
# How it works and how to add tasks: tools/jvm-harness/joybot-bench/README.md
set -u
cd "$(dirname "$0")/../.." || exit 1

bash tools/jvm-harness/joybot-bench/compile-app.sh || exit 1
APP=tools/jvm-harness/out-joybot-app
LIBS=$(cat "$APP/.libs")
SRCROOT=$(cat "$APP/.root")
OUT=tools/jvm-harness/out-joybot-bench
rm -rf "$OUT"; mkdir -p "$OUT/shim" "$OUT/bench"

# The shims replace android.jar's "Stub!" bodies at RUN time; they compile against android.jar so
# their signatures match what the app was compiled against.
# @argfiles: the library classpath is far past what a Windows command line takes.
printf -- '-cp "%s"\n' "$LIBS" > "$OUT/cp-shim.txt"
javac -nowarn -encoding UTF-8 -d "$OUT/shim" @"$OUT/cp-shim.txt" $(find tools/jvm-harness/joybot-bench/shim -name '*.java') || exit 1
CP="$OUT/bench;$OUT/shim;$APP;$LIBS"
printf -- '-cp "%s"\n' "$CP" > "$OUT/cp.txt"
javac -nowarn -encoding UTF-8 -d "$OUT/bench" @"$OUT/cp.txt" $(find tools/jvm-harness/joybot-bench/src -name '*.java') || exit 1

MAIN=JoybotBench
for arg in "$@"; do [ "$arg" = "--probe" ] && MAIN=Probe; [ "$arg" = "--bridge" ] && MAIN=BridgeE2E; done
[ "$MAIN" != JoybotBench ] && set --
# jdk.net.unixdomain.tmpdir: on this PC no AF_UNIX socket works under %LOCALAPPDATA%, so any JVM that
# opens a selector (the fake service, OkHttp) dies with "Unable to establish loopback connection".
mkdir -p C:/Temp 2>/dev/null
exec java -Djdk.net.unixdomain.tmpdir=C:/Temp -Dbench.assets="$SRCROOT/app/src/main/assets" -Dbench.home=tools/jvm-harness/joybot-bench \
  -Dbench.cpfile="$OUT/cp.txt" @"$OUT/cp.txt" "$MAIN" "$@"
