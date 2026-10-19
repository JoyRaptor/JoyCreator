#!/bin/bash
# Build the integration APK under the lock, then install it on the Note 9 under the phone lock (HOME first if Joy is in front).
S=${LEAD_SCRIPTS:-$(dirname "$0")}
W=/c/Temp/jb-integ-fix
ADB=/c/Users/JoyRaptor/AppData/Local/Android/Sdk/platform-tools/adb.exe
PL=/c/Users/JoyRaptor/AppData/Local/Temp/jb-phone.lock
APK=$1


sha256sum $APK | cut -c1-64
SER=$("$ADB" devices -l | grep SM_N960U | awk '{print $1}'); [ -n "$SER" ] || { echo NO_NOTE9; exit 1; }
for i in $(seq 1 120); do
  if [ -e "$PL" ]; then age=$(( $(date +%s) - $(stat -c %Y "$PL") )); [ $age -gt 7200 ] && mv "$PL" "$PL.stale-$(date +%H%M)"; fi
  ( set -o noclobber; echo "JoyBrush Lead (fork) install $(date)" > "$PL" ) 2>/dev/null && break; sleep 30; done
grep -q "Lead (fork)" "$PL" 2>/dev/null || { echo "GAVE_UP phone lock: $(cat $PL)"; exit 1; }
if "$ADB" -s $SER shell dumpsys activity activities | grep -m1 -E "mResumedActivity|topResumedActivity" | grep -q com.fadcam.beta; then "$ADB" -s $SER shell input keyevent KEYCODE_HOME; sleep 5; fi
"$ADB" -s $SER install -r $APK | tail -1
"$ADB" -s $SER logcat -c
LAUNCH=$("$ADB" -s $SER shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER com.fadcam.beta | tr -d '\r' | tail -1)
"$ADB" -s $SER shell am start -n "$LAUNCH" | tail -1
sleep 8
"$ADB" -s $SER logcat -d -s AndroidRuntime:E | grep -E "FATAL|Exception" | head -5
"$ADB" -s $SER shell dumpsys activity activities | grep -m1 -E "mResumedActivity|topResumedActivity" | sed 's/.*{//' | cut -c1-120
"$ADB" -s $SER exec-out screencap -p > $S/after-install.png && echo "SCREENSHOT $S/after-install.png"
rm -f "$PL"; echo INSTALL_DONE
