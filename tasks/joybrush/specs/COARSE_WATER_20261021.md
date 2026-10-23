# Coarse water, fine paint (2026-10-21, JoyBrush Lead)

Why: one water step costs the Note 9 about 0.056 ms per thousand pixels at best (the window engine; the atlas 0.06-0.11).
A frame must stay under ~33 ms, so at full resolution only about a 350 x 350 px pool can take one step a frame. A wash is
bigger than that. Nothing short of fewer water cells makes watercolour feel immediate (owner, 10-21: "water is the best
brush, we need water to be a wonderful experience"). Codex's review reached the same place (its item 5).

## The model

- **Water lives on a coarse grid**, F = 2 first (1/4 of the cells), F = 4 later (zoomed out): depth w, paper wetness s,
  wet age, outflow (W0); pigment carried in the water (W1); the levelling flux; the running water; the brush input.
  All values are per-pixel averages over the F x F pixels a cell covers, so the shaders keep their units.
- **Paint stays fine**: deposited pigment and body (P0, P1), the paper bakes, the look and the ground, at full resolution.
- **Coupling is exact by construction.** One water step is two passes:
  1. *coarse update* (water): move, mingle, input, dry, soak, wick, as today; then for settle and lift it reads its F x F
     children's paint and paper and computes each child's deposit d_i and lift l_i with the SAME function the fine pass uses,
     and changes the water by their mean;
  2. *fine paint* (full resolution): each pixel recomputes its own d_i and l_i from its parent cell's water and its own
     paint and paper, and applies them. Settle and lift act on the water as it stands MID-step (after move, input, dry and
     soak), so the coarse pass also writes those mid-step values (pigment in the water; depth, edge factor, dry-out) as two
     more targets, and the fine pass reads its parent's (nearest, not interpolated: conservation).
  Same inputs, same code, so the pigment that leaves the water is the pigment that lands on the paper, to the bit.
- Granulation stays fine (k_i uses the pixel's own paper height). The dark edge forms where the coarse water thins, 2 px
  wide at F = 2 (0.1 mm on the Note 9 at 100 %).
- The look reads W0/W1 bilinearly at the coarse resolution (wet sheen, floating pigment).
- Flux, the bead and the running water run at the coarse resolution; the bead field at F = 2 is the existing half-
  resolution field, so the bead's 2x2 average becomes a copy (or goes).

## In the engines

- A MediaLayerEngine mode (`coarseWater`), so the window and the atlas both have it and the device probe still compares
  the two engines bit for bit; the old full-resolution water stays selectable (owner rule: keep the old behaviour).
- Atlas: the water fields are half-resolution slots (shift 7), like the bead today; slot bytes drop by about 80 B/px, so
  the same memory holds about 1.6x the slots (fewer refusals, no D2 pressure).
- Water stores at rest become 128² tiles (a new store format; files with full-resolution water drop that water on load,
  logged: water lasts seconds).

## Checks

- Conservation: total pigment (water + paper) and total water + soaked + evaporated, before and after N steps, JVM-free in
  the PC harness (SwiftShader) and on the device probe.
- Visual: the owner's S-zigzag and sweep replayed, F = 1 vs F = 2 side by side, flat and tilted, at 100 % and zoomed in.
- Speed: crowd.sh (six wet strokes on one drawing), latency p50/p95 and frame p95.

## Result on the Note 9 (2026-10-21, same day)
- Built whole (water on 2 x 2 cells, paint/paper/look full resolution); device probe atlas == window, scrambled == window, 0 px,
  with coarse water on and off.
- Crowd bench (six wet strokes on one drawing, atlas): the water's flux fell from 150-214 ms to 23-40 ms a frame and the bead
  pass went, but the update stayed (it does four children's settle per cell) and the paint pass added 25-55 ms. Frames ~15-25 %
  faster at first; once the look was drawn once per tile (below), coarse vs full was within run-to-run noise.
- The owner compared bench shots: the full-resolution water "looks much better" (the fine beading where water pools in the
  paper's tooth; coarse averages the tooth under each cell and pools come out as soft blobs).
- Decision: OFF by default (`MediaLayerEngine.coarseWaterDefault = false`), kept whole behind the bench extra
  `jb_coarse_water` for a later water-detail choice (e.g. zoomed out, where a cell is under a screen pixel).
- Found on the way: granulation never reached the shaders in the app (u_gran was always 0; the lab passed it, the port did
  not). Now `MediaLayerEngine.wetGran` = the newest wet brush's `gran`.
- Found on the way: the atlas look drew each wet tile's water extent plus a 53 px halo separately, so the same pixels were drawn
  up to four times a frame; now drawn once per tile (`MediaAtlas.writeLookOnce`).
