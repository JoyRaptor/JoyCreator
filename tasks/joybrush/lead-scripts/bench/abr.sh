#!/bin/bash
# abr.sh <tag> : ab3.sh's brushes on the atlas, scattered passes as one (rows 0) vs by row (rows 2), interleaved twice.
cd /c/Temp/lead-1020
WARM="media:dry:HB,media:wet:Round,media:paste:Oil round"
B="media:wet:Round,media:wet:Wash,media:paste:Oil flat,media:paste:All-round,media:paste:Palette knife 1,media:paste:Palette knife 2,media:paste:Fan blender,media:dry:HB,media:dry:Proto Pencil"
T="media:wet:Round,media:wet:Wash,media:wet:All-round"
for rep in 1 2; do for r in 0 2; do
  BRUSH="$WARM,$B" TAG=${1}flat${rep}_$r TIMEOUT=900 EXTRA="--ez jb_media_atlas true --ei jb_atlas_rows $r" bash water_profile.sh wp_ab topScribble 1500 today > /dev/null 2>&1
  BRUSH="$WARM,$T" TAG=${1}tilt${rep}_$r TIMEOUT=900 EXTRA="--ez jb_media_atlas true --ei jb_atlas_rows $r --es jb_slope 0,0.5" bash water_profile.sh wp_ab topScribble 1500 today > /dev/null 2>&1
done; done
echo ABR_DONE
