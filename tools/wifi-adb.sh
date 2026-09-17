#!/usr/bin/env bash
# wifi-adb.sh — get (or get back) a wireless adb connection to the phone.
#
# WHY THIS EXISTS: JoyRaptor's USB connector is damaged, so wireless is the only path
# (tasks/WIRELESS_ADB_CONNECT.md). The connection drops constantly — `:app:installDefaultDebug`
# restarts the adb server and kills it (tasks/INBOX.md 2026-09-15), the phone's Wireless
# Debugging toggle turns itself off, and the port changes on every toggle or reboot. Every
# session was re-deriving the same four commands by hand, several times an hour.
#
#   bash tools/wifi-adb.sh            connect, or report exactly why it cannot
#   bash tools/wifi-adb.sh --quiet    same, but only print the serial (for scripting)
#
# Prints the IP:port on success — that string IS the adb serial afterwards, not the
# hardware serial. Exit 0 = connected, 1 = not.
set -u
cd "$(dirname "$0")/.." || exit 1

ADB="${LOCALAPPDATA:-$HOME/AppData/Local}/Android/Sdk/platform-tools/adb.exe"
[ -x "$ADB" ] || ADB="$HOME/AppData/Local/Android/Sdk/platform-tools/adb.exe"
[ -x "$ADB" ] || { echo "adb not found under Android/Sdk/platform-tools."; exit 1; }

QUIET=0
[ "${1:-}" = "--quiet" ] && QUIET=1
say() { [ "$QUIET" = "1" ] || echo "$@"; }

# Already good? Only a WIRELESS entry counts - one with a colon in it, because a wireless
# adb serial is ip:port while a USB one is the hardware serial.
#
# This used to accept ANY ready device, which is wrong the moment both phones are attached:
# asking for a wireless connection with the sandbox phone on USB answered "already connected:
# <usb serial>" and exited 0, so the caller went on to talk to the wrong phone. Found
# 2026-09-16 trying to read the Note 20's logs while the Note 9 sat on the cable.
online="$("$ADB" devices | awk '/	device$/{print $1}' | grep ':' | head -1)"
if [ -n "$online" ]; then
  say "already connected: $online"
  [ "$QUIET" = "1" ] && echo "$online"
  exit 0
fi
for dead in $("$ADB" devices | awk '/\toffline$/{print $1}'); do
  say "dropping offline entry $dead"
  "$ADB" disconnect "$dead" >/dev/null 2>&1
done

# mDNS is the only discovery that works here, and it needs a live server to have scanned.
# A restart makes it re-scan; without it the list is often empty or stale.
try_connect() {
  local endpoints
  endpoints="$("$ADB" mdns services 2>/dev/null | tr -d '\r' \
      | awk '/_adb-tls-connect/{print $NF}' | sort -u)"
  [ -z "$endpoints" ] && return 1
  # Stale records outlive the listener, and the NEWEST record wins — so try them all
  # rather than trusting the first (WIRELESS_ADB_CONNECT.md, field lesson 2026-08-31).
  local ep
  for ep in $endpoints; do
    say "trying $ep"
    if "$ADB" connect "$ep" 2>&1 | grep -q "^connected\|already connected"; then
      sleep 1
      if "$ADB" devices | grep -q "$ep[[:space:]]*device$"; then
        say "connected: $ep"
        [ "$QUIET" = "1" ] && echo "$ep"
        return 0
      fi
    fi
  done
  return 1
}

try_connect && exit 0

say "no luck on the first pass; restarting the adb server and re-scanning"
"$ADB" kill-server >/dev/null 2>&1
sleep 2
"$ADB" start-server >/dev/null 2>&1
sleep 3
try_connect && exit 0

cat <<'EOF'
Could not reach the phone.

The two things that are almost always it:
  1. Wireless Debugging turned itself off. Ask JoyRaptor to open
     Settings > Developer options > Wireless debugging, switch it on, and STAY on that
     page — leaving it or locking the screen stops the mDNS advertisement.
  2. Phone and PC are on different networks (phone on mobile data, PC on WiFi).

Then run this script again. Do NOT ask for a USB cable: the connector is damaged.
EOF
exit 1
