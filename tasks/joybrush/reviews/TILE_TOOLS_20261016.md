# TILE TOOLS — every non-media tool on an armed Tile board (2026-10-16)

Branch `helper1/tile-tools` (pushed), base `origin/joy-creator` 1198fb2f1. Author: helper 1 (Opus 5.5) for the JoyBrush Lead.
Not merged, not installed. **No phone run: everything below is JVM or SwiftShader evidence.**

## What the artist gets
| Tool on an armed Tile board | Before | Now |
|---|---|---|
| Stamp brushes (ink, pencil-stamp, marker, airbrush), eraser | wrapped | unchanged, confirmed (see §3) |
| Tuft (Sable, Bristle) | wrapped | unchanged, confirmed (see §3) |
| Smudge, Flat Paint | "Turn tiling off to use smudge" | picks up and lays down across the seam as if the tile repeats; one undo |
| Fill pen | "Turn tiling off to use fill" | the drawn shape fills the repeat: parts past an edge land on the far side; one undo |
| Eyedropper (long-press, pill drag) | "Turn tiling off to use the eyedropper" | samples what is on screen, which on a Tile board IS the repeat |
| Watercolour line | "Turn tiling off to use watercolor" | pools and textures seamlessly across the seam; one undo |
| Realistic pencil / watercolour / oil (media) | refused | **still refused** (Lead's lane, Media Atlas) |
| Hint when arming | "Smudge, fill and the eyedropper do not wrap yet." | "Realistic pencil, watercolour and oil do not wrap yet." |

There is no tap flood-fill reachable in the app (FloodFill/FillTrace have no main-code caller), so "fill" = the fill pen.

## 1. Design (the one rule: a Tile board's picture is its rectangle repeated, R50)
- **Core `PeriodicTiles`** (`core/brush/PeriodicTiles.kt`): `wrap` (Long-safe floor-mod), `inside(rect,key)`, and
  `compose(rect,key,reader)` = one 256² tile of the REPEATED picture, from the stored rectangle.
- **Smudge.** `SmudgeStroke(…, period)` wraps every sample point into the board, so the carried colour of a dab on the
  seam is exactly what it would pick up from a layer that physically held the repeat. The engine paints each dab's period
  copies (existing `TilePainting.dabs`), **each with its own dab's carried colour** (the old `IdentityHashMap` lookup
  returned index 0 for copies), clipped to the board. Flat Paint's texture pickup (`u_texturePickup` 0.68, reads up to
  `0.35·r` behind the pen) gets **periodic pickup cells**: every 3×3 pickup cell not wholly on the board is bound to a
  texture composed from pen-down read-backs. No smudge shader change.
- **Fill pen.** `SelectionMask.foldInto(rect)` (`core/select/SelectionMaskFold.kt`): every covered pixel is carried back
  into the board by whole periods; overlapping copies take the strongest coverage (union). Applied in `JbCanvasView`'s
  fill commit; `FillPenRaster.plan` and `replaceTiles` unchanged (one transaction, one undo).
- **Eyedropper.** It reads the displayed frame (`sampleScreen` → `glReadPixels`), and `drawDisplay` shows the repeat on
  a Tile board, so it already returns the wrapped pixel. Only the two refusals were removed.
- **Watercolour line.** Dab copies were already wrapped; the seam was in the **finish**: (a) pooling reads coverage up
  to `max(edgeWidth, gravityWidth)` px away and the bands outside the board were empty → copies are now generated over
  board⊕halo (`TilePainting.dabs(…, halo)`, halo = that reach + 2 px) and the bands are not scissored; outputs stay
  board-only and `endStroke` already clips the commit to the board. (b) the wash backdrop and the fine grain were sampled
  at raw document position (not periodic) → when armed they use `jb_tileTextureR`, the paper's own 4-corner smoothstep
  blend (now shared via `jb_tilePeriodicWeights` in `jb_tile_paper.glsl`), variance-kept around 0.5 like the paper.
- **Refusals / hint.** Removed in `JbCanvasView` for smudge, fill, eyedropper ×2, watercolour line; the engine's two
  `require` backstops for smudge/watercolour removed. Media refusal (`JCV` "pencil, watercolour and oil") untouched.

## 2. Hot-file hunks (for the Lead's integration)
**`GlPaintEngine.kt`** (+~60/-8):
1. import `PeriodicTiles` (l.32).
2. fields `strokeReads`, `periodicPickup` after `smudgeTexturePickup` (l.555-558).
3. `beginStrokeBuffer`: removed the two Tile `require`s; smudge reader → `strokeRead`, passes `strokeTileRect` (l.~3381-3404).
4. `addWatercolourDabs`: unarmed path rewritten equivalently (`pass.add(buckets){null}` — the old clip lambda was always
   null unarmed); armed path = halo copies, unclipped bands, board-only outputs (l.~3475-3496).
5. `addSmudgeDabs`: indexed copies, board filter + MAX_TILES check, `preparePeriodicPickup` before draw state,
   `clipTileStroke(key)` per tile, scissor disabled after the loop (l.~3589-3633).
