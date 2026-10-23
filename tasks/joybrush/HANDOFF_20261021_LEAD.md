# JoyBrush Lead handoff, 2026-10-21

Read `HANDOFF_20261019_LEAD.md` for the rules and lanes, then `HANDOFF_20261020_LEAD.md`. This note supersedes the 10-20
note's "Atlas: stays parked": the owner, 10-20, "we need everything working flawlessly on one layer". The atlas is the plan
and is now at or near the window's speed on the Note 9 for most brushes, without the window's seam.

Branch `lead/atlas-quad` (worktree `C:/Temp/jb-integ-fix`). The app's Paint engine row (the ⋯ menu) still defaults to the
window; the atlas is selectable there.

## What made the atlas fast (all on the Note 9, Adreno 630; proofs below)

1. **Own-tile builds.** The quadrant address costs this GPU about as much as the shader's own work per read. Each pass that
   reads near its pixel gets a second build: `JB_ATLAS_OWN` (only own-tile reads) or `JB_ATLAS_NEAR` (near reads own-tile,
   far reads keep the quadrant address through `jb_bilinearLSFar`). `MediaAtlasDraw.split` (JVM-tested) draws each tile's
   inside with it and only the edge strips with the quadrant build. Paste dab (reach 9), copy (own only), wet update (4),
   bead (26), flux (NEAR, reach from the steer distance each frame), look (NEAR, 3; the impasto march is far).
2. **A scissor round every slot pass.** This tiled GPU loads and stores the whole target of an unscissored pass: an atlas pass
   cost in proportion to the ATLAS. Measured: the same build with 4 x 6 slots ran update and bead at half the cost per pixel of
   6 x 6. `MediaAtlasGl.drawRect` now scissors round what it draws (15-44 % of the atlas). Update and bead per pixel are now
   about the window's.
3. **Compact slots** (`MediaAtlasSlots(columns)`): room is made from one of the 4 least recently used pages, the one nearest
   what the frame holds, so a frame's pages gather and the scissor shrinks.
4. **Paste steps keep up** (`PasteSpacing`, both engines): a phone behind the pen spaces steps up to 2x (each sliding as far),
   but never in a turn (2x showed scallops at a round brush's turn; the turn rule removed them, screenshots sent to the owner).
   The old 0.3 mm hops are 3-7 % of these brushes' widths (Photoshop's default spacing is 25 %).
5. **Water work capped per frame** at the window's 4.2 M px-steps (the atlas has no window to stop a wide wash: one frame asked
   18.9 M). Fewer steps that frame instead; the water runs a little slower for a moment.
6. **Still water's look redone a few tiles a frame** (`LOOK_STILL_MS`, `LOOK_STILL_PER_FRAME` in MediaAtlas), moving water and
   its ring every frame. The look over every wet tile each frame was the largest pass on a wide wash.
7. **Atlas size**: what is free beyond 700 MB, 24-48 slots (`MediaAtlasPlacement.RESERVE_BYTES`); a tilted wash needs ~40.

Tried and dropped (measured): a scissor on the dab alone (no gain: the dab's cost was the address), the wet input as one
unscissored draw (70 ms a frame against 10), the drip "finger" test at half resolution (flux -21 %, the new pass cost twice
that).

## Proofs

- Device probe (`mediaAtlasProbe`, the app's own engine on the Note 9): atlas == window 0, scrambled == window 0 after every
  change above.
- Suites: core 2520, androidkit 1057 (app 317 last run 10-20; run before merging).
- PC harnesses (B1-B3, B3w): unchanged shader paths (the new builds are app-only and covered by the probe). B3w's 18 one-ulp
  values in the long-trail scenario are still OPEN (harness-caused: old shaders + new harness give the same 18).

## Speed (bench `ab3.sh`, window vs atlas interleaved twice, p50 ms; the phone swings ~2x run to run, compare same-run pairs)

Run `r15_` (commit 50e93985e), 2026-10-21 02:15, Note 9:

| brush | window | atlas |
|---|---|---|
| Round (watercolour) | 185 | **135** |
| Wash | 171 | 173 |
| Oil round | 34 | 45 |
| Oil flat | 33 | 36 |
| All-round (oil) | 33 | **24** |
| Palette knife 1 / 2 | 64 / 85 | 64 / 82 |
| Fan blender | 82 | **67** |
| HB, Proto Pencil | 16, 16 | 16, 16 |
| Round, tilted | 250 | **172** |
| Wash, tilted | 235 | **202** |
| All-round (watercolour), tilted | 216 | **143** |
| Oil round, tilted | 32 | 49 |

The atlas's p95 on watercolour is still higher (488-654 against 283-402) and it draws fewer frames of a tilted wash.
This morning (r7, before any of the above): Oil flat 31 / 110, Knife 1 83 / 195, tilted Round 209 / 271.

## Open

- Watercolour p95 spikes (above). Wash was bimodal (180-200 or 460-500 ms) before items 5 and 6; r15 had it level.
- Oil round 10-15 ms behind the window (flat and tilted).
- Water refusals on runs where the phone reports < 1.3 GB free (5 x 6 or 6 x 6 atlas): a tilted wash needs ~40 slots.
  Ideas: water planes only for wet slots (a smaller water atlas), half-float paint state.
- Flux is still ~2.5x the window's per pixel on tilted paper: its far drip taps (36 reads a pixel) keep the quadrant address.
  Next idea with numbers behind it: Qualcomm's `GL_QCOM_tiled_rendering` (present on the Note 9) to name each slot's area
  exactly (needs a small JNI shim), or fewer far taps.
- Default ON: after the owner's seam test and a same-hour 22-brush + 6 tilted run.
- Symmetry fixes owed by MiniMax (list in the 10-20 note); media on the Tile board (refused in both engines).

## Tools (C:/Temp/lead-1020, scratch; copy what you need into lead-scripts)

`ab3.sh <tag>` (full interleaved A/B), `abs3.js` (summary), `ph.js` / `phx.js` (phases per brush, mean or max), `perstep.js`
(paste ms per step), `abt.sh` (tilted wet, atlas only), `vis.sh` (first-brush screenshots at a fixed paste spacing:
bench extra `jb_paste_spacing`). Bench phases include `wet.kpx (count)` (simulated px x steps) and the atlas logs
`scissor:` shares and still-water counts every second.
