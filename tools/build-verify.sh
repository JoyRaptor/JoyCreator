#!/usr/bin/env bash
# Build the debug APK and PROVE a symbol you just wrote is actually inside it.
#
# ── Why this exists (2026-09-13) ─────────────────────────────────────────────────────────────
# Two agents were working in this repo at once, sharing ONE build directory. One build deleted
# the javac output from under the other, and from then on Gradle's up-to-date checks lied:
#
#     BUILD SUCCESSFUL in 1s
#
# ...with no FaditorEditorActivity.class anywhere in app/build, and an APK newer than the source
# edit that did NOT contain the edit. Nothing in the Gradle output said so. An install from that
# APK would have "verified" a change that was never compiled, and the wrong conclusion would have
# been drawn on a phone.
#
# So: do not trust BUILD SUCCESSFUL. Trust the dex. This greps the packaged dex files for a symbol
# you name — a method you just added is ideal — and fails loudly if it is missing.
#
# This is the same discipline tools/build-install.sh already applies with its timestamp check, one
# layer deeper: a fresh TIMESTAMP is not a fresh BUILD when another process is racing you.
#
# Usage:  bash tools/build-verify.sh <symbol> [gradle-task]
# e.g.    bash tools/build-verify.sh applyPreviewStackElevations
set -u
cd "$(dirname "$0")/.." || exit 1

SYMBOL="${1:-}"
if [ -z "$SYMBOL" ]; then
  echo "usage: bash tools/build-verify.sh <symbol-to-prove> [gradle-task]"
  exit 2
fi
TASK="${2:-assembleDefaultDebug}"
APK="app/build/outputs/apk/default/debug/app-default-arm64-v8a-debug.apk"

echo "--- building $TASK ---"
./gradlew "$TASK" --console=plain 2>&1 | grep -E "error:|BUILD SUCCESSFUL|BUILD FAILED|Unable to delete" | head -20
STATUS=${PIPESTATUS[0]}

# A lock failure is CONTENTION, not a code error — say which it was, because the two get
# confused and cost hours (see the memory note on multiple watchers).
if [ "$STATUS" -ne 0 ]; then
  echo
  echo "BUILD FAILED. If the message was 'Unable to delete directory', another build is running"
  echo "in this same tree — that is contention, not your code. Wait for it and retry."
  exit "$STATUS"
fi

if [ ! -f "$APK" ]; then
  echo "NO APK at $APK — nothing to verify."
  exit 1
fi

python - "$APK" "$SYMBOL" <<'PY'
import sys, zipfile
apk, symbol = sys.argv[1], sys.argv[2].encode()
z = zipfile.ZipFile(apk)
hits = [n for n in z.namelist() if n.endswith('.dex') and symbol in z.read(n)]
if hits:
    print("VERIFIED: %s is in %s (%s)" % (symbol.decode(), apk, ", ".join(hits[:3])))
    sys.exit(0)
print("NOT IN THE APK: %s" % symbol.decode())
print("  The build reported success and the APK is on disk, but your symbol is not in it.")
print("  Gradle's task state is stale — most likely another build in this tree deleted the")
print("  javac output mid-run. Recover with:")
print("    rm -rf app/build/intermediates/javac/defaultDebug")
print("    ./gradlew assembleDefaultDebug --console=plain --no-configuration-cache")
sys.exit(1)
PY
