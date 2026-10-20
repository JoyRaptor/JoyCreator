#!/bin/bash
# rep.sh <label> <n> : n repeated tile-board runs of five stamp brushes
for i in $(seq 1 $2); do
  BRUSH="joybrush.pencil,joybrush.marker,joybrush.softair,joybrush.bristle,joybrush.ink,joybrush.sable" TAG=$1${i}_ TIMEOUT=200 EXTRA="--es jb_matrix_boards tile" bash water_profile.sh wp_rep matrix 300 today 2>&1 | tail -1
done
