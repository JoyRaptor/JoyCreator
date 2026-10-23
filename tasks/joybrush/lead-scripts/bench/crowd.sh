#!/bin/bash
# crowd.sh <tag> [modes] : six wet strokes on ONE drawing (no clear between), the owner's case; window/atlas, flat and tilted
cd /c/Temp/lead-1020
B="media:wet:Round,media:wet:Wash,media:wet:All-round,media:wet:Round,media:wet:Wash,media:wet:All-round"
for m in ${2:-false true}; do
  BRUSH="$B" TAG=${1}flat_$m TIMEOUT=900 EXTRA="--ez jb_media_atlas $m --ez jb_bench_clear false" bash water_profile.sh wp_ab topScribble 300 today > /dev/null 2>&1
  BRUSH="$B" TAG=${1}tilt_$m TIMEOUT=900 EXTRA="--ez jb_media_atlas $m --ez jb_bench_clear false --es jb_slope 0,0.5" bash water_profile.sh wp_ab topScribble 300 today > /dev/null 2>&1
done
echo CROWD_DONE
