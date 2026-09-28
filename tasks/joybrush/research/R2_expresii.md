# R2: Expresii (Nelson S.-H. Chu): the brush, the ink, and how to replicate them in Joy Paint

Research date: 2026-09-28. Author: research sub-agent.
Status legend used throughout:
- **[SRC]**: stated in a primary source (paper, thesis, shipped code, official site/guide, the developer's own forum posts, the patent text). The URL is in the Sources section.
- **[INF]**: my own inference or engineering estimate. It has not been verified against Expresii's code, which is closed.

---

## 0. TL;DR for Joy Paint

1. **Expresii is MoXi (SIGGRAPH 2005) grown into a product.** The two core ideas are fully published:
   - **Brush** [SRC, thesis ch. 2]: a physically based *deformable 3D tuft*. It is a spine plus lateral nodes, solved each frame by constrained energy minimization (SQP). The footprint is where the tuft penetrates the paper plane. Alpha "split", "dry" and "grain" maps add the bristle detail.
   - **Paper** [SRC, MoXi paper + thesis ch. 3]: a *three-layer paper* (surface, flow, fixture). Water percolates in the flow layer by a *modified D2Q9 lattice Boltzmann* model with variable permeability, low-density advection damping, **pinning** by full bounce-back, and extra evaporation at pinned edges, which gives **edge darkening**. Pigment rides the water by semi-Lagrangian advection with "hindrance" and settles into the fixture layer as the paper dries.
   - **Rendering** [SRC]: an implicit-surface **boundary trim** turns a low-res simulation grid into crisp, rough-edged, higher-res marks. MoXi ran a 512² sim for a 1536² output. Expresii later shipped "Vexel rendering" and deep zoom, with export up to 32K.
2. **Product philosophy** [SRC]: one versatile brush ("One brush = many brushes"). For years there were only two presets, *Flow* (deformable) and *Needle* (rigid, precise), plus a handful of modes (Eraser, Tip Only, Sprayer, Pigment ±, Eyedropper). The brush is loaded with a **colour gradient from tip to base** plus a **water level**. Pressure and **tilt** drive the brush. **Barrel twist was deliberately removed for round brushes, because it "actually makes the brush harder to use"** (developer, forum). That is a key lesson for us.
3. **Mobile is feasible, but not as a straight port.** MoXi needed a GeForce 6800 (2004) for a 512² sim at 44 fps. A 2017 GLES 3.0 oil-paint simulation ran a 1024×768 shallow-water sim in 8.8 ms on an iPad Air 2 [SRC]. Galaxy Note 9 / Note 20 / Tab S7+ GPUs are in the same class or faster [INF]. Recommendation: a **tile-sparse "MoXi-lite"** fragment-shader simulation in GLSL ES 3.00. It uses fused LBM passes, RGBA16F, a sim cell of 2 to 4 canvas px, runs only in wet tiles, and **sleeps when nothing is wet**. The same shaders run unmodified in **WebGL2** for the desktop prototype.
4. **The brush must be cheap on a phone.** Do not run per-frame SQP. Use an **analytic 2D "tuft footprint"**: a teardrop signed distance field (SDF) whose contact length and belly width come from pressure-driven penetration and tilt. Its **bend direction** is a damped state vector pulled by the drag direction and the tilt azimuth. Add Chu-style **plasticity**, bristle split and dry maps in stroke space, and a water/pigment reservoir with a tip-to-base colour gradient. When there is no barrel sensor, derive rotation from stroke direction with length-based damping. Android has *no* barrel-rotation axis anyway.
5. **Licensing and patents (not legal advice; get counsel before shipping in the US):**
   - The published MoXi Cg code is licensed "for research or educational purpose" only. **Do not copy it into GPL-3.0 Joy Paint.** Clean-room re-implement from the paper equations.
   - **US 8,296,668 (Adobe)** claims a watercolour/ink simulation driven by a paper texture with a *dedicated absorption channel* that sets the flow "blocking" factor. Dependent claims add lattice Boltzmann and the three layers. Estimated expiry ≈ **2030-05-20** if maintained.
   - **US 9,030,464 (Microsoft; Baxter, *Chu*, Govindaraju)** claims a 3D brush model plus a canvas-resolution 2D *pickup map*. Estimated expiry ≈ **2031-11-24**.
   - **US 8,462,173 (Adobe)** claims a two-buffer *pickup + reservoir* brush. Estimated expiry ≈ **2031-08-12**.
   - **US 8,605,095 (Adobe)**: vector output from bristle simulation. Active, expires **2032-04-02**.
   - **US 8,654,143 (Adobe)**: loading a brush non-uniformly by sampling the canvas. Active, expires **2031-03-27**.
   - I found no patents held by Expresii or Nelson Chu personally. The search was not exhaustive.
   - All of these are designable-around; section 5 has details.

---

## 1. Who and what

- **Nelson Siu-Hang Chu (朱紹恆).** HKUST PhD in 2007, supervised by Chiew-Lan Tai [SRC].
  - 1999: started brush simulation.
  - 2002: Pacific Graphics brush paper.
  - 2004: IEEE CG&A brush paper.
  - 2005: SIGGRAPH MoXi.
  - 2006: MoXi non-exclusively licensed to **Adobe**. 2007: licensed to **Sony**.
  - 2008–2011: Microsoft (Project Gustav, which became **Fresh Paint**, simulating oil and pastel).
  - 2012: started Expresii. 2013: SIGGRAPH Asia preview. 2016: v1.0.
  - 2017: Microsoft Surface Pro launch partner, SIGGRAPH Appy Hour.
  - 2021: "vexel rendering".
  - 2023: Digital Stationery Consortium member.
  - The product is still actively updated; forum replies are dated 2025 [SRC about-us, forum].
- **Engine names** [SRC, expresii.com]:
  - **Moxi Paint Engine**: the fluid/paint simulation. The name means "ink play".
  - **Yibi Brush Engine**: the deformable brush. Site copy: "our virtual brush is really a brush, not some stiff wires in 3D space"; "One brush = many brushes".
  - **Youji Rendering Engine**: "No more fat pixels", Vexel Rendering, "Ultra-Deep Zoom", export "up to 32K x 32k Bitmaps".
- Chu on Adobe [SRC, Expresii FAQ]: "We actually licensed our original MoXi tech to Adobe back in 2006 … [Adobe Sketch's watercolor] looks like a simplified version our over-a-decade-old MoXi watercolor."

---

## 2. The papers: algorithms, concretely

### 2.1 Brush model (Chu & Tai 2002 PG, 2004 CG&A; thesis ch. 2, 2007)

**Geometry** [SRC]:
- A **spine** of line segments, progressively shorter toward the tip. The tip gets more resolution because "the tip is softer and … usually only the tip and the belly are used to paint".
- Each joint has 2 degrees of freedom: a **bend angle φ** and a **turn angle θ**.
- Each spine node carries **two lateral nodes** on a "lateral line" with one rotational degree of freedom. These model *flattening and lateral spreading* when the tuft is pressed. Non-penetration constraints keep the lateral lines of the contacting nodes parallel to the paper.
- The surface is a sweep of cross-sections, each made of **two half-ellipses**. The major radii are (a + r_lat) and (b + r_lat). The minor radius c comes from area conservation: `r² = ½·c·(a + b + 2·r_lat)` (reconstructed from the thesis figure).
- **Fine hair**: an alpha **split map** on the tuft surface. It comes from a scanned bristle image or from patched mask primitives. A static split map plus flattening "is sufficient for simulating convincing bristle splitting as long as the brush is not pressed too hard".

**Dynamics** [SRC]:
- Quasi-static **energy minimization**, not F=ma: "real brushes reach equilibrium almost instantaneously".
- The energy has three parts:
  - strain energy: bend and twist springs `E = κ·θ^m` with m = 2 or 3, stiffer at the root, and no twist spring at the root;
  - internal friction: the same form, applied to the change since the last frame;
  - external (Coulomb) friction: `μ·R·x` for each contacting node, with **anisotropic μ** so sideways drag resists more.
- A wet tip gets a small extra friction even at zero normal force, so it adheres.
- Constraint: the brush stays above the paper, tested with a *shrunk* ellipse Φ. The real surface is therefore allowed to **penetrate**, and the penetration *is* the footprint and gives the flat-bottom look.
- The solver is local SQP, warm-started from the last frame's Hessian (up to 50% fewer iterations). Cap q_max ≈ 20 iterations per 6-segment tuft. Unconverged solutions "continue in the next time step". That is visible as a slight lag, which reads as bristle inertia.

**Plasticity** [SRC]:
- Shift each spring's rest angle toward the previous frame's angle, clamped by a user parameter α: `E = k·|θ − ρ|^m`, `ρ = clamp(θ′, −α, α)`.
- "This simple but effective method significantly improves the realism."
- The brush can be reset to a sharp point on command, or with α = 0 it never needs re-pointing.

**Pore resistance** [SRC]:
- When the tip of a slanted brush is *pushed* toward the direction it points, a vertical blocking plane sits in front of the tip.
- Its lead distance is `L = a·t + b·u + c`, where `t = max(β − γ, 0)` (tip-segment angle past a critical tilt γ) and u is the tip pressure.
- This produces the rough "pushed stroke" texture and lets calligraphers straighten a soft brush for blade-like stroke ends.

**Multiple tufts / splitting** [SRC]:
- Hierarchical child tufts sprout from a parent spine node on heuristics: lateral-node displacement, pore resistance, bending, drying.
- "At most five" tufts suffice for most painting. Many tufts only matter for scrubby dry-brush, where "the essence … lies on the controlled randomness rather than the brush elasticity".

**Loading and deposition** [SRC]:
- **Loading**: users define a **colour gradient mapped axially** along the tuft (tip to base). The thesis notes Chinese painters "mix colors on the brush" (Ning Yeh).
- **Footprint**: the orthographic projection of the penetrating part of the tuft, rendered on the GPU clipped by the paper quad. For continuity between sim frames, the tuft geometry is **stretched between time t and t+1** (motion-blur style, Wloka & Zeleznik) instead of stamped.
- **Dry brush**: a *dry map*, a greyscale image from real dry-brush prints thresholded by moisture and pressure. The split map is multiplied by the dry map.
- **Soaked brush**: expand the tip geometry slightly and dilate the split map, done by scaling and shifting it down the spine.
- **Grain**: a paper height field thresholded by wetness and pressure. It only shows when the brush is fairly dry.

**Tablet mapping** [SRC, thesis §5.2.2]:
- "The sensed pressure controls the height of the brush above paper, while the tilt controls the orientation of the brush."
- 5-DOF tablets lack twist.
- Chu tried a 6-DOF tracker and found it too bulky. He found tablet pressure "not really that bad".
- On the Wacom 6D Art Pen, the slanted felt nib moves its contact point when twisted, which makes it "awkward to control" [SRC, MoXi FAQ].

**Flat brushes** [SRC]: the thesis §2.8 extends the model to Western flats. Only there does twist become essential [INF].

### 2.2 MoXi: ink dispersion in absorbent paper (Chu & Tai, SIGGRAPH 2005; thesis ch. 3)

**Why lattice Boltzmann (LBE)** [SRC]:
- No Poisson solve, all operations local, GPU-friendly, and extra physics is easy to add.
- The compressibility artefact is harmless because water in paper moves slowly.
- Fickian diffusion or cellular automata without momentum "could only produce blurry images". Momentum is what makes **branching and feathery flow**.

**Three-layer paper** [SRC]:
1. **Surface** layer: an unabsorbed reservoir of water and ink, `s`.
2. **Flow** layer: water percolates here (LBE) and carries pigment and glue.
3. **Fixture** layer: pigment settled permanently as the paper dries.

**Deposition and receptivity** [SRC]:
- Each footprint pixel is masked by `max(1 − ρ/λ, m)`, where ρ is flow-layer water, λ ∈ [0.3, 1] is receptivity, and m = 0.1. Wet paper therefore accepts less, which gives the **light fringe** effect.
- Seepage from surface to flow is `ϕ = clamp(s, 0, π − ρ)` with fibre capacity π = 1.
- Pigment mixes by volume: `p_f ← (p_f·ρ + p_s·ϕ)/(ρ + ϕ)`.

**LBE core** [SRC]:
- D2Q9 lattice with weights 4/9, 1/9, 1/36.
- He-Luo "incompressible" equilibrium, modified with an **advection weight ψ**:
  `f_i^eq = w_i·(ρ + ψ·ρ0·(3 e_i·u + 9/2 (e_i·u)² − 3/2 u·u))`, with `ψ = smoothstep(0, α, ρ)` and α ∈ [0.2, 0.5].
  - This stops low-density (almost dry) sites from being advected into negative density. Mass is still conserved.
- Relaxation `f ← lerp(f, f_eq, ω)`. Normally ω = 0.5; ω = 1.5 for more fluid flow.
- Viscosity is `(1/ω − ½)/3`.

**Permeability and blocking** [SRC]:
- Streaming uses partial ("half-way") bounce-back: `f_i(x + e_i) = (1 − κ_i)·f_i(x) + κ_i·f_k(x)`, where f_k is the opposite direction.
- κ_i is the **average of the blocking factors of both linked sites**, so blocking is symmetric and conserves mass and momentum.
- Blocking factor `κ = k1 + k2·G + k3·A + k4·g + k5·h`, where:
  - G = paper grain: *scanned Xuan paper thickness, backlit*;
  - A = alum texture (procedural random dots);
  - g = glue in flow;
  - h = fixed ink accumulation.
- Glue stops flow by raising κ, not by lowering ω. Modulating ω "does not give the desired behavior".

**Free boundary, pinning and roughening** [SRC]:
- A dry site is a **pinning site** if all 8 neighbours have `ρ < σ`, with threshold 2σ on the diagonals (the code uses a "Corn_mul" multiplier). Pinning sites get κ = **∞** (full bounce-back).
- `σ = q1 + q2·h + q3·lerp(G, P, smoothstep(0, ϑ, g))`. P is a **pinning texture** of "light line segments on a dark background", which gives **toe patterns** in concentrated ink.
- The infinity is useful. "Any interpolation between a finite value and infinity is infinity", so a single bilinear fetch of κ tells you whether a sample straddles the wet/dry boundary. This is used in advection and in trimming.

**Evaporation and edge darkening** [SRC]:
- Global evaporation εs ∈ [0, 0.005] per step is subtracted from ρ.
- Distributions that **bounce back at pinned links** additionally lose εb = 5×10⁻⁵ per step.
- The extra loss at the pinned edge induces flow, and pigment with it, toward the edge. That is the **edge darkening** (coffee-ring). It is the same physical idea as Curtis et al. 1997, but localized to the actual pinned front.

**Pigment and glue advection** [SRC]:
- For *newly wetted* sites: `p* = (1/ρ)·Σ f_i(x − e_i)·p(x − e_i)`, i.e. pigment arrives with the streamed water.
- For *already wet* sites: semi-Lagrangian back-trace `p* = p(x − u)` with hardware bilinear filtering. If the back-trace straddles the boundary (κ = ∞ detected), keep `p* = p`.
- **Hindrance**: `γ* = lerp(1, γ, smoothstep(0, ς, |u|))`, then `p ← lerp(p*, p, γ*)`. Pigment lags behind water. This is why diluted ink shows *feathery streaks* and a *water halo* beyond the pigment.
- **"Ink-coloured water"** [INF, interpretation]: ink in MoXi is not a colour painted by a stamp. It is a *concentration carried by water*. It moves only where water moves, lags behind it (hindrance), is diluted by clear water arriving (volume-weighted mixing), and becomes permanent only when water leaves (fixture). Clear water painted into wet ink therefore pushes a pale front and makes blooms. Expresii's "Stain from Bottom Layer" slider lets the active layer's water pick up ink from the layer below [SRC, user guide].

**Fixture (drying)** [SRC, pseudo-code]:
```
wLoss = max(ρ' − ρ, 0);  Fix = wLoss > 0 ? wLoss/ρ' : 0
μ* = clamp(μ + ξ·glue, 0, 1)
Fix = max(Fix · (1 − smoothstep(0, μ*, ρ)), η)
p_x += Fix·p_f;  p_f −= Fix·p_f
```
- More fixing happens as the paper gets drier and with more glue, and everything is settled when dry.
- Re-wetting can optionally move fixture pigment back to the flow layer.

**Western watercolour extensions** [SRC, thesis §3.12]:
- **Granulation**: extra fixture where `|u| < SettlingSpeed`, modulated by `1 − smoothstep(0, GranulThres, grain)`.
- **Backruns**: three region types (dry, **damp**, wet). A per-site time-to-live counter marks "damp". Pinning is applied at dry↔wet/damp *and* dry↔damp boundaries. **Desorption** `SinkInk = −FixInk·UnFix·wf` applies when the flow is fast. The result is a continuum from wet-in-wet diffusion to soft-edged and then hard-edged backruns, depending on paper wetness.
- **Glazing**: Kubelka-Munk. Chu later argues KM is not physically right for watercolour, because pigment sits between fibres rather than forming layers. As an artist he often prefers predictable linear or CMY mixing [SRC, thesis §4.2]. **Takeaway: KM is optional flavour, not a must.**

**Stability and "pushing ink"** [SRC, thesis §3.14]:
- Writing brush velocity into the LBM blows it up. The brush moves tens of cells per step while LBM moves at most 1.
- MRT-LBM would fix this but did not fit RGBA parallelism.
- Chu clamps ρ to [0, 1] and uses **accelerated advection** `y = x − a·u(x)` with a ≈ 10 (suminagashi) or up to 80 ("ink-rush").

**Rendering at higher resolution** [SRC, paper §6 / thesis §4.1]:
- Upsample the sim output I to I′ bilinearly (d-scale 3 or 4), then **trim** pixels by an implicit function.
- Each site keeps `ρτ = max(ρτ, ρ)` while wet, and a small negative value if dry. The function is `φ = ρτ + r_scale·r + r_bias`, with r a blurred random-spot "rougher" texture.
- Trim only during the "pinned" life stage (not expanding, not yet shrinking).
- Anti-alias with `I′ *= smoothstep(−a, a, φ − EdgeThres)`.
- A **hair texture**, from photos of stained paper, is modulated into partially soaked regions, and "venetian blind" patterns follow paper thickness.
- I′ is refreshed only every 200–400 frames because of fp16 filtering drift; the on-screen view trims every frame.

**GPU implementation data** [SRC, supplementary + render-pass chart]:
- Four RGBA16F sim textures: `VelDen[u,v,wf,seep]`, `Misc[blk,f0,lwf,ws]`, `Dist1 f[N,E,W,S]`, `Dist2 f[NE,SE,NW,SW]`.
- Ink maps:
  - `FlowInk[P1,P2,P3,glue]`
  - `SurfInk[…]`
  - `FixInk[P1,P2,P3,fblk]`
  - `Stain[wpen,wpin,edge]`
  - `SinkInk`
  - `DetInk[P1,P2,P3,wetcut]`
- **6 passes for LBE + 6 for ink + 1 for render**. The LBE passes average ~30 asm instructions.
- Limited to **3 pigments** so pigments plus glue fit in one RGBA. The CMY bases span the gamut.
- fp16 storage with fp32 maths. Pigment transfer is quantized to 1/2048 to stop fp16 write losses.
- Performance on a GeForce 6800 Ultra: 256² sim → 70 fps; 512² → 48 fps; 512² sim at d-scale 3 (1536² output) → 44 fps.
- MoXi FAQ table, 512² at d-scale 3: GeForce 7800 GTX 61 fps, 8800 GTX 120 fps.
- Suminagashi mode: 1024×512 water, 2048×1024 ink, 1920×1080 output at 25 fps on a 7800 GTX.

### 2.3 Detail-preserving paint modelling (Chu, Baxter, Wei, Govindaraju, NPAR 2010)

Relevant to our **smudge/nudge** brush [SRC]:
- **Problem**: repeated brush↔canvas exchange at every imprint resamples each pixel about N times for an N-px footprint. That is an N-iterated blur.
- **Fix 1, resolution-matched pickup map**: a canvas-resolution 2D bitmap under the brush holds picked-up paint. It is rotated with the brush orientation, and the footprint masks the transfer. Rotation resampling is acceptable because it is not repeated.
- **Fix 2, canvas snapshot buffer Ω**: pickup reads from a snapshot that is current *except under the brush's pickup area*, so you never pick up what you just laid. Refresh Ω fully on sharp direction changes. Blending between Ω and the live canvas becomes an artistic "wetness" control.
- Deposit colour: `C_s = lerp(C_intrinsic, C_pickup, f(pickup thickness))`. Imprint spacing must be ≤ 1 px for clean smears.
- 145 fps with a 60×60 px dab on a 2008-era tablet PC GPU.
- **Warning**: this is patented by Microsoft (US 9,030,464, which names Chu). See §5.

### 2.4 Later Expresii developments (blog/forum/guide)

- **Brush presets** [SRC, user guide 2017]: *Flow* and *Needle* only.
  - Modes: **Eraser**. Low-strength erasing gives "negative scratchiness", and erased areas **block flow**, so it doubles as a flow-steering tool.
  - **Tip Only**, **Sprayer**, **Pigment Amount Adjuster** (lighten or darken paint already down, "circle … like polishing"), **Eyedropper** (pressure-sensitive loading, "Halfway" loads half the tuft).
  - Quick lighter/darker variants of the loading; **Flip** gradient (tip↔base).
- **Water level and scratchiness** [SRC]:
  - Scratchiness appears when brush wetness is below 6, and more with faster motion or lower pressure.
  - Ink flows (a "textured" look) when wetness is above 6 (tuned to ≥ 5 in 2025). Below that the mark is "smooth" and static.
  - **Streakiness** chooses streaky (bristle) vs grainy (paper) dry texture.
- **Paper operations** [SRC]:
  - Show wet area, **Lock wet-area expansion** (pre-wet an area, then confine flow to it), **Freeze flow**, add moisture, dry ([D] dries a bit, double press dries all).
  - "Even after drying, paint is NOT permanently fixed". Rewetting makes it flow again.
  - **Surface tilt**: paint runs downhill. It can be driven by the UI, the device G-sensor, a game controller, or an Android phone.
- **Settled paint (2021)** [SRC, forum]: "Settling" rasterizes flowing paint into a permanent state ("some state will be lost forever"), as opposed to "dry to touch", which keeps it re-wettable. An auto-settle timer with a "Settling Degree" exists. It allows glazing on one layer. A "Lifter" brush mode was being considered.
- **Layers** [SRC]: originally 2 paint layers plus Overlay/Underlay reference layers, "Snap Down" (rasterize the bottom layer into the underlay) and "Staining" interaction. A new layer UI was in development in 2024/25.
- **Resolution** [SRC]:
  - "Paper is measured in units instead of pixels".
  - "Expresii is somewhat between bitmap-based and vector-based". Export was 12K in 2017, **32K now**.
  - "Strokes residing in the paint layers are still editable, while those strokes snapped down … are not". Artwork files (.XAW) "need more information than just pixels".
- **Vexel rendering (2021)** [SRC for the claim; mechanism INF]:
  - The blog post was behind a bot wall during this research. Sources confirm "pixels zoom like vector", 20× to 100× zoom demos, and a SIGGRAPH 2017 description of "hybrid vector-raster rendering".
  - [INF] Given MoXi §6, it is almost certainly the boundary-trim implicit function evaluated **per screen pixel at the current zoom**: bilinear/bicubic sim fields, a smoothstep iso-edge, plus procedural or photo fibre detail defined in paper units. Together with stroke data kept "editable" until settled, this looks resolution-independent.
- **Power** [SRC]: "By default, the ink flow would pause when Expresii is in idle state … for power saving and keeping the machine cool (good for tablets)."
- **Undo** [SRC]: "limited by the video memory". The default is 5 levels, because sim state is large.
- **Platforms** [SRC, FAQ]: Windows (OpenGL), and a Linux build mentioned in the forum. iOS and Android were "less likely". In 2018 Chu worried most Android GPUs were not yet capable.

---

## 3. Product design philosophy, and what artists say

### 3.1 Philosophy [SRC unless marked]

- **"One brush = many brushes."** The richness comes from *wielding* one deformable tuft: tip for hairlines, belly for washes, side for "wipe strokes", dryness for scratchy texture. It does not come from presets.
  - The thesis argues 2D stamps lose "rhythmic vitality", which "is lost in the process of adjusting control points or parameters".
  - Chu refused auto-painting features ("Our goal is not to make a 'me-too' app").
- **Load the brush, not the canvas.** A gradient on the tuft, plus water level, *is* the colour model. "In Western painting, you mix colors on the palette. In Chinese brush painting, you mix colors on the brush."
- **Serendipity, with undo as a safety net.** "Let Happy Accident Happen … you're always backed with undo's."
- **"Organic, not realistic."** "We bring in physics only for getting the organic quality." Physics is a means, not a goal.
- **Very few tools.** Brush, eraser, tip-only, sprayer, pigment ±, eyedropper, water, dry, freeze, lock-wet, tilt.
- **Shows the 3D brush.** The virtual tuft is displayed deforming (handle, shadow, transparency options), "so that you can see exactly what mark the brush is making". Users can hide it; hiding "would speed things up quite a lot".

### 3.2 Tablet features

- **Pressure**: yes. It sets brush height, i.e. penetration.
- **Tilt**: yes, and strongly recommended. Workarounds exist for pens without tilt: right-drag, or an Android phone's IMU streamed over UDP.
  - Surface pens give no tilt while hovering, so the brush "snaps" into its tilt only on contact. A user complained about this.
- **Twist/rotation**: supported for the Wacom 6D Art Pen in early versions, then **removed for round brushes**. The developer said: "the twist actually makes the brush harder to use. … In the future, we may add some flat brushes and probably we will add back the twist reading."
- **Device tilt / G-sensor**: tilts the paper so ink runs. A signature "wow" feature on tablets.

### 3.3 Praise [SRC: forum, AlternativeTo/Renderosity snippets]

- The most organic water flow of any app ("the most beautiful and realistic watercolor painting" vs Rebelle 3, Painter 2020, Fresco).
- "Fast, simple, and as close to real watercolour as it gets (better than Rebelle even)". "Never experience a lag". Dual colour loading and an "authentic brush experience".
- "A feeling of liberation". Deep zoom and huge exports.
- Used in production: *Red Squirrel Mai*, *Find Find*, Beijing Olympics VFX (MoXi).

### 3.4 Complaints [SRC: forum]

- **UI is confusing or dated.** Examples:
  - Wetness vs settled state shown by a checkerboard.
  - Two different "Deposition" controls (paper "reception" vs paint "blend").
  - Disabled legacy sliders.
  - Flickering controls.
  - Lock-wet-area left on by accident.
  - Unusual hotkeys.
- **Clear water does not blend or soften colour into white paper** (2017 request for a natural "blender"). A loaded brush gives **zero deposition when paper water is very high**. Users expect wet-on-wet colour to flow in.
- **Dab displacement.** The contact patch slides ahead of or away from the cursor with large brushes and near-vertical pens. Users want the mark anchored at the pen point.
- Few layers for years. No accumulation on one layer until "settled paint" (2021).
- Windows-only. GPU requirements. Integrated GPUs had to use Lite modes.
- Western watercolour, granulation and backruns are "room for improvement" (Chu's own words).
- **Takeaway [INF]**: the physics is the moat. The *UX of wetness state* is the weak spot, and Joy Paint can win there.

### 3.5 Paper choices [SRC partial]

- MoXi used **scanned Xuan (rice) paper thickness** as grain, a procedural **alum/sizing** texture, a **pinning** texture of line segments, and a **hair/fibre** texture for partially soaked areas.
- Expresii exposes paper material controls: absorbency, a "Boundary" control for edge pigment, reception/deposition on saturated paper, paper texture and granulation.
- The forum has "paper texture variety" and "make our own paper" requests [SRC thread titles]. I did not read those threads.

---

## 4. Replicating it on a phone GPU (GLES 3.x) and in the WebGL2 prototype

### 4.1 Platform facts that shape the design

- **Same shader language on both targets.** WebGL2 is essentially GLES 3.0, so the desktop JS prototype and Android can share **GLSL ES 3.00** brush and paper modules [INF, standard].
  - Float render targets need `EXT_color_buffer_float`/`half_float` on WebGL2. On GLES, RGBA16F is colour-renderable via `EXT_color_buffer_half_float`/`EXT_color_buffer_float`, core in GLES 3.2 [INF, from spec knowledge; verify per device].
  - RGBA16F is texture-filterable in core GLES 3.0. 32F linear filtering needs `OES_texture_float_linear` [INF].
- **Android stylus axes** [SRC, Android docs]:
  - `AXIS_PRESSURE` (0..1, may exceed 1, so normalize).
  - `AXIS_TILT`: 0 = perpendicular, π/2 = flat.
  - `AXIS_ORIENTATION`: for a stylus, the **azimuth of the tilt**, −π..π.
  - **There is no barrel-rotation axis** in the documented stylus API.
  - Samsung S Pens (Note 9/20, Tab S) report no barrel rotation [INF]. Tilt support varies by device and firmware. Query `InputDevice.getMotionRange(AXIS_TILT)` at runtime and fall back to derived tilt [INF].
- **Target GPUs** [INF, public spec knowledge]: Note 9 = Adreno 630 / Mali-G72 MP18; Note 20 = Adreno 650 / Mali-G77 MP11; Tab S7–S9 = Adreno 650–740.
  - Mobile reference point [SRC, Stuyck et al. 2017]: a GLES 3.0 shallow-water oil sim on an **iPad Air 2** (2014). Sim-only GPU time was 2.9 ms at 512×384, **8.8 ms at 1024×768** and 33.4 ms at 2048×1536 (O(N²)). The full app ran at 45 fps with a 1024×768 sim and 2048×1536 render, using ≈19 MB with half floats.
  - [INF] A Note 9 GPU should be roughly 1.5–2.5× an iPad Air 2 on this kind of bandwidth-bound work, and a Tab S8/S9 ≈ 4–6×.

### 4.2 The paper simulation: "MoXi-lite" (recommended)

**Grid vs canvas** [INF, following MoXi's d-scale]:
- The sim cell is **2–4 canvas pixels** (d-scale 2–4). MoXi used 3; Stuyck rendered at 2× sim res.
- Example: a 4096² canvas uses a 1024²-cell sim at d = 4, or 2048² at d = 2 on tablets.
- Detail below the sim scale comes from the implicit boundary trim plus procedural and photo fibre textures, not from simulation.
- Expose a quality setting: d = 4 on phones, 2 on tablets.

**Tile-sparse, wet-only execution** [INF]:
- Split the sim into **32×32-cell tiles**. A tile is *active* if any cell has water above ε, or surface water, or flow-layer pigment still unsettled. Newly active tiles come from brush contact, plus 1-tile dilation of active tiles every K steps.
- Draw only active tiles: instanced quads or a scissored multi-draw. Keep a CPU-side bitset updated by:
  - brush bounding boxes;
  - a cheap per-tile max-reduction every ~8 frames (mip-reduce of water into a tile-level texture, read back asynchronously via PBO).
- **Sleep when there are no active tiles.** Expresii does this for battery and heat.
- Memory: allocate sim textures as a **tile atlas / sparse page pool** instead of full-canvas textures, so a 4K canvas does not cost 8 × 16 MB × 2 (ping-pong) when only 10% is wet.

**Pass structure per sim step** [INF; MoXi used 12+ passes]:
1. **Deposit + surface→flow seep + blocking/pinning** (fragment, MRT). This writes blk, ws and seep, and injects surface pigment.
   - The pinning test is 8 neighbour reads of ρ. Store κ = +∞ (or a sentinel such as 65504 in fp16) for pinned sites.
   - fp16 infinity is representable, but make sure the driver keeps it through blending. Using a sentinel ≥ 2 and testing `> 1.0` like the MoXi code (`pinned = b > 1`) is safer.
2. **Fused stream + collide (pull scheme)** with **MRT → 3 RGBA16F** targets: (f1..f4), (f5..f8), (f0, ρ, ux, uy).
   - Each fragment reads 8 neighbour distributions (pull) plus κ of self and neighbours, applies partial bounce-back and pinned-edge evaporation, computes ρ and u, applies ψ-weighted equilibrium, and relaxes.
   - This replaces MoXi's 5 LBE passes with **1 pass**. GLES 3.0 guarantees ≥ 4 draw buffers.
3. **Pigment/glue advection + hindrance** (1 pass): the newly-wet fill rule, semi-Lagrangian back-trace, the κ-straddle test, and `lerp` hindrance.
4. **Fixture** (1 pass, MRT to flow and fixture pigment): MoXi's SIMULATEFIXTURE. Add optional granulation (slow flow + grain valleys) and optional desorption (fast flow).
   - Use RGBA32F or MoXi's 1/2048 quantization for the fixture transfer, to avoid fp16 loss.
5. **Stain/edge bookkeeping** for rendering: ρτ, the pinned-edge mask, damp-TTL. This can be folded into pass 1 or 4.

That gives **~4 passes per step**, each a full-screen quad over active tiles only. Run **1 step per display frame**, as MoXi did (flow speed is an art parameter), with an optional 2–4 substeps for "fast/fluid" paper.

**Cost estimate** [INF, back-of-envelope]:
- Per cell per step: ≈ 30–45 texel fetches × 8 B (fp16 RGBA). Most hit cache in these stencil patterns. Writes are ≈ 7 × 8 B.
- Effective DRAM traffic is ≈ 100–150 B/cell/step.
- At 250k active cells (e.g. a 500×500-cell wet wash): ≈ 25–40 MB/step, i.e. **≈ 2–4 ms on a Note 9-class GPU** at ~10–15 GB/s effective. A full 1024² grid would be ≈ 8–15 ms, comparable to Stuyck's iPad Air 2 figure.
- Implication: full-canvas simulation at 60 Hz is marginal on a Note 9. Wet-region-only simulation is comfortable. Tablets have headroom for d = 2.
- **Profile early** on both Adreno and Mali. Mali tile-based deferred rendering likes fragment ping-pong with no read-after-write within a pass. Prefer fragment shaders over compute for portability to WebGL2 and older drivers. Revisit compute (GLES 3.1 shared-memory LBM tiles) only if profiling shows fetch-bound passes.

**Cheaper fallback, "Darcy-lite", for low-end devices or "Lite mode"** [INF]:
- Replace D2Q9 with a **5-point capillary/Darcy flow** on water height `h`: flux `J = −k(x)·∇(h + p_cap(h))`, where k comes from grain/sizing, the fibre-direction texture gives anisotropy, and the same pinning rule and edge evaporation apply.
- Pigment is transported upwind by J, with hindrance and fixture as in MoXi.
- Cost is ~1/3 of LBM: 2 passes, 1–2 RGBA16F per field.
- You lose momentum-driven branching and feathery streaks. Fake these partly with an anisotropic permeability texture and the pinning texture (toes). Chu explicitly warns the result trends "blurry" without momentum.
- Expresii itself shipped "Lite" modes: one layer, one ink colour.
- **D2Q5** LBM is another intermediate option (half the distributions). It is effectively an advection-diffusion solver, so the same caveat applies.

**Rendering (resolution independence)** [SRC mechanism from MoXi §6; INF for extension]:
- Composite per display pixel at the *current zoom*. Sample sim fields bicubically/bilinearly, and compute `φ = ρτ + r_scale·noise_paper(worldPos) + r_bias`. The alpha edge is `smoothstep(−a, a, φ − thr)`.
- Modulate pigment by a **fibre/hair texture in paper units** in partially soaked regions, with multi-octave procedural detail so zooming 20× shows fibres instead of pixels. This is our version of "vexel".
- Colour: subtractive, e.g. `RGB = paper · exp(−Σ c_k·K_k)` per pigment (Beer-Lambert style) [INF], or KM as an option. Chu found linear/CMY mixing often preferable as an artist.
- Export at any resolution by re-rendering from sim fields plus procedural detail, or by **replaying the recorded input stream** at a finer sim scale.
  - Stuyck et al. record all actions for offline re-render [SRC]. Expresii keeps strokes "editable" until snapped down [SRC].
  - [INF] Make the sim **deterministic** (fixed step, no wall-clock dt, seeded noise) so replay works. This also helps animation onion-skinning and undo.

**Undo** [INF, informed by Expresii's VRAM-limited 5-level undo]:
- Keep per-stroke tile deltas of *settled* layers, plus periodic sim-state checkpoints of active tiles only. Undo within a wet session means "restore checkpoint + replay remaining strokes".

**Wetness UX (beat Expresii here)** [INF]:
- Show a live wet-area tint on long-press or via the S Pen button.
- Keep two explicit, well-named states: **Wet** (flows, rewettable) and **Settled** (baked). One "Dry" gesture advances Wet → Dry-to-touch → Settled.
- Make a loaded brush always deposit into very wet paper, with pigment diffusing into the pool. This fixes the Expresii complaint.
- Offer clear-water "blend/lift" as the same brush with zero pigment. Lifting desorbs pigment from flow and optionally from fixture.

### 4.3 The brush: a cheap "tuft footprint" model with tip-vs-belly behaviour

Goal: capture what Chu's SQP tuft gives artists (tip hairlines vs belly washes, flattening, lateral spread, trailing tip, biased-tip strokes, plasticity, splitting, dry scratch) at the cost of a few scalar updates per input sample plus one SDF shader. **[INF: design proposal]**

**State per brush** (CPU or JS, updated per input sample):
- `b`: unit 2D **bend direction**, pointing from the belly toward the tip along the tuft as it lies on the paper.
- `ψ`: twist angle. Used only by flat/chisel shapes.
- `κ_p`: plastic "set" of the bend (0..1), plus the plastic bend direction.
- `W_b`: brush water (0..10 scale, Expresii-compatible thresholds). `P_b(s)`: pigment loading gradient along the tuft, s = 0 at the tip and 1 at the root, as 2–3 colour stops plus concentration.
- `pickup`: a small colour state for smudge. See §5 for the design that stays clear of the pickup-map patents.

**Inputs**:
- `p` = pressure in 0..1 after a user curve.
- `θ` = tilt (AXIS_TILT), `α` = tilt azimuth (AXIS_ORIENTATION).
- `v` = stylus velocity in canvas units per second, smoothed.
- Barrel twist is not available on Android and is not wanted for round brushes.

**Penetration and contact geometry**:
- `d = L · gainP · p` is the penetration depth, where L is bristle length.
- Contact length along b: `ℓ = clamp(sqrt(2·L·d)·(1 + gainT·sin θ), r_tip, L)`. The sqrt mimics a bending beam laying down more length as it is pressed.
- Belly half-width: `w_max = R · (d/L)^0.5 · (1 + spread·d/L)`. Lateral spreading uses area conservation: width grows while thickness "shrinks".
- Light touch (small d) gives a tiny rounded tip of radius `r_tip ≈ R·0.3·(1 − tipSharpness) + r_min`, i.e. a hairline. A hard press gives the belly as a long, wide teardrop. That is the tip-vs-belly behaviour.

**Footprint SDF** in the local frame (x along b from belly toward tip, y perpendicular):
- The half-width profile is `w(x) = w_max · (1 − x/ℓ)^(tipTaper) · (x/x0 smooth-start behind the anchor)`.
- It is rendered as an SDF in a fragment shader over a quad, with a soft `smoothstep` edge.
- **Continuous strokes**: sweep the SDF between consecutive samples. Interpolate the parameters and take the union over sub-steps (≤ 0.25·w spacing), or use an analytic capsule-sweep for the belly. This is MoXi's "stretched tuft", in 2D.
- **Anchor**: with "Anchor: tip" the stylus point maps to the tip end of the footprint (x = ℓ), so the belly grows *behind* it along −b as pressure rises. With "Anchor: centre" it maps to the belly centroid. The shape never slides ahead of the pen.
  - This addresses Expresii users' "dab slides ahead of the cursor" complaint. Expose "Anchor: tip / centre" [INF].

**Bend direction dynamics** (tip trails the motion; tilt lays the tuft):
- Target direction: `t = normalize( wT·sinθ·(−â) + wV·(−v̂)·smoothstep(v0, v1, |v|) + wP·κ_p·b_plastic )`.
  - −â: with the handle leaning toward azimuth a, the tuft lies on the paper continuing the handle axis past the contact point, so the tip points away from the lean [INF from brush geometry].
  - −v̂: drag makes the tip trail.
- Update: `b ← slerp(b, t, 1 − exp(−Δs/λ_b))`. Δs is the distance travelled since the last sample, and `λ_b = L·(1 − stiffness)·c_b` is the **lag length**: longer and softer bristles take longer to swing around.
  - Distance-based damping, not time-based, keeps the result independent of input rate and makes slow careful strokes stable [INF].
- **Biased-tip strokes** emerge naturally. When tilt azimuth and motion disagree, b is not antiparallel to v, so the tip runs along one side of the stroke. The thesis highlights this effect.
- **Anisotropic friction** [inspired by SRC]: when motion is perpendicular to b (a sideways drag), reduce the effective `ℓ` and increase `w_max` slightly. The tuft "wipes" sideways, which gives Expresii's "wipe strokes (using the side of the brush)".
- **Plasticity** [SRC idea, 2D version]: on lift-off, `κ_p ← clamp(κ_p + plasticity·bendAmount, 0, αmax)` and `b_plastic ← b`. On the next touchdown the brush starts pre-bent, so it may need "re-pointing", as with a real brush. Expose the Chu-style "reset tip" gesture and a plasticity slider whose default is low.
- **Pore resistance** [SRC idea, 2D version]: if motion is *toward* the tip (v̂·b > cos 30°) and θ > γ, add a stochastic scratch mask, raise dryness locally, and shrink `ℓ`. This gives the rough "pushed stroke".

**Twist without a sensor (flat/chisel shapes only)** [INF]:
- `ψ_target = atan2(v)` for a chisel held "edge-leading", or `atan2(v) + π/2` for "broad-leading", taken mod π because a flat is symmetric.
- `ψ ← ψ + wrapπ(ψ_target − ψ)·(1 − exp(−Δs/λ_ψ))`, with `λ_ψ = L·(1 − stiffness)·c_ψ` (bristle length and stiffness damp the swing).
- Freeze ψ when |v| < v_min (hysteresis) so dotting and slow turns do not spin the brush.
- Optional "lock angle" (calligraphy pen), and user "twist offset" via the S Pen button plus drag [INF].
- For round brushes, **ignore twist entirely** (Expresii's lesson). Bend direction plus footprint asymmetry carries all the directionality.

**Ink, water and colour transfer brush → paper** [SRC MoXi rules + INF]:
- Each footprint fragment has a tuft coordinate `s = 1 − x/ℓ` (s = 0 at the tip, s → 1 toward the belly/root) and a lateral coordinate `y/w(x)`.
- **Colour from loading**: `C = gradient(s)`. The tip deposits the tip colour and the belly the base colour. Expresii's gradient loading, dip-to-load with pressure, "halfway" loading and tip↔base flip all fall out of this [SRC for the features].
- **Water/ink deposit**: `Δs_paper = rate · W_b/10 · footprintAlpha · max(1 − ρ/λ, m)` (MoXi receptivity mask) into the **surface layer**, with pigment concentration from the gradient. The brush loses `W_b` proportionally.
- **Dry brush**: when `W_b < wDry` (≈ 6 on the 0–10 scale) or speed is high or pressure is low, threshold a **bristle split map** sampled at `(y/w(x), strokeArcLength·k)`:
  - streaky mode: stretched along the stroke, so clumps persist and make streaks;
  - grainy mode: paper grain height field in canvas space.
  
  This is Chu's split × dry × grain maps, in stroke space. Expresii's "Streakiness" is the blend between the two [SRC].
- **Soaked tip**: when `W_b > wSoak`, dilate the split mask and slightly enlarge `r_tip` [SRC idea].
- **Splitting** [INF simplification of multi-tuft]: at low `W_b` plus high bend, or with a "split" parameter, render **2–5 sub-footprints** offset laterally within w(x), each with its own small lag λ. This gives multiple parallel lines, as when Chu's child tufts sprout.
- **Deposition blend** [SRC concept]: "replace" (gouache-like) vs "darken" (ink/felt-tip), as a paint property.

**Stroke-to-sim coupling**: the brush writes only to the **surface layer and its pigment**. The sim handles seepage and flow. When `W_b` is below the flow threshold, deposit directly into the fixture/"settled" layer (a static mark, cheap, no sim). This mirrors Expresii's "smooth vs textured" split [SRC behaviour] and keeps inking strokes out of the simulation.

### 4.4 "One great brush": a proposed parameter set

A single engine, **Joy Brush "Yi"**, with every preset as a parameter vector [INF]. Parameters marked * are the few exposed in the simple UI.

| Group | Parameter | Range / default | Notes |
|---|---|---|---|
| Tuft | *size R* | canvas mm | belly radius at full press |
| | length ratio L/R | 2.5–5 (3.5) | long = more lag, more tip range |
| | tip sharpness | 0–1 (0.8) | r_tip and taper exponent |
| | spread | 0–1 (0.4) | lateral flattening under pressure |
| | shape | round / flat / chisel | flat enables derived twist ψ |
| Mechanics | *stiffness* | 0–1 (0.5) | sets λ_b, λ_ψ and max bend |
| | plasticity | 0–0.5 (0.1) | Chu's α; 0 = self-pointing brush |
| | side friction | 0–1 (0.5) | wipe-stroke behaviour |
| | pore resistance | 0–1 (0.3) | pushed-stroke roughness |
| | pressure curve, tilt gain | user curves | per-device calibration |
| | anchor | tip / centre | fixes the Expresii dab-drift complaint |
| Load | *colour gradient* | 1–3 stops tip→base | dip-to-load, halfway, flip |
| | *water W* | 0–10 (7) | <≈6 static and scratchy; >≈6 flows (Expresii thresholds) |
| | pigment strength | 0–1 | concentration in the carried water |
| | glue / viscosity | 0–1 | raises blocking, limits spread (MoXi) |
| Mark | split map, streakiness | texture id, 0–1 | streaky vs grainy dry-brush |
| | scratch threshold | 0–1 | Expresii [A]/[S] hotkeys analogue |
| | deposition blend | replace ↔ darken | gouache vs ink |
| Paper (per document) | grain, sizing/alum, pinning (toes), fibre textures | set of textures | the paper *is* the second half of the brush |
| | absorbency λ, capacity π | 0.3–1, 1 | receptivity/light fringe |
| | evaporation εs, edge evaporation εb | 0–0.005, 5e-5 | drying speed, edge darkening |
| | hindrance γ, advect α, relax ω | MoXi ranges | feathering, fluidity |
| | granulation, backrun/damp TTL | optional | Western watercolour |

**Presets as parameter vectors (the "few excellent brushes")**:
- **Ink** (inking/calligraphy): stiffness 0.7, tip sharpness 0.9, W = 3–5 (static, crisp, scratchy at speed), deposition = darken. Everything else is emergent.
- **Wash**: stiffness 0.3, spread 0.7, W = 8–10, low pigment. Flows, fringes, edge-darkens.
- **Fill / flat wash**: shape flat, large R, W = 9, derived twist on. Paired with Lock-Wet-Area for controlled fills.
- **Dry / texture**: W = 2, streakiness 0.8, split 3–5 tufts, pore resistance 0.6.
- **Water / blend / lift**: zero pigment, W = 10. Rewets, blooms, lifts, "pushes" ink using MoXi's accelerated advection a ≈ 10 inside the footprint, which gives the *nudge*.
- **Smudge (non-wet media)**: same footprint with a pickup colour state. See §5 for a patent-safe design.

---

## 5. Patents and licensing: what is protected and what it means for Joy Paint

**Not legal advice.** Patent status was read from USPTO PDFs and, where noted, Google Patents. Expiry dates are computed as filing date + 20 years + the printed term adjustment, *assuming maintenance fees were paid*. Adobe demonstrably pays them (US 8,605,095 shows 4th, 8th and 12th-year fees).

| Patent | Owner / inventors | Filed | Est. expiry | Core claim (independent) | Risk for us |
|---|---|---|---|---|---|
| **US 8,296,668 B1**, "Paper texture for watercolor painting effects…" | Adobe; Y. Song, P. Falco (cites MoXi) | 2009-02-06 (prov. 2008-08-15) | **≈ 2030-05-20** (+468 d) | Ink deposition on a paper texture with *multiple parameter channels, one a **dedicated absorption parameter channel***. A *blocking effect* depends on that channel, and the simulated ink effect depends on the blocking. Dependents: LBM (stream + collide), 3-layer surface/flow/fixture, grain and pinning channels, noise on absorption, wax masking, evaporation. | **High** if we copy MoXi with a paper texture carrying an absorption channel that feeds κ. **Design-around [INF]**: no paper-texture channel dedicated to absorption. Derive blocking from grain height (G), glue and fixed pigment (as in MoXi eq. 6; the 2005 paper is prior art to Adobe's filing). Make absorbency a *global scalar* receptivity λ. Consider the Darcy-lite solver. Revisit after 2030. |
| **US 9,030,464 B2**, "Simulating painting" | Microsoft; W. Baxter, **Siu Hang Chu**, N. Govindaraju | 2010-04-08 | **≈ 2031-11-24** (+595 d) | A brush component outputs a **3D model** of the tool. The paint component makes a **2D pickup map for the footprint at resolution matching the canvas paint map**, updated from the canvas as the model moves. | **Medium** for smudge. **Design-around [INF]**: our brush is not a 3D model (analytic 2D SDF footprint). Keep pickup as a *single averaged colour/small low-res state* (MyPaint/Krita style), not a canvas-resolution map. |
| **US 8,462,173 B2**, "Pickup and reservoir model" | Adobe; DiVerdi, Hadap, Krishnaswamy | 2010-08-25 (prov. 2009-09-30) | **≈ 2031-08-12** (+352 d) | A two-layer brush with a **reservoir buffer** (belly paint) and a separate **pickup buffer** (surface paint picked up), depositing from both, with a configurable ratio. | **Medium** for wet/smudge brushes. **Design-around [INF]**: a single brush colour state that mixes (MyPaint-style "smudge bucket", long-standing open-source prior art), rather than two separately deposited buffers. Our watercolour brush deposits only from its loading gradient; pickup happens on the paper by the sim ("staining"), not on the brush. |
| **US 8,605,095 B2**, "Vector output from physical simulation of a bristle brush" | Adobe; DiVerdi, Hadap, Tomack | 2010-05-28 | **2032-04-02** (active, fees paid to year 12) | Per-bristle swept paths turned into vector curves and composited. | Low. Our "vector-like" zoom is implicit-field rendering, not per-bristle vector paths. Do not build bristle-path→Bézier export before 2032. |
| **US 8,654,143 B2**, "Non-uniform loading of digital paint brushes" | Adobe; Krishnaswamy, DiVerdi, Hadap | 2010-08-25 (prov. 2009-09-30) | **2031-03-27** (active) | Loading a brush with a *non-uniform colour distribution by sampling the canvas under the brush*, as a single sample or continuously. | Low–medium. Gradient loading from *swatches* (Expresii-style) is different. Avoid "sample the canvas region under the brush into a non-uniform brush load". Eyedropper → single colour or 2-stop gradient from two explicit picks is safer. |

- **Expresii's own IP.** Searches found no patents by Nelson Chu or Expresii on the brush, MoXi or vexel rendering. MoXi's techniques were *licensed* to Adobe and Sony (2006/2007), which suggests HKUST/Chu held rights. Those may be copyright/know-how licences or patents I did not find (e.g. HK/CN filings). **Search CN/HK databases (CNIPA, HK IPD) before launch** [gap].
- **Code licence.** The MoXi Cg shaders (LBESimCgCode.zip, 2005) carry: "Permission to use, copy, modify and distribute … for research or educational purpose … without fee". **That is not GPL-compatible and not permitted for a commercial app.** Joy Paint must **clean-room implement from the published equations**. Equations and algorithms are not copyrightable; the code is. Do not paste or port the Cg files. The PDFs say "Not for redistribution", so do not commit them to the repo either.
- **GPL-3.0 note [INF].** GPL-3 §11 governs *our* patent grants to users. It does nothing to shield us from third-party patents. If a module ever needed a patent licence that cannot be sublicensed to all recipients, it could not be shipped under GPL-3 (§12 "liberty or death"). That is another reason to design around rather than license.

---

## 6. Risks and open questions

1. **Mobile performance.** A full-canvas LBM at 60 Hz on a Note 9 is marginal. Wet-tile sparsity and d-scale 3–4 are required. Mali and Adreno differ, so profile both.
   - fp16 precision will bite: MoXi hit pigment loss and interpolation drift. Keep fixture in 32F or quantize.
   - **Thermal throttling** on long sessions needs sleep-when-dry, and possibly frame-rate-decoupled sim steps.
2. **Tilt availability.** Some Samsung devices or firmware may report no or poor tilt. The brush must degrade gracefully: derive bend from motion alone, and use tilt only to add. Barrel rotation is not available on Android. Design it out, as Expresii did.
3. **Determinism and animation.** Joy Paint is also an animation app. Wet simulation per frame needs per-frame sim state or deterministic replay. Plan for "settle on frame change" as a default, with optional "keep wet across frames" for ink-flow animation (MoXi's "flare and wash animation" is prior art for this use).
4. **UX.** Expresii's weakest point is the legibility of wet/dry/settled state. Invest in visual wetness feedback and fewer, well-named controls.
5. **Patents** (§5). The Adobe absorption-channel patent (to ~2030) and the pickup-map and reservoir/pickup patents (to ~2031) constrain a naive MoXi + Gustav clone. Architect around them from day one. Get counsel sign-off before a US release.
6. **Gaps in this research.**
   - The Expresii blog (vexel post, brush-engine updates) and the online user guide were behind a Cloudflare bot wall. Vexel internals are therefore inference.
   - Chu & Tai 2002/2004 were covered via the thesis (which supersedes them), not read separately.
   - Legal status of US 8,296,668 / 9,030,464 / 8,462,173 was not verified online (Google Patents rate-limited), only computed.

---

## 7. Sources

Primary papers and code:
- MoXi project page (HKUST), with links to the paper, thesis, supplement, Cg code and FAQ: http://visgraph.cse.ust.hk/MoXi/
- Chu & Tai, "MoXi: Real-Time Ink Dispersion in Absorbent Paper", ACM TOG 24(3), SIGGRAPH 2005, author PDF: http://visgraph.cse.ust.hk/MoXi/moxi.pdf (ACM: https://dl.acm.org/doi/10.1145/1073204.1073221)
- MoXi supplementary material (GPU details, precision): http://visgraph.cse.ust.hk/MoXi/MoXi_suppli.pdf
- MoXi implementation sketch (use of infinity): http://visgraph.cse.ust.hk/MoXi/implsketch_07_submitted.pdf
- MoXi render-pass chart: http://visgraph.cse.ust.hk/MoXi/RenderPassChart25.pdf
- MoXi Cg LBE code (research/education licence): http://visgraph.cse.ust.hk/MoXi/LBESimCgCode.zip
- MoXi FAQ (2009; Adobe/Sony licensing, GPU table, 6D pen): http://visgraph.cse.ust.hk/MoXi/FAQ.html
- N. S.-H. Chu, *Making Digital Painting Organic*, PhD thesis, HKUST 2007 (brush model ch. 2, ink ch. 3, rendering ch. 4, UI ch. 5): http://visgraph.cse.ust.hk/MoXi/ChuPhDThesis2007.pdf
- Chu & Tai, "Real-time painting with an expressive virtual Chinese brush", IEEE CG&A 24(5) 2004 (via thesis; record): https://www.researchgate.net/publication/8103063_Real-Time_Painting_with_an_Expressive_Virtual_Chinese_Brush
- Chu, Baxter, Wei, Govindaraju, "Detail-Preserving Paint Modeling for 3D Brushes", NPAR 2010: https://www.microsoft.com/en-us/research/wp-content/uploads/2010/06/PaintModel_NPAR_2010.pdf
- Stuyck, Da, Hadap, Dutré, "Real-Time Oil Painting on Mobile Hardware", CGF 2017 (GLES 3.0, iPad Air 2 timings): https://tuurstuyck.github.io/assets/oilpaint_low_res.pdf
- DiVerdi et al., "A lightweight, procedural, vector watercolor painting engine", I3D 2012 (alternative approach): https://dl.acm.org/doi/10.1145/2159616.2159627
- Chu, "Expresii watercolor", SIGGRAPH 2017 Appy Hour ("hybrid vector-raster rendering"): https://dl.acm.org/doi/10.1145/3098900.3098902

Expresii product sources:
- About Us / chronicle: https://www.expresii.com/about-us.html
- Youji Rendering Engine (vexel, deep zoom, 32K export): https://www.expresii.com/youji-rendering-engine.html
- Yibi Brush Engine: https://www.expresii.com/yibi-brush-engine.html
- A New Way to Paint: https://www.expresii.com/a-new-way-to-paint.html
- Expresii FAQ (tilt pens, twist not used, platforms, Adobe Sketch remark): https://www.expresii.com/faq.html
- Expresii Quick User Guide PDF (2017; tools, loading, water levels, modes, layers, export): http://www.expresii.com/files/theme/ExpresiiUserGuide.pdf
- Vexel blog post (not retrievable, bot wall): https://www.expresii.com/blog/vexel-marrying-pixels-with-vector-for-organic-digital-painting
- CGV Space article on vexel rendering (Chinese): https://www.cgvisual.com/CGVSpace/archives/4306
- CG Channel 2013 preview: https://www.cgchannel.com/2013/11/video-the-expresii-digital-ink-simulation-system/
- Expresii forum threads (developer replies):
  - Twist removed: https://expresii.forumotion.net/t170-wacom-art-pen-kp701e2
  - Wetness/settle/deposition UI: https://expresii.forumotion.net/t367-questions-about-expresii
  - Rebelle comparison, user praise: https://expresii.forumotion.net/t340-thoughts-on-rebelle-5
  - Clear-water blending, dab displacement, zero deposition: https://expresii.forumotion.net/t190-some-thoughts-about-watercolor-bleeding-blending-stylus-dab-displacement
  - Android GPU remark: https://expresii.forumotion.net/t260-huawei-mediapad-5-pro-with-tilt-sensitive-pen-and-android-support
  - Tilt on hover, comparison with other apps: https://expresii.forumotion.net/t275-first-painting-and-a-question-admin-about-comparison-w-other-apps-pen-tilt-during-hover-snapdown
  - Settled paint 2021: https://expresii.forumotion.net/t314-each-brush-stroke-darkens-paint
  - Spreading threshold 2025: https://expresii.forumotion.net/t361-has-the-rendering-of-the-blurriness-changed-in-relation-to-the-amount-of-water
  - Surface pen tilt: https://expresii.forumotion.net/t193-tilt-using-surface-pen
- Reviews:
  - AlternativeTo: https://alternativeto.net/software/expresii/reviews/
  - Renderosity: https://magazine.renderosity.com/article/2892/product-review-expresii

Patents (USPTO full-text PDFs):
- US 8,296,668 B1 (Adobe, watercolour paper texture / LBM): https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/8296668 ; https://patents.google.com/patent/US8296668
- US 9,030,464 B2 (Microsoft, Baxter/Chu/Govindaraju, pickup map): https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/9030464
- US 8,462,173 B2 (Adobe, pickup + reservoir): https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/8462173
- US 8,605,095 B2 (Adobe, vector output from bristle sim; legal events): https://patents.google.com/patent/US8605095B2/en
- US 8,654,143 B2 (Adobe, non-uniform loading; legal status): https://patents.justia.com/patent/8654143 ; https://patents.google.com/patent/US8654143B2/en

Platform:
- Android advanced stylus features (AXIS_TILT, AXIS_ORIENTATION, AXIS_PRESSURE): https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/advanced-stylus-features
