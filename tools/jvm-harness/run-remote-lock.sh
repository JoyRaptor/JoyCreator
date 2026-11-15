#!/usr/bin/env bash
# Remote Studio lock (B3, 11-15): the pairing-code guess limiter. Plain javac, no Gradle.
# Usage:  bash tools/jvm-harness/run-remote-lock.sh
set -u
cd "$(dirname "$0")/../.." || exit 1
OUT=tools/jvm-harness/out-remotelock.$$
trap 'rm -rf "$OUT"' EXIT
mkdir -p "$OUT"
javac -nowarn -encoding UTF-8 -d "$OUT" \
  app/src/main/java/com/fadcam/streaming/LoginThrottle.java \
  tools/jvm-harness/remotelock/LoginThrottleTest.java || exit 1
java -cp "$OUT" LoginThrottleTest
