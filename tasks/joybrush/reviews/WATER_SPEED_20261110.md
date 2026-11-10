# Watercolour speed on the Note 9: what limits it, measured 2026-11-10

The owner (2026-11-10): watercolour feels "like painting over dial-up". He wants water roughly 20x faster, so he can see it
run and steer it live by tilting the phone. Faster than real life is fine.

Build: `claude/atlas-solid-20261109`, the Note 9. Evidence in `../evidence/water-speed-20261110/`. New measuring tools:
a once-a-second `JoyBrushWater: speed:` line (simulated seconds per real second), a per-pass profile behind
`adb shell setprop log.tag.JoyBrushWaterProf DEBUG`, the bench extra `jb_water_budget_k` (fixed water work per frame), and
every bench phase on its own log line.

## What was measured

| Case (tilted 0.35 unless said) | Water speed vs real time | Water steps a second | Frames a second |
|---|---|---|---|
| One stroke, Round | 0.09x | 11 | 30 |
| One stroke, Wash | 0.07x | 9 | 31 |
| Two wash bands, flat | 0.09x | 11 | 32 |
| Four wash bands (most of the page) | 0.03x | 4 | 25 |
| Four wash bands, Fast water (2 x 2 cells) | 0.064x | 8 | 32 |
| Four wash bands, water work per frame forced 1x / 3x / 8x | 0.033x / 0.033x / 0.036x | 4 / 4 / 5 | 24 / 11 / 5 |

**The phone's graphics chip is the limit, not the scheduling.** Giving the water more work per frame only makes frames
slower: the same 4 to 5 steps a second come out.

Per-pixel cost of one water step (profile, ns per pixel): flat 80 (update 52, flow 28); tilted 166 (flow 77, update 57,
bead 32). Tilting doubles it: the drip logic in `jb_wet_flux.frag` reads the bead field about 50 times per pixel.

## Why 20x cannot come from tuning

- Running water moves at most 0.45 cells per step (the transport's stability limit, `vMax` in `jb_wet_flux.frag`), so how
  fast a drip travels on screen is steps per second x 0.45 cells. At 11 steps a second that is about 5 px a second.
- A longer time step is unstable. `jb_wet_substeps 2` (dt 1/60) and `1` (dt 1/30) both break into coloured speckle within
  seconds (`longer-time-step-goes-unstable.png`). The pigment mingling term (`u_mingle * dt` summed over 4 neighbours) is the
  likely first term past its limit. dt 1/120 stays.
- So the only levers are fewer cells (coarser water) or cheaper steps.

## What shipped from this

- **Water detail switch** in the ⋯ menu: Detailed (default, as before) or Fast. Fast is the existing coarse-water mode: water
  on 2 x 2 pixel cells, paint, paper and look at full resolution. Measured 2x the steps a second, and each step carries
  water twice as far, so drips run about 4x as fast. All 7 watercolour brushes ran clean in it, tilted (21 runs). Switching
  while water runs is safe (stores keep one format). On 10-21 the owner preferred Detailed's fine beading; this lets him
  compare live with tilt.

## The road to about 20x (not built)

1. Make the flow pass's tilt path cheap: compute the bead slope once in the bead pass (one read instead of 16) and bake
   the sizing noise into the paper bake. Expected 1.5x when tilted; nearly the same picture (needs the owner's look).
2. Fast water at 4 x 4 cells while the paper is tilted and water runs: about 4x the steps and 4x the distance per step
   again. Paint stays full resolution.
3. Run only moving water every step: settled pools step less often. Needs care: an edge between running and paused water
   showed as a crease on 10-21.
4. Stable mingling at a longer time step (an implicit or clamped form) would let dt grow; the other terms then need checking
   one by one.

Taken together (1 + 2 + 3) running water could reach roughly 15-30x today's on-screen speed, at a cost in the finest
water detail while it runs. That trade is the owner's call.
