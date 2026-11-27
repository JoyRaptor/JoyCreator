# Explore: swipe the lobby sideways through its rooms with a shot each (to find where JOY BRUSH sits).
. "$HERE_LIB/lib_app.sh"
through_intro
settle 3
shot lobby
for i in 1 2 3 4 5 6 7 8; do
    swipe 950 700 120 700 350
    settle 2
    shot "room_$i"
done
