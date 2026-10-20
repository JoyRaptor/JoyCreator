#!/usr/bin/env bash
# water_profile.sh <outdir> <case> <settle_ms> <ids...> — the owner's six water choices on the Note 9, over adb only, under
# jb-phone.lock (released on exit). Never installs, uninstalls or clears data. Home before and after each run (the bench
# opens its own fresh, unsaved drawing). Per choice: an app-only atrace (the JB.media.* sections) and the bench's report.
export MSYS_NO_PATHCONV=1
OUT=$1; CASE=$2; SETTLE=$3; shift 3
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
LOCK="$LOCALAPPDATA/Temp/jb-phone.lock"
if ! ( set -o noclobber; echo "JoyBrush bench run (water_profile.sh) $(date -Iseconds)" > "$LOCK" ) 2>/dev/null; then
  echo "phone lock held: $(cat "$LOCK")"; exit 1
fi
trap '"$ADB" -s "$SER" shell input keyevent KEYCODE_HOME; rm -f "$LOCK"; echo "phone lock released $(date +%T)"' EXIT
SER=$("$ADB" devices -l | grep -i "SM_N960U\|SM-N960U" | awk '{print $1}' | head -1)
[ -z "$SER" ] && { echo "Note 9 not connected"; exit 1; }
APP=com.fadcam.beta
mkdir -p "$OUT"
for id in "$@"; do
  out="$TAG$id"
  echo "== $id $(date +%T)"
  "$ADB" -s "$SER" shell input keyevent KEYCODE_HOME
  sleep 1
  "$ADB" -s "$SER" logcat -c
  "$ADB" -s "$SER" shell "atrace --async_stop -o /dev/null >/dev/null 2>&1; atrace --async_start -a $APP -b 16384" > /dev/null
  "$ADB" -s "$SER" shell "am start -S -n $APP/com.fadcam.joybrush.JoyBrushBenchActivity --es jb_bench '$CASE' --es jb_bench_brushes '${BRUSH:-media:wet:Round}' --ei jb_bench_runs 1 --el jb_settle_ms $SETTLE --es jb_water $id --el jb_hold_ms 5000 $EXTRA" > /dev/null
  start=$(date +%s)
  while true; do
    "$ADB" -s "$SER" logcat -d -s JoyBrushPerf:I JoyBrushWater:I JoyBrushMediaMemory:I AndroidRuntime:E > "$OUT/$out.log" 2>/dev/null
    P=$("$ADB" -s "$SER" shell pidof $APP | tr -d ''); [ -n "$P" ] && "$ADB" -s "$SER" logcat -d --pid=$P > "$OUT/$out.all.log" 2>/dev/null
    if [ ! -f "$OUT/$out.png" ] && grep -q "JoyBrushPerf: hold" "$OUT/$out.log"; then
      "$ADB" -s "$SER" shell screencap -p /data/local/tmp/jb_water_shot.png && "$ADB" -s "$SER" pull /data/local/tmp/jb_water_shot.png "$OUT/$out.png" > /dev/null
    fi
    grep -qE "JoyBrushPerf: done|\"event\":\"done\"" "$OUT/$out.log" && break
    [ $(( $(date +%s) - start )) -gt ${TIMEOUT:-$(( SETTLE / 1000 + 90 ))} ] && { echo "timed out"; break; }
    sleep 3
  done
  "$ADB" -s "$SER" shell "atrace --async_stop -o /data/local/tmp/jb_water_$id.txt" > /dev/null
  "$ADB" -s "$SER" pull /data/local/tmp/jb_water_$id.txt "$OUT/$out.trace.txt" > /dev/null
  "$ADB" -s "$SER" shell "rm -f /data/local/tmp/jb_water_$id.txt"
  echo "   log $(wc -l < "$OUT/$out.log") lines, trace $(wc -c < "$OUT/$out.trace.txt") bytes, $(( $(date +%s) - start )) s"
done
