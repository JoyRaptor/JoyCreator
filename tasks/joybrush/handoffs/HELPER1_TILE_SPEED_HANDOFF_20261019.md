# Helper 1 handoff: tile-board speed (2026-10-19, 11:10)

Written by "sonnet helper 1" (Opus). I stopped because the owner's weekly usage limit was at 98%.
Branch `helper1/media-atlas-b1`, worktree `C:/Temp/jb-helper1-atlas`.
The JoyBrush Lead directs this lane; send them every checkpoint.

## What landed

**`d4b3cd917`: tile board, one paint pass per frame instead of one per pen event.** It is built and passes all
suites (the Lead ran androidkit 1052, app 317, core). The Lead's integration branch `ec0a50e94` has it. **It has not
been phone-tested yet.**

- **Cause** (Note 9 profile): each pen event redrew every 256-px tile it touched. Near the wrap edge that is about
  twice the tiles, so a slow frame let more events pile up for the next frame, and tile frames snowballed to 500 ms.
- **Fix:** a tile stroke's dabs (stamp brushes) and tuft stamps (Sable, Bristle) now wait in
  `TileStrokeBatch.kt`, and `GlPaintEngine.paintWaitingTileStroke()` paints them in one pass.
- **When the waiting paint is flushed:** before every draw, read, end, undo/redo, or layer/board change. When a stroke
  is cancelled, `releaseStrokeTiles` drops it instead.
- **What stays per event:** tile creation and the size refusals.
- **Not touched:** Flat Paint (smudge) and the legacy watercolour.
- **Pixel-exact by construction:** each tile gets the same dabs, in the same order, with the same uniforms.
  - A run is painted early whenever the pen lean changes, because the lean is the one per-batch uniform taken from a
    dab (`StampPass.isLive`).
  - `TileStrokeBatchTest` proves batched draws equal per-event draws, tile by tile, for a stroke that crosses the
    wrap edge.

`73c691498` (bench phase timing) is on main.

## Done on the phone (main build = the timing build, same code as main 1e33f1188)

- Same-hour MAIN baseline: the matrix with all 33 brushes on all four boards, 132 cells, run 10:39–10:59.
  - Log: the scratchpad's `wp_tile4/main_today.log`.
  - All media brushes refuse on tile ("Turn tiling off…"), which is expected.
- Main tile frame times, p50 ms / frames:

  | Brush | Main tile |
  |---|---|
  | Ink | 15.7 / 36 |
  | Sable | 21.5 / 27 |
  | Pencil | 78 / 4 |
  | Bristle | 67 / 8 |
  | Marker | 53 / 6 |
  | Soft air | 115 / 5 |
  | Eraser | 15.5 / 39 |
  | Flat Paint | 134 / 2 |
  | Watercolour | 63 / 4 |

- Scratchpad: `C:\Users\JOYRAP~1\AppData\Local\Temp\claude\C---Projects-Screenrecorder-FadCam\082ae856-f41d-48db-a226-534c168c87b2\scratchpad`.
  The scratchpad is session-local, so copy what you need.
- Command used (Git Bash, under `jb-phone.lock`, handled by the script):

  ```bash
  BRUSH=all TAG=main_ TIMEOUT=2400 EXTRA="--es jb_matrix_boards canvas,animation,tile,sprite" bash water_profile.sh wp_tile4 matrix 300 today
  ```

## What is left (in order)

1. **Patch run.** Once the Lead has `install -r`'d d4b3cd917, run the same command with `TAG=patch_`.
   - Compare per cell with main.
   - Canvas, animation and sprite must be unchanged within noise. Tile must improve.
   - `"verdict"`, `changedPx`, `wrapPx` and `leakPx` must match main exactly on every tile cell.
   - Note: this build times phases with a glFinish around every paint call while the bench records, and that inflates
     both builds. The comparison is still fair because both builds pay it, but app frame times are lower.
2. **Seam pixel check: NOT done.** Two methods failed:
   - The bench closes to the home screen when it finishes, so screenshots taken afterwards show the launcher.
   - The matrix undoes its stroke about 0.5 s after pen-up. A raw capture loop (`seam_seq.sh` / `seam_final.py`)
     picked different "finished" frames run to run, so main vs main didn't match.
   - **Clean fix (needs a build):** in `MatrixBench.finish()`, log a hash of the after-state plane per cell
     (`stateAfter` is already read back; add e.g. `"afterHash"`). Then main and patch hashes on the tile cells are the
     pixel-exact proof, seams included. This needs one main build plus the patch build, same hour.
3. Report to the Lead: the frame table, the hashes, and whether it is ready for main.
4. Only after measuring:
   - step 2: trim wrap copies (count them first);
   - step 3: Flat Paint and legacy watercolour on tile, with their own plan.

## Also done today

I reviewed SYMMETRY_20261019 E4 (realistic paint with symmetry). The Lead folded all nine points into it (main
1e33f1188).
