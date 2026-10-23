#!/bin/bash
# crowdc.sh <tag> <atlas true|false> <coarse true|false> : crowd.sh's six wet strokes on one drawing, flat and tilted, with coarse water on or off
cd /c/Temp/lead-1020
B="media:wet:Round,media:wet:Wash,media:wet:All-round,media:wet:Round,media:wet:Wash,media:wet:All-round"
m=$2; c=$3
BRUSH="$B" TAG=${1}flat_$m TIMEOUT=900 EXTRA="--ez jb_media_atlas $m --ez jb_coarse_water $c $XTRA --ez jb_bench_clear false" bash water_profile.sh wp_ab topScribble 300 today > /dev/null 2>&1
BRUSH="$B" TAG=${1}tilt_$m TIMEOUT=900 EXTRA="--ez jb_media_atlas $m --ez jb_coarse_water $c $XTRA --ez jb_bench_clear false --es jb_slope 0,0.5" bash water_profile.sh wp_ab topScribble 300 today > /dev/null 2>&1
echo CROWDC_DONE $1 $m $c
