# The owner's items 2 to 8: Note 9 and PC lab receipts (2026-11-13)

Debug bench runs on the Note 9 (`JoyBrushBenchActivity`, the media atlas and the wet sheet on, the water profiler off) and
sheets drawn by the PC media lab (`joybrush/tools/media_render.js`). Every phone picture shows the app's own canvas only.
Cards: `../../../jobs/ATLAS-SOLID_never_refuse.md` (the 11-13 table), `../../../jobs/OIL-KNIFE_real_knife_and_glop.md`;
design: `../../specs/WET_SHEET_20261110.md` rules 6, 10 and 17. These are an agent's checks, not the owner's acceptance.

Builds: the first phone pass ran on `2ac9e5f7f`, the follow-up on `e446c56d8` (the same code with the Fan blender's pull
taken off); both had passed 4,866 unit tests (core, androidkit, joybrush-android), none failing.

| Picture | What it shows | Numbers from the same run's log |
|---|---|---|
| `oil_pull_round_crossing_lab.jpg` | PC lab, Oil round crossing a wet blue stroke: left lying (before), colour pulled along at 0.7 (shipped) and at 1.0 | the hard line where the stroke enters the blue is gone; the blue keeps its ridges |
| `oil_pull_white_through_stripes_lab.jpg` | PC lab, Oil round, white dragged through four wet stripes: before and shipped | each stripe trails into the next along the travel |
| `oil_pull_flat_crossing_lab.jpg` | PC lab, Oil flat, the same crossing: before and shipped | |
| `oil_pull_each_brush_note9.jpg` | Note 9, each oil hair brush alone as shipped: a band, two strokes across it, a white stroke through them. Oil flat, Oil round and All-round pull; the Fan blender does not | off against on, ms a step: Oil flat 3.50 and 3.50, Oil round 3.15 and 3.33, All-round 3.12 and 2.99; no failed frame |
| `fan_beads_before_fix_note9.jpg` | Note 9, why the fan does not pull: four brushes over each other, the fan last; with the pull on (below) its fast white stroke left a row of bead-like patches and dotted lines | in a burst a hop stretches to 12 % of the brush's width: 1.7 mm for a fan with 2.5 mm of hair |
| `parted_wash_film.jpg` | Note 9: three wide washes at 31 %, then zoom to 100 % and a stroke down through the top one, 12 s of running, zoom back out | "the wash is parted: 86 tiles of what the new view leaves behind are printed"; "sheet parted and carried ... holds the part in view, still wet" |
| `parted_wash_against_set_whole_at_31pct.jpg` | The last frame of that run (above) against the same strokes with the older rule, the whole wash setting (below); each as shown and with the contrast stretched | mean brightness along each band, 40 px at a time, falls evenly across the join in both; the two differ by under 1 level past the join in the lowest band, by the grain's own run-to-run difference in the others |
| `parted_wash_undo_redo_note9.jpg` | The parted wash; after undo, undo (the stroke after the parting, then the parting together with the wash stroke under it: two whole bands are left); after redoing both | each press one step; no failed frame |
| `symmetry_oil_pencil_watercolour_x6.jpg` | Six-way symmetry in the atlas: Oil round, 6B pencil, and watercolour on the wet sheet | nothing refused or pinned |

Not pictures, from the same passes:

- **Animation frames** (bench matrix on an animation board): watercolour Round 161047 px in the frame's own cel, Oil round
  98839, 6B pencil 28741; none on the shared plane inside the board; one undo step each, undone exactly; verdict "works".
- **Water speed**, profiler off, fine grid (720 x 1376 cells): three Round strokes 39.6 frames a second at 0.90 of the
  clock, three Wash strokes 35.2 at 0.80; the morning's runs 39.2 at 0.94, 32.5 at 0.76 and the nine-band page at 31 %
  30.6 at 0.85.
- **First wash after a start**: "sheet made: 720 x 1376 cells in 66 ms" (67, 68 on other starts); about 1 s on the very
  first start after an install, while the phone builds the programs once.
- **Graphics memory taken away** (2 GB and 3 GB of noise held in textures, then five oil strokes and a save): all of it
  was given, the strokes painted, "snapshot of 31 tiles; tile incidents this run: 0". The 10-31 lost squares were not
  reproduced.
- **Every realistic-paint brush** (23, two strokes each): no stroke refused, no failed frame, no line in
  `files/joybrush-incidents.log`.
