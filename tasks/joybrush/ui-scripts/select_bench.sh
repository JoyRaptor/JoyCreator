#!/bin/bash
# The Select tool's phone acceptance, unattended (bench/SelectBench.kt; reviews/SELECT_LINES_GAPS_20261023.md §4).
# Usage: select_bench.sh <out-dir> [lane-name]
# Needs a DEBUG build of the app on the Note 9 (the sandbox; never the Note 20). Takes and releases jb-phone.lock, logs the
# phone's temperature before and after, saves one PNG per "SHOT" line and every step's JSON to <out-dir>/results.jsonl.
# Opens a fresh unsaved drawing in a second Joy Brush screen and closes it at the end: the owner's drawings are untouched.
set -u
OUT=${1:?out dir}; LANE=${2:-UI specialist (Select bench)}
export PATH="/c/Users/JoyRaptor/AppData/Local/Android/Sdk/platform-tools:$PATH"
. "/c/+Projects/Screenrecorder/FadCam/tools/devices.local.sh"   # SANDBOX_SERIAL (private, never printed)
S=$SANDBOX_SERIAL
L="$LOCALAPPDATA/Temp/jb-phone.lock"
mkdir -p "$OUT"
if ! ( set -o noclobber; printf '%s\n%s\n%s\n' "$LANE" "$(date '+%F %T')" "Select bench" > "$L" ) 2>/dev/null; then
  echo "PHONE BUSY:"; cat "$L"; exit 2
fi
trap 'rm -f "$L"' EXIT
temp() { adb -s $S shell dumpsys battery | tr -d '\r' | awk '/temperature/{print $2/10}'; }
echo "temp_before_C $(temp)" | tee "$OUT/temps.txt"
adb -s $S shell dumpsys package com.fadcam.beta | tr -d '\r' | grep -m1 lastUpdateTime | tee "$OUT/build.txt"
adb -s $S logcat -c
adb -s $S shell am start -n com.fadcam.beta/com.fadcam.joybrush.JoyBrushBenchActivity --es jb_bench select > /dev/null
: > "$OUT/results.jsonl"
end=$((SECONDS + 240))
adb -s $S logcat -v raw -s JoyBrushPerf:I | tr -d '\r' | while IFS= read -r line; do
  case "$line" in
    SHOT\ *) n=${line#SHOT }; adb -s $S exec-out screencap -p > "$OUT/$n.png"; echo "shot $n" ;;
    \{\"selectBench\"*|*\"selectBench\"*) echo "$line" >> "$OUT/results.jsonl"; echo "$line" ;;
    refused:*) echo "$line" | tee -a "$OUT/refusals.txt" ;;
    done*) echo "bench done"; break ;;
  esac
  [ $SECONDS -gt $end ] && { echo "TIMEOUT"; break; }
done
echo "temp_after_C $(temp)" | tee -a "$OUT/temps.txt"
python - "$OUT/results.jsonl" <<'EOF'
import json, sys
rows = [json.loads(l) for l in open(sys.argv[1], encoding='utf-8') if l.strip()]
for r in rows:
    if r.get('selectBench') == 'summary': continue
    print(('PASS ' if r.get('pass') else 'FAIL ') + r['selectBench'], {k: v for k, v in r.items() if k not in ('selectBench', 'pass')})
s = [r for r in rows if r.get('selectBench') == 'summary']
print('SUMMARY', s[0] if s else 'missing (the bench did not finish)')
EOF
