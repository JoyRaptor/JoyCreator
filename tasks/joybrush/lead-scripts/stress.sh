#!/bin/bash
for i in $(seq 1 ${1:-10}); do
  BRUSH="media:paste:Oil flat,media:paste:Oil round,media:paste:Fan blender,media:paste:Palette knife 1,media:paste:Palette knife 2,media:paste:Scraper,media:paste:All-round" TAG=${2:-st}${i}_ TIMEOUT=300 EXTRA="--es jb_matrix_boards canvas,sprite" bash water_profile.sh wp_stress matrix 300 today > /dev/null 2>&1
  n=$(cat wp_stress/${2:-st}${i}_today.log wp_stress/${2:-st}${i}_today.all.log | grep -c "duplicate after\|Process: com.fadcam\|JoyBrushDup")
  echo "run $i dup=$n $(date +%T)" >> stress.out
  if [ "$n" != "0" ]; then break; fi
done
echo STRESS_DONE >> stress.out