6. `releaseStrokeTiles`: recycles pickup cells via `recycleLayerTex`, clears read-back cache (l.~4991).
7. new `strokeRead` / `preparePeriodicPickup`; `bindPickupTiles` uses a periodic cell when one exists (l.~5044-5071).
Unarmed paths: identical GL calls except one extra `glDisable(GL_SCISSOR_TEST)` after the smudge loop (smudge never
enabled it unarmed).

**`JbCanvasView.kt`** (+6/-6): import `foldInto` (l.38); eyedropper refusals removed (l.1122, 1175); smudge+fill
refusals removed (l.1372-1373); watercolour-line refusal removed (l.1405); fill commit folds the mask when a Tile board is
armed (l.1579-1582).

Other edits: `TilePainting.dabs(halo)`; `SmudgeStroke(period)`; `jb_tile_paper.glsl` (shared weights + `jb_tileTextureR`);
`lines/jb_wc_finish.frag` (gated wash/grain, Lead-approved); `BoardRuntimeController.kt` hint + its test string.

## 3. Evidence
| Check | Result |
|---|---|
| Core focused `:core:jvmTest` (PeriodicTilesTest 7, SelectionMaskFoldTest 5, SmudgeStroke*, SelectionMaskTest) | **37 tests / 4 suites, 0 fail/err/skip** |
| Core FULL `:core:jvmTest` | see §3b |
| `:androidkit:compileKotlin :androidkit:test` (full) | **879 tests / 89 suites, 0 fail/err/skip** (878 before + new halo test) |
| NEW `joybrush/tools/tile_wc_seam_check.js` (real shaders, WebGL2/SwiftShader) | **PASS, 8 gates** |
| — unarmed finish vs base shader, 3 tiles × 128², rich inputs | **0 mismatching bytes** (bit-identical) |
| — `jb_tilePaperRead` armed + unarmed, RGBA32F bits vs base | **0 mismatches** each |
| — armed finish periodicity F(p) vs F(p+period), 489,460 pairs (31,872 within 4 px of a seam) | **max byte error 0** |
| — control: same render unarmed | red (max 241) — test is not vacuous |
| — mutant: armed wash read unwrapped / armed grain read unwrapped | red (245 / 64) |
| Existing `watercolour_check.js` (shader gates; POC perceptual needs the local POC page, not run) | **37 checks, 0 failures** |
| Existing `contact_check.js` | fails "Live per-dab depth {light:0.729, firm:1}" — **identical failure on base 1198fb2f1** (pre-existing, not mine) |
| JVM oracles: smudge across seam == smudge on a layer that physically holds the repeat (60 dabs over every seam/corner, 2 carried colours) | bit-identical `FloatArray` |
| JVM oracles: `compose` == per-pixel wrapped read (4 awkward boards incl. 1×1, negative, non-aligned) | exact |
| JVM oracles: `foldInto` == brute-force max over period copies, every pixel of the board (triangle across a corner, shape larger than the tile, ellipse/rect across negative tile borders) | exact |
| Stamp/tuft wrap (no change): copies are pure translations, tip grain is dab-space, tuft noise is stamp-relative, paper periodic since be616a0a | by construction + existing `TilePaintingTest`, `TilePaperGrainSeamTest`, `tile_paper_gpu_check.js` |

## 4. Smudge read-back cost (the Lead asked)
- Plain **Smudge** (no texture pickup): reads exactly the tiles its sample points wrap onto — same order as an
  unarmed smudge (1–4 tiles at pen-down, more as it travels), shared cache now.
- **Flat Paint** (texture pickup) near a seam builds pickup cells: e.g. a 512² aligned board, pen-down 2 px inside the
  left edge → stroke tiles (0,0),(1,0) → **8 cells composed, 4 distinct read-backs (1 MiB glReadPixels) + 8 uploads
  (2 MiB) + 8 × 65,536-px CPU copies at pen-down.** Cells are made once per stroke and recycled at pen-up; worst case
  is one read-back per board tile (≤256 by TileLimits) for a stroke circling a big board.
  **This is more than "a few tiles" at pen-down — please measure on the Note 9** (first-dab latency of Flat Paint
  on a Tile board, pen-down within ~r of a seam). If it hitches, the cheap fix is to compose only the cells the
  `0.35·r` drag can reach (usually 1–3, not 8).

## 5. What only the phone can prove / known gaps
- Feel and cost of all four tools on a Tile board on the Note 9 (esp. §4).
- The watercolour wash/grain blend pivots at 0.5 (the paper uses its measured mean): the periodic wash keeps its contrast
  but its average may shift slightly mid-tile if the backdrop's mean is not ~0.5. Artistic look check owed.
- A board touched ONLY by the fill pen does not get `Board.tiled = true` (that flag is set by `endStroke`'s wrapped
  stroke; it only drives the unarmed icon/label "tile"). Cosmetic; one-line follow-up in `replaceTiles` if wanted.
- `joybrush-android` Robolectric test (`BoardRuntimeControllerTest`, string-only change) not run here — app build.
- Watercolour erase variant still falls to the stamp path (unchanged behaviour).
