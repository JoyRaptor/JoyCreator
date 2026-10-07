# Joy Brush Media Engine — plan, state and port guide (2026-10-06, updated to lab v9 2026-10-07)

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
`node joybrush/tools/media_render.js out.png "test=proto"`. The sheets:
- pencil: `proto pencil scribble ladder prokoside sidepro tiltladder`
- watercolour: `wash drop swatch mingle flick tilt runny flood flood2`
- oil: `oil roundtwo impasto dabs holes blade passes`

`node joybrush/tools/media_measure.js "<query>" "<js>"` prints numbers instead (`__probe`, `__row`, `__dark`). Tuning hooks:
`&press=`, `&mat=`, `&proto=`, `&zones=`, `&wet=key:value,…` override the tables without editing them. Add `&zoom=4&cx=..&cy=..` for a close-up, `&vector=1` for a vector replay, `&mode=1..7` for
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
| `paper` | RGBA32F | R = crush (tooth flattened by hard pencil, tooth units), G = graphite flake volume V, B = Σ V·flake reflectance | dry |
| `w0` | RGBA32F | surface water (mm), capillary saturation s, wet time, outflow | wet |
| `w1` | RGBA32F | pigment still floating: K rgb, S | wet |
| `flux` | RGBA16F | pipe outflow L R D U (the slow levelling flow; kept step to step) | wet |
| `run` | RGBA16F | running water's outflow this step, signed x, y (no memory) | wet, tilted |
| `bead` | ½-res RGBA16F ×2 | the water depth Gaussian-blurred (the running bead's field) | wet, tilted |
| bakes | RGBA16F/8 | paper height, fluid map, water-scale height at layer resolution | wet, paste |
| brush | 32×8 RGBA32F ×2 | the paste brush's cells (lanes × tip→belly): K rgb, amount / S | paste |

**Full float is required** for `p0 p1 w0 w1`. Watercolour moves pigment in thousands of tiny steps, and with half floats
increments below half an ulp are lost: deposits froze and colours drifted (seen 2026-10-06). The app must check
`EXT_color_buffer_float` (GLES 3.2 core on the Note 9) and **refuse wet media** without it; it must not silently fall back.

**Optics = Kubelka–Munk per RGB channel** over the paper colour, in `jb_media_render.frag`. Low S is a transparent glaze;
high S is an opaque body. (This is per-channel KM, which is 1931 prior art; **no** Mixbox-style latent mixing.)

**Lighting:**
- One lamp across the true relief: paper tooth − crush + paint body + water surface.
- Under paint, the paper's relief is read from the SAME baked (normalised) height the paint filled against, in the
  slope and in the soft-shadow march, so paint that fills the weave cancels it exactly. Reading the paper texture
  instead (other filtering, other scale) showed the weave through thick paint as a grid (v8–v9.1).
- Wet water glint.
- Oil gloss while open.
- Graphite sheen on burnished tooth.
- **Soft self-shadows** from thick paint: a 10-tap march toward the lamp, with a penumbra that grows with distance.

### 1.2 The three mechanisms (as built)
**Dry: pencil/graphite.** Files: `jb_stick.glsl`, `jb_dry_dab.*`, `jb_media_apply.frag`, `lab/media/js/stick.js`.
The v7 cone model regressed (owner, 2026-10-07: "B tier to D tier"): its worn flats lifted the tip, inverting the
gradient and moving the mark away from the pen. v8 replaced it:
- **Three stacked contact zones, all anchored at the pen tip**, each with its own fade:
  - **the point**: a disc of radius `tipR` → `tipMax` with pressure (`P^0.8`); a hard edge;
  - **side 1, the worn face**: reaches `side1` mm behind the tip, with a strong gradient (`(1−u)^1.2`);
  - **side 2, the whole side**: reaches `side2` mm, feathering out; near flat it becomes an even swath with soft ends.
- **Tilt chooses the zones, not just the size.** With `t = tilt / reach` (reach = the "Side at" slider, default 68°):
  below `ZONES.side1From` (0.5) the pencil draws with its point only, because people rarely hold a pen upright and a
  slightly leaning pencil still draws a crisp line. Side 1 comes in over 0.5→0.78, side 2 over 0.7→0.98, and the even
  plateau from 0.88. Firm pressure keeps some gradient even when flat.
- **Squeeze** `D = tooth · depth · P^gamma / (1 + sideEase·(0.4·s1 + s2))`: the same force spread over a wider face
  presses each point less.
- **Two-layer paper** (stiff tooth on a soft pad): a 256-entry table (sqrt-spaced) maps squeeze → tooth level `a`.
  The tooth's depth is reshaped into plateaus, `(1−h)^2.2`, so tops catch together.
- **Deposit has two parts:**
  - **dusting**: ∝ `a`, even, with the deepest pits shielded (they show as soft specks); a light back-and-forth
    builds an even tone;
  - **piling**: Archard work `T·(e/T)^2.2·slide`, only from mid pressure (smoothstep 0.12→0.75). It is directional
    (faces meeting the stroke catch more) and clumped by the paper's coarse height: firm strokes jam dark dust
    against the tooth.
- **Lead softening scales with the local squeeze** (`σ = √(σh² + (leadSoft·a)²)`). A constant one gave every touched
  pixel the same deposit, which is how v7 lost its fade.
- **Graphite is flakes in the paper state** (G volume, B reflectance sum). Cover is `1 − exp(−V / (0.18·cap))`, so a
  light layer greys and only piling goes to black.
- **Smear**: soft grades push already-laid flakes downstream a little (soft dusting).
- **Crush**: hard pressure flattens and burnishes the tooth, and the crush is stored.

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
- **Wetness** (Expresii's drop and napkin; owner, 2026-10-07): five levels, dry · damp · wet · loaded · runny, tapped
  with Drier/Wetter and shown only for watercolour brushes. Each sets the brush's water load and flow. Paint
  strength per mm of water goes as `(0.8/load)^0.6`: blotted paint skips richly over the tooth, runny paint floods
  paler. Picking the Dry brush starts it at dry.
- **All-round brush**: a pointed round from a hairline (0.06 mm) to a 4.5 mm belly on pressure^1.6 and tilt; the
  hairs swing round over 1.5 mm of travel, so a dab's jitter makes a round puddle.
- **Running water and drips** (owner: "runny with dripping down the page"). The paper's tilt is `u_slope` = sin(angle)
  pointing downhill. The slow levelling flow alone is ~1000× too slow to ever run (it is overdamped by design so
  edges stay put), so a tilted paper adds a second flow:
  - **Kinematic, no memory.** Running water is stored in `run`, apart from the pipe flow's momentum: momentum on
    an incline grows roll waves (horizontal ridges, seen).
  - **The bead decides.** The water depth is Gaussian-blurred at half resolution (`jb_wet_bead.frag`). Speed ∝
    `((bead − tooth hold)/runMm)²`. Only water standing above the tooth runs (hold = ½ the tooth depth), so rough
    paper holds a wash that a smooth one lets go. Whether a bead breaks a dry edge is judged on the bead as a
    whole, so drips come away bead-wide rather than as a one-cell comb.
  - **A held edge turns the water along itself.** Near an edge, the part of the flow pushing out through an edge that
    holds is removed, so a slanted edge drains to its lowest point and drips there. Without this, the edge gave way
    along its whole length as a straight-sided curtain.
  - **Fingers: who gets to drip.** Along a bead's edge, a point may let go only:
    - where the bead stands highest for the paper's hold there, within ±4 mm along the edge;
    - or where it is already pouring;
    - and never while a neighbour within that span pours more (1 mm beyond the edge, margin 0.01 mm).

    The drip then drains the bead beside it and the rest of the edge holds. Without this rule, a deep bead let go
    everywhere and slid down as one sheet with ruler-straight sides. A drained root also made its neighbour the new
    high point, which unzipped the edge into a wide curtain (both seen on the first live try).
  - **Only edges facing downhill let go on a slope.** A stream's sides and its upper edge hold, so streams do not
    spread sideways.
  - **Surface tension, lightly** (`runCohere` 0.3): water runs from where the bead stands high to where it drained,
    feeding a drip from the bead beside it.
  - **Water that reaches the page edge runs off.** The simulated area grows downhill up to 4 cm past the paint and
    1 cm sideways.
  - **The paper steers drips sideways** (lateral only, deep beads only): drips wander along the grain. Steering
    along the slope trapped water in valleys as spots, and steering thin water sorted a wash into lace (both seen).
  - **A film stays behind** (0.012 mm): a drip leaves a wet trail and never drains a spot bare.
  - **Uneven sizing**: mm-scale noise in how hard an edge holds.
  - **Any blur must be a Gaussian.** A slope taken from a ring of taps aliases and sorts water into stripes (seen):
    its spectrum has negative lobes.
- **Tilt sources**: the lab's pad (up to upright) or **Phone tilt** (the gravity sensor, relative to how the phone was
  held when switched on, with a small dead zone).

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
- **Strokes start and end like a real brush** (owner, 2026-10-07: "a perfectly sharp cutoff is a dead giveaway"):
  - A light dab is a dot (a round) or the pressed chisel (a flat). The tail grows only as the brush travels.
  - Splayed hairs come in after 2–6 mm of travel, never on touchdown.
  - The direction swings round over 1.5 mm of travel, not per pen report, so jitter cannot fan a dab into straight lines.
  - A round has a round head; a flat has a shallow, uneven arc front.
  - Every edge ramps over the step's slide, so nothing is cut straight.
- **All-round oil brush**: a hairline tip to a 6 mm belly (pressure^1.6, tilt), thin paint for detail, thick for impasto.
- **Holes:** a new stroke reaches down into dips in old paint (the reach gives with pressure and load), so it no
  longer skips over them.
- **Belly colour modes** (tap to cycle): off · manual · darker · lighter · warmer · cooler · last colour · shift.
  The loading tray puts the second colour in the belly on a round, side to side on a flat.
- **Palette knife 1 is the v6/v7 trowel, back by request** (owner, 2026-10-07: "the old one had excellent
  texture", easier to control). It follows the stroke, its paint goes through the hair-brush trade (rigid,
  scrape 0.99, ridge, bow, lumps), and it keeps its own step geometry (`trowel: true`). A rigid tool presses paint
  straight into the weave (fast fill); only the layer above the peaks builds up step by step.
- **Edge modes for Palette knife 2 and the scraper** (owner, 2026-10-07: "set an option to toggle these different
  behaviors so I can figure out what works"), an Edge button that cycles, remembered per tool and stored in the
  stroke record:
  - **pen angle** (default): the edge lies along the pen's lean; tilt lays it down from the point. The owner preferred
    this look for the scraper over the squeegee;
  - **across stroke**: a squeegee centred on the pen, turning with the stroke; lean widens it;
  - **along stroke**: the edge trails the pen point along the path, a groove that follows the line.
- **Palette knife 2 is the v8 rigid blade (in pen-angle mode, held like the pen):**
  - The edge lies along the pen's lean (azimuth), whatever the direction of travel.
  - **Pressure lowers the blade:** height above the canvas peaks = `Hmax·(1−P)^1.5`. A light touch shaves the peaks;
    full pressure reaches the canvas and presses into the weave (`0.6·tooth·P²`).
  - **Tilt lays the edge down:** upright = the point, flat = the whole edge (`tan(75°·(1−t)³)`).
  - A bead of paint rides ahead of the blade. The knife lays and spreads paint with lumps; the scraper picks up
    10× faster and leaves a film (0.002 mm), so the canvas comes back.

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

  They landed. The lab now uses only the catalogue's own papers (the bundle ships `drawing_tooth bristol_tooth
  cold_press hot_press rough_press canvas_linen cardboard`); the lab stand-ins were deleted.

## 3. Status
| | Piece | State |
|---|---|---|
| ✅ | Lab, headless renderer, measuring tool, single-file bundle, phone-size startup check | built, run |
| 🔨 | Pencil: stacked zones, two-layer paper, dust + piling, flakes, smear, crush | v8 rebuilt after the v7 regression; awaiting the owner |
| 🔨 | Spline input | built in lab; app not yet |
| 🔨 | Watercolour: flow, pinning, edges, mingle, blooms, granulation, lift, wetness levels, all-round, running water and drips, phone tilt | v9; owner: "B tier, keep going" before drips |
| 🔨 | Oil: cells, two-way trade, travel-grown tails, round/flat ends, all-round, belly modes, holes fix, rigid knife and scraper, soft shadows | v8–v9; owner: "approaching A tier" |
| 🔲 | Watery oil (thinned paint pulling pigment into drips; the owner's two-stage brush) | next |
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
  - while the paper is tilted: the three bead passes (½ res) before each flux step, the `run` target beside `flux`,
    and wet tiles spreading downhill into neighbours only as water reaches them (the lab grows a rectangle, capped
    at 4 cm past the painted area; the app tracks tiles);
  - the paper bake is per tile.
- **M5.5 Brush format (T2).** Brush version 8: `engine: "media"`, `medium: dry|wet|paste`, plus the parameter blocks
  exactly as the lab's tables (`STICKS`, `ZONES`, `PRESS`, `WET_BRUSHES`, `WET`, `WETNESS`, `PASTE_BRUSHES`). Names
  repeat across media (an all-round brush in watercolour and in oil), so a brush is identified by medium + name.
  The wetness level and belly mode are brush state, tapped on the canvas bar: wetness shows only for wet brushes,
  belly only for hair paste brushes. Every number gets a knob in
  `BrushKnobs.forBrush` (the owner's rule: holding a brush opens all its settings with a live preview).
- **M5.6 Gravity (T2).** A "Phone tilt" switch. `TYPE_GRAVITY` → the in-plane component, turned by the display
  rotation into document space → minus the reading when switched on (drawing at a comfortable angle stays level)
  → low-pass (0.25 per frame) → dead zone 0.04 → `u_slope` (sin of the angle, ≤ 1). The canvas view's own rotation
  must be applied too (the lab view never rotates).
- **M5.7 Vector layers (T1).** Records are saved in the document; replayed on edit; a sharp view replay when zoomed past 1.3×.
- **M5.8 Owner sign-off (T3).** Pencil, watercolour and oil on the Note 9 against Infinite Painter Proto, Expresii and
  Rebelle.

## 5. Numbers worth keeping (lab v9, 2026-10-07)
- **Pencil "Proto"**: `tipR 0.25 → tipMax 1.0, side1 5.5, side2 16 mm`; zones 0.5→0.78 / 0.7→0.98, even from 0.88;
  `PRESS.depth 1.9, gamma 1.5`; transferExp 2.2, piling from P 0.12→0.75; tilt reach 68° (the owner's S Pen tops out
  at 71°).
  - Measured fade across a side swath at t 0.85: ≈7 / 20 / 43 / 68 / 79 % dark at P 0.15 / 0.3 / 0.5 / 0.75 / 1,
    falling to 0 at the far edge.
- **Watercolour**: dt 1/120 s × 4 substeps per frame; pin 0.22 mm × (0.4 + sizing); film 0.12 mm; edge drop-out ×5;
  mingle 12 cells²/s; settle 0.08/s; evaporation 0.003 mm/s (×4 at thin edges).
  - Depths a stroke leaves on cold press: wet 0.17, loaded 0.22, runny 0.26 mm.
  - Running water: `runMmPerS 8`, `runMm 0.3`, hold ½ tooth, film 0.012, cohere 0.3, steer 4 (sideways), bead 0.6 mm
    (blur σ 0.3 mm); sizing noise ±45 % at 2.4 / 0.9 mm; drip gap 4 mm; pour margin 0.01 mm. The step caps speed at 0.45 cell per substep (≈2.7 mm per sim second).
  - At 45° for 8 s: dry, damp and wet hold; loaded drips twice; runny drips four times. At 15° nothing drips and
    the bead pools at the bottom.
- **Oil**: opaque paint S 30/mm; a full flat brush leaves 0.6 mm; cell capacity = thick · rate · loadLen / len.
  - Knife: blade 22 × 9 mm, Hmax 0.9, paint 1.1 mm. Scraper: 14 × 0.7 mm, Hmax 1.6, film 0.002 mm, pick-up ×10.

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
- **2026-10-07, after v6 (wetness, oil ends, blades):**
  - Expresii's water drop and napkin, dry brush to runny "with dripping down the page", shown only for brushes that
    use them → v9 wetness levels and running water.
  - Belly colour gets tedious to pick: manual plus auto modes, tap to cycle → v9.
  - Holes in paint that new strokes ignore → v8 reach fix.
  - Pencil light end must be a real fade (soft, feathery), not 100%-black dots; light = even dusting with shielded
    specks, firm = dark piled clumps along the travel → v8.
  - Scraper: pressure depth, wide edge scrapes, angled from tilt, oriented by the pen not the path, scrape the
    peaks → v8 blades.
  - Thick paint with wateriness pulling pigment into runny drips (a two-stage, loaded-then-watery brush) → next.
- **2026-10-07, v7 regression:** "B tier to D tier". The hard edge to soft fade over several grades was gone; marks
  jumped away from the tip at shallow angles; tilt only changed size → v8 stacked zones, softening by squeeze.
  Watercolour B tier ("keep going"), oil approaching A; the goal is S tier for all three.
- **2026-10-07, oil:** light dabs must be dots that only lengthen with travel; splayed hairs come in late; no
  straight start/end edges (shallow arc, S, blobby end); the blending brush splinters too early at light pressure
  (its slight wet look is liked) → v8.
- **2026-10-07, after v9.1:** pencils "A+" (on the laptop they look big: the lab's 100 % is the phone's true size, so a
  desktop screen shows about 4× enlarged), watercolour "S-tier", oils "much better overall".
  - The Wacom pen stopped drawing (mouse fine) → v9.2: a refused pointer capture had dropped every pen stroke.
  - "I liked the previous palette knife better … excellent texture" → v9.2: Palette knife 1 (v6/v7 trowel) and
    Palette knife 2 (v8 blade). The thick-paint weave grid fixed at the same time.
  - The new knife is "a little bit unwieldy" and the scraper "unintuitive". The owner preferred the pen-angle
    scraper's look to the squeegee and asked for toggles → v9.3 Edge modes.
