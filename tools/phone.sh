#!/usr/bin/env bash
# phone.sh — one entry point for driving the test phones over adb.
#
# WHY THIS EXISTS: agents keep failing device work for two avoidable reasons.
#   1. `adb` is NOT on PATH here, so `adb devices` returns "command not found" and the
#      agent concludes no device is attached. (2026-08-29: an agent skipped every device
#      check on that basis while a phone was plugged in.)
#   2. `uiautomator dump` returns a NULL ROOT on these devices, so there is no element
#      tree. The only way in is screenshot -> read pixels -> tap by coordinate. An agent
#      that waited for a tree reported 41/41 BLOCKED at the splash screen.
# Both are now one command away.
#
# Usage:
#   bash tools/phone.sh devices              list attached devices
#   bash tools/phone.sh size                 physical AND override size (tap against OVERRIDE)
#   bash tools/phone.sh install              install the current debug APK
#   bash tools/phone.sh launch               force-stop + launch the app
#   bash tools/phone.sh shot <file.png>      screenshot to that path
#   bash tools/phone.sh tap <x> <y>          tap (device pixels, per `size`)
#   bash tools/phone.sh swipe <x1> <y1> <x2> <y2> [ms]
#   bash tools/phone.sh log <TAG>            dump logcat for one tag
#   bash tools/phone.sh audio                our app's AudioTracks + allocation count
#   bash tools/phone.sh build                last BUILD line from build.log, with its date
#
# Multi-device: export PHONE=<serial> to pin one. Otherwise the first is used.
set -u
cd "$(dirname "$0")/.." || exit 1

ADB="${LOCALAPPDATA:-$HOME/AppData/Local}/Android/Sdk/platform-tools/adb.exe"
[ -x "$ADB" ] || ADB="$HOME/AppData/Local/Android/Sdk/platform-tools/adb.exe"
[ -x "$ADB" ] || { echo "adb not found. Looked in \$LOCALAPPDATA and \$HOME under Android/Sdk/platform-tools."; exit 1; }

PKG=com.fadcam.beta
APK=app/build/outputs/apk/default/debug/app-default-arm64-v8a-debug.apk

dev() {
  if [ -n "${PHONE:-}" ]; then echo "-s $PHONE"; return; fi
  local first
  first=$("$ADB" devices | awk '/\tdevice$/{print $1; exit}')
  [ -n "$first" ] && echo "-s $first"
}

cmd="${1:-}"; shift || true
case "$cmd" in
  devices) "$ADB" devices -l ;;
  size)    "$ADB" $(dev) shell wm size
           echo "NOTE: if an Override size is printed, compute ALL taps against the OVERRIDE." ;;
  install) "$ADB" $(dev) install -r "$APK" ;;
  launch)  "$ADB" $(dev) shell am force-stop $PKG
           "$ADB" $(dev) shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
           echo "launched; wait ~8s before the first screenshot" ;;
  shot)    out="${1:?usage: shot <file.png>}"
           "$ADB" $(dev) exec-out screencap -p > "$out" && echo "wrote $out" ;;
  tap)     "$ADB" $(dev) shell input tap "${1:?x}" "${2:?y}" ;;
  swipe)   "$ADB" $(dev) shell input swipe "${1:?x1}" "${2:?y1}" "${3:?x2}" "${4:?y2}" "${5:-300}" ;;
  log)     "$ADB" $(dev) logcat -d -s "${1:?usage: log <TAG>}" ;;
  audio)   p=$("$ADB" $(dev) shell pidof $PKG | tr -d '\r')
           echo "pid=$p"
           echo "--- AudioTracks (want state:started while playing) ---"
           "$ADB" $(dev) shell "dumpsys audio | grep $p" | grep -i audiotrack
           echo "--- AudioTracks allocated (churn is a bug even if it sounds fine) ---"
           # NOTE: "new player piid:" lines live in dumpsys audio's own event log, NOT in
           # logcat. Counting them from logcat returns 0 and reads as "no churn" — which is
           # exactly the kind of false green this file exists to prevent.
           "$ADB" $(dev) shell "dumpsys audio | grep -c 'new player piid'" ;;
  build)   ls -l --time-style=full-iso build.log
           tail -c 40000 build.log | iconv -f UTF-16LE -t UTF-8 2>/dev/null \
             | grep -aE "^BUILD (SUCCESSFUL|FAILED)" | tail -1 ;;
  *) sed -n '2,30p' "$0" ;;
esac
