# Watercolour line brush: integration plan for the phone (2026-10-12)

**For:** Codex (builder). **From:** the vector brush specialist (Claude). **Owner:** JoyRaptor.
**Reference implementation (read-only):** `C:\Users\JoyRaptor\Downloads\Watercolor-POC\`.
- `index.html` is authoritative.
- `approved-watercolor-settings.json` is the owner's accepted tuning.
- Ignore the `*.py` patch scripts.
- The POC is a Canvas-2D renderer: port its MATHS into GLSL and Kotlin; don't embed the HTML.

The goal is the POC's look on the Note 9, as an **editable line** (JB-5.20) and as pixels, from one engine. Keep the owner's
accepted feel:
- tilt slides the dab window;
- fine tip when upright;
- edge alpha build-up;
- edge and gravity pooling (gravity colour shift);
- the backdrop with a seeded per-stroke offset;
- the one-second pooling fade.

## 0. What the POC does (so you port the right thing)

Per stroke, all randomness comes from one seed (POC `rng(seed)`; port the generator exactly or replace it with core
`SplitMix`, consistently):

1. **Dab choice.** An ordered set of N dab alpha images, tight to bleeding. A window of W = 8 slides with **tilt**:
   `start = round(tiltResponse(t) * (N - W))`. Each dab picks uniformly inside the window. Rotation is
   `(r - 0.5) · 2π · rotation% + heading · follow%`. Scatter radius is `sqrt(r) · scatter · d · mod(pressure, tilt)`.
2. **Diameter** (`diameter()`): `minimum`, `taper` on pressure, `tipWidth` (fine tip), `tiltMinimum`, and
   `tipAngle` = a 40° allowance before tilt counts (`tiltResponse`). Pressure and tilt go through cubic-Bézier curves
   (257-entry tables).
3. **Dab alpha.** `opacity × variation × depletion`:
   - variation: a seeded sine wave over travel distance;
   - depletion: `1 − e^(−travel/T)`;
   - spacing compensation: `1 − (1 − α)^(spacing / (0.15·d))`.
4. **Dryness bands.** Dryness = initial + build-up over travel. Each dab deposits into bands 0, 0.5 and 1 with
   linear weights (alpha `1 − (1 − α)^weight`). At assembly each band is cut by the PAPER HEIGHT:
   `coverage = clamp((h − dry·0.85)·8 + 0.5)`, mixed by `paperCut`.
5. **Pooling (finished stroke only).**
   - edge signal = `(α − min α at ±edgeWidth, 4 directions) / α`;
   - gravity = mean over k = 1..4 of `max(0, α − α(y + depth·k/4)) / α` (downward);
   - colour: the tint's linear RGB raised to concentration `1 + 2·level` (source-atop);
   - alpha build-up: `1 − (1 − α)^(1 + 4·edgeAlpha·edgeSignal)`.
6. **Masks.** × backdrop wash (the 1254-px tile, contrast, seeded offset per stroke, world-anchored scale) × fine grain.
7. **Composite.** Normal or multiply at `strokeOpacity`.
8. **Fade.** On pen-up, crossfade unpooled → pooled over 1 s (smoothstep), then commit. Undo, a new stroke or export
   settles it immediately.

## 1. How it maps onto Joy Brush (the design)

| POC piece | Joy Brush piece | Why |
|---|---|---|
| Dab placement, curves, window, rotation, scatter, alpha, dryness weights | **`core/brush/WatercolourDabber.kt`** (new, pure Kotlin, deterministic), producing `Dab`s with extra fields `sample` (index), `bandWeights` | Live and replay share it, so a watercolour stroke is a replayable LINE (D4). The vector edits (move, recolour, re-brush, tilt) come free |
| 25 tinted canvas dabs | **One `GL_TEXTURE_2D_ARRAY`, R8 alpha only, one dab per layer, mipmapped**. The tint is applied at finish, so recolouring a line costs nothing | No offsets, no row bounds, no re-tinting per colour |
| 3 dryness band canvases | **One RGBA16F stroke buffer**: R, G, B = bands 0, ½, 1; blend `ONE, ONE_MINUS_SRC_COLOR` (each channel "over" with its own alpha) | Three bands in one pass, no extra targets |
| Paper height placeholder | **The document's real paper surface**: height = `.z` of `jb_tilePaperSurface(docPx)` (`jb_tile_paper.glsl`), canvas-aligned, same as the pencil | Owner requirement; one paper for every brush |
| CPU half-res pooling with readback | **A GPU finish pass** (`shaders/lines/jb_wc_finish.frag`): edge taps ±edgeWidth plus 4 gravity taps on the assembled coverage, at FULL resolution | No readback, no 1-px softening, cheap on Adreno |
| Optical LUTs | `pow()` in the shader on linear RGB (decode the sRGB tint once on the CPU) | Exact, cheap per pixel on the GPU |
| Backdrop and grain masks | Backdrop = the wash tile (an asset, R8, REPEAT, mipmapped) at `(docPx + offset(seed)) / backdropWorld`. Grain = the paper's own fine grain or a small blue-noise tile | World-anchored, deterministic per line |
| Composite | The finish pass writes **premultiplied RGBA**; commit uses an RGBA-source path with `u_blend` (normal / multiply, already in `jb_commit.frag` for single-colour strokes) | One more commit mode: "RGBA stroke + blend" |
| 1 s fade | The engine keeps the unpooled and pooled stroke images for up to 1 s (max 8 pending), draws the mix, then commits pooled. `RENDERMODE_WHEN_DIRTY` → request frames for 1 s | Cosmetic only, never in the record |

**Tiles and seams (important).** Strokes live in 256-px stroke tiles. The finish pass reads neighbours: the edge needs 3
px and gravity up to `gravityWidth` (36 px). Bind the 3×3 neighbouring stroke tiles, the way smudge's `bindPickupTiles`
already does, so a tile's pooling sees across its border. Without that, every tile edge shows a seam (LEAD_PLAN F3).

**As a line.**
- Add engine word `"watercolour"` to `StrokeEdit.INK_ENGINES`, so `InkReplay` uses `WatercolourDabber`.
- The line's look tiles are produced by the same dab pass plus finish pass (§8 StampPass pattern, `u_scale` for sharp
  levels).
- Recolour = re-finish with a new tint (no re-dab).
- Pooling and the fade are part of finish, so a replayed line comes back already pooled.

## 2. Build steps for Codex (each one a reviewed branch; the lead merges)

**W1: core dabber + brush format (no GL).**
- Port `diameter`, `tiltResponse` (with `tipAngle`), `dabWindow`, `modulation`, `dabSpacing` (+ compensation), rotation,
  scatter, variation, depletion and the dryness weights into `WatercolourDabber`.
- Add a `watercolour` section to `BrushPreset` (brush version current + 1, written that way, see VECTOR_LINES):
  - every control in `approved-watercolor-settings.json`, with its units;
  - the two Bézier curves;
  - asset names for the dab array and the backdrop.
- Gates:
  - (a) determinism test: same record + seed → identical dabs;
  - (b) golden test against the POC: export the POC's dab list for its 3 sample paths with seeds 78234–78236 (write a
    small Playwright script that calls `stamp()` and logs the parameters), then match index, rotation, diameter, alpha
    and position within 1e-4. Port the RNG exactly for this;
  - (c) the shipped preset `joybrush/brushes/watercolour/brush.json` = the approved settings.

**W2: assets.**
- Dabs: extract each dab as its own centred R8 PNG (the POC's `extractDabs` row bounds are only for this sheet) into
  `joybrush/assets/watercolour/<set>/dab_00.png`…, plus `backdrop.png` (the 1254 tile, R8).
- Licence check: these were AI-generated for the owner; record their provenance in an ASSETS note.
- Loader: `GrainTextures`-style, array texture with mipmaps.
- Gate: the asset test lists N layers, every perimeter transparent (the POC's 5-px check).

**W3: GL passes, pixels first.**
- New programs: `shaders/lines/jb_wc_dab.vert/.frag` (instanced like jb_dab; samples the array layer; rotation, scale
  `u_scale`; writes band weights × alpha) and `jb_wc_finish.frag` (bands × paper cut → coverage → edge and gravity
  pooling → optical colour → backdrop and grain masks → premultiplied RGBA).
- Commit: an RGBA-source mode with `u_blend`.
- Engine: `GlPaintEngine.beginStroke`/`addDabs`/`endStroke` route `engine == "watercolour"` here (lead-approved hot-file
  edit), including the 1 s fade.
- Gates:
  - (a) a headless WebGL checker `tools/watercolour_check.js`: renders the POC sample paths with the app's shaders and
    compares with POC screenshots (perceptual: mean ΔE < 3, edges visually reviewed);
  - (b) a seam test: one stroke across a tile corner, rendered tiled vs as one big target, 0 mismatches;
  - (c) `:app:assembleDefaultDebug`; then the lead installs and the owner draws.

**W4: as an editable line.** `"watercolour"` in INK_ENGINES; look tiles by replay; recolour by re-finish; one undo step.
Gate: draw, then nudge and recolour a watercolour line on the phone; it re-renders identically, with no seam.

**W5: Note 9 performance.**
- Use the lead's FrameStats (glFinish-bracketed; Adreno timer queries are unreliable).
- Measure one long wash at size 300 with spacing 15%: per-frame dab pass, finish pass at pen-up, fade frames.
- Budget: the live frame within 16 ms.
- Levers, in order: cap dabs per stroke as the POC does (6,000 phone); a half-resolution live preview of finish (full
  resolution only at pen-up); bigger spacing at size.

## 3. Suggestions to make it better (owner picks; none are blocking)

1. **Pooling at full resolution on the GPU** (above). The POC's half-res band softens by about a pixel; ours won't.
2. **Gravity follows the phone.** Once the lead's gravity sensor and tilt pad (M5.6) exist, feed its direction into
   gravity pooling instead of "down the page". Your watercolour will pool toward wherever you tip the phone.
3. **Linear-light compositing of the tint.** The POC composites in sRGB. A linear multiply glaze keeps saturated overlaps
   from going muddy, which matters for the green-over-orange you drew.
4. **Real paper does the dryness.** On rough watercolour paper, dry-brush breaks up on the tooth exactly where the paper
   is high. That is free once the real height is wired in.
5. **Dab count: your 5×5 vs 4×4 question.**
   - My recommendation: **fewer, sharper dabs, and a variable count.** Each dab is rotated through a full 360°,
     scattered, and drawn from a sliding window of 8, so variety is already more than the eye can track.
   - What shows is softness when a big dab is drawn larger than its picture. A 300-px brush (15 mm on the Note 9) draws a
     ~250-px cell slightly magnified, and zooming in magnifies it further.
   - So: **16 dabs at 512 px each**, in an array texture where the count is whatever the brush file lists (the window
     works for any N ≥ 8). That is about 5 MB with mipmaps, fine on the Note 9.
   - Keep 25 only for a brush that genuinely needs 25 distinct stages from tight to bleeding.
   - No grid is involved at all, so "no offsets" is automatic.
6. **A second brush from the same engine for free.** Dabs, window and pooling are all data. An "ink on wet" brush (harder
   dabs, little pooling) and a "dry brush" (high dryness build-up) are just presets.

## 4. The rest of the vector work, ready for Codex ("go build")

Branches are pushed; the details are in `tasks/joybrush/handoffs/VECTOR_SPECIALIST_HANDOFF_20261011.md`.

| # | Task | Done when |
|---|---|---|
| 1 | Finish **`vector/stamp-pass`** (H1). In `C:/Temp/jb-vector-h1` run `node joybrush/tools/shader_check.js`, then compare `contact_check.js` with main (both fail the same known way), then `:app:assembleDefaultDebug`, then rebase onto main | Lead A/Bs ink, pencil and marker on the Note 9 and merges |
| 2 | Merge **`vector/docs-and-export`** (docs). Then surface `InkTiles.Omission` in RegionRenderer/OraExport export results as a sentence | No silent drops in export |
| 3 | **Line lab** (`vector/line-lab`): finish `--shaders <dir>` and `u_grainCapacity` from `extensions.deposit == "tooth"`; render the shipped pencil, P1 and P2 to `C:/Temp/jb-linelab-out/` | 4 PNGs exist |
| 4 | **Pencil** (`vector/pencil`, rebase first): compare the lab sheets against the owner's Proko shots; tune `lab/lines/presets/pencil_p2.json` | The owner picks a sheet |
| 5 | **SVG export** (`vector/svg-export`, rebase first): tests (outline vs dab raster IoU ≥ 0.99, one `<path>` per stroke), plus a sample SVG | Tests green |
| 6 | **Tween fix**: `anim/Tween.kt moved()` divides `screenPerDoc` by the fit's scale (the UI specialist's finding) | A test with a ×2 tween |
| 7 | **V4 presets**: the 10 line brushes of VECTOR_RENDER_DESIGN §2 as `joybrush/brushes/lines/*.json` | Validated by `BrushJson.decodeChecked` |

Rules for every task:
- build through `jb-gradle.lock`, one at a time;
- never resolve a merge conflict (re-apply your change on the new base);
- never install on the phone (the lead does, after the owner presses Home);
- never flip pen tilt globally (only the media brushes are 180° off, and that fix is the lead's).
