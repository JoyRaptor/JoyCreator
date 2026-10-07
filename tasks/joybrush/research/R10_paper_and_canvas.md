# R10 — Paper, background and canvas texture: research, design and build plan

**Author:** the paper/texture specialist session (Claude), 2026-10-01. Started from `HANDOFF_paper_texture.md`.
**Status:** SPECCED (2026-10-01). Rows JB-9.01–9.11 in `specs/`, dispatched in `../PAPER_DISPATCH.md` (Codex · OpenCode · Claude · Lead).
**Legend:** [S] = a cited source says so · [C] = checked in our code or on the PC today · [I] = my engineering judgement.

---

## 0. The short version

- **What was wrong [C].** The paper the pencil and Sable feel is ONE blocky 256-px noise picture (`cloud_fine_256.png`,
  square value noise) repeated every ~43 document px. The eye finds the grid at once (`R10_img/repeat_test.jpg`, top strip).
  The paper you SEE is only a flat colour (`glClearColor`); a drawing with a paper texture is refused at open.
- **What the best apps do [S].** Rebelle is the clear realism leader: scanned colour papers at 1:1 physical scale,
  a height map that decides where paint lands, and live lighting of the relief. ArtRage lights canvas and paint with one
  light. Corel Painter is the only app with **directional grain** (a toggle; its maths is unpublished). Infinite Painter
  puts a **Paper swatch at the bottom of the Layers list** with separate *Depth* (how much it bites) and *Opacity* (how
  much it shows). Everyone else treats paper as a flat image or a per-brush mask.
- **The design (§3–§6).** Paper becomes ONE document setting that every grained brush feels. Each paper has two parts:
  a **look** (colour picture, re-tintable live) and a **surface** (height, plus its slopes, i.e. a normal map, derived automatically).
  It never repeats visibly (**hex-tiling**, Mikkelsen 2022). It stays right at every zoom (mip-aware maths plus a detail octave
  when magnified). It deposits by **stroke direction**: dry media catch the faces of bumps that face the brush, and wet media pool in the valleys.
- **Proven on the PC today [C]:** the repeat is gone with hex-tiling (`repeat_test.jpg`, bottom strip). The directional
  deposit works: the same dry stroke dragged left vs right catches opposite faces (`dir_test.jpg`, middle vs right).
  Generated papers (pulp in three grades, crumpled) read as paper (`pulp_artisan_lit.jpg`, `crumpled_lit.jpg`).
- **Patents:** no patent found on direction-dependent grain or normal-mapped paper (prior art: Murakami et al. 2005,
  Rudolf et al. 2005). Stay off Autodesk US 8,081,187 (two noise maps + smoothstep, active to ~2029-09); our route
  (one authored height picture, hex-tiled, linear threshold) is DESIGNED to differ. That is an engineering choice, not a legal
  opinion: a professional freedom-to-operate check is required before commercial ship.

---

## 1. Owner rulings recorded in this round (2026-10-01)

