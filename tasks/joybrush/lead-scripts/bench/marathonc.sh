#!/bin/bash
# marathonb.sh <tag> <dropDry true|false> [mb] : 30 wet strokes over the top half (bands 0-4, six times), one drawing; refusals counted.
cd /c/Temp/lead-1020
C=$(for c in 1 2 3 4 5 6 7 8 9 10; do for i in 0 1 2; do printf "band$i,"; done; done); C=${C%,}
BRUSH="media:wet:Round" TAG=${1}_ TIMEOUT=1500 EXTRA="--ez jb_media_atlas true --ez jb_bench_clear false --ei jb_media_budget_mb ${3:-20} --ez jb_drop_dry $2" bash water_profile.sh wp_ab "$C" 1200 today > /dev/null 2>&1
echo "$1 strokes $(grep -c '{"legacy"' wp_ab/${1}_today.log) refused $(grep -c 'refused:' wp_ab/${1}_today.all.log): $(grep -o 'refused: .\{0,60\}' wp_ab/${1}_today.all.log | sort | uniq -c | tr '\n' ';')"
