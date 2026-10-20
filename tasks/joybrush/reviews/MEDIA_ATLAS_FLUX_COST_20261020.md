# Media Atlas: why the water pass is slow, and what it costs to fix (2026-10-20, the JoyBrush Lead)

Question left open on 10-19: the atlas's tilted water ran 3-5x the window's on the Note 9, and four attempts (shared reads,
precomputed offsets, no-wall builds, unrolling) did not move it. "Finding it needs the Adreno profiler or a bisection of
the flux body."

## Method

The parity harness already times every layer-shaped pass in the Note 9's own Chrome (`media_atlas_parity_check.js --time
--cdp`, Adreno 630): window build vs atlas build, same scenarios, GPU finished before and after each pass. A shader edit
and a run take about a minute, no APK. For the bisection I added `JB_X` (comma list of defines added to every atlas pass)
and `JB_X_*` variants of `jb_atS` (the per-read address function) in `jb_media_atlas.glsl`. Branch `lead/atlas-exp`
(e72ea559e): timing only, the variants return wrong data on purpose, none of it is for main.

## Result: it is the per-read address arithmetic, in the flux pass only

Whole-run GPU ms per pass, window vs atlas (1024 px, the harness's 10 scenarios), one run each:

| build of the address function | flux | update | bead |
|---|---|---|---|
| shipped (3x3 neighbour array, clamp, ok, wall select) | 491 -> 1812 (**3.7x**) | 1.26x | 1.42x |
| every read lands in the draw's own slot (no array, no walls: the floor) | 1.18x | 0.9x | 1.15x |
| a branch for "own tile" on each read | 4.6x | 4.5x | 1.4x |
| page table fetched per read, branch free | 9.1x | 2.5x | 1.5x |
| constant array index, rest of the arithmetic kept | 2.58x | 1.30x | 1.34x |
| dynamic array index only (no clamp, no ok, no wall) | 2.56x | 1.31x | 1.27x |
| toroidal layout: modulo address + two bit masks for residency and walls | 2.00x | 1.29x | 1.08x |

- The slowness is not the draw structure (one draw per tile) and not the atlas memory layout: with the address faked
  to a constant offset the flux pass is within noise of the window (the window itself moved 490-650 ms between runs).
- Flux is the outlier because the tilted path reads the bead field about 56 times a fragment (14 bilinear reads) plus 25
  more; every read pays the address arithmetic. Every other pass reads a handful of texels.
- Branches and dependent texture fetches are far worse on this GPU than arithmetic (4.6x and 9x). So "skip the lookup when
  the read stays in the tile" cannot be done per read, and a table texture is the wrong tool.
- The two costs add up: the dynamic index into the 3x3 array is about 0.6 s of the excess, the clamp / ok / wall arithmetic
  about 0.6 s, the floor 0.8 s.

## What that means for the atlas

- Per-read addressing cannot reach the window's speed. The best arithmetic form I found (a toroidal layout) is still 2.0x
  on flux, and a toroidal atlas is not sparse (its texture must cover the whole modulo range), so it is not an atlas.
- A cheaper exact form exists on paper (per-draw offsets, a zero slot for non-resident neighbours instead of an ok flag,
  walls behind a UNIFORM branch, one address per bilinear footprint): expect flux about 2x, sim about 1.5x flat and 2x
  tilted, look 1.5x. That is a real project (shader + MediaAtlasGl.neighbours + harness + all the parity checks) and ends
  at "slower than the window on the same pixels, faster only where the water is sparse".
- Memory does not favour the atlas for the owner's seam case: a wash that wets most of the screen needs about 40 tiles of
  wet state at 14.6 MB each (about 580 MB) in an atlas too. The atlas wins on long thin or scattered strokes, not on a
  screen-wide wash.

Recommendation: leave the atlas parked. Do the seams in the window engine instead (see the handoff).