| # | Ruling (owner's words, condensed) |
|---|---|
| P1 | Papers are quickly swappable, show behind the drawing, and export with it or are left out (transparent). |
| P2 | A paper has two parts: a **convincing analogue look** (no visible seam or loop, good zoomed in AND out), and a **surface** (bump/normal map: weave, grain, tooth). |
| P3 | The surface is **directional**: raised points take ink FROM the side the paint comes from. Dry paint scraped right-to-left loads the right faces of the bumps and leaves the left faces clean. Dry paint rides the surface, and wet paint pools in the cracks. |
| P4 | The paper is **universal**: every brush respects it by default unless the brush brings its own texture. A brush's own height texture gets a normal map derived automatically, so imported Photoshop/Procreate brushes behave BETTER here than in their own apps. |
| P5 | Changing the paper applies to every brush unless overridden. Vector (ink) strokes re-render with the current paper. Raster strokes already laid down cannot be recomputed. |
| P6 | Every background has a default surface (chalkboard = convincing chalk look + a fine, even, rough surface). |
| P7 | **Placement: a paper swatch at the very bottom of the Layers panel** (Infinite Painter style, not a gear like Concepts). It is visible though out of the way, and it sits physically BELOW every layer, which gives the right mental map. |
| P8 | Backgrounds at launch: AMOLED black · dusty black chalkboard · dusty green chalkboard (a different dust) · tan construction paper with variance · blueprint · aged parchment · ancient papyrus · off-white · rice paper · several popular canvases · user colour · transparent/none. Looks may tint to any colour, so "user colour" is always live. |
| P9 | Surfaces: pressed pulp in grades (chunky handmade > artisan > factory refined) · canvas (several weaves) · cement · fabric · silk · crumpled paper (2). |
| P11 | **Zoom:** Joy Brush is a lot of zoom (0.05×–64× on one pixel density), not truly infinite, so detail fades in as you zoom in. Mischief-style: a finer, offset copy fading in so gradually it never reads as a loop (JB-9.06 Decision 4). |
| P12 | **Rice paper:** filaments and fibre clumps in a thin translucent sheet. FLAT: whiter areas are more OPAQUE, not raised (layered cobweb). Cool white or warm cream. Fibres in the look, near-smooth surface. |
| P13 | **Thai sugarcane pulp:** short straw fibres plus brown and grey-brown chunks of unbleached plant matter. |
| P14 | CC0 downloads approved. Codex (image generation, 3D) makes candidate looks and 3D height renders. The specialist keeps taste and the final say. |
| P10 | A normal map can be faked by offsetting colour channels. Answer: that offset IS a slope measure. We do the careful version (Scharr filter on the height map, all directions), as NVIDIA's Photoshop plugin did. |

---

## 2. What exists today [C] (do not reinvent)

| Piece | Where | Keep / change |
|---|---|---|
| Height-threshold deposit `clamp((h − (1 − level))/edge + 0.5)`, tilt-aimed level plane, radial dome | `shaders/jb_grain.glsl`, CPU twin `core/grain/GrainMath.kt` | **Keep.** Directional term and wet term are added to `h` before the threshold (§5). |
| Paper sampled in document space, never moves (`v_dabCentre + v_offset`, R45) | `shaders/jb_grain_sample.glsl` | **Keep the space; replace the single `texture()` read with a hex-tiled read** (§4). |
| Pitch = 64 / scale doc px, fixed physical size (R38) | `GrainMath.GRAIN_UNIT_PX` | Keep the idea; papers get their own physical size (§4.3). |
| Texture loader, `GL_REPEAT`, mipmaps, 1×1 placeholder, never-white-on-failure (R45.4) | `androidkit/gl/GrainTextures.kt` | Keep; extend to RGBA surface textures. |
| Sable's paper tooth + stray-hair stutter read the same paper | `jb_tuft.frag`, `TuftStroke.shading` | Moves to the document paper with everything else. |
| Document `Paper(color, textureId, textureScale, includeInExport)` | `core/doc/DocModel.kt` | Extend (§6.1). Lift the `JbCanvasView.load` refusal. |
| Include-paper export, paper colour pill | JB-2.13b (wired) | Export gains look + optional relief lighting (§6.4). |
| Fixed `edge` with no `fwidth`: will shimmer zoomed out | `jb_grain.glsl:22-26` | **Fix** (§4.4). |
| Generator: periodic value noise | `core/grain/CloudNoise.kt` | Superseded for paper by authored surfaces (§7); stays for tip clouds. |

---

## 3. The paper model

A **paper** = `look` + `surface` + numbers.

