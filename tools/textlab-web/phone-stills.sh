#!/usr/bin/env bash
# TextLab: render looks' stills in the APP on the phone and pull them, for compare.py.
#   bash tools/textlab-web/phone-stills.sh <out dir> <look> [<look> ...]
# Uses the debug screen (cc.joycreator.textlab.TextLabDebugActivity) at 1280 x 720, t = 3 s, the look's own words.
# Claim the phone first (tools/phone.ps1 claim). Logs the battery temperature for each look.
set -u
ADB="${ADB:-$HOME/AppData/Local/Android/Sdk/platform-tools/adb.exe}"
PKG="${PKG:-com.fadcam.beta}"
OUT="$1"; shift
mkdir -p "$OUT"
for look in "$@"; do
  "$ADB" logcat -c
  MSYS_NO_PATHCONV=1 "$ADB" shell am start -S -n "$PKG/cc.joycreator.textlab.TextLabDebugActivity" \
    --es look "$look" --ef t 3 --ei w 1280 --ei h 720 > /dev/null
  for i in $(seq 1 60); do
    "$ADB" logcat -d -s TextLab:I | grep -q "still \|failed" && break
    sleep 1
  done
  sleep 4   # let the live frame time settle
  "$ADB" logcat -d -s TextLab:I TextLab:E | grep -E "still |failed|live:" | tail -2 | sed "s/^/$look: /"
  echo "$look: battery $("$ADB" shell dumpsys battery | grep temperature | tr -d ' \r')"
  MSYS_NO_PATHCONV=1 "$ADB" pull "/storage/emulated/0/Android/data/$PKG/files/textlab/${look}_t3.png" "$OUT/" > /dev/null
done
