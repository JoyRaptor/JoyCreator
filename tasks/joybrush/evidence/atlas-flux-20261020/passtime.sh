#!/usr/bin/env bash
# passtime.sh <out.json> [JB_X defines] : per-pass window vs atlas GPU timing on the Note 9 through its Chrome (phone lock, Home before/after).
# The shaders come from C:/Temp/lead-atlas-exp (edit them there). JB_X (comma list) is added to every atlas pass's defines.
export MSYS_NO_PATHCONV=1; ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"; SER=$("$ADB" devices -l | grep SM_N960U | awk "{print $1}"); LOCK="$LOCALAPPDATA/Temp/jb-phone.lock"
( set -o noclobber; echo "JoyBrush Lead pass timing in Chrome $(date -Iseconds)" > "$LOCK" ) 2>/dev/null || { echo "lock held: $(cat $LOCK)"; exit 1; }
trap '"$ADB" -s $SER forward --remove tcp:9333 2>/dev/null; "$ADB" -s $SER shell input keyevent KEYCODE_HOME; rm -f "$LOCK"' EXIT
"$ADB" -s $SER shell input keyevent KEYCODE_HOME; sleep 1
"$ADB" -s $SER shell am start -n com.android.chrome/com.google.android.apps.chrome.Main -a android.intent.action.VIEW -d about:blank > /dev/null 2>&1; sleep 4
"$ADB" -s $SER forward tcp:9333 localabstract:chrome_devtools_remote > /dev/null; sleep 1
export NODE_PATH=$USERPROFILE/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules
export JB_X="$2"
cd C:/Temp/lead-atlas-exp && timeout 1500 node joybrush/tools/media_atlas_parity_check.js --time --cdp 9333 --size 1024 --quick > "$1" 2>"$1.err"
node -e "
const d=JSON.parse(require('fs').readFileSync(process.argv[1],'utf8')); const pt=d.passTime||{};
for (const n of ['flux','update','bead','apply','render']) { const w=(pt.new||{})[n]||{}, a=(pt.atlas||{})[n]||{}; console.log(n.padEnd(8),'window',String(w.ms).padStart(8),'atlas',String(a.ms).padStart(8),((a.ms||0)/Math.max(w.ms||1,0.1)).toFixed(2)+'x'); }
for (const [sc,v] of Object.entries(d.timing||{})) if (/wet/.test(sc)) console.log('  '+sc.padEnd(48),'sim w',v.new.simMedian,'a',v.atlas.simMedian);
console.log(d.renderer||'');" "$(cygpath -m "$1")"