- **Look** (what you see): a tileable colour picture stored as *detail around a base colour*, so any look can be re-tinted live
  (the owner's "user colour is always live"). Shown behind every layer.
- **Surface** (what brushes feel): one RGBA8 tileable texture with mipmaps:
  - **R, G** = slopes dh/dx, dh/dy (the "normal map", derived with a Scharr filter, never hand-made),
  - **B** = height,
  - **A** = height² (so zoomed-out mips still know how ROUGH the surface was, not just its average; §4.4).
  Slopes are stored instead of normals because slopes add and filter linearly. They blend correctly across hex tiles and across
  octaves, and a normal is `normalize(−dx, −dy, 1)` when lighting needs one [S: Mikkelsen 2020/2022].
- **Numbers per paper:** physical size (doc px per texture repeat), `rotatable` (false for weaves, laid lines and papyrus, whose
  direction is part of the look), hex-tile size, relief strength, default tint.
- **Numbers per document (the Paper sheet):** background (look) · surface · tint/colour · **Show** (how visible the look and
  relief are) · **Bite** (how strongly brushes feel the surface; Infinite Painter's Depth vs Opacity split) · **Scale** ·
  **Light** (relief lighting on/off) · **Include in export** / transparent.
- **Numbers per brush:** **Paper influence** (0 = ignores the paper, as ink does today; 1 = full), **Directional** (how much
  stroke direction matters), **Wet pooling** (dry rides peaks → wet fills valleys), and an optional **own texture** override.
  All three are `BrushKnobs`, so holding the brush shows them with the live preview (handoff §1).

## 4. Looking right: no repeat, every zoom

### 4.1 No visible repeat: hex-tiling [S: Mikkelsen 2022 JCGT; Heitz & Neyret 2018]
Three reads of the same texture on a hexagonal lattice, each hex with a random offset (and a random rotation only when the
paper is `rotatable`), blended with weights raised to a power so contrast survives. It works on slope maps because slopes
blend linearly, and Mikkelsen designed it for exactly that. Cost: 3 reads instead of 1. The phone on the desk is the **US Note 9
(SM-N960U, Snapdragon 845 / Adreno 630)** [C], which has more texture headroom than the Mali version. Fallback if
measurement says no: Quilez's 2-read variant.
- A canvas weave is SUPPOSED to repeat (thread after thread). What must not repeat are its flaws (slubs, knots, priming).
  So weaves are hex-tiled with rotation off and a tile size of many threads.

### 4.2 Zoomed out (0.05×): no shimmer, no grey mush
- Mipmaps average the look correctly.
- The threshold `edge` gets a floor of `fwidth(h)`, so grain smaller than a pixel turns into tone instead of sparkle (the owner's
  brief §2.3; it is missing today).
- When the grain period drops below ~2 screen px, coverage switches to the expected value from height mean + variance
  (the B and A channels): `coverage = 1 − Φ((threshold − mean)/σ)`. A pencil zoomed out then shows the right grey, not blotches.

### 4.3 Zoomed in (to 64×): stays crisp
- Paper is in physical units (doc px). At 1× on the Note 9 one doc px is ~0.05 mm, so real fibres are hair-thin and 20–60 px long.
  What reads at 1× is the paper's cloudiness and tooth [I].
- Past the texture's own resolution a **detail octave** fades in (the same surface at a much finer scale, low strength, its
  slopes added), the way real paper shows fibres under a magnifier [brief §2.3; prideout zoomable texturing].
- **Deep-zoom precision:** the per-octave origin is reduced modulo the texture period on the CPU in double precision and passed
  as a uniform, so far from the origin the paper does not swim [S: prideout].

### 4.4 Cost control
The paper behind the drawing is rendered into a cached screen layer only when the view pans, zooms or rotates, not every
frame. Brushes read the surface only inside their own footprint [I].

## 5. Feeling right: directional dry deposit, wet pooling

Added to the existing threshold, per pixel of each dab (the shader and its CPU twin, in the same commit):

```
g    = surface slopes at this doc point (hex-tiled, R,G)
v    = unit travel direction of the stroke at this dab
face = dot(g, v) / sqrt(1 + |g|²)          // > 0 on faces that meet the oncoming brush
hDry = h + Directional · face              // dry media: facing slopes catch first
hWet = h + WetPool · (blur(h) − h)         // wet media: valleys/cavities collect (blur = a coarser mip)
coverage = jb_heightCoverage(mix(hDry, hWet, wetness), level, max(edge, fwidth(h)))
```

- Sign check (owner's P3): dragging right-to-left, `v = (−1, 0)`, `face = −∂h/∂x`, which is positive on faces that fall toward +x, i.e. the
  RIGHT faces load. Verified in `R10_img/dir_test.jpg` [C].
- Sources: Rudolf, Mould & Neufeld 2005 (wax crayon: slopes rising into the stroke catch more wax); Murakami, Tsuruno
  & Genda 2005/2006 (pastel/charcoal deposit looks like the paper lit from the stroke's direction); Zimmer's original Painter
  patent (expired) for the threshold itself.
- **Travel direction per dab.** Today the dab instance has no travel direction, and per-dab data widening is the open JB-6.03 Q1
  debate. Step 1 uses the per-batch direction (one frame of movement; batches are small). Step 2 widens the instance record.
  The tuft engine already knows its travel direction in stroke space.
- **A brush's own texture** (imports: ABR `patt`, Procreate `Grain.png`, Krita patterns) is a height map. On load it gets the same
  Scharr slope derivation, so it gains directional behaviour too (owner's P4). This is part of JB-1.05d.

## 6. The product pieces

### 6.1 Document format
`Paper` gains `lookId`, `surfaceId`, `tint`, `show`, `bite`, `light`, keeping `color` (flat colour = no look) and `includeInExport`.
That is a new serialised field, so per R3/R31 it is a DOC_VERSION bump (3 → 4) with `wordsNeedingVersion`-style lowest-version writing,
so a plain-colour drawing still writes at version 3. Built-in papers are referenced by id. A user's imported paper is stored inside the file.

### 6.2 On screen
A paper pass drawn under the layers (look × tint, relief lit from a fixed upper-left light when Light is on, scaled by Show).
It replaces the flat `glClearColor` in `GlPaintEngine.draw/drawComposited`.

### 6.3 The Paper sheet (owner P7)
A swatch row at the bottom of the Layers panel, always below the layers. Tap it to open: a row of round previews for **Background**,
a row for **Surface** (the Concepts screenshot's layout), a colour well for Tint/Colour, sliders Show · Bite · Scale, Light on/off,
Include-in-export. Every control has a hover label (`ChromeKit.label`). Swapping is live, and one swap is one undo.

### 6.4 Export
Include paper → the look (and relief lighting if on) is rendered into the PNG by the same maths as the screen. Off →
transparent. The CPU renderer (`RegionRenderer`) gets a CPU twin of the paper sampler (deterministic hash, golden-tested
against the shader), or export runs the GPU path (R38's FxExecutor seam). Builder's choice, with a golden either way.

### 6.5 Swapping paper after painting (P5)
Raster strokes keep the grain they were laid with; every app we surveyed does the same (ArtRage, Painter). Ink layers (JB-5.01 replay) re-render
with the current paper. The look and relief behind the drawing always update.

## 7. Where the pictures come from

| Source | Good for | Cost |
|---|---|---|
| **Our generator** (`joybrush/tools/paper/papergen.py`, deterministic, tileable, no licence questions) [C] | Pulp grades, crumpled, chalk grit, cement, canvas weaves, silk/fabric weaves | Taste iteration; weaves still too regular today |
| **CC0 scans** (ambientCG, Poly Haven: public-domain, ship height maps) | Papyrus, parchment, rice paper, cement, fabric: pictorial looks where generated noise reads as CG | Each download needs the owner's OK (filename, source, size) |
| **The owner's own scans** (≥1200 dpi, flatbed, raking light for height) | Signature papers that are uniquely ours | The owner's time |

Recommendation [I]: generator first for every surface, CC0 for looks the generator cannot make convincing, and the owner's
scans for the 2–3 papers the owner cares most about. Every look is tileable and hex-tiled, so even a scan never shows its repeat.

**Launch list mapped to parts (P8/P9):**

| Background (look) | Default surface |
|---|---|
| AMOLED black (true #000) | Smooth (bite only, no relief light, so the black stays black) |
| Dusty black chalkboard | Chalk grit (fine, even, rough) + dust smears in the look |
| Dusty green chalkboard | Chalk grit + a different dust pattern |
| Tan construction paper | Coarse pulp + darker fibre flecks in the look |
| Blueprint | Factory pulp; cyan with faint mottling (a blueprint grid comes from Guides, JB-2.12) |
| Aged parchment | Smooth skin; mottled, veined look |
| Ancient papyrus | Crossed strips of plant fibre (rotation off) |
| Off-white | Artisan pulp |
| Rice paper | Long translucent fibres in look and surface |
| Canvas: fine linen · cotton duck · rough jute | Three weaves (rotation off) |
| User colour / Transparent | Any surface |

Surfaces also selectable alone: pulp ×3, canvas ×3, cement, fabric, silk, crumpled ×2, chalk grit, papyrus, smooth.

## 8. Build phases

| Phase | What the owner will see | Main files |
|---|---|---|
| **A. Paper Lab pilot (PC/phone web page)** | Pick papers, pinch-zoom 0.05×–64×, relief light, draw dry and wet strokes in any direction, old-vs-new repeat. Judge looks before any engine work. | `tools/paperlab/` (new; shares `jb_grain*.glsl`) |
| **B. Quick win: pencil & Sable stop showing the grid** | Same brushes, a better paper underneath, no lattice | `jb_grain_sample.glsl` (hex read), new surface asset, `GrainTextures` RGBA, `GrainMath` twin + tests |
| **C. Document paper, seen and swappable** | Paper swatch under the layers, Background/Surface rows, visible textured paper, export with or without | `DocModel.Paper` + version, `GlPaintEngine` paper pass, `JbCanvasView` refusal lifted, Layers panel UI, export twin |
| **D. Directional + wet + Paper influence knobs** | Dry brushes scrape directionally; wet ones pool; per-brush sliders with live preview | `jb_grain.glsl`, `jb_dab.frag`, `jb_tuft.frag`, `BrushKnobs`, `BrushPreset`, per-dab direction |
| **E. Zoom quality** | No shimmer at 0.05×; fibres appear at 16×+ | `fwidth` floor, moment coverage, detail octave, double-precision origin |
| **F. Imported brush textures get slopes** | ABR/Procreate/Krita grains draw, with direction | JB-1.05d (spec file does not exist yet: write it) |
| **G. Full launch library** | All of §7's table | generator + CC0/owner scans, catalogue file |

## 9. Who builds what (proposal; the owner decides)

The guiding rule [I]: **taste and visual judgement stay with the specialist; large precise engine plumbing goes to the strongest
coding harness; small, fully specified, test-checkable pieces go to the free agents.** Every row gets a spec first (my job),
and the Joy Brush Lead is consulted before anything touches its hot files (`GlPaintEngine.kt`, `JbCanvasView.kt`,
`JoyBrushActivity.kt`, `shaders/*`).

| Work | Who | Why |
|---|---|---|
| Specs for every row below; this plan; reviews of all landed work | **Claude (paper specialist)** | Holds the whole design |
| A. Paper Lab pilot | **Claude** | Iterating looks is taste work |
| Generator + look tuning, the launch library, CC0 picks | **Claude** | Taste work; needs eyes on every picture |
| B. Hex-tiled paper read in `jb_grain_sample.glsl` + `GrainMath` twin | **Claude, with the Lead's OK** (or the Lead) | Small, shader-hot, touches Sable |
| C. Engine: paper pass, cache, doc version bump, lift refusal, export twin | **Codex (Sol 6.1)** | Large, cross-file, precision-heavy GL + format work |
| C. Paper sheet UI at the bottom of Layers | **Joy Brush Lead** | Owns all Joy Brush UI and the phone |
| D. Directional/wet maths in shaders + per-dab travel direction | **Codex** (maths spec'd and sign-checked by Claude) | Instance-layout change across engine and both shaders |
| D. `BrushKnobs`/`BrushPreset` fields, validation, JSON version words, tests | **OpenCode free agents** | Pure core, fully specified, test-checkable |
| E. Zoom quality | **Codex** | Precision and filtering subtleties |
| F. Scharr slope derivation (CPU, core) + golden tests; importer hook | **OpenCode free agents** | Pure function, easy to test |
| Catalogue file (paper ids, sizes, flags) + its validator | **OpenCode free agents** | Data + a schema |

## 10. Open questions for the owner (1, 2 and 4 answered 2026-10-01: P11, P14, PAPER_DISPATCH.md)

1. **Zoom and grain size.** On an endless canvas, if you zoom to 16× and draw tiny details, should the paper's tooth stay at its
   real physical size (tiny strokes ride over big bumps, like drawing under a magnifier) or shrink with the zoom you drew at?
   My recommendation: **real size**, with the fine-fibre detail that appears when zoomed in. A per-brush "grain follows zoom" switch
   could come later if you miss it.
2. **May I download CC0 (public-domain) paper/fabric/cement scans** from ambientCG / Poly Haven to compare against generated
   ones? I will list each file first.
3. **Your own scans:** are there 2–3 real papers you would like scanned as Joy Brush signatures?
4. The §9 split.

## 11. Sources

Rebelle papers/manual: escapemotions.com/products/rebelle/papers, …/manual/8/interface/panel-visual-settings/ · ArtRage canvas:
artrage.com/manuals/the-canvas/ · Corel Painter grain direction: product.corel.com/help/Painter/540111162/…Adjusting-grain-direction-and-behaviour ·
Infinite Painter layers: docs.infinitestudio.art/painter/layers · Mikkelsen 2022 hex-tiling: jcgt.org/published/0011/03/05/ ·
Heitz & Neyret 2018: doi.org/10.1145/3233304 · Burley 2019: jcgt.org/published/0008/04/02/ · Quilez: iquilezles.org/articles/texturerepetition/ ·
prideout zoomable texturing: prideout.net/zoomable-texturing/ · LEAN mapping: userpages.cs.umbc.edu/olano/papers/lean/ ·
Rudolf, Mould & Neufeld 2005: people.scs.carleton.ca/~mould/papers/crayon-cgf.pdf · Murakami et al.: doi.org/10.1007/s00371-006-0021-7 ·
Blending normal maps: blog.selfshadow.com/publications/blending-in-detail · Patents: US 8,081,187 (Autodesk), US 8,296,668 (Adobe, lapsed),
US 5,347,620 (Zimmer, expired), US 9,030,464 (Microsoft, to 2031), US 12,001,656 (Corel, to 2040): patents.google.com.
Earlier in-repo research: R1 (Rebelle), R2 (Expresii), R3 (Krita/MyPaint), R4 (Photoshop/Procreate), R5 (Concepts/Infinite Painter), R8 (patents), the owner's
`natural-media-shader-research.md` §2.3/§7 (kept outside the repo, in Downloads).

## 12. Audit of round 1 (2026-10-01): rulings

| Finding | Ruling |
|---|---|
| Dispatch deadlock ("build it first" not in the specs; "same time" false) | ✅ Agreed. Dispatch v2 is serial by gates, and no row builds another's files |
| Several Gradles vs ~1 GB free | ✅ One shared build lock + `--no-daemon` (dispatch house rule 1) |
| Candidates unpushable (ignored folder vs worktree-only) | ✅ Candidates go straight to the main folder's ignored `candidates/`; the one exception to worktree-only |
| JB-9.07 to Codex breaks the Lead's app-file chain | ✅ JB-9.07 is the Lead's only |
| LEAD_DESK instructions overwritten | ✅ Repaired from history; rule: append only |
| `detailStrength` in the shipped catalogue | ✅ My error; deleted |
| JB-9.09 count 1339 < parent 1346 | ⚠️ Misreported: its worktree XML holds 1357/0 (90 suites). The tree is fine; the report was wrong |
| Slope zero decodes as +r/255 | ✅ Fixed in JB-9.03b: snorm-style 127 = 0 |
| Float twin drift; dy unpinned; CPU re-quantises; `byteOf` truncates; Float lattice | ✅ All in JB-9.03b |
| No seed for the detail octave | ✅ `seed` parameter (JB-9.03b), used by JB-9.06 |
| `highp float` missing; slope work for height-only reads; Sable reads paper it ignores | ✅ JB-9.03b |
| Premultiplied upload risk | ✅ Explicit byte upload with a pure, tested packer (JB-9.03b) |
| Folder by name prefix; magic scale 32; dead `PAPER_TOOTH_SCALE`; pencil file lists ignored fields | ✅ JB-9.03b |
| JB-9.09 ships dead sliders | ✅ Hidden until JB-9.08 lands (owner rule: a dead knob is worse than none) |
| JB-9.09 version-6 values untuned | Accepted: starting values, tuned by the owner on the Note 9 when JB-9.08 lands (one owner, one phone; R38) |
| Pencil retune after the paper change | Accepted: the owner judges on the Note 9 (T3) |
| Detail octave is display-only | Accepted by design: layers hold one pixel per doc px, so brushes cannot deposit below that. Fibres seen at 16× are the paper, not paint |
| Far-field precision in dab shaders | Accepted to ±1e6 doc px for deposit; the screen pass uses the local frame (JB-9.06 Decision 5) |
| JB-9.11 impossible as written | ✅ Rescoped to the pure core function (landed `86f1711b`); decode + polarity move to JB-1.05d; ABR patterns to a future JB-8.01c |
| Legal conclusion in R10 | ✅ Reworded: a design choice, not a legal opinion |

---

## 11. Photographic papers and the contrast-keeping blend (2026-10-06, the R9/Sable session)

**Owner:** the procedural papers "read as early-90s 3D graphics, not anything worth shipping". They supplied photoreal paper images they generated themselves (`tasks/joybrush/research/GBT texture sample image generations/`, not in the repo) and asked for the best paper engine in the industry. They also sent pencil feel notes; the pencil and the other media now belong to the "Realistic brush engines" session, which builds on this paper.

**What was wrong (measured on the PC with `tools/paper/papersim.py`):**
1. The looks were synthetic. The surfaces were blurred fractal noise, so a dry stroke on them was smooth, like a marker.
2. The background lit that noise with a strong lamp (shade 0.6–1.4, relief ×6). That emboss is what reads as 90s CG.
3. The hex blend was convex (JB-9.02 Decision 2). Where three patches meet, a plain average is flatter than any one patch, so photographic looks show soft, washed-out blotches at every seam.

**What was built:**
- `tools/paper/photo2paper.py` turns a photo into a paper:
  - de-light (very low frequencies only);
  - Moisan periodic + smooth decomposition, so it is seamless with no blend band;
  - a 1024 look as JPEG, with its mean measured after decoding;
  - a surface HEIGHT from the same picture: a tooth band of ≈5 doc px / 0.25 mm plus a pulp band, rank-equalised to uniform 0..1, with the relief span set per kind (rice paper nearly flat, owner P12);
  - a FLUID map for the wet and impasto engines.

  `install_photo_papers.py` installs 12 papers: 5 replace procedural ones under the same ids, and the green chalkboard is the black photo recoloured.
- Surfaces may ship as height only (`packed: false`, grey PNG); the app packs the slopes at load with `SurfaceMaps.expand`. The tooth's texelPx (1.0) is finer than the look's (2.5): a photo cannot hold a 0.25 mm grain at its own scale. Library: 24 MB, of which 19.4 MB is PNG, within the 25 MB budget.
- **Variance-preserving hex blend** (`HexTile.contrastKeep`, shaders, CPU twins). The result is `centre + Σw·(read − centre)/√Σw²`. A = E[h²] is recomposed as B² + Σw²σ²/Σw², so A − B² stays a valid local variance at every mip (the dry/wet engines prefilter on it). **This supersedes JB-9.02 Decision 2.**
- The relief light is clamped to 0.9–1.1, and photo papers ship with the light OFF. The photograph carries what the eye sees.
- Catalogue `SurfaceEntry` gains:
  - `heightMean`, `packed`;
  - `fluid` / `fluidTexelPx` / `fluidHexTexels`;
  - the physical numbers `toothDepthMm`, `compliance`, `sizing`, `absorbency`, `capacity`, `wickSpeed`, `anisotropy`;
  - and `PaperPhysical.DOC_PX_PER_MM = 20`.
- `jb_paper.glsl` gains `jb_paperFluid` / `jb_paperFluidCoarse`. Fibre directions turn by 2× the hex angle, so they always agree with the slopes (test `fibresTurnWithThePaperExactlyAsItsSlopesDo`). `GlPaintEngine` binds the fluid map on unit 2.

**Hand-offs:**
- JB-9.08's directional dry deposit moves into the brush-engines session's dry-media engine.
- JB-9.08's wet pooling is superseded by its wet engine.
- The remaining procedural looks (canvases, papyrus, parchment, silk, fabric, cement, crumpled, blueprint, off-white, pulps) wait for owner photos or CC0 scans of the same kind. The installer takes them as new rows.

### 11.1 Felt papers: drawing paper and watercolour cold, hot and rough (2026-10-06, same session)

The brush-engines session asked for, in order: a medium-tooth drawing paper (tooth ~0.1–0.15 mm), cold-press watercolour (~1 mm felt bumps), then hot press and rough. No owner photos of these existed, so the owner gave permission to download. CC0 scans of real art papers at macro scale are rare: ambientCG's papers are "PBR approximated", the good free Arches/Saunders scans (Gumroad) do not allow redistribution in an app, and museum scans are flat-lit. One CC0 raking-lit photo of a real cold-press sheet was usable (publicdomainpictures.net #260479, 1920², `tools/paper/sources/`).

**Why a new tool (`tools/paper/felt2paper.py`) and not photo2paper:**
1. Under a raking lamp a felt paper's brightness is its SLOPE, not its height. photo2paper's "bright = high" would shift every bump by half its width.
2. No phone or scanner photo holds the 0.02 mm fibres a pencil's dust actually catches. The photo is soft below ~0.2 mm.

**What it does:**
- **Felt** (measured): shape-from-shading in Fourier space.
  - The lamp axis is the spectrum's strongest direction.
  - The slope is integrated with eps·|k|² regularising the band across the lamp.
  - A small height-in-brightness term (valleys see less light) is solved so the slope along the lamp has zero skew. A true isotropic relief climbs and falls equally; a wrong term leaves an emboss.
  - The photo has a shallow depth of field, so the tile is cut from its sharpest square (`sharpest_square`).
  - It is then made isotropic again by direction (`even_angles`, anisotropy 0.37 → 0.03) and stationary in strength (`even_amplitude`).
  - The lamp is taken as up-left, the photographer's convention. That gives broad rounded tops and narrow creases (negative height skew), the profile of a felt-pressed sheet.
- **Fibres** (modelled at true size): a random fibre network (Kallmes & Corte), the physical model of what paper is.
  - Cotton fibres 2.5 mm long (log-normal), 0.02 mm wide, gently curled, coverage 4 layers, with a slight machine-direction bias.
  - Fibres are laid on a torus at 0.05 mm per texel, thinned by the sheet's flocs, which are the photo's own mottling.
  - The press evens out floc thickness on the top face, so the tooth keeps only the grain within ~1 mm. The flocs go to the fluid map as absorbency.
- **Grades**, the same mould-made sheet finished differently:

  | Grade | Felt bump | Felt : fibre | toothDepthMm | Look keeps lamp shading |
  |---|---|---|---|---|
  | cold press | 1.1 mm | 1 : 0.55 | 0.18 | 30%, relief light on |
  | rough | 2.2 mm | 1 : 0.4 | 0.35 | 45%, relief light on |
  | hot press | 1.1 mm | 0.3 : 1 | 0.03 | 8%, light off |
  | drawing | 0.5 mm | 0.3 : 1 | 0.07 | 10%, light off |

  Surfaces are all texelPx 1.0 (0.05 mm), 1024² (a 51 mm tile). The fluid G,B channels are the fibres' own directions (structure tensor of the network). A is capacity in the felt's valleys.
- **Looks**: the whole photo at its physical scale (cold 3.0 doc px per texel, rough 6.0, drawing 1.4), with its lamp shading reduced and exposed to the sheet's real white.

**Judged on the PC** (`papersim.py`, pencil threshold): drawing and hot press take graphite as granular, fibre-broken strokes, the real look of pencil on a smooth sheet. Cold and rough catch on the felt hills, with fibre-ragged edges.

**Install:** `python tools/paper/install_felt_papers.py tools/paper/sources/watercolour_cold_press_cc0.jpg assets/paper`.

**Ids:**

| Look | Surface |
|---|---|
| `drawing_paper` | `drawing_tooth` |
| `watercolour_cold` | `cold_press` |
| `watercolour_hot` | `hot_press` |
| `watercolour_rough` | `rough_press` |

**Upgrade path:** rough is the cold-press felt scaled up. A real rough-paper scan (or an owner photo under a raking lamp) would replace its felt; the tool takes any such photo.
