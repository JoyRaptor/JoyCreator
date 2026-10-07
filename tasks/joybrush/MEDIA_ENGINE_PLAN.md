# Joy Brush Media Engine — plan, state and port guide (2026-10-06)

Owner brief (2026-10-06, condensed): realistic **oil** (Rebelle class: impasto, soft shadows), **watercolour**
(Expresii class), **pencil** (the owner's feel notes in §6), each in **raster and vector**, on one **paper** both use.
"Once the engines are right, every brush is just parameters." The architecture call was left to Claude.
Added the same day:
- Colours wick up the brush to different depths and show with tilt.
- Painting through thick paint dirties *that part* of the brush for later strokes.
- Phone tilt (gravity) can move wet paint.

**Team split** (agreed with the paper session, 2026-10-06):
- The **paper session** owns paper: photo papers, surface and fluid maps, `jb_paper.glsl`, `core/paper/*`, and the catalogue.
- **This session** owns the media engine. It adds new files only: `joybrush/lab/media/**`, `joybrush/shaders/media/**`,
  `joybrush/tools/media_*.js`, and later `core/media/**`.
- App wiring goes through the Lead (`GlPaintEngine`/`JbCanvasView` are the Lead's hot files).

**See it:** the lab is published as a private Artifact, "Joy Brush Media Lab" (claude.ai/artifact/StiwT7wvUB3NBAcTnrt7Ut).
Build it with `node joybrush/tools/media_lab_bundle.js <out.html>`. Render any test sheet to a PNG with
`node joybrush/tools/media_render.js out.png "test=proto"`; the sheets are `proto pencil scribble flick wash drop swatch mingle tilt
oil roundtwo impasto`. Add `&zoom=4&cx=..&cy=..` for a close-up, `&vector=1` for a vector replay, `&mode=1..7` for
debug views, and `PROBE='[[x,y]]'` for exact state values.

## 1. The architecture call: ONE engine, three ways of touching the paper

Every medium is pigment in a vehicle:
- water (watercolour, ink),
- an oil or paste body (oil, acrylic, gouache),
- a solid stick (graphite, charcoal, pastel).

So there is one per-layer **material state** and one optics model, and three **transfer mechanisms** that write it.
Pencil, watercolour and oil are parameter sets of their mechanism. Palette knife, fan, wash, 2H…9B are more parameter sets.
Media interact for free:
- a glaze over dried wash,
- oil over a pencil drawing,
- water lifting soluble pigment.

Each mechanism only allocates its textures when first used.

### 1.1 Layer state (lab: full-layer textures; app: 256-px tiles)
| Texture | Format | Channels | Written by |
|---|---|---|---|
| `p0` | RGBA32F | pigment absorption K·X (rgb), body thickness (mm) | all |
| `p1` | RGBA32F | pigment scattering S·X (rgb), openness (wet = movable) / solubility (wet media) | all |
| `paper` | RGBA16F | R = crush (tooth flattened by hard pencil, tooth units) | dry |
| `w0` | RGBA32F | surface water (mm), capillary saturation s, wet time, outflow | wet |
| `w1` | RGBA32F | pigment still floating: K rgb, S | wet |
| `flux` | RGBA16F | pipe outflow L R D U | wet |
| bakes | RGBA16F/8 | paper height, fluid map, water-scale height at layer resolution | wet, paste |
| brush | 32×8 RGBA32F ×2 | the paste brush's cells (lanes × tip→belly): K rgb, amount / S | paste |

**Full float is required** for `p0 p1 w0 w1`. Watercolour moves pigment in thousands of tiny steps, and with half floats
increments below half an ulp are lost: deposits froze and colours drifted (seen 2026-10-06). The app must check
`EXT_color_buffer_float` (GLES 3.2 core on the Note 9) and **refuse wet media** without it; it must not silently fall back.

**Optics = Kubelka–Munk per RGB channel** over the paper colour, in `jb_media_render.frag`. Low S is a transparent glaze;
high S is an opaque body. (This is per-channel KM, which is 1931 prior art; **no** Mixbox-style latent mixing.)

**Lighting:**
- One lamp across the true relief: paper tooth − crush + paint body + water surface.
- Wet water glint.
- Oil gloss while open.
- Graphite sheen on burnished tooth.
- **Soft self-shadows** from thick paint: a 10-tap march toward the lamp, with a penumbra that grows with distance.

### 1.2 The three mechanisms (as built)
**Dry: pencil/graphite.** Files: `jb_dry_dab.*`, `jb_media_apply.frag`, `lab/media/js/stick.js`.
- **The stick** is a real rounded cone: tip radius, cone angle, lead diameter, exposed length, and a worn facet. When
  tilted, its underside height over the paper is `jb_stickZ`.
- **Two-layer paper.** The paper is a stiff rough tooth on a soft pad (springs in series, `contactLut`). The CPU solves,
  per dab, how deep the stick sinks for the pen's force (force balance over the footprint).
  - A feather touch only clips tooth tips.
  - Firm pressure flattens the tooth and sinks the pad, so the mark widens: the owner's "pillowy paper".
  - Along a tilted lead the squeeze falls off, giving a hard tip edge and a grainy fade along the side, with no special case.
- **Light, tilted strokes settle onto the side.** The lighter the touch, the flatter the lead lies, so the faintest
  strokes are the widest. This gives the soft-shading sweep that fans out as it fades.
- **Deposit.**
  - Abrasion follows Archard with Hertzian asperity pressure, `work = δ^1.5/√T · slide`, prefiltered over each pixel's
    height variance (`E[max(0,δ)]`), so zoomed-out deposit never shimmers.
  - The deposit saturates in closed form: `V' = cap − (cap − V)·exp(−k·W)`, so layering only darkens toward the tooth's
    limit.
  - Hard pressure crushes and burnishes the tooth, and the crush is stored.
  - Dust falls into pits the stick passes over but cannot reach.
  - Faces that meet the stroke catch more (directional deposit, JB-9.08's dry half).
  - Soft grades drag a little of what is already down.

**Wet: watercolour.** Files: `jb_wet_*.frag`, `lab/media/js/wet.js`.
- **Water flow.** A virtual-pipe shallow-water model over paper height + water: water runs along the paper's trenches and
  pools in its valleys. It is **not lattice Boltzmann** (patent guard). Mobility ∝ depth², so thin films cling and deep
  puddles level.
- **Pinning.** A dry front holds until the head exceeds a threshold that varies with the paper's absorbency map, which
  makes ragged hard edges. Damp paper mostly releases the hold, and the uneven release draws bloom fingers.
- **Drying and edges.** Evaporation is faster at thin edges, and pigment drops out faster at the contact line. That
  outward flow is the dark edge. Uneven sizing makes uneven drying, which gives soft mottling.
- **Pigment movement.**
  - Pigment mingles through connected water only: wet into wet, never into dry.
  - It settles faster the thinner the film.
  - It granulates in the fine tooth.
  - Soluble pigment lifts when re-wetted.
- **Soak and wick.** Water soaks in (sizing) and wicks through the fibres; damp paper unpins later washes.
- **The brush.**
  - It exchanges water by wetness: a loaded brush gives, a thirsty brush takes back.
  - It lands with a bead pool, bleeds while held still, and drops the rest on lift.
  - Hair runs stripe a drying brush, so dry-brush skips the tooth in streaks.
- **Paper tilt** adds a gravity slope (`u_tilt`), so water runs downhill and pools on the low edge.

**Paste: oil, knife, scraper.** Files: `jb_paste*.{glsl,frag}`, `lab/media/js/paste.js`.
- **The brush** is a fixed grid of 32 lanes × 8 depths, each holding ONE paint. Patent guard: one state per cell, no
  pickup-vs-reservoir split, a coarse grid, a 2D footprint, and no non-destructive canvas sampling for loading.
- **Each step trades paint both ways** with the canvas under each cell, from the same rules on both sides:
  - lay down where the canvas is thinner than the target;
  - plough up where it is thicker and still open;
  - swap along some hairs (broken colour streaks);
  - stir.
- **Paint wicks slowly between cells.** Pressure and tilt bring deeper cells (the belly) into contact. On a pressed round
  brush, the splayed side hairs are belly hairs. The brush keeps its state between strokes ("Dirty brush").
- **Loading tray.** A two-colour load: tip/belly on a round, side-to-side on a flat.
- **The target layer is measured from the canvas peaks**, so paint fills the weave first:
  - scrape hard and the canvas comes back;
  - lay it on thick and the weave disappears.
- **Shape of the deposit:**
  - a bow wave piles ahead and is left as a ridge where the brush lifts;
  - side ridges;
  - drag lumps stretched along the stroke;
  - ragged squeezed-out edges;
  - uneven hair lengths break a lifting brush into fingers;
  - per-lane shade variation.
- **Knife and scraper.** The knife is a rigid trowel. The scraper is a cutting edge: it ploughs a groove into the weave and
  pushes ridges up beside it.

### 1.3 Raster and vector are the same physics
The only difference is when it runs. Every pass takes `u_layerOrigin`/`u_layerScale`:
`layerPx = (docPx − origin) · scale`. Paper and brush geometry live in document space; state lives in layer space.
- **Raster:** origin 0, scale 1.
- **Vector** (`lab/media/js/vector.js`):
  - A stroke is kept as its record: brush, colours, seed, and the pen samples after the spline.
  - It is replayed in order into any layer. The page is replayed after an edit (recolour, delete). The visible region is
    replayed at screen resolution when zoomed in, so pencil grain and paint ridges stay sharp (verified: 4× zoom, same
    strokes).
  - Paste brushes run through every record in order (brush history), but only draw inside the region.
  - Watercolour replays its timing too (capped gaps). It is simulated per document cell, so the sharp zoom view is pencil
    and oil only; watercolour's sharpness comes from paper and edges.
  - Patent guard: vector output is one record per stroke, never per bristle ('605). There is no outline-band geometry
    ('745).

### 1.4 Input: fast strokes without facets, without smoothing
`lab/media/js/spline.js` passes a centripetal Catmull–Rom curve **through every real pen point**:
- nothing is averaged away;
- sharp turns stay sharp;
- only the gaps between reports are filled;
- cost: one report of lag.

The owner saw ~16 facets in a quick curve (2026-10-06). Stabilising stays a separate, optional user setting. The app's
input path needs the same (§4, M5.2).

## 2. Paper interface (from the paper session; do not edit their files)
- `jb_paperSurface(docPx)` → slopes, h, E[h²]. The hex blend is variance-preserving, centred on `u_paperHeightMean`.
- `jb_paperFluid(docPx)` / `jb_paperFluidCoarse` → absorbency, fibre direction (double angle, document space), capacity.
  `u_paperFluidTexelPx = 0` means the paper has none; the wet engine then derives sizing variation from the surface.
- The catalogue gives, per surface: toothDepthMm, compliance, sizing, absorbency, capacity, wickSpeed, anisotropy, and
  `packed: false` height PNGs (the lab packs slopes itself, the same way `pack.py` does).
- `PaperPhysical.DOC_PX_PER_MM = 20`.
- **Requested from the paper session:**
  1. a medium-tooth drawing paper (macro photo, texelPx ~0.4);
  2. cold-press watercolour;
  3. hot-press/rough.

  Until they land, the lab uses LAB-ONLY stand-ins from `lab/media/papers/make_lab_papers.py` (never shipped).

## 3. Status
| | Piece | State |
|---|---|---|
| ✅ | Lab, headless renderer, single-file bundle, phone-size startup check | built, run |
| 🔨 | Pencil (dry engine): force balance, two-layer paper, fan-out, crush, dust, directional, smear | built; owner's first try: "pretty good", fan-out added after |
| 🔨 | Spline input | built in lab; app not yet |
| 🔨 | Watercolour: flow, pinning, edges, mingle, blooms, granulation, lift, dry-brush, bead/lift pools, tilt | built; second round after the owner's references |
| 🔨 | Oil: cells, two-way trade, swap streaks, wicking, two-colour load, canvas-relative layer, bow, lumps, fingers, knife, scraper, soft shadows | built; first round |
| 🔨 | Vector replay (records, recolour, sharp zoom) | built; pencil and oil |
| 🔨 | Rect undo (only the touched area is kept), lazy textures | built |
| 🟢 | Port to the app (§4) | specced here, not started |

## 4. Port to the app (M5): sized rows for builders, Lead reviews
Rule: the shaders in `joybrush/shaders/media/` are the app's shaders. The lab's JS orchestration becomes Kotlin
in `core/media/` (pure, testable on the JVM) plus GL plumbing in `androidkit/gl/MediaLayerEngine.kt`. **Never fork the
GLSL; any change goes through the lab first and is proved by `media_render.js`.**

- **M5.1 Core twins (T2, Kotlin, `core/media/`).**
  - `Stick` and `StickGeometry`, plus `stickZ`, `contactLut` and `solveContact`, ported line by line from `stick.js`.
  - `DryStroke` (dabs as a FloatArray, 20 floats each); `WetStroke`, `wetUniforms` and `paintFromColor`; `PasteStroke`
    and `opaquePaint`; `SplineFeeder`; `VectorRecord` and replay order.
  - Tests: the numbers in §5 (contact widths and depths per pressure/tilt, spline passes through every point, dab counts
    per mm, replay determinism).
- **M5.2 Input (T1 review).** `SplineFeeder` sits in front of every engine in `JbCanvasView`, fed by
  `MotionEvent.getHistorical*`. Prove it with a sparse-sample test (`flick` sheet: straight-joined vs spline).
- **M5.3 Media layer kind (T1, Lead).**
  - A layer gains `kind = media`, stored as tiles of p0/p1/paper (+ w0/w1 while wet) in RGBA32F. Undo snapshots stay
    per-tile, like the existing `UndoLog`.
  - Save format: float tiles, in a new archive entry type, with a document version bump.
  - Export composites the media layer through `jb_media_render.frag` (lit or flat).
- **M5.4 GL engine (T1).** `MediaLayerEngine` runs the passes on tiles:
  - dabs go into a tile-set delta (additive, batched);
  - one apply/update pass per frame over the dirty tiles;
  - wet tiles are simulated while wet (substeps 2–4 per frame) and sleep when dry;
  - the paper bake is per tile.
- **M5.5 Brush format (T2).** Brush version 8: `engine: "media"`, `medium: dry|wet|paste`, plus the parameter blocks
  exactly as the lab's tables (`STICKS`, `WET_BRUSHES`, `PASTE_BRUSHES`). Every number gets a knob in
  `BrushKnobs.forBrush` (the owner's rule: holding a brush opens all its settings with a live preview).
- **M5.6 Gravity (T2).** `TYPE_GRAVITY` sensor → low-pass → `u_tilt` in document space, with a "lock to canvas" toggle;
  off when the phone lies flat.
- **M5.7 Vector layers (T1).** Records are saved in the document; replayed on edit; a sharp view replay when zoomed past 1.3×.
- **M5.8 Owner sign-off (T3).** Pencil, watercolour and oil on the Note 9 against Infinite Painter Proto, Expresii and
  Rebelle.

## 5. Numbers worth keeping (lab, 2026-10-06)
- Pencil "Proto": 5 mm woodless stick, 20° cone, tip 0.14 mm, exposed 9 mm. `PRESS.forceScale 0.085`, `gamma 1.6`,
  `toothStiffness 9`. The tilt reach default is 68° (the owner's S Pen tops out at 71°).
- Watercolour: dt 1/120 s × 4 substeps per frame; pin 0.22 mm × (0.4 + sizing); film 0.12 mm; edge drop-out ×5;
  mingle 12 cells²/s; settle 0.08/s; evaporation 0.003 mm/s (×4 at thin edges).
- Oil: opaque paint S 30/mm; a full flat brush leaves 0.6 mm; knife 1.1 mm, scrape 0.99; cell capacity =
  thick · rate · loadLen / len.

## 6. Owner feedback log (verbatim points condensed)
- **Pencil (pre-session, to other agents):**
  - Grainy, never smooth.
  - The tip is affected by the paper too.
  - The soft side is a real grainy fade.
  - Angle mostly sets size; pressure sets darkness and grain fill, with a small size boost from pillowy paper.
  - A light touch clips tooth tips; hard pressure presses the tooth down and only the deepest divots dodge (soft specks).
  - Tilted: force goes to the tip (hard tip, feathered side); near flat: no hard tip.
  - Woodless reaches about an inch.
  - Soft grades lightly smudge.
- **Pencil:** the Infinite Painter "Proto pencil" reference sheet is the bar: one brush, every mark, no sliders.
- **2026-10-06 lab, first try:**
  - White blocks layering up → fixed (a NaN from `tanh` on ANGLE).
  - "Light and steep got smaller; I want soft shading" → fixed (settle onto the side, fan-out).
  - Fast strokes are faceted (~16 segments) → spline.
  - Watercolour "synthetic" → second round: real pools, mingling, hard/soft edges, dry-brush skipping, slight darkening
    when wet. Reference images are in that message.
  - Phone tilt for water → built (lab pad; app sensor in M5.6).
- **2026-10-06 impasto references:**
  - colour variation;
  - dynamic thickness;
  - scrape off and the canvas texture takes over;
  - knife lines;
  - bristle fingers.
