#!/usr/bin/env bash
# TextLab: run the see-through output check (ClearCheck) in the app on the phone, one look at a time.
#   bash tools/textlab-web/phone-clearcheck.sh <look> [<look> ...]
# Survives a cable that drops: every adb call has a time limit, a look whose run was cut off is tried again
# when the phone is back (up to 3 tries), and the results so far are printed as they come.
set -u
ADB="${ADB:-$HOME/AppData/Local/Android/Sdk/platform-tools/adb.exe}"
PKG="${PKG:-com.fadcam.beta}"
a() { MSYS_NO_PATHCONV=1 timeout 20 "$ADB" "$@"; }
waitPhone() {
  for i in $(seq 1 60); do
    a get-state 2>/dev/null | grep -q '^device' && return 0
    sleep 2
  done
  return 1
}
for look in "$@"; do
  for try in 1 2 3; do
    waitPhone || { echo "$look: phone not back after 2 minutes"; break; }
    a logcat -c
    a shell am start -S -n "$PKG/cc.joycreator.textlab.TextLabDebugActivity" \
      --es look "$look" --ef t 3 --ei w 1280 --ei h 720 --ez clearcheck true --ez live false > /dev/null || continue
    got=""
    for i in $(seq 1 30); do
      got=$(a logcat -d -s TextLab:I 2>/dev/null | grep -E "clear check|not saved|failed" | sed -E 's/^.*TextLab *: //')
      [ -n "$got" ] && break
      sleep 1
    done
    if [ -n "$got" ]; then echo "$got"; break; fi
    echo "$look: cut off (try $try)"
  done
done
a shell input keyevent KEYCODE_HOME > /dev/null 2>&1
echo "battery $(a shell dumpsys battery 2>/dev/null | grep temperature | tr -d ' \r')"
