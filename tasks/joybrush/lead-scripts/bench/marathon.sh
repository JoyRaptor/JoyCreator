#!/bin/bash
# marathon.sh <tag> <dropDry true|false> [mb] : 30 wet strokes in bands across the page, one drawing, a small media room; refusals counted.
cd /c/Temp/lead-1020
C=$(for c in 1 2 3; do for i in 0 1 2 3 4 5 6 7 8 9; do printf "band$i,"; done; done); C=${C%,}
adb_shell() { :; }
BRUSH="media:wet:Round" TAG=${1}_ TIMEOUT=1500 EXTRA="--ez jb_media_atlas true --ez jb_bench_clear false --ei jb_media_budget_mb ${3:-60} --ez jb_drop_dry $2" bash water_profile.sh wp_ab "$C" 1200 today > /dev/null 2>&1
echo "$1 strokes $(grep -c '{"legacy"' wp_ab/${1}_today.log) refused $(grep -c 'refused:' wp_ab/${1}_today.all.log) first-refusal-at $(grep -n 'refused:\|{"legacy"' wp_ab/${1}_today.all.log | grep -m1 -n 'refused' | cut -d: -f1)"
