# Bounded POC pooling restore proposal

Requested by the JoyBrush Lead after frontier review of the original and GPU
images. **Proposal only: do not change the default shaders/driver until approved.**
Existing source c8c01ece remains available for comparison. Original POC files,
settings, PNGs and failing <3 appearance gate remain unchanged.

## Contract

Keep full-resolution dab placement, deterministic seed, approved controls,
real canonical document paper, wash anchoring and one-second cached fade.
Restore the POC's half-resolution pooling, byte-quantized optical tables and
two overlay compositions as the default. The current crisp finish remains a
named diagnostic, not an accepted default. No diffusion/particles, CPU readback,
blur filter, global pen-tilt flip, or new product controls.

## Frozen stroke grid (important)

The POC does not use a global 2-pixel pooling lattice. It crops the assembled
stroke, then downsamples that rectangle to ceil(width/2) by ceil(height/2).
Odd rectangle dimensions slightly change its step and phase. A global grid
would remove ordinary tile seams but would still change the approved rim.

Track the POC geometry bounds (centre +/- diameter*0.72). At pen-up freeze
integer **render-pixel** bounds: floor(min*scale)-4 through ceil(max*scale)+4.
For the bounded reference viewport apply its original canvas clipping; the
phone's unbounded canvas uses the same rule without an invented page edge.
Freeze low dimensions ceil(boundWidth/2), ceil(boundHeight/2), with exact
stepX=boundWidth/lowWidth and stepY=boundHeight/lowHeight.

All pool tiles share that stroke origin, dimensions and step. Compute document
coordinates from global integer pool indices in one expression, rather than
adding independently rounded world offsets for each tile. Negative document
coordinates use floor addressing; never truncation or modulo that flips sign.
Render scale 2 still represents the same document geometry; integer render
bounds and pool taps are recalculated at that level, as the POC does.

Allocate sparse pool tiles intersecting the stroke's output keys and their tap
dependencies, rather than a giant rectangle covering the entire stroke bounds.
Their global grid dimensions are metadata, not one large texture allocation.

## Passes at pen-up

1. Full-resolution assembly, MRT: cached unpooled RGBA plus **R8 premask
   coverage**, using the canonical paper sampler. This replaces the existing
   unpooled finish. Quantize coverage before pooling, matching getImageData.
2. Half-grid coverage: downsample premask R8 coverage with the source bilinear
   footprint and quantize to R8. Cross physical document tile edges through
   neighbour reads, with correct same-size empty textures.
3. Half-grid pooling, MRT into a two-layer RGBA8 array: colour overlay and
   extra-alpha overlay. Use the exact POC radius/depth rounding (including the
   minimum one pixel), four gravity taps, 65 concentration levels, and its
   extra-alpha formula. Uploaded colour/alpha LUTs reproduce the POC byte
   values. Store both overlays premultiplied, as Canvas does before filtering.
4. Full-resolution composition: bilinear overlay sampling, source-atop colour
   over the original coverage, then source-over extra alpha. Apply the existing
   wash/grain masks afterward. Preserve the two finished fade caches.

Thus four shading passes replace the current two pen-up finishes: **two extra
passes**, both at half resolution. Live painting stays on the current unpooled
path. Fade still reads two cached RGBA images; no repeated pooling each frame.
Recolour retains coverage/grid, updates the colour LUT, repools the cheap half
overlays and re-composes; it does not re-dab the geometry.

Each overlay array tile has a one-low-pixel guard. Guard pixels are calculated
from the same global pool indices, including neighbouring cells, rather than
copied from a clamped tile edge. This makes bilinear sampling identical on both
sides of a boundary. Full compose binds a 3x3 set of two-layer overlay arrays
plus current full coverage, wash and grain: 12 fragment units, below ES3's 16.
Coverage assembly uses the existing nine band tiles plus canonical paper.

Extra-alpha upsampling can extend beyond the original dab footprint. Include
an output halo of two render pixels in watercolor-only output key enumeration
and the line adapter; preserve band deposition and all generic Tiles/StampPass
math. Otherwise a very small dab wholly on one side of a physical tile border
could lose its alpha halo on the other side.

## Extra storage estimate

Per 256-document-pixel tile at scale 1: full R8 coverage64KiB, half R8
coverage16KiB, two guarded130x130 RGBA8 overlays132KiB: about **212KiB extra**,
plus shared ~16.25KiB alpha LUT and a tiny colour LUT. Existing band and
plain/pooled cache allocations remain. At scale2: approximately840KiB extra
per document tile. Sparse allocation avoids charging the whole bounding box.
These are byte estimates, not Note9 timings; the lead measures pen-up and live
frames before accepting the performance tradeoff.

## Owned source paths

- Existing gl/watercolour/WatercolourPass.kt, GlWatercolourLineRenderer.kt,
  WatercolourTextures.kt; new WatercolourPoolPass.kt and WatercolourPoolGrid.kt.
- New shaders/lines/jb_wc_base.frag, jb_wc_downsample.frag, jb_wc_pool.frag,
  jb_wc_compose.frag and a grid vertex shader; share existing band/coverage/mask
  code through new includes. Existing dab and cached-mix shaders stay unchanged.
  Preserve jb_wc_finish.frag as the named crisp diagnostic.
- GlPaintEngine: only watercolor output-halo allocation and pool preparation
  delegation at the existing hook. No new media/atlas or generic stamp changes.
- tools/watercolour_check.js and its README; pure grid tests under androidkit
  test/gl/watercolour. No brush tuning, format bump or changes to W1 placement.

## Gates before any default acceptance

- Original half-resolution POC isolation uses unchanged stamps/reference and
  unchanged painted/full-frame DeltaE <3, with visual rim review. Report approved
  comparison too, including genuine paper/grain input differences.
- Actual production fine-grain input must be included; the previous approved
  shader comparison used zero grain and constant canonical paper against the
  POC's placeholder. Do not describe these inputs as identical. Isolate pooling
  with paper/grain disabled, and independently test actual canonical paper.
- Record reference scale explicitly (existing comparisons force1, whereas
  saved owner preview is0.65). Add a labelled saved-quality case if needed;
  never silently replace the original gate with a more convenient reference.
- Tiled vs monolithic: zero byte mismatches with the same frozen pool grid,
  negative origins, fractional positions/bounds, odd dimensions, scale1/2,
  one-pixel dabs near a tile border, real paper and the alpha halo.
- Mutations: discard neighbour/guard/halo, wrong half-grid phase, remove alpha
  buildup, wrong source-atop/source-over composition and wrong LUT level must
  produce failing controls. Existing old-brush parity remains a final regression
  gate; don't rerun unchanged shaders until new pipeline source is ready.
- Backend/APK checks in the lead's explicit slot, followed by owner Note9 A/B
  and glFinish-bracketed frame measurements. No install by this worker.

Pencil/default comparison is a separate frontier task. Preserve shipped pencil,
P1/P2 and approved raster snapshots; produce same-input sheets with source/hash
labels before tuning. LineLab port is ready, but fixture/render gates await a
serial build/browser slot and the lead's exact raster/Proko reference selection.
