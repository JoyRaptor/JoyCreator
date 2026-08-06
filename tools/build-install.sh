#!/usr/bin/env bash
# Build + install the debug APK onto the SANDBOX phone, from inside the agent.
#
# ── Why this exists (2026-08-06) ──────────────────────────────────────────────
# DEVICE_CONTROL_RUNBOOK §7f said an agent cannot build here: Gradle died with
# "java.io.IOException: Unable to establish loopback connection", and that was believed to be
# the sandbox blocking localhost. **It was not, and the message is a red herring.**
#
# Raw loopback works fine here — bind, connect, and cross-process accept all succeed. The real
# failure is in Selector.open(): on JDK 17/Windows it builds its wakeup pipe from an AF_UNIX
# socket pair, and AF_UNIX bind/connect fails with "Invalid argument" when java.io.tmpdir is an
# 8.3 SHORT PATH — which it is in the agent's shell (C:\Users\JOYRAP~1\AppData\Local\Temp).
# Gradle's daemon connection opens a Selector, so every Gradle invocation died at the same spot
# and reported it as a network problem.
#
# The fix is one system property, pointed at a non-short temp dir. JAVA_TOOL_OPTIONS rather than
# GRADLE_OPTS because the DAEMON needs it too, and Gradle does not forward -D flags into
# daemonOpts (verified: the daemon started, then failed its own Selector.open, and the launcher
# reported "A new daemon was started but could not be connected to").
#
# Usage:  bash tools/build-install.sh [gradle-task]     (default :app:installDefaultDebug)
#
# ⚠ DEVICE RULE: this installs onto whatever phone is attached. The Note 9 SANDBOX_SERIAL is
# the sandbox; the Note 20 REAL_SERIAL holds JoyRaptor's real project. This script REFUSES to run if
# the Note 20 is attached — the 2026-08-04 incident where a build was pushed onto it happened
# because nothing checked.
set -u
cd "$(dirname "$0")/.." || exit 1

export PATH="$PATH:/c/Users/JoyRaptor/AppData/Local/Android/Sdk/platform-tools"
if adb devices | grep -q "REAL_SERIAL"; then
  echo "REFUSING: the Note 20 (REAL_SERIAL) is attached — it holds the real project."
  exit 1
fi

export JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=C:\\Windows\\Temp"
TASK="${1:-:app:installDefaultDebug}"

./gradlew.bat --offline "$TASK" 2>&1 | tail -25
STATUS=${PIPESTATUS[0]}
[ "$STATUS" -eq 0 ] || { echo "BUILD FAILED ($STATUS)"; exit "$STATUS"; }

# Timestamp check, not a claim: HANDOFF_20260804c lesson 0 is that "compile-green" without a
# fresh APK on the device is worthless, and this session lost ~20 minutes to exactly that when
# the user's watcher died silently and build.log kept showing an old BUILD SUCCESSFUL.
echo "--- installed APK ---"
# Explicit serial: a bare `adb shell` here silently produced nothing when a stale offline
# entry was in the device list, which turned the freshness check into a no-op — the exact
# false reassurance it exists to prevent.
adb -s SANDBOX_SERIAL shell dumpsys package com.fadcam.beta 2>/dev/null \
    | grep -E "lastUpdateTime" || echo "  (could not read lastUpdateTime — CHECK MANUALLY)"
date '+now                     %Y-%m-%d %H:%M:%S'
