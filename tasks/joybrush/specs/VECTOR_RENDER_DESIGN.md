# Vector line rendering and the line brush set — design note

| | |
|---|---|
| **Author** | Vector brush specialist (Opus), 2026-10-10 |
| **Status** | 📝 For lead review. No code until approved |
| **Binds to** | JB-5.20 D1–D15 (D4, D5, D6 and D8a kept as written; one addition proposed in §1) |
| **Device floor** | Note 9 SM-N960U: Snapdragon 845, Adreno 630, 1440×2960. Document is 20 px/mm, so 1:1 ≈ phone pixels |

## 0. The problem in one line

D3/D4 render lines into 256-px look tiles at **document resolution**. The display magnifies tiles bilinearly with no
screen-resolution path (`GlPaintEngine.kt:2396-2405`, `jb_tile.frag`), so at 8× a line's 1 px antialiased edge becomes
~8 screen px of blur. Concepts stays sharp there. D4 alone is enough at ≤ 1.25× zoom and **not enough above it**.

## 1. Technique: a look pyramid, filled by the same dab maths (keeps D4)

- **Level 0** is D3's look tile, unchanged: document resolution, saved with the file, and used at zoom ≤ 1.25.
- **Levels 1–4** (2×, 4×, 8×, 16×) are sharp tiles. Each holds 256² texels covering 256/2ᵏ doc px. They live in
  cache only and are never saved. The display uses level ⌈log₂ zoom⌉, with level 4 serving 16–64×.
- A sharp tile is made **exactly the way a level-0 look is made**: the tile's slabs (bilinear, since pixels are
  pixels) and lines are composited in `seq` order with each line's operator (D8a). The only change is that the dab
  shader gets a `scale = 2ᵏ`.
- The tip is an analytic superellipse whose antialiasing comes from `fwidth` in target pixels (`jb_tip.glsl:59`,
  `TipMath.kt:29-35`). So the same dabs at scale 2ᵏ give an edge ~1 *screen* px wide. Paper grain stays locked to the
  document, so a pencil line magnifies its paper just as the paper background does.
- **"Same brush, same maths at 1:1"** holds by construction: level 0 is the live path's dabs (D4).
- **Seams are impossible by construction.** Every output pixel is a pure function of (dab list, pixel centre, item
  order). Tiles cull dabs with a halo of `TipMath.extent`, so no pixel depends on which tile drew it. Tested in §5 B5.
- **Refinement is progressive.** While zooming, or before a sharp tile exists, the display shows the level below,
  magnified; that is today's look. Sharp tiles render inside a **4 ms/frame budget**, nearest the finger first. They
  are kept in an LRU of about 48 MB (≈ 190 tiles; the 8× screen needs ~72).
- **The live stroke at zoom > 1.25** draws its new dabs into a sharp overlay incrementally, at the same cost per dab
  as the live path. Lines are therefore sharp *while* drawing at 8×, not just after pen-up.
- **Pen-up commits a line by replay** (record → dabs → look tiles), not by copying the live stroke buffer. The look then
  equals the record by construction, even where live and replay drift (see H7 for the one known drift). Pen-up is
  also the moment taper-out becomes possible (§3).

**Editing one line among 1,000: the sandwich cache.** "Normal" and "erase" are both of the form `D′ = S + k·D`.
Any run of them in `seq` order therefore collapses to one premultiplied colour `S` plus one scalar `k`.
- At grab time, each tile the line touches is built once as two parts: *below* (one RGBA16F) and *above* (`S`,`k`:
  RGBA16F + R16F).
