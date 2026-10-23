# Water jobs: the brush at the screen's pace, the water as fast as the phone allows (2026-10-21)

## Why
Six wet strokes on one drawing (the owner's case, `crowd.sh`) leave ~3.6 M px of water. One water step over that costs the
Note 9 ~150-250 ms (flux + update + bead, full resolution), the look over it ~80-130 ms. A frame does at least one step and
the whole look, so the screen updates 2-3 times a second and a new stroke shows up late, "all at once". No per-pixel saving
closes a 10x gap; the water has to stop holding the screen hostage.

Measured on the way (crowd, flat, same build): coarse water (2 x 2 cells) did not help enough and looked worse (owner);
scattered atlas slots made every pass load and store up to 2x the area it drew (fixed: passes by atlas row, -25-35 %).

## The rule
- **The brush mark shows the frame it is drawn.** Its dabs go into the brush input as today; the LOOK adds whatever input is
  not yet simulated (water depth and pigment), so the mark is on screen before the water has run.
- **The water runs in jobs.** A job = N water steps (2, even: no paint copy) over a rectangle fixed when it starts, cut into
  items (each pass over a band of tile rows). Each frame runs items until the frame's budget is spent; the rest wait for the
  next frame. Nothing outside the job reads a half-made step: every pass writes the other ping-pong half, and the look reads
  the last finished step.
- **Input never waits and is never lost.** Two input buffers: the job eats "current"; new dabs go to "next"; a new job swaps
  them. The look adds both (exactly what is not yet in the water).
- **When a job ends** its whole rectangle's look is redrawn (also in items, into the look texture) and copied out to the
  layer's tiles all at once (no tile showing a different moment from its neighbour for more than the frames that copy takes).
- **The budget follows the clock**: a target frame time (~33 ms); after each frame the item budget (px x pass weight) grows
  or shrinks by how long the frame really took. The water's wet age already counts real time (`u_ageScale`).
- **Everything else finishes the job first**: paste, pencil, eraser, undo/restore, stores written, the atlas letting a job
  tile go, sleep. One synchronous finish, as today's frame.
- **Unbudgeted = today**: the window engine (and the device probe) run each job whole inside its frame: the same passes, the
  same order, bit for bit (probe: atlas == window, 0 px).

## Steps
1. Engine: job object (rect, constants, steps, cursor), two input buffers, `finishWaterJob()` guards, look overlay of the
   input, budget = infinite by default. Probe 0 and suites green.
2. Atlas: job tiles pinned while the job lives; flush writes the dab look every frame and the job look at its end.
3. Canvas: finite budget in the atlas, the clock-driven budget, the look items at job end.
4. Bench: crowd.sh frame times and pen-to-screen latency (atlas, jobs on/off), screenshots of the same strokes.

## Results (Note 9, 2026-10-21, crowd bench: six wet strokes on one drawing, atlas)
| | jobs off | jobs on (first build) |
|---|---|---|
| frame p50 | 362-584 ms | 29-59 ms |
| pen-to-frame p50 / p95 | 176-323 / 353-624 ms | 26-99 / 164-742 ms |
| frames a stroke | 7-12 | 31-74 |
Device probe: atlas, scrambled and banded (the water in the smallest slices, each finished) == window, 0 px.
First build's spikes (up to 1.9 s) were the stroke's end waiting for the water to take in every dab; replaced by stores
from the last finished step, the waiting dabs written in place once the water has them (or before the next stroke).

Final build (same day): the frame's own look drawn round the new dabs only (no halo, no water margin: 200-450 k px -> 50-70 k),
all of a frame's look pieces in one call, the water's look in pieces within the budget and copied out together.
Crowd (six strokes, atlas): pen-to-frame p50 30-48 ms, p95 80-190 ms; a new stroke's first frame 3-89 ms; 39-64 frames a
stroke; worst frame 0.4 s. Every brush (ab3 list, atlas, jobs off / on, p50 / p95 ms): Round 194/406 -> 45/65, Wash 260/443
-> 69/110, tilted Round 102/567 -> 47/95, tilted Wash 480/496 -> 83/148, tilted All-round 447/482 -> 48/88; oil, knives,
fan and pencils unchanged. Probe: atlas, scrambled, banded == window, 0 px.
Undo or redo within a moment of lifting the pen first writes the stroke's waiting dabs (d0dd9be20, MediaCanvas.settleOwed).
