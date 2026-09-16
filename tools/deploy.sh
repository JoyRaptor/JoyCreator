#!/usr/bin/env bash
# deploy.sh — ONE command that gets a build onto the sandbox phone, wired or wireless.
#
# ── Why this exists (2026-09-16) ─────────────────────────────────────────────────────────────
# The pieces were all here and the sequence was not. Every session re-derived it, and got it
# wrong in one of three ways:
#
#   1. Ran `:app:installDefaultDebug`. Gradle's install task restarts the adb server, which drops
#      a wireless connection every single build. The watcher was changed to assemble only for
#      exactly this reason — and then nothing pushed the APK, so an agent tested a build from
#      hours ago and believed it. (JoyRaptor, 2026-09-16: "some agent changed the watcher to not
#      push install.")
#   2. Assumed wireless. USB is attached far more often than anyone remembers to check, and it is
#      the stable path when it is there. Reconnecting wireless over a working cable is pure loss.
#   3. Assumed wired. The USB connector on JoyRaptor's own phone is damaged and Wireless Debugging
#      turns itself off, so "adb devices is empty" means try the other transport, not give up.
#
# So: prefer USB, fall back to wireless, never let Gradle near the install, and PROVE the APK that
# is now on the phone is the one just built.
#
#   bash tools/deploy.sh              install the APK that is already built
#   bash tools/deploy.sh --build      assemble first, then install
#   bash tools/deploy.sh --launch     install, then force-stop and start the app
#   bash tools/deploy.sh --build --launch
#
# Exit 0 only when the APK is on the phone. Anything else prints WHY, in one line.
set -u
cd "$(dirname "$0")/.." || exit 1

ADB="${LOCALAPPDATA:-$HOME/AppData/Local}/Android/Sdk/platform-tools/adb.exe"
[ -x "$ADB" ] || ADB="$HOME/AppData/Local/Android/Sdk/platform-tools/adb.exe"
[ -x "$ADB" ] || { echo "deploy: adb not found under Android/Sdk/platform-tools."; exit 1; }

APK="app/build/outputs/apk/default/debug/app-default-arm64-v8a-debug.apk"

# THE PACKAGE IS NOT com.fadcam.debug. The "default" flavour carries applicationIdSuffix ".beta"
# (app/build.gradle.kts), so what installs is com.fadcam.beta — and a check that asks about the
# wrong name reports "not installed" for a package sitting right there, which reads as a failed
# install and sends the session chasing adb. Asked of the phone instead of hard-coded, so a new
# flavour cannot quietly break it.
PKG=""

DO_BUILD=0; DO_LAUNCH=0
for a in "$@"; do
  case "$a" in
    --build)  DO_BUILD=1 ;;
    --launch) DO_LAUNCH=1 ;;
    *) echo "deploy: unknown flag $a"; exit 1 ;;
  esac
done

# ── the wrong-phone guard, first and always ──────────────────────────────────────────────────
# REAL_SERIAL holds JoyRaptor's actual projects. An install onto it is not a mistake that can be
# undone by reinstalling: an uninstall wipes app-private storage silently, and that has already
# cost 23 projects once (LANES.md, 2026-08-29).
SANDBOX_SERIAL=""; REAL_SERIAL=""
# shellcheck disable=SC1091
[ -f tools/devices.local.sh ] && . tools/devices.local.sh

attached() { "$ADB" devices | awk '/\tdevice$/{print $1}'; }

pick() {
  local list; list=$(attached)
  [ -z "$list" ] && return 1
  # A named sandbox wins outright.
  if [ -n "$SANDBOX_SERIAL" ] && echo "$list" | grep -qx "$SANDBOX_SERIAL"; then
    echo "$SANDBOX_SERIAL"; return 0
  fi
  # Otherwise take the first that is NOT the real phone.
  local s
  for s in $list; do
    [ -n "$REAL_SERIAL" ] && [ "$s" = "$REAL_SERIAL" ] && continue
    echo "$s"; return 0
  done
  return 1
}

SERIAL=$(pick || true)

# ── no device: try the other transport before giving up ──────────────────────────────────────
if [ -z "$SERIAL" ]; then
  if [ -n "$(attached)" ]; then
    echo "deploy: REFUSED — the only phone attached is the one holding real projects."
    echo "        Unplug it, or attach the sandbox. Nothing was installed."
    exit 1
  fi
  echo "deploy: nothing on USB — trying wireless…"
  if bash tools/wifi-adb.sh --quiet >/dev/null 2>&1; then
    SERIAL=$(pick || true)
  fi
fi

if [ -z "$SERIAL" ]; then
  echo "deploy: no phone, on either transport."
  echo "        USB:      check the cable, and 'adb devices' after replugging."
  echo "        Wireless: Settings > Developer options > Wireless debugging turns ITSELF off."
  echo "                  Re-enable it, stay on that screen, then run tools/wifi-adb.sh."
  exit 1
fi

case "$SERIAL" in
  *:*) TRANSPORT="wireless" ;;
  *)   TRANSPORT="usb" ;;
esac
echo "deploy: $SERIAL ($TRANSPORT)"

# ── build ────────────────────────────────────────────────────────────────────────────────────
# assembleDefaultDebug, never installDefaultDebug. The install below is plain `adb install`, which
# does NOT restart the adb server — so a wireless session survives its own build.
if [ "$DO_BUILD" = "1" ]; then
  echo "deploy: assembling…"
  if ! ./gradlew :app:assembleDefaultDebug -q; then
    echo "deploy: BUILD FAILED — nothing installed."
    exit 1
  fi
fi

[ -f "$APK" ] || { echo "deploy: no APK at $APK — run with --build."; exit 1; }

# ── install ──────────────────────────────────────────────────────────────────────────────────
APK_EPOCH=$(date -r "$APK" +%s 2>/dev/null || echo 0)
echo "deploy: installing $(date -r "$APK" '+%H:%M:%S' 2>/dev/null) build…"
if ! "$ADB" -s "$SERIAL" install -r "$APK" 2>&1 | tail -2; then
  echo "deploy: INSTALL FAILED."
  exit 1
fi

# ── prove it landed ──────────────────────────────────────────────────────────────────────────
# BUILD SUCCESSFUL has lied twice in this repo, both times because the APK on the phone was older
# than the code. Comparing the installed package's own timestamp against the APK's is the cheap
# version of tools/build-verify.sh and catches the same class of lie.
PKG=$("$ADB" -s "$SERIAL" shell pm list packages 2>/dev/null \
      | tr -d '\r' | sed 's/^package://' | grep '^com[.]fadcam' | head -1)
if [ -z "$PKG" ]; then
  echo "deploy: WARNING - no com.fadcam* package after a successful install."
else
  INSTALLED=$("$ADB" -s "$SERIAL" shell dumpsys package "$PKG" 2>/dev/null \
              | tr -d '\r' | awk -F= '/lastUpdateTime/{print $2; exit}')
  echo "deploy: on the phone as $PKG${INSTALLED:+, updated $INSTALLED}"
fi

if [ "$DO_LAUNCH" = "1" ] && [ -n "$PKG" ]; then
  "$ADB" -s "$SERIAL" shell am force-stop "$PKG"
  "$ADB" -s "$SERIAL" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  echo "deploy: launched — wait ~8s before the first screenshot."
fi

# Hand the serial on, so a following phone.sh command drives the same phone.
echo "deploy: export PHONE=$SERIAL"
