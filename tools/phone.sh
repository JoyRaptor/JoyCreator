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
#   bash tools/phone.sh deploy               THE ONE TO USE: fresh-build check -> install ->
#                                            prove the APK on the phone is the one just built
#   bash tools/phone.sh doctor               why is this not working? Answers in one command.
#
# Multi-device: export PHONE=<serial> to pin one. Otherwise the first is used.
set -u
cd "$(dirname "$0")/.." || exit 1

ADB="${LOCALAPPDATA:-$HOME/AppData/Local}/Android/Sdk/platform-tools/adb.exe"
[ -x "$ADB" ] || ADB="$HOME/AppData/Local/Android/Sdk/platform-tools/adb.exe"
[ -x "$ADB" ] || { echo "adb not found. Looked in \$LOCALAPPDATA and \$HOME under Android/Sdk/platform-tools."; exit 1; }

PKG=com.fadcam.beta
APK=app/build/outputs/apk/default/debug/app-default-arm64-v8a-debug.apk

# Serials live OUTSIDE the repo (tools/devices.local.sh, gitignored) so they never reach
# GitHub. Missing file is not fatal here the way it is in build-install.sh -- that script
# INSTALLS and must fail closed, while most of this one only reads -- but the wrong-phone
# preference below cannot work without it, so say so once rather than choosing silently.
SANDBOX_SERIAL=""; REAL_SERIAL=""
if [ -f "tools/devices.local.sh" ]; then
  # shellcheck disable=SC1091
  . tools/devices.local.sh
fi

# Every serial adb currently calls ready. Offline/unauthorized entries are excluded on
# purpose: a stale offline entry used to win the old "first device" race and turn every
# check into a silent no-op.
ready_serials() { "$ADB" devices | awk '/\tdevice$/{print $1}'; }

#
# WHICH PHONE. The old version took the first ready device, which is wrong in the two
# situations that actually occur here:
#
#   * BOTH phones attached. The Note 20 holds JoyRaptor's real projects and the Note 9 is the
#     sandbox. "First" is whichever adb happened to list first, so the target was luck.
#   * A wireless entry and a USB entry for the SAME phone. Either works, but they are
#     different names for one device and mixing them across commands within a run makes the
#     freshness checks compare a phone against itself and disagree.
#
# Order: an explicit PHONE= always wins; then the sandbox if it is there; then USB over
# wireless (USB does not switch itself off mid-session); then whatever is left.
#
pick_serial() {
  if [ -n "${PHONE:-}" ]; then echo "$PHONE"; return; fi
  local all usb wifi
  all=$(ready_serials)
  [ -z "$all" ] && return
  if [ -n "$SANDBOX_SERIAL" ] && printf '%s\n' "$all" | grep -qx "$SANDBOX_SERIAL"; then
    echo "$SANDBOX_SERIAL"; return
  fi
  # NEVER VOLUNTEER THE REAL PHONE. SANDBOX_SERIAL above is preferred when present; if it
  # is not, the one phone that must never be picked by accident is the one holding
  # JoyRaptor's projects. An install there is not undoable - an uninstall wipes
  # app-private storage in silence, and that cost 23 projects once (2026-08-29). Pinning
  # PHONE= still overrides, so a deliberate read-only session on it stays possible.
  if [ -n "${REAL_SERIAL:-}" ]; then
    all=$(printf '%s\n' "$all" | grep -vx "$REAL_SERIAL")
    [ -z "$all" ] && return
  fi
  usb=$(printf '%s\n' "$all" | grep -v ':' | head -1)
  [ -n "$usb" ] && { echo "$usb"; return; }
  wifi=$(printf '%s\n' "$all" | head -1)
  echo "$wifi"
}

dev() {
  local sel
  sel=$(pick_serial)
  [ -n "$sel" ] && echo "-s $sel"
}

# Human-readable name for a serial, without ever printing a serial into a tracked file.
nameof() {
  local sel="$1"
  if [ -n "$SANDBOX_SERIAL" ] && [ "$sel" = "$SANDBOX_SERIAL" ]; then echo "the sandbox phone"
  elif [ -n "$REAL_SERIAL" ] && [ "$sel" = "$REAL_SERIAL" ]; then echo "JoyRaptor's own phone"
  elif printf '%s' "$sel" | grep -q ':'; then echo "a wireless device"
  else echo "a USB device"; fi
}

