# Atlas A2 — verified source constraints, October12

Lead: Codex. A1 c7b630e3 provides tested residency metadata/fake callbacks. A2 still needs a concrete GPU driver and
parity proof; this note corrects assumptions before delegating implementation. No production rendering change yet.

## Read domain and frozen input

`MediaLayerEngine.dryFrame` accumulates all original ordered dabs using ONE/ONE ADD into RGBA16F delta; dry dab reads
pre-frame crush from `cur.paper`. Apply reads `cur.p0/p1/paper` and writes the next targets once, then copyRect updates
the current generation. Current source is the three-target path; do not assume an older paper-only optimization landed.
The original WHOLE-batch final travel (+16/+17 of the last dab) feeds u_smearPx for every output tile.

The apply shader reads displaced paper state and four axial1.5px taps through jb_bilinear. Halo size must be computed
from the actual material, pxPerMm, scale and terminal travel, including bilinear support. Both dab-contact crush and
apply smear must see the same frozen pre-frame paper generation across ALL slot draws. Copying one completed tile
back into that input before its neighbor applies would introduce scan-order dependence.

Canonical paper reads in dry dab are global jb_paperSurface/read/coarseHeight samples, using textureGrad and the existing
paper uniforms. Their derivativeScale values choose mips; they are not local media-state halo radii. Paper coordinates,
seed, filtering and derivatives must remain unchanged.

## Coordinate and publication gates

Dry/quad vertices use local layer pixels and u_targetSize for clip mapping; direct texture reads divide by that same
size. jb_bilinear uses pixel coordinates and textureSize. Look derives document coordinates from gl_FragCoord/pan/zoom,
then uses local layer pixels. A physical atlas slot offset is a distinct address: changing only viewport or origin is
insufficient. A driver needs a fully enumerated logical-to-physical sampler/viewport contract for every target.

No texture image may become both a sampled pass input and draw output. Halo generation and tile apply need separate
input/output images or valid explicit copies. All halos come from one frozen generation, and every output interior is
written exactly once. Untouched output interiors must retain current bytes before any global generation swap.

Preflight all output/read dependencies, COW backing reservations and actual texture bytes before painting. Include the
staging slot, halos, ping-pong targets, delta, look/ground, paper bake, grid padding and temporary gather/snapshot images.
Do not publish an optimistic slot count from interior area alone. Full-float active state cannot spill through half-float
stores between passes/frames. If dependencies cannot fit, refuse atomically rather than silently clip or round them.

## Bounded proof slice

Candidate owned paths: new androidkit/gl/media/MediaAtlasDryDriver.kt and supporting atlas address/pass code, new
shaders/media atlas variants, and tools/media_atlas_dry_parity_check.js. Preserve current window shaders/callers until
variants pass. Reuse original physical maths and already-built dab generation; no brush-feel or precision edits.

Compare stitched3x3 output-tile interiors against a larger single-window oracle with an outer ring at least as wide as
every source read. A768px oracle without a guard ring tests its own edge clamp, not full neighbors. Seed non-flat paper,
nonzero crush/pigment/ground, missing cold stores, negative tile origins, ordered overlapping dabs and reversing travel.
Cover multiple frames, tiny and wide dirty rectangles and original final-travel semantics. Establish allowed scale(s)
explicitly; unsupported scales must refuse. Compare state channels and RGBA8 look, clear all consumed delta, inspect GL
errors and allocation manifest. Bit/pixel thresholds remain strict; interpolation differences are failures to diagnose.

Broken controls: omit a halo, copy a completed tile into the frozen input early, use per-tile terminal travel, and leave
stale delta. Each must fail a sensitive fixture. A1 JVM success is not GPU proof. Moving water/Tile periodic flow remain
A3/A5; do not enable a live replacement until supported media, undo/save and device gates are complete.
