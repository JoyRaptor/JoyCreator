# Media atlas, quadrant addressing (2026-10-20, the JoyBrush Lead)

The owner, 10-20: "we need everything working flawlessly on one layer". The atlas is the plan again; it was off (parked) for
speed. This is the speed fix and where it leaves the atlas.

## What changed (5b617a97d)

- `jb_media_atlas.glsl`: every JB_ATLAS_NBR read now uses the 2x2 tiles toward the fragment's corner of its tile, picked once
  per fragment (`jb_nbrInit`). A read is subtracts, compares and selects. Tested and dropped on the way (Note 9 timings in
  `reviews/MEDIA_ATLAS_FLUX_COST_20261020.md`): a per-read branch (4.6x), a page-table fetch per read (9x), integer
  multiplies (2.9x), an "is it here" test per read (3.3x).
- A ZERO slot (the atlas's last slot, never a tile's) replaces the per-read "is it here" test: a missing tile reads zeros.
- Walls (drained tiles, the D2 last resort) get their own build of the water's passes (`JB_ATLAS_WALLS`), drawn only
  while a wall exists.
- The Note 9's compiler rejects function-like macros (WebGL took them), so `jb_atlasBegin()` is a function.

## Proofs (files here)

| check | result |
|---|---|
| B1 identity atlas == window (SwiftShader) | 0 of 67.6M, broken table caught; window build unchanged vs base (0) |
| B2 free and scrambled placement == big-window oracle | 0, missing ring caught |
| B3 long strokes over 36 tiles, eviction and reload | 0, both controls caught |
| B3w drained walls (PC GPU) | 18/24 values one half-float step off in the long-trail scenario; the OLD shaders with the new harness give 18 too, so most of it is the harness change. OPEN |
| Note 9 device probe (Adreno 630, the app's own engine) | atlas == window 0, scrambled == window 0, broken caught |
| Suites | core 2514, androidkit 1057 |

## Speed on the Note 9

Per pass (Chrome on the Note 9, 10 scenarios): flux 3.5x -> 1.6x the window, update 1.3 -> 1.0, bead 1.4 -> 1.1, the look and
every dry, oil and paste pass at the window's cost. Whole water sim per frame: tilted wash 288 vs 229 ms, flat 121 vs 75.

In the app (matrix, canvas board, same hour, `app_matrix_canvas_atlas_*.log`, `app_atlas_phases.log`) the atlas is still
about 2x slower on watercolour and oil, and it REFUSES Wash and both palette knives ("20 areas of 18"). Where it goes:
- tile loads: 15-40 ms a frame, mostly re-baking the paper per tile (7-22 ms) and the page-table upload per load;
- admission (CPU, working out the frame's tiles): 12-23 ms a frame;
- the paste brush still reads through the 5x5 array per read (oil and knife steps 1.6x the window's);
- capacity: 4 x 5 slots on the Note 9 with a drawing open (20 % of 1.67 GB free), 18 usable: one knife frame needs 20;
- the first stroke after opening compiles 3 more programs (1.3 s once).

## Next, in order

1. The paste brush's quadrant (init at the canvas point the cell samples).
2. Tile loads: bake the paper once per tile and keep it (or bake lazily), one table upload per frame, not per tile.
3. Admission on the CPU: no per-frame sets of page objects.
4. Capacity: split a frame that needs more than the atlas holds in TIME (as the dry flick already does), so nothing is
   refused; raise the slot budget if the phone allows.
5. Then: the full 22-brush + 6 tilted list atlas on vs off same hour, the owner's seam test, then default ON with the
   window kept selectable.
6. After that: the Tile board (media is refused there today in both engines) and symmetry's realistic paint (E4).