- Every drag frame then replays **only the edited line** and does one composite pass per tile.
- A tile whose *above* run holds a behind or multiply line falls back to full re-render.
- At gesture end, one exact full re-render (D3's float-then-quantise-once) replaces the preview.

**Plan B, only if the bench says so: ribbons.** For hard, round, cap-1 brushes (pen, fineliner, wire, marker), a
ribbon could replace the dabs at levels ≥ 1. It is a tessellated variable-width outline (the same one SVG uses, §4),
with ~1× overdraw instead of ~12×. Level 0 stays dabs (D4). The ribbon is admitted per brush only if it matches the
dab render at that level within 2/255 and ½ px of edge. I do not propose it until B2 shows the dab path missing a gate.

### Cost on the Note 9 (estimates; B2 replaces them with measurements)
Assumptions:
- Ink at 6 px places a dab every 0.5 doc px (`DabPlacer` floor). Each pixel is covered by ~12 dabs, and each quad is
  ~5.5× the disc area.
- The Adreno 630 runs this fragment shader at ~1.5 Gfrag/s (~0.7 with paper grain).

| Case | Fragments | Time |
|---|---|---|
| Live stroke, any zoom (per frame, ~40 new dabs) | 40 × ≤ 5 k | < 0.2 ms |
| Commit a 1,500 px ink line at level 0 (pen-up replay) | 3,000 dabs × 155 | ≈ 0.3 ms GPU + CPU replay (B1) |
| 8× view, 10 lines crossing the screen, all sharp tiles | ≈ 40 M | ≈ 27 ms → ready in ~7 frames of 4 ms |
| Panning at 8× (new strip per frame) | ≈ 2–4 M | 1.5–3 ms, inside budget |
| Drag one line among 1,000 (sandwich), per frame | one line + 12 tile composites | ≈ 1–2 ms GPU |
| Same, building the sandwich at grab | every line in ~12 tiles (dense: ~100/tile) | ≈ 30 ms GPU + CPU replay |

**The real risk is CPU replay, not the GPU.** Rebuilding a dense tile replays every line crossing it from samples
(smoother → dabber → scatter). One 1,500 px line is 3,000 dabs, and 100 lines are 300 k dabs. That is ~100–300 ms
on the phone if Kotlin replay runs at ~1–3 M dabs/s. Mitigation: an LRU of GPU-ready dab arrays per line, packed at
24 B per dab (32 MB budget ≈ 450 average lines), so a re-render replays only lines not in cache. B1 decides how much
of this is needed.

## 2. The brush set (10 now, 2 later)

Each line brush also gets the shared line controls: taper in/out, smoothing, pressure/speed/tilt response, and
"Editable lines" (D6).

| # | Brush | Why it exists |
|---|---|---|
| 1 | **Pen** | The everyday inker. Pressure plus thinning when fast (Concepts' Pen), with a gentle taper at both ends. |
| 2 | **Brush pen** | Expressive swell. Wide pressure range, long tapers, and an oval tip that follows direction for calligraphic thick/thin. |
| 3 | **Fineliner** | A truly constant width with round caps. For diagrams and clean SVG/CAD export. |
| 4 | **Wire** | A hairline of constant *screen* width at any zoom, for construction and perspective. The only view-dependent brush (§3). |
| 5 | **Sketch pencil** | Hard, light construction lines with paper grain. Tilt widens it a little. |
| 6 | **Shading pencil** | Soft and dark. Lay the S Pen down and it shades with the side of the lead, the grain locked to the paper. Concepts users' favourite feature. |
| 7 | **Chisel marker** | A flat nib angled by pen tilt direction (stroke direction for a finger). Translucent "wash", so a stroke doesn't darken itself. |
| 8 | **Line watercolour** | Soft edge, and overlaps darken (multiply, D6/D8a). An editable glaze, without Concepts' "only blends on its own layer" quirk. |
| 9 | **Dash pen** | Dashed and dotted lines (pattern measured in widths): hidden lines, stitches, borders. |
| 10 | **Fill pen** | Exists. Closed shapes, recolourable (D10). |
| 11 | *Line airbrush* (later) | Soft editable shading. Waits on owner Q2 (D6) and on B2, because soft brushes have the highest overdraw. |
| 12 | *Sable as a line* (later) | The owner's own inker, editable. See below. |

**Sable can become a line.** `TuftStroke` is deterministic in (samples, seed, preset, `screenPerDoc`)
(`TuftStroke.kt:45-57`), and a record already stores all four. Before Sable lines ship, three things are needed:
1. A `TuftReplay` beside `InkReplay`.
2. A replay of the GPU tuft stamps through the existing `jb_tuft` shaders, proved byte-equal to the live stroke in the
   headless checker.
3. A ruling on paper: a Sable line's look depends on the document's paper, so changing paper re-renders its tiles.
   That is the honest behaviour, and it invalidates the cache.

CPU export needs either a port of `jb_tuft.frag` or a GPU export path. It is phase 2, after stamp lines are proven on
the phone.

## 3. Line maths `InkReplay` lacks (new core files; pure functions, all tested)

- **Taper in/out.** Width × `smoothstep` over a length measured in brush diameters from each end, with a floor of 0.1.
  It is applied to dab radii after placement, so positions and seeds are unchanged and re-brush is safe.
  - Taper-out needs the stroke's total length, which is only known at pen-up. The live stroke shows an untapered tail,
    and the pen-up replay (§1) draws the true taper; Procreate does the same.
  - For pixel strokes with the same brush, the engine re-dabs the stroke buffer at pen-up from its kept samples (H3),
    so "same brush, same look" holds.
- **Velocity.** Speed must be in **screen** px/s (doc speed × `screenPerDoc`); today it is doc px/s
  (`BrushDabber.kt:164-183`). Otherwise the same hand speed thins differently at another zoom. Tuft already judges speed
  on screen. No stamp preset uses speed yet, so fixing it now changes nothing already drawn.
- **Constant screen width (Wire).** Width is stored in screen px and drawn at `width / levelZoom` per pyramid level. A
  wire is drawn at ≥ 1 target px at every level; levels −1 and −2 are added for zoom < 1, used only by tiles holding
  wires. It is never baked into level 0 except by Rasterize Down, at the zoom of that moment.
- **Dashes.** A pure filter on the dab list by arc length: on/off lengths in brush widths, phase from stroke start. Each
  dash end gets a round cap from its last dab. Dots are just spacing ≥ 1.2.
- **Re-brush.** Already safe, because samples carry every channel. Moving to or from Wire converts width through the
  record's `screenPerDoc`.

## 4. SVG export: one outline per stroke (R8)

- **The outline.** Each stroke is one variable-width outline: the envelope of its dab tips (support points of each
  convex superellipse on each side of travel, plus caps). It is simplified to 0.1 doc px and fitted with cubic Béziers.
  Pressure, taper and speed survive the export; Concepts flattens strokes to one weight.
- **Element per stroke kind:**
  - Dash pen: one `<path>` with dash sub-paths.
  - Wire: a centreline with `vector-effect="non-scaling-stroke"`.
  - Line watercolour: `mix-blend-mode:multiply`.
  - Fill: `fill-rule="nonzero"`.
- **Pixels.** Pixel slabs go in as `<image>` elements in `seq` order, so a mixed layer exports honestly.
- **Not done.**
  - Pencil grain is not exported (a flat fill at the brush's opacity), and there are no per-bristle paths.
  - There is no offset band for edge darkening: R8 §2.8, US 7,777,745 runs to 2028.

## 5. Hooks I need from the lead (I write everything else in `gl/lines/`, `shaders/lines/`, `brushes/lines/`)

| # | Hook | Where |
|---|---|---|
| H1 | Extract the stamp draw (program, dab VBO upload, grain/paper uniforms) into `gl/StampPass.kt`: `draw(dabs: FloatArray, n: Int, target: Fbo, originDocPx: Vec2, scale: Float, preset: FrozenBrush)`. Add `uniform float uScale` to `jb_dab.vert`. Live engine and line renderer then share one shader path | `GlPaintEngine.kt:1500-1735` |
| H2 | Display: in the per-tile loop, `if (lines?.drawsTile(layer, celId, key, view) == true) continue`; after the loop, `lines?.drawLayer(layer, celId, view)` | `GlPaintEngine.draw` 2113-2154 and `drawComposited` |
| H3 | Pen-up: for line brushes, `lines.commitLine(record, frozenBrush)` writes the looks (replaces the stroke-buffer commit). For pixel brushes with taper-out, re-dab the stroke buffer from the kept samples before commit | 5.20d commit path, `GlPaintEngine.kt:1737-1801` |
| H4 | Live tap: `lines?.onLiveDabs(layerId, celId, dabs, n, floatsPerDab)` plus `onStrokeEnd/Cancel`, for the sharp live overlay | stamp batch upload, `GlPaintEngine.kt:1529` |
| H5 | Read access to a cel's slab textures and op/seq list, and a frozen-brush lookup (D5) | 5.20a/d model |
| H6 | `blend: "multiply"` as a brush word, with stroke-level multiply in `jb_commit.frag`, for live preview of line watercolour | `brush/BrushValidate.kt:29-33`, `jb_commit.frag:36` |
| H7 | Core fixes found while reading, all small; each prevents replay ≠ live or export ≠ screen. See the list below | core `vector/`, `brush/` |
| H8 | (perf, yours) The display has no viewport culling: every tile of every layer is drawn every frame (`GlPaintEngine.kt:2131`) | `draw` |

The H7 fixes:
- **(a)** Replay passes `tilt = NaN` to Scatter while live passes `dab.tilt` (`InkReplay.kt:157` vs
  `JbCanvasView.kt:1572`).
- **(b)** Speed in screen px/s (§3).
- **(c)** A NaN `widthScale` is read as 0 in replay but 1 in the codec and in reweight.
- **(d)** `InkTiles` ignores paper grain, yet the pencil makes lines; it also uses `hardness.base` where live uses the
  first-dab value.
- **(e)** The parity test compares replay with a copy of the live path written inside the test, not with
  `JbCanvasView`.

## 6. What I benchmark, and how

- **B1. JVM `InkReplay` throughput** (dabs/s per brush, `:core:jvmTest`). This is the phone proxy that sizes the dab-array LRU.
- **B2. Note 9 GPU**, using a debug-only bench screen with `EXT_disjoint_timer_query`; the lead installs it. It
  measures dab Mfrag/s at levels 0–4, plain vs paper grain, dabs vs ribbon.
- **B3. Edit one line among 1,000** in a synthetic A4 drawing: grab (sandwich build) and per-frame drag, in ms. Gate:
  < 100 ms.
- **B4. Pan and pinch at 8×** over the same drawing (Perfetto): p95 frame time, and time to fully sharp.
- **B5. Headless SwiftShader parity**, all at 0 mismatches:
  - level-0 replay equals the live commit, byte for byte;
  - sharp tiles tiled vs one big target;
  - sandwich drag vs full re-render.
  - Ribbon vs dabs is checked within tolerance.
- **For the owner:** PNG strokes of each brush at 1:1 and at 8×, side by side with today's magnified look. He picks.

## 7. Build slices (each one reviewed and pushed as `vector/<topic>`)

| Slice | Contents |
|---|---|
| V1 | Core line maths (taper, dashes, screen speed behind a flag, wire width) + B1 + tests |
| V2 | `LinePyramid` + sharp-tile renderer on SwiftShader (B5), after H1 |
| V3 | Sandwich cache + B3 |
| V4 | Brush presets 1–10 + owner PNG sheet |
| V5 | SVG export |
| V6 | Phone benches B2/B4 (needs H2–H4 and an install) |
| V7 | Sable line (phase 2) |

## 8. H1 contract: `gl/StampPass.kt` (specialist → lead, 2026-10-10)

```kotlin
/** The one instanced-dab draw. The live stroke and line rendering share it (H1). GL thread only, allocation-free per call. */
class StampPass(programs: GlPrograms, grains: GrainTextures) {
    /** Everything fixed for one stroke or line: tip, accumulate, grain specs and textures, paper surface/response. */
    class StrokeLook internal constructor(/* tip: TipShape, grain: GrainMath.StrokeGrain, paper binding, … */)
    fun lookFor(preset: BrushPreset, first: Dab, paper: DocPaper?): StrokeLook   // what beginStroke computes today

    /** Draws dabs into the bound target with the stroke-buffer blend (ONE, ONE_MINUS_SRC_ALPHA). */
    fun draw(look: StrokeLook, dabs: List<Dab>, originDocX: Float, originDocY: Float, scale: Float, targetPx: Int)
    /** Same, from a GPU-resident instance buffer (the line renderer's dab cache). */
    fun drawInstances(look: StrokeLook, vbo: Int, first: Int, count: Int, originDocX: Float, originDocY: Float, scale: Float, targetPx: Int)
}
```

| # | Requirement | Why |
|---|---|---|
| S1 | **Tilt amount and lean direction travel per dab** (instance attributes), not per batch. Today `u_tiltAmount`/`u_leanDir` come from the batch's newest dab (`GlPaintEngine.kt:1732-1733`) | Batches follow MotionEvents, which a replay cannot know. A pencil line replayed later would grain differently from how it was drawn. This breaks D4 for every grain brush |
| S2 | `uniform float u_scale` (target texels per doc px; 1 at level 0). Position = (dab − origin) × scale. Quad half-size = r·scale·1.4143 + 2 **target** px | Sharp levels 1–4 |
| S3 | Tip maths (`minPx` fade, aspect, taper, anchor) in **doc** units; only the antialias band (`fwidth`) in target px | A thin line must fade the same at 8× as at 1:1, only crisper |
| S4 | Paper grain sampled at doc position `origin + fragCoord / scale`; tip grain in dab space as now | Grain stays locked to the paper at every level |
| S5 | Output is a function of (look, dab list, origin, scale) only: any split of a list into calls gives identical pixels | Seam-free tiles, and replay = live |
| S6 | Instance layout written once in `StampPass` (17 floats today + S1's 3 = 20) and documented, with `fillInstances` moved there | One packer, so the line renderer's cached VBOs can't drift from the live path |

Proof: extend `joybrush/tools/contact_check.js` with (a) one list drawn in one call vs in random splits, byte-equal; and (b)
scale 2 vs level 0 downsampled, within AA tolerance. I'll write both checks once the extraction lands.

## 9. Sampled watercolour line brushes (added 2026-10-11 from the owner's research, R12; corrected the same day)

Concepts' watercolours are dab samples, not ribbons (owner, R12). So a watercolour line is a stamp line with four
additions, all deterministic in (record, seed, frozen brush with its samples):
- **Sample set**: several image dab tips in one array texture; each dab picks one by seed, pressure and tilt (small hard
  tips when light and upright, big bleeding ones when pressed and laid over), rotated by angle jitter.
- **Max combine**: the stroke buffer keeps the darker of dab and buffer (blend equation MAX), so a stroke never builds to
  black inside.
- **Wash capacity**: at commit the stroke density is multiplied by the brush's wash texture, sampled in the line's own
  frame (an origin and rotation stored with the line, moved by edits), so full density shows the wash, not flat colour.
- **Per-line operator** (D8a): multiply glaze or normal.
Engine hooks: an accumulate word "max", an image-tip array, the wash sampler in the commit pass, and a line frame in the
record. They go to the Lead as a design note before any build. Samples are ours (simulation bakes and the owner's
photographs). Ribbon "skin" brushes remain a possible later kind for dry-brush streaks.
