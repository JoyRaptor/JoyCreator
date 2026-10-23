#!/bin/bash
# abj.sh <tag> : ab3.sh's brushes on the atlas, water jobs off (false) vs on (true), once each.
cd /c/Temp/lead-1020
WARM="media:dry:HB,media:wet:Round,media:paste:Oil round"
B="media:wet:Round,media:wet:Wash,media:paste:Oil flat,media:paste:All-round,media:paste:Palette knife 1,media:paste:Palette knife 2,media:paste:Fan blender,media:dry:HB,media:dry:Proto Pencil"
T="media:wet:Round,media:wet:Wash,media:wet:All-round"
for rep in 1; do for j in false true; do
  BRUSH="$WARM,$B" TAG=${1}flat${rep}_$j TIMEOUT=900 EXTRA="--ez jb_media_atlas true --ez jb_water_jobs $j" bash water_profile.sh wp_ab topScribble 1500 today > /dev/null 2>&1
  BRUSH="$WARM,$T" TAG=${1}tilt${rep}_$j TIMEOUT=900 EXTRA="--ez jb_media_atlas true --ez jb_water_jobs $j --es jb_slope 0,0.5" bash water_profile.sh wp_ab topScribble 1500 today > /dev/null 2>&1
done; done
echo ABJ_DONE