# Bring a phone back without asking which transport it is on. USB first because it needs
# nothing; the wireless helper only runs when USB found nothing, and it is the one that can
# print instructions JoyRaptor has to act on.
ensure_device() {
  [ -n "$(pick_serial)" ] && return 0
  "$ADB" reconnect offline >/dev/null 2>&1
  "$ADB" devices >/dev/null 2>&1
  [ -n "$(pick_serial)" ] && return 0
  echo "no phone on USB; trying wireless"
  bash tools/wifi-adb.sh || true
  [ -n "$(pick_serial)" ]
}

# The freshest BUILD line the watcher wrote, decoded from its UTF-16.
build_log_tail() {
  tail -c 60000 build.log 2>/dev/null | iconv -f UTF-16LE -t UTF-8 2>/dev/null \
    || tail -c 60000 build.log 2>/dev/null | tr -d '\000'
}

cmd="${1:-}"; shift || true
case "$cmd" in
  devices) "$ADB" devices -l ;;
  size)    "$ADB" $(dev) shell wm size
           echo "NOTE: if an Override size is printed, compute ALL taps against the OVERRIDE." ;;
  install) ensure_device || { echo "no phone reachable"; exit 1; }
           "$ADB" $(dev) install -r "$APK" ;;

  # ── THE ONE TO USE ──────────────────────────────────────────────────────────
  #
  # JoyRaptor, 2026-09-16: "some agent changed the watcher to not push install ... we need a
  # good way to work both wired and wireless, because wireless debug often switches off and usb
  # sometimes has cable issues, sometimes watcher goes stale."
  #
  # Three separate things can silently leave an OLD build on the phone, and each of them has
  # cost this project time:
  #   1. the watcher is dead, so build.log's BUILD SUCCESSFUL is hours old;
  #   2. the watcher built, but no longer installs;
  #   3. install ran against a phone that had dropped off, or the wrong one.
  # Every one of them ends the same way -- an agent testing a build that is not the build --
  # so this checks all three and REFUSES rather than reporting a success it cannot support.
  deploy)
           # --build assembles first. assembleDefaultDebug ONLY: gradle's
           # installDefaultDebug restarts the adb server, which drops a wireless session
           # on every single build. The install below is plain `adb install`, which does
           # not - so a wireless session survives its own build.
           if [ "${1:-}" = "--build" ]; then
             shift
             echo "assembling..."
             ./gradlew :app:assembleDefaultDebug -q || {
               echo "BUILD FAILED - nothing installed."; exit 1;
             }
           fi
           if [ ! -f "$APK" ]; then
             echo "NO APK at $APK"
             echo "  The watcher has never produced one. Start it, or run: bash tools/build-install.sh"
             exit 1
           fi
           apk_age=$(( $(date +%s) - $(stat -c %Y "$APK") ))
           log="$(build_log_tail)"
           overall="$(printf '%s' "$log" | grep -aE "^BUILD (SUCCESSFUL|FAILED)" | tail -1)"
           compile="$(printf '%s' "$log" | grep -aE "Task :app:compile.*JavaWithJavac" | tail -1)"
           case "$compile" in
             *FAILED*) echo "COMPILE FAILED - fix the code before deploying."; exit 1 ;;
           esac
           echo "APK built $((apk_age / 60)) min ago   |   $overall"
           # Not a hard stop: a run that only changed a comment legitimately leaves the APK
           # untouched, and refusing that would be its own kind of wrong. Say it loudly instead.
           # Only shout when a SOURCE file is newer than the APK. Age alone is meaningless:
           # a run that changed only docs legitimately leaves yesterday's APK in place.
           newest=$(find app/src -name '*.java' -o -name '*.xml' 2>/dev/null \
                    | xargs stat -c %Y 2>/dev/null | sort -n | tail -1)
           if [ -n "$newest" ] && [ "$newest" -gt "$(stat -c %Y "$APK")" ]; then
             echo "  WARNING: a source file is NEWER than this APK. The watcher has not caught"
             echo "  up, so what lands on the phone will NOT include your latest change."
           fi

           ensure_device || {
             echo "REFUSING: no phone reachable over USB or wireless."
             echo "  Run: bash tools/phone.sh doctor"
             exit 1
           }
           sel=$(pick_serial)
           echo "installing onto $(nameof "$sel")"
           "$ADB" -s "$sel" install -r "$APK" || { echo "INSTALL FAILED"; exit 1; }

           # PROVE IT LANDED. dumpsys reports when the package was last written, so a compare
           # against the APK's own mtime is the only check that cannot be fooled by a stale
           # "Success" from an install that went to a different device.
           installed=$("$ADB" -s "$sel" shell dumpsys package $PKG 2>/dev/null \
                       | grep -m1 lastUpdateTime | sed 's/.*lastUpdateTime=//' | tr -d '\r')
           echo "APK file       $(date -r "$APK" '+%Y-%m-%d %H:%M:%S')"
           echo "on the phone   ${installed:-UNKNOWN}"
           if [ -z "$installed" ]; then
             echo "  could not read it back - verify by hand before trusting this build"
           fi ;;

  # ── WHY IS THIS NOT WORKING ─────────────────────────────────────────────────
  #
  # One command that answers the question, instead of four commands and a guess. Everything
  # here is read-only.
  doctor)
           echo "== adb =="
           echo "  $ADB"
           "$ADB" version 2>&1 | head -1
           echo
           echo "== phones adb can see =="
           "$ADB" devices -l | tail -n +2 | sed 's/^/  /'
           n=$(ready_serials | wc -l | tr -d ' ')
           echo "  ready: $n"
           if [ "$n" = "0" ]; then
             echo "  -> nothing ready. USB: replug, and check the phone for an 'Allow USB"
             echo "     debugging' prompt. WIRELESS: Settings > Developer options > Wireless"
             echo "     debugging, switch on and STAY on that page, then: bash tools/wifi-adb.sh"
           else
             sel=$(pick_serial)
             echo "  -> would use $(nameof "$sel")"
           fi
           echo
           echo "== identities =="
           if [ -f "tools/devices.local.sh" ]; then
             echo "  tools/devices.local.sh present (serials stay out of git)"
           else
             echo "  tools/devices.local.sh MISSING - cannot tell the two phones apart."
             echo "  Copy tools/devices.local.sh.example and fill it in."
           fi
           echo
           echo "== the watcher =="
           if [ -f build.log ]; then
             echo "  build.log last written $(date -r build.log '+%Y-%m-%d %H:%M:%S')"
             log="$(build_log_tail)"
             printf '%s' "$log" | grep -aE "^BUILD (SUCCESSFUL|FAILED)" | tail -1 | sed 's/^/  /'
             # IS IT DEAD, OR WAS THERE NOTHING TO DO? A bare "no build in N minutes" fires
             # on any session that only touched docs, and a warning that cries wolf is one
             # people learn to scroll past. Compare against the newest SOURCE file instead:
             # the watcher is only late if something it should have rebuilt is newer than it.
             newest=$(find app/src -name '*.java' -o -name '*.xml' 2>/dev/null \
                      | xargs stat -c %Y 2>/dev/null | sort -n | tail -1)
             blog=$(stat -c %Y build.log)
             if [ -n "$newest" ] && [ "$newest" -gt "$blog" ]; then
               echo "  -> a source file is NEWER than the last build ($(( (newest - blog) / 60 )) min)."
               echo "     The watcher is not keeping up. Restart it, or: bash tools/build-install.sh"
             else
               echo "  -> up to date with the source (nothing has changed since the last build)."
             fi
           else
             echo "  no build.log at all - the watcher has never run here."
           fi
           echo
           echo "== the APK =="
           if [ -f "$APK" ]; then
             echo "  built $(date -r "$APK" '+%Y-%m-%d %H:%M:%S')"
           else
             echo "  MISSING at $APK"
           fi ;;
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
  build)   # Report the COMPILE separately from the overall build. The watcher also runs
           # installDefaultDebug, which FAILS whenever no phone is attached, so the final
           # "BUILD FAILED" line says nothing about whether the code compiles. Reading only
           # that line sends you hunting a compile error that does not exist.
           ls -l --time-style=full-iso build.log
           log="$(tail -c 60000 build.log | iconv -f UTF-16LE -t UTF-8 2>/dev/null)"
           [ -z "$log" ] && log="$(tail -c 60000 build.log | tr -d ' ')"
           overall="$(printf '%s' "$log" | grep -aE "^BUILD (SUCCESSFUL|FAILED)" | tail -1)"
           compile="$(printf '%s' "$log" | grep -aE "Task :app:compile.*JavaWithJavac" | tail -1)"
           case "$compile" in
             *FAILED*) echo "COMPILE: FAILED  <-- this one is your code" ;;
             "")       echo "COMPILE: not in the tail (probably up-to-date)" ;;
             *)        echo "COMPILE: clean" ;;
           esac
           echo "$overall"
           case "$overall" in
             *FAILED*)
               if printf '%s' "$log" | grep -qa "No connected devices"; then
                 echo "  ^ that is the INSTALL step with no phone attached, not your code."
                 echo "    Reconnect with: bash tools/wifi-adb.sh"
               fi ;;
           esac ;;
  *) sed -n '2,30p' "$0" ;;
esac
